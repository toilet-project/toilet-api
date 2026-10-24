package com.example.toiletapi.review;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.growth.GrowthService;
import com.geupddong.growth.GrowthLedger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.oauth2.jwt.Jwt;

/** Actual review FK cascades, public/mine aggregates, growth ledger and transactions; no operational DB. */
class AdminReviewModerationDatabaseTest {
    ReviewDatabaseTest fixture;
    JdbcTemplate jdbc;
    GrowthService growth;
    AuditLogService audit;
    AdminReviewModerationService moderation;
    @BeforeEach void setup() throws Exception {
        setup(new DriverManagerDataSource("jdbc:h2:mem:review_moderation_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa",""),true);
    }
    void setup(DataSource source,boolean h2) throws Exception {
        fixture=new ReviewDatabaseTest();fixture.setup(source,h2);jdbc=fixture.jdbc;
        jdbc.execute("CREATE TABLE user_role(user_id BIGINT,role VARCHAR(20),PRIMARY KEY(user_id,role))");
        jdbc.update("INSERT INTO app_user(user_id,status,display_name,auth_version) VALUES(3,'ACTIVE','검증 관리자',4)");
        jdbc.update("INSERT INTO user_role VALUES(3,'ADMIN')");
        jdbc.execute("CREATE TABLE current_toilet_region(toilet_id BIGINT PRIMARY KEY,status VARCHAR(30),sigungu_code CHAR(5))");
        jdbc.execute("CREATE TABLE region_sigungu_reference(sigungu_code CHAR(5) PRIMARY KEY,sido_code CHAR(2),sido_name VARCHAR(50),sigungu_name VARCHAR(80),display_name VARCHAR(160),is_active BOOLEAN)");
        String ddl=new ClassPathResource("db/migration/V40__member_growth.sql").getContentAsString(StandardCharsets.UTF_8);
        if(h2)ddl=ddl.replace("BOOLEAN","TINYINT");
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V44__growth_award_notice.sql")).execute(source);
        jdbc.update("INSERT INTO current_toilet_region VALUES(1,'VERIFIED','30110')");
        jdbc.update("INSERT INTO region_sigungu_reference VALUES('30110','30','대전','동구','대전 동구',TRUE)");
        growth=new GrowthService(jdbc,fixture.policies,true,fixture.clock);
        fixture.call(()->growth.initializePolicy(true));
        audit=mock(AuditLogService.class);
        jdbc.execute("CREATE TABLE moderation_audit(actor BIGINT,action VARCHAR(100),target_type VARCHAR(50),target_id BIGINT,details VARCHAR(300))");
        doAnswer(call->{
            jdbc.update("INSERT INTO moderation_audit VALUES(?,?,?,?,?)",call.getArgument(0),((AuditAction)call.getArgument(1)).name(),
                    call.getArgument(2),call.getArgument(3),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(call.getArgument(4)));
            return null;
        }).when(audit).record(anyLong(),any(),anyString(),anyLong(),anyMap());
        moderation=service(growth);
    }
    AdminReviewModerationService service(GrowthService ledger) {
        return new AdminReviewModerationService(new AdminReviewAccess(jdbc),new AdminReviewRepository(jdbc),ledger,audit);
    }
    Jwt admin(){return Jwt.withTokenValue("synthetic-admin").header("alg","HS256").subject("3").claim("auth_version",4).build();}
    ReviewModels.Item create(String text) {return fixture.create(text);}
    long id(ReviewModels.Item review){return Long.parseLong(review.id());}
    String key(ReviewModels.Item review){return jdbc.queryForObject("SELECT review_key FROM toilet_review WHERE review_id=?",String.class,id(review));}
    AdminReviewModels.Remove request(ReviewModels.Item review){return new AdminReviewModels.Remove(key(review),review.version(),"SPAM",true);}
    AdminReviewModels.Removed remove(ReviewModels.Item review,AdminReviewModels.Remove request){return fixture.call(()->moderation.remove(admin(),id(review),request));}
    int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}

    @Test void deletionRemovesBodyRatingsSubmissionsAndRecomputesEveryReadWhileKeepingThrottles() throws Exception {
        var target=create("제거할 합성 광고");var proof=request(target);
        var other=fixture.call(()->fixture.service.create(fixture.other,fixture.input("남을 리뷰"),UUID.randomUUID().toString()));
        jdbc.update("UPDATE toilet_review SET satisfaction=5,cleanliness=5,paper_available=FALSE,wait_minutes=0 WHERE review_id=?",id(other));
        assertEquals(2,fixture.call(()->fixture.service.summary(1)).count());
        var result=remove(target,proof);assertTrue(result.deleted());assertEquals(target.id(),result.id());
        assertEquals(1,count("toilet_review"));assertEquals(1,count("toilet_review_submission"));
        assertEquals(2,count("toilet_review_write_guard"));assertEquals(2,count("toilet_review_toilet_guard"));
        assertTrue(fixture.call(()->fixture.service.mine(fixture.author,null,null,null,10)).items().isEmpty());
        var publicReviews=fixture.call(()->fixture.service.publicPage(1,null,10));assertEquals(List.of(other.id()),publicReviews.items().stream().map(ReviewModels.Item::id).toList());
        var summary=fixture.call(()->fixture.service.summary(1));assertEquals(1,summary.count());assertEquals(5.0,summary.averageRating());
        assertEquals(0.0,summary.paperPercent());assertEquals(0,summary.latestWaitMinutes());assertEquals(1,summary.crowding().sampleCount());
        assertFalse(fixture.call(()->fixture.service.creationStatus(fixture.author,1)).canCreate());
        assertNull(fixture.call(()->fixture.service.creationStatus(fixture.author,1)).existingReviewId());
        var details=jdbc.queryForObject("SELECT details FROM moderation_audit",String.class);
        var parsed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(details);
        assertEquals(Set.of("reason","policyVersion","reviewKey"),new HashSet<>(java.util.stream.StreamSupport.stream(Spliterators.spliteratorUnknownSize(parsed.fieldNames(),0),false).toList()));
        assertEquals("SPAM",parsed.path("reason").asText());assertEquals("review-moderation-v1",parsed.path("policyVersion").asText());
        assertFalse(details.contains("합성"));assertEquals(proof.reviewKey(),parsed.path("reviewKey").asText());
        assertEquals("REVIEW_DELETED",jdbc.queryForObject("SELECT action FROM moderation_audit",String.class));
    }
    @Test void deletingLastEligibleReviewRevokesXpBadgesAndPendingAwardNoticesEvenWhenGrowthIsDisabled() {
        var target=create("합성 성장 대상");var proof=request(target);
        fixture.call(()->growth.backfillUser(1,true));assertEquals(30,GrowthLedger.totalXp(jdbc,1));
        moderation=service(new GrowthService(jdbc,fixture.policies,false,fixture.clock));
        remove(target,proof);
        assertEquals(0,GrowthLedger.totalXp(jdbc,1));assertEquals(0,count("growth_review_evidence"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_award WHERE active=TRUE",Integer.class));
        assertTrue(fixture.call(()->growth.awardNotices(new GrowthService.Actor(1,0))).items().isEmpty());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event WHERE reason='REVIEW_REMOVED' AND delta_xp<0",Integer.class));
    }
    @Test void auditFailureRollsBackPhysicalDeletionFkCascadesEvidenceAndXpRevocation() {
        var target=create("롤백할 합성 본문");var proof=request(target);
        fixture.call(()->growth.backfillUser(1,true));
        jdbc.update("INSERT INTO growth_review_exclusion VALUES(?,?,?,CURRENT_TIMESTAMP)",id(target),proof.reviewKey(),"기존 제외");
        doThrow(new IllegalStateException("synthetic audit failure")).when(audit).record(anyLong(),any(),anyString(),anyLong(),anyMap());
        assertThrows(IllegalStateException.class,()->remove(target,proof));
        assertEquals(1,count("toilet_review"));assertEquals(1,count("toilet_review_submission"));assertEquals(1,count("growth_review_exclusion"));
        assertEquals(1,count("growth_review_evidence"));assertEquals(30,GrowthLedger.totalXp(jdbc,1));assertEquals(0,count("moderation_audit"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM growth_xp_event WHERE delta_xp<0",Integer.class));
    }
    @Test void growthFailureAlsoRollsBackDeletionBeforeAnyAuditEntry() {
        var target=create("성장 실패 롤백");var proof=request(target);var failed=mock(GrowthService.class);
        doThrow(new IllegalStateException("synthetic growth failure")).when(failed).reconcileAfterReviewRemoval(any(),anyString());moderation=service(failed);
        assertThrows(IllegalStateException.class,()->remove(target,proof));assertEquals(1,count("toilet_review"));assertEquals(1,count("toilet_review_submission"));verifyNoInteractions(audit);
    }
    @Test void staleVersionAndReusedNumericIdCannotDeleteTheNewIncarnation() {
        var target=create("기존 본문");var old=request(target);
        fixture.call(()->fixture.service.edit(fixture.author,id(target),new ReviewModels.Edit(0L,5,4,true,10,"새로운 내용")));
        assertEquals("REVIEW_CHANGED",assertThrows(ReviewFailure.class,()->remove(target,old)).code());
        var current=new AdminReviewModels.Remove(old.reviewKey(),1L,"ABUSE",true);
        jdbc.update("UPDATE toilet_review SET review_key=? WHERE review_id=?",UUID.randomUUID().toString(),id(target));
        assertEquals("REVIEW_CHANGED",assertThrows(ReviewFailure.class,()->remove(target,current)).code());assertEquals(1,count("toilet_review"));assertEquals(0,count("moderation_audit"));
    }
    @Test void currentAdminRevocationAndInvalidConfirmationOrReasonNeverDelete() {
        var target=create("권한 검증");var proof=request(target);
        for(var invalid:List.of(new AdminReviewModels.Remove(proof.reviewKey(),0L,"SPAM",false),
                new AdminReviewModels.Remove(proof.reviewKey(),0L,"OTHER",true),new AdminReviewModels.Remove("invalid",0L,"SPAM",true)))
            assertThrows(IllegalArgumentException.class,()->remove(target,invalid));
        jdbc.update("DELETE FROM user_role WHERE user_id=3");assertEquals(403,assertThrows(ReviewFailure.class,()->remove(target,proof)).status());
        jdbc.update("INSERT INTO user_role VALUES(3,'ADMIN')");jdbc.update("UPDATE app_user SET auth_version=5 WHERE user_id=3");
        assertEquals(401,assertThrows(ReviewFailure.class,()->remove(target,proof)).status());assertEquals(1,count("toilet_review"));verifyNoInteractions(audit);
    }
    @Test void repeatedRemovalReturnsNotFoundWithoutDuplicatingAuditAndDetachedStaleEvidenceIsCleaned() {
        var target=create("연결 해제된 합성 내용");var proof=request(target);fixture.call(()->growth.backfillUser(1,true));
        jdbc.update("UPDATE toilet_review SET author_user_id=NULL,author_detached=TRUE WHERE review_id=?",id(target));
        remove(target,proof);assertEquals(0,count("growth_review_evidence"));assertEquals(0,GrowthLedger.totalXp(jdbc,1));
        assertEquals(404,assertThrows(ReviewFailure.class,()->remove(target,proof)).status());assertEquals(1,count("moderation_audit"));
    }
}
