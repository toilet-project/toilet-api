package com.example.toiletapi.growth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;

import com.example.toiletapi.policy.service.PolicyConsentService;
import com.geupddong.growth.GrowthLedger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Ledger behavior with real SQL, frozen targets and user-row locks. */
class GrowthServiceTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private GrowthService growth;
    private MutableClock clock;
    private final GrowthService.Actor actor=new GrowthService.Actor(1,0);

    @BeforeEach void setup() throws Exception {
        DataSource ds=new DriverManagerDataSource("jdbc:h2:mem:growth_"+UUID.randomUUID()+
                ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","");
        jdbc=new JdbcTemplate(ds);
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(30),auth_version BIGINT DEFAULT 0)");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,visibility_status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,review_key CHAR(36) UNIQUE,toilet_id BIGINT,author_user_id BIGINT,author_detached BOOLEAN,satisfaction INT,cleanliness INT,paper_available BOOLEAN,created_at DATETIME)");
        jdbc.execute("CREATE TABLE toilet_review_submission(user_id BIGINT,review_id BIGINT)");
        jdbc.execute("CREATE TABLE current_toilet_region(toilet_id BIGINT PRIMARY KEY,status VARCHAR(30),sigungu_code CHAR(5))");
        jdbc.execute("CREATE TABLE region_sigungu_reference(sigungu_code CHAR(5) PRIMARY KEY,sido_code CHAR(2),sido_name VARCHAR(50),sigungu_name VARCHAR(80),display_name VARCHAR(160),is_active BOOLEAN)");
        String ddl=new ClassPathResource("db/migration/V40__member_growth.sql").getContentAsString(StandardCharsets.UTF_8)
                .replace("BOOLEAN","TINYINT");
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(ds);
        jdbc.update("INSERT INTO app_user VALUES(1,'ACTIVE',0)");
        jdbc.update("INSERT INTO region_sigungu_reference VALUES('30110','30','대전','동구','대전 동구',TRUE),('30140','30','대전','중구','대전 중구',TRUE),('36110','36','세종',NULL,'세종',TRUE),('29110','29','광주','동구','광주 동구',TRUE),('12210','12','전남광주','동구','전남광주 동구',TRUE)");
        clock=new MutableClock(Instant.parse("2026-10-04T14:59:00Z"));
        growth=new GrowthService(jdbc,mock(PolicyConsentService.class),true,clock);
    }

    private <T> T call(java.util.function.Supplier<T> operation) {return tx.execute(status->operation.get());}
    private void facility(int id,String region) {
        jdbc.update("INSERT INTO toilet VALUES(?,'VISIBLE')",id);
        jdbc.update("INSERT INTO current_toilet_region VALUES(?,'VERIFIED',?)",id,region);
    }
    private void review(int id,int toilet,int satisfaction) {
        jdbc.update("INSERT INTO toilet_review VALUES(?,?,?,?,FALSE,?,4,TRUE,'2026-10-01 12:00:00')",
                id,String.format("00000000-0000-4000-8000-%012d",id),toilet,1,satisfaction);
        jdbc.update("INSERT INTO toilet_review_submission VALUES(1,?)",id);
    }
    private void facilities(int count) {
        for(int id=1;id<=count;id++) {facility(id,id<=count/2?"30110":"30140");review(id,id,id%5+1);}
    }

    @Test void publicAuthorRankFollowsCurrentLevelAndExcludesInactiveAccounts() {
        assertTrue(call(()->growth.publicAuthorRanks(List.of(1L))).isEmpty()); // Enabled, but policy is not frozen yet.
        facility(1,"30110");
        call(()->growth.initializePolicy(true));
        assertEquals("white",call(()->growth.publicAuthorRanks(List.of(1L))).get(1L)); // No ledger row yet.
        jdbc.update("INSERT INTO growth_account(user_id,total_xp,updated_at) VALUES(1,0,CURRENT_TIMESTAMP)");
        long[] xp={0,80,240,990,2240,6240,15990};
        String[] ranks={"white","green","yellow","blue","red","pink","black"};
        for(int i=0;i<xp.length;i++) {
            jdbc.update("UPDATE growth_account SET total_xp=? WHERE user_id=1",xp[i]);
            assertEquals(ranks[i],call(()->growth.publicAuthorRanks(List.of(1L))).get(1L));
        }
        jdbc.update("UPDATE app_user SET status='WITHDRAWN' WHERE user_id=1");
        assertTrue(call(()->growth.publicAuthorRanks(List.of(1L))).isEmpty());
        GrowthService disabled=new GrowthService(jdbc,mock(PolicyConsentService.class),false,clock);
        assertTrue(call(()->disabled.publicAuthorRanks(List.of(1L))).isEmpty());
    }

    @Test void policyPreviewDoesNotWriteAndOldRegionAliasAndSejongAreCounted() {
        facility(1,"29110");facility(2,"36110");review(1,1,1);review(2,2,1);
        GrowthService.PolicyPreview preview=call(growth::previewPolicy);
        assertFalse(preview.initialized());assertEquals(2,preview.targetDistricts());assertEquals(2,preview.regions());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_policy_snapshot",Integer.class));
        assertTrue(GrowthLedger.currentTargets(jdbc).stream().anyMatch(t->t.code().equals("12210")));
        assertTrue(GrowthLedger.currentTargets(jdbc).stream().anyMatch(t->t.code().equals("36110")));
        call(()->growth.initializePolicy(true));
        var result=call(()->growth.backfillUser(1,true));
        assertEquals(60,result.reconcile().totalXp());
        assertEquals(2,result.preview().distinctFacilities());
    }

    @Test void previewSummarizesExclusionReasonsWithoutChangingTheLedger() {
        for(int id=1;id<=5;id++) {facility(id,"30110");review(id,id,id==4?0:4);}
        jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=2");
        jdbc.update("INSERT INTO growth_review_exclusion VALUES(3,?,'운영 제외','2026-10-04 12:00:00')",
                "00000000-0000-4000-8000-000000000003");
        jdbc.update("UPDATE toilet SET visibility_status='HIDDEN' WHERE toilet_id=5");
        var preview=call(()->growth.previewUser(1));
        assertEquals(5,preview.linkedReviews());assertEquals(1,preview.eligibleReviews());
        assertEquals(1,preview.excludedReasons().get("SUBMISSION_UNVERIFIED"));
        assertEquals(1,preview.excludedReasons().get("REVIEW_EXCLUDED"));
        assertEquals(1,preview.excludedReasons().get("INVALID_CONTENT"));
        assertEquals(1,preview.excludedReasons().get("FACILITY_HIDDEN"));
        assertEquals(preview.linkedReviews(),preview.eligibleReviews()+preview.excludedReasons().values().stream()
                .mapToInt(Integer::intValue).sum());
        assertEquals(30,preview.expectedXp());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award",Integer.class));
    }

    @Test void goldNeedsThirtyDistinctFacilitiesAndAllDistrictsAndBackfillIsIdempotent() {
        facilities(30);call(()->growth.initializePolicy(true));
        var dry=call(()->growth.backfillUser(1,false));
        assertFalse(dry.applied());assertEquals(520,dry.preview().expectedXp());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award",Integer.class));
        var first=call(()->growth.backfillUser(1,true));
        assertEquals(520,first.reconcile().totalXp());
        assertEquals(35,first.reconcile().earnedCount());
        var repeat=call(()->growth.backfillUser(1,true));
        assertEquals(0,repeat.reconcile().earnedCount());assertEquals(0,repeat.reconcile().revokedCount());
        assertEquals("gold",call(()->growth.summary(actor)).regions().stream()
                .filter(r->r.code().equals("30")).findFirst().orElseThrow().tier());
        jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=30");
        var revoked=call(()->growth.reconcileUser(1,"REVIEW_EDITED"));
        assertEquals(410,revoked.totalXp());assertEquals(2,revoked.revokedCount());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE award_key IN ('T:30','P:30') AND active=FALSE",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE scrubbed_at IS NOT NULL",Integer.class));
    }

    @Test void duplicateReviewKeepsFacilityUntilLastEligibleSourceIsUnlinkedEvenWhenFlagOff() {
        facility(1,"30110");review(1,1,1);review(2,1,5);
        call(()->growth.initializePolicy(true));call(()->growth.backfillUser(1,true));
        assertEquals(30,GrowthLedger.totalXp(jdbc,1));
        jdbc.update("UPDATE toilet_review SET author_user_id=NULL,author_detached=TRUE WHERE review_id=1");
        jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=1");
        call(()->growth.reconcileAfterDetach(1,String.format("00000000-0000-4000-8000-%012d",1)));
        assertEquals(30,GrowthLedger.totalXp(jdbc,1));
        GrowthService disabled=new GrowthService(jdbc,mock(PolicyConsentService.class),false,clock);
        jdbc.update("UPDATE toilet_review SET author_user_id=NULL,author_detached=TRUE WHERE review_id=2");
        jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=2");
        call(()->disabled.reconcileAfterDetach(1,String.format("00000000-0000-4000-8000-%012d",2)));
        assertEquals(0,GrowthLedger.totalXp(jdbc,1));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_review_evidence",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE award_key IS NOT NULL AND active=FALSE",Integer.class));
    }

    @Test void checkInUsesKoreanCalendarOncePerDate() {
        facility(1,"30110");
        call(()->growth.initializePolicy(true));
        assertEquals(2,call(()->growth.checkIn(actor)).totalXp());
        assertFalse(call(()->growth.checkIn(actor)).checkInAvailable());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award",Integer.class));
        clock.at=clock.at.plusSeconds(120);
        assertEquals(4,call(()->growth.checkIn(actor)).totalXp());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE award_kind='CHECKIN'",Integer.class));
        assertEquals(LocalDate.of(2026,10,5),LocalDate.ofInstant(clock.instant(),ZoneId.of("Asia/Seoul")));
    }

    @Test void previousVersionAwardsKeepOriginalAmountsWhileValidAndPreviewMatchesApply() {
        facility(1,"30110");review(1,1,4);
        jdbc.update("""
                INSERT INTO growth_policy_snapshot VALUES('v0','2026-09-01 00:00:00',1,7,2,11,13,17,19,1,10,30,50,100)
                """);
        jdbc.update("INSERT INTO growth_policy_target VALUES('v0','30110','30','대전','대전 동구')");
        call(()->GrowthLedger.reconcile(jdbc,1,GrowthLedger.policy(jdbc,"v0"),"BACKFILL",clock));
        assertEquals(31,GrowthLedger.totalXp(jdbc,1));
        facility(2,"30140");call(()->growth.initializePolicy(true));
        var preview=call(()->growth.backfillUser(1,false)).preview();
        assertEquals(31,preview.expectedXp());assertEquals(0,preview.deltaXp());
        var applied=call(()->growth.backfillUser(1,true));
        assertEquals(31,applied.reconcile().totalXp());assertEquals(0,applied.reconcile().revokedCount());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE active=TRUE AND policy_version='v0'",Integer.class));
        jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=1");
        assertEquals(0,call(()->growth.backfillUser(1,false)).preview().expectedXp());
        assertEquals(0,call(()->growth.backfillUser(1,true)).reconcile().totalXp());
    }

    @Test void adminExclusionAndRestorationReconcileLowRatingsWithoutBodyRequirement() {
        facility(1,"30110");review(1,1,1);call(()->growth.initializePolicy(true));
        assertEquals(30,call(()->growth.backfillUser(1,true)).reconcile().totalXp());
        assertEquals(0,call(()->growth.setReviewExcluded(1,true,"반복된 허위 리뷰")).reconcile().totalXp());
        assertEquals(30,call(()->growth.setReviewExcluded(1,false,"오인 판정 해제")).reconcile().totalXp());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM growth_review_evidence",Integer.class));
    }

    @Test void disabledPrivacyCleanupOnlyRevokesAndInactiveAccountsCannotBeBackfilled() {
        facility(1,"30110");review(1,1,4);call(()->growth.initializePolicy(true));
        assertEquals(30,call(()->growth.backfillUser(1,true)).reconcile().totalXp());
        facility(2,"30110");review(2,2,4); // Feature OFF: no automatic award or new evidence.
        GrowthService disabled=new GrowthService(jdbc,mock(PolicyConsentService.class),false,clock);
        jdbc.update("UPDATE app_user SET status='WITHDRAWN' WHERE user_id=1");
        GrowthFailure error=assertThrows(GrowthFailure.class,()->call(()->growth.previewUser(1)));
        assertEquals(403,error.status());
        jdbc.update("UPDATE toilet_review SET author_user_id=NULL,author_detached=TRUE WHERE review_id=1");
        jdbc.update("DELETE FROM toilet_review_submission WHERE review_id=1");
        var cleanup=call(()->disabled.reconcileAfterDetach(1,"00000000-0000-4000-8000-000000000001"));
        assertEquals(0,cleanup.earnedCount());
        assertEquals(20,cleanup.totalXp()); // District remains valid through review 2; review 2 earns no new 10 XP.
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_review_evidence",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE award_key='T:2'",Integer.class));
    }

    @Test void historyPagesReadBeyondFiftyByDeltaSignAndOnlyForTheSignedInMember() {
        facility(1,"30110");call(()->growth.initializePolicy(true));
        jdbc.update("INSERT INTO app_user VALUES(2,'ACTIVE',0)");
        List<Long> earnedIds=new ArrayList<>(),deductedIds=new ArrayList<>();
        for(long id=1;id<=72;id++) {
            int delta=id%6==0?-10:2;
            // Sign, not an event label, defines the requested accounting direction.
            historyEvent(id,1,delta,id%6==0?"EARN":"REVOKE");
            (delta>0?earnedIds:deductedIds).add(id);
        }
        historyEvent(73,2,2,"EARN");historyEvent(74,2,-10,"REVOKE");
        List<Long> seen=new ArrayList<>();
        for(int page=0;page<6;page++) {
            int requestedPage=page;
            var result=call(()->growth.historyPage(actor,"earned",requestedPage,10));
            assertEquals(60,result.total());assertEquals(page,result.page());assertEquals(10,result.size());
            assertEquals(10,result.items().size());assertTrue(result.items().stream().allMatch(item->item.deltaXp()>0));
            seen.addAll(result.items().stream().map(GrowthService.HistoryItem::id).toList());
        }
        assertEquals(earnedIds.reversed(),seen);assertEquals(60,seen.stream().distinct().count());
        var deducted=call(()->growth.historyPage(actor,"deducted",1,10));
        assertEquals(12,deducted.total());assertEquals(deductedIds.reversed().subList(10,12),deducted.items().stream().map(GrowthService.HistoryItem::id).toList());
        assertTrue(deducted.items().stream().allMatch(item->item.deltaXp()<0));
        var all=call(()->growth.historyPage(actor,null,0,50));
        assertEquals(72,all.total());assertEquals(50,all.items().size());assertEquals(72,all.items().getFirst().id());
        var pastEnd=call(()->growth.historyPage(actor,"earned",Integer.MAX_VALUE,50));
        assertEquals(60,pastEnd.total());assertTrue(pastEnd.items().isEmpty());
        var legacy=call(()->growth.history(actor));
        assertEquals(50,legacy.items().size());assertEquals(all.items(),legacy.items());
        assertEquals(74,jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_account",Integer.class));
    }

    @Test void historyPageBoundsAndDirectionsAreValidatedWithoutWriting() {
        for(int[] bounds:new int[][]{{-1,10},{0,0},{0,-1},{0,51},{0,Integer.MAX_VALUE}}) {
            GrowthFailure error=assertThrows(GrowthFailure.class,()->call(()->growth.historyPage(actor,"earned",bounds[0],bounds[1])));
            assertEquals(400,error.status());assertEquals("INVALID_GROWTH_HISTORY_PAGE",error.code());
        }
        for(String direction:List.of("", "all", "EARN", "earned' OR 1=1")) {
            GrowthFailure error=assertThrows(GrowthFailure.class,()->call(()->growth.historyPage(actor,direction,0,10)));
            assertEquals(400,error.status());assertEquals("INVALID_GROWTH_HISTORY_DIRECTION",error.code());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event",Integer.class));
    }

    @Test void historyPagesKeepFeaturePolicyAndAccountGuardsAndNeverExposeUnappliedPreview() {
        facility(1,"30110");review(1,1,4);
        assertEquals(30,call(()->growth.previewUser(1)).expectedXp());
        assertEquals(new GrowthService.HistoryPage(List.of(),0,2,10),call(()->growth.historyPage(actor,"earned",2,10)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_policy_snapshot",Integer.class));
        call(()->growth.initializePolicy(true));
        assertEquals(0,call(()->growth.historyPage(actor,"earned",0,10)).total());
        historyEvent(1,1,2,"EARN");
        GrowthService disabled=new GrowthService(jdbc,mock(PolicyConsentService.class),false,clock);
        assertEquals(new GrowthService.HistoryPage(List.of(),0,0,10),call(()->disabled.historyPage(actor,"earned",0,10)));
        assertEquals(401,assertThrows(GrowthFailure.class,()->call(()->growth.historyPage(new GrowthService.Actor(1,99),"earned",0,10))).status());
        jdbc.update("UPDATE app_user SET status='WITHDRAWN' WHERE user_id=1");
        assertEquals(403,assertThrows(GrowthFailure.class,()->call(()->disabled.historyPage(actor,"earned",0,10))).status());
        jdbc.update("UPDATE app_user SET status='ACTIVE' WHERE user_id=1");
        PolicyConsentService policies=mock(PolicyConsentService.class);
        doThrow(new com.example.toiletapi.policy.service.PolicyConsentRequiredException()).when(policies).requireEligibleUser(1L);
        GrowthService consentRequired=new GrowthService(jdbc,policies,true,clock);
        assertThrows(com.example.toiletapi.policy.service.PolicyConsentRequiredException.class,
                ()->call(()->consentRequired.historyPage(actor,"earned",0,10)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award",Integer.class));
    }

    private void historyEvent(long id,long userId,int delta,String type) {
        jdbc.update("INSERT INTO growth_xp_event(event_id,user_id,delta_xp,event_kind,reason,happened_at) VALUES(?,?,?,?,?,'2026-10-05 12:00:00')",
                id,userId,delta,type,"HISTORY_TEST");
    }

    private static final class MutableClock extends Clock {
        Instant at;
        MutableClock(Instant at){this.at=at;}
        @Override public ZoneId getZone(){return ZoneId.of("UTC");}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return at;}
    }
}
