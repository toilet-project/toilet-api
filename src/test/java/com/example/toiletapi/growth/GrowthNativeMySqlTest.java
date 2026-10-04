package com.example.toiletapi.growth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.example.toiletapi.auth.support.NativeMySqlFixture;
import com.example.toiletapi.policy.service.PolicyConsentService;
import com.geupddong.growth.GrowthLedger;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Unmodified production V17/V40 SQL on a guarded, disposable native MySQL fixture. */
class GrowthNativeMySqlTest {
    JdbcTemplate jdbc;
    DriverManagerDataSource ds;
    GrowthService growth;
    TransactionTemplate readCommitted;

    @BeforeEach void setup() {
        Assumptions.assumeTrue(NativeMySqlFixture.enabled());
        ds=NativeMySqlFixture.create();jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(30),auth_version BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,visibility_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,review_key CHAR(36) UNIQUE,toilet_id BIGINT,author_user_id BIGINT,author_detached BOOLEAN,satisfaction INT,cleanliness INT,paper_available BOOLEAN,created_at DATETIME)");
        jdbc.execute("CREATE TABLE toilet_review_submission(user_id BIGINT,review_id BIGINT)");
        jdbc.execute("CREATE TABLE current_toilet_region(toilet_id BIGINT PRIMARY KEY,status VARCHAR(30),sigungu_code CHAR(5))");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V17__create_sigungu_reference.sql"),
                new ClassPathResource("db/migration/V40__member_growth.sql")).execute(ds);
        jdbc.update("INSERT INTO app_user VALUES(1,'ACTIVE',0)");
        growth=new GrowthService(jdbc,mock(PolicyConsentService.class),true,Clock.systemUTC());
        readCommitted=new TransactionTemplate(new DataSourceTransactionManager(ds));
        readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private void facilityAndReview(int id,String code) {
        jdbc.update("INSERT INTO toilet VALUES(?,'VISIBLE')",id);
        jdbc.update("INSERT INTO current_toilet_region VALUES(?,'VERIFIED',?)",id,code);
        jdbc.update("INSERT INTO toilet_review VALUES(?,?,?,?,FALSE,1,5,TRUE,'2026-10-01 12:00:00')",
                id,String.format("00000000-0000-4000-8000-%012d",id),id,1);
        jdbc.update("INSERT INTO toilet_review_submission VALUES(1,?)",id);
    }

    @Test void allSixteenCanonicalRegionsAndLegacyAliasesUseOneFrozenTargetEach() {
        List<String> regions=jdbc.query("""
                SELECT MIN(sigungu_code) FROM region_sigungu_reference
                WHERE sido_code NOT IN ('29','46','42','45')
                  AND LEFT(sigungu_code,2)=sido_code
                GROUP BY sido_code ORDER BY sido_code
                """,(rs,i)->rs.getString(1));
        assertEquals(16,regions.size());
        for(int i=0;i<regions.size();i++)
            facilityAndReview(i+1,regions.get(i).startsWith("12")?"12210":regions.get(i));
        // A pre-consolidation Gwangju code resolves to the same current district as 12210.
        facilityAndReview(17,"29110");
        var preview=readCommitted.execute(tx->growth.initializePolicy(false));
        assertEquals(16,preview.regions());
        assertEquals(16,preview.targetDistricts());
        readCommitted.executeWithoutResult(tx->growth.initializePolicy(true));
        var result=readCommitted.execute(tx->growth.backfillUser(1,true));
        assertEquals(17,result.preview().distinctFacilities());
        assertEquals(490,result.reconcile().totalXp());
        assertEquals(16,jdbc.queryForObject("SELECT COUNT(DISTINCT sido_code) FROM growth_policy_target",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM growth_policy_target WHERE sigungu_code='36110'",Integer.class));
    }

    @Test void backfillAfterCommittedDetachCannotReawardFromEarlierPreviewSnapshot() throws Exception {
        facilityAndReview(1,"30110");
        readCommitted.executeWithoutResult(tx->growth.initializePolicy(true));
        CountDownLatch previewed=new CountDownLatch(1),detached=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread apply=Thread.ofPlatform().start(()->{
            try {
                readCommitted.executeWithoutResult(tx->{
                    growth.previewUser(1);
                    previewed.countDown();
                    await(detached);
                    growth.backfillUser(1,true);
                });
            } catch(Throwable error) {failure.set(error);previewed.countDown();}
        });
        assertTrue(previewed.await(10,TimeUnit.SECONDS));
        readCommitted.executeWithoutResult(tx->{
            jdbc.query("SELECT user_id FROM app_user WHERE user_id=1 FOR UPDATE",(rs,i)->rs.getLong(1));
            jdbc.update("UPDATE toilet_review SET author_user_id=NULL,author_detached=TRUE WHERE review_id=1");
            jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=1");
            growth.reconcileAfterDetach(1,"00000000-0000-4000-8000-000000000001");
        });
        detached.countDown();apply.join(10000);
        assertFalse(apply.isAlive());
        if(failure.get()!=null) fail(failure.get());
        assertEquals(0,GrowthLedger.totalXp(jdbc,1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award",Integer.class));
    }

    @Test void preProvinceRenameCodesResolveWithoutObsoleteReferenceRows() {
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM region_sigungu_reference WHERE sido_code IN ('42','45')",Integer.class));
        facilityAndReview(1,"42110"); // Chuncheon -> 51110
        facilityAndReview(2,"45111"); // Jeonju Wansan -> 52111
        facilityAndReview(3,"46110"); // Mokpo -> 12110
        facilityAndReview(4,"29110"); // Gwangju Dong-gu -> 12210
        var codes=GrowthLedger.currentTargets(jdbc).stream().map(GrowthLedger.Target::code).toList();
        assertTrue(codes.containsAll(List.of("51110","52111","12110","12210")));
        assertEquals(4,codes.size());
        readCommitted.executeWithoutResult(tx->growth.initializePolicy(true));
        assertEquals(120,readCommitted.execute(tx->growth.backfillUser(1,true)).reconcile().totalXp());
    }

    private static void await(CountDownLatch latch) {
        try {if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("fixture timeout");}
        catch(InterruptedException error) {Thread.currentThread().interrupt();throw new AssertionError(error);}
    }
}
