package com.example.toiletapi.review;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.policy.service.PolicyConsentService;
import com.example.toiletapi.review.ReviewModels.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/** Fast real SQL transactions; the separate MySQL test applies the unmodified production migration. */
class ReviewDatabaseTest {
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    ReviewService service;
    PolicyConsentService policies;
    final MutableClock clock=new MutableClock();
    final ReviewService.Actor author=new ReviewService.Actor(1,0),other=new ReviewService.Actor(2,0);
    @BeforeEach void setup() throws Exception {
        setup(new DriverManagerDataSource("jdbc:h2:mem:reviews_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa",""),true);
    }
    void setup(DataSource ds,boolean h2) throws Exception {
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(20),display_name VARCHAR(100),auth_version BIGINT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),latitude DECIMAL(10,7),longitude DECIMAL(10,7))");
        String ddl=new ClassPathResource("db/migration/V12__create_location_reviews.sql").getContentAsString(StandardCharsets.UTF_8);
        if(h2)ddl=ddl.replace("CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci","").replace("BOOLEAN","TINYINT");
        new ResourceDatabasePopulator(new ByteArrayResource(ddl.getBytes(StandardCharsets.UTF_8))).execute(ds);
        jdbc.update("INSERT INTO app_user(user_id,status,display_name) VALUES(1,'ACTIVE','작성자 하나'),(2,'ACTIVE','작성자 둘')");
        jdbc.update("INSERT INTO toilet VALUES(1,'합성 화장실',36.3,127.3),(2,'좌표 없는 화장실',NULL,NULL)");
        for(long id=3;id<=20;id++)jdbc.update("INSERT INTO toilet VALUES(?,'다른 합성 화장실',36.3,127.3)",id);
        policies=mock(PolicyConsentService.class);
        service=new ReviewService(new ReviewRepository(jdbc),policies,new ReviewConfiguration.ReviewSettings(true,60,10),clock,key->{});
    }
    <T>T call(Supplier<T> action){return tx.execute(status->action.get());}
    Create input(String comment){return new Create(1L,4,5,true,20,comment,new ReviewRules.Position(36.3,127.3,10.0,clock.instant()));}
    Item create(String comment){return call(()->service.create(author,input(comment),UUID.randomUUID().toString()));}
    Item createAt(long toilet,String comment){return call(()->service.create(author,new Create(toilet,4,5,true,20,comment,input(comment).position()),UUID.randomUUID().toString()));}
    long id(Item item){return Long.parseLong(item.id());}
    void failure(String code,Supplier<?> action){assertEquals(code,assertThrows(ReviewFailure.class,()->call(action)).code());}

    @Test void durableCreateIdempotencyAndNewestKeysetPage() {
        String key=UUID.randomUUID().toString();Create request=input("첫 번째");
        Item first=call(()->service.create(author,request,key));
        clock.advance(360);
        assertEquals(first.id(),call(()->service.create(author,request,key)).id()); // expired fix, no new write
        failure("REVIEW_REQUEST_REUSED",()->service.create(author,input("다른 내용"),key));
        Item second=createAt(3,"두 번째");
        Page page=call(()->service.mine(author,null,null,null,1));
        assertEquals(second.id(),page.items().getFirst().id());assertTrue(page.hasMore());
        Page next=call(()->service.mine(author,null,null,page.nextCursor(),1));
        assertEquals(first.id(),next.items().getFirst().id());assertFalse(next.hasMore());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT daily_count FROM toilet_review_write_guard WHERE user_id=1",Integer.class));
    }
    @Test void onlySelectedAuthorIsRemovedAndContentAndOtherAuthorsRemain() {
        Item first=create("지워지지 않는 본문");clock.advance(60);Item second=createAt(3,"내 다른 리뷰");
        Item third=call(()->service.create(other,input("다른 사용자 리뷰"),UUID.randomUUID().toString()));
        failure("REVIEW_NOT_FOUND",()->service.detach(other,id(first),new Detach(0L,true)));
        assertThrows(IllegalArgumentException.class,()->call(()->service.detach(author,id(first),new Detach(0L,false))));
        call(()->service.detach(author,id(first),new Detach(0L,true)));
        var rows=call(()->service.publicPage(1,null,10)).items();
        Item anonymous=rows.stream().filter(r->r.id().equals(first.id())).findFirst().orElseThrow();
        assertEquals("익명",anonymous.authorDisplayName());assertEquals(first.comment(),anonymous.comment());
        assertEquals(first.createdAt(),anonymous.createdAt());assertEquals(first.updatedAt(),anonymous.updatedAt());
        assertEquals(List.of(second.id()),call(()->service.mine(author,null,null,null,10)).items().stream().map(Item::id).toList());
        assertEquals("작성자 둘",rows.stream().filter(r->r.id().equals(third.id())).findFirst().orElseThrow().authorDisplayName());
        assertEquals("작성자 하나",jdbc.queryForObject("SELECT display_name FROM app_user WHERE user_id=1",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission WHERE review_id=?",Integer.class,id(first)));
        failure("REVIEW_NOT_FOUND",()->service.mineDetail(author,id(first)));
        failure("REVIEW_NOT_FOUND",()->service.edit(author,id(first),new Edit(1L,5,5,true,0,"수정")));
        assertEquals(2,call(()->service.summary(1)).count());
        failure("REVIEW_ALREADY_EXISTS",()->service.create(author,input("해제로 제한 우회"),UUID.randomUUID().toString()));
    }
    @Test void unlinkProtectionRunsOnlyAfterOwnerVersionDeadlineAndAcknowledgementChecks() {
        var protection=mock(ReviewUnlinkProtection.class);
        service=new ReviewService(new ReviewRepository(jdbc),policies,new ReviewConfiguration.ReviewSettings(true,60,10),clock,protection);
        Item first=create("보존할 글");
        failure("REVIEW_NOT_FOUND",()->service.detach(other,id(first),new Detach(0L,true)));
        failure("REVIEW_CHANGED",()->service.detach(author,id(first),new Detach(2L,true)));
        assertThrows(IllegalArgumentException.class,()->call(()->service.detach(author,id(first),new Detach(0L,false))));
        verifyNoInteractions(protection);
        call(()->service.detach(author,id(first),new Detach(0L,true)));
        verify(protection).record(jdbc.queryForObject("SELECT review_key FROM toilet_review WHERE review_id=?",String.class,id(first)));
    }
    @Test void failedProtectionDoesNotUnlinkOrRemoveRequestIdentity() {
        Item first=create("보존할 글");
        service=new ReviewService(new ReviewRepository(jdbc),policies,new ReviewConfiguration.ReviewSettings(true,60,10),clock,key->{throw new ReviewFailure(503,"REVIEW_UNLINK_UNAVAILABLE","synthetic");});
        failure("REVIEW_UNLINK_UNAVAILABLE",()->service.detach(author,id(first),new Detach(0L,true)));
        assertEquals(1L,jdbc.queryForObject("SELECT author_user_id FROM toilet_review",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT version FROM toilet_review",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission",Integer.class));
    }
    @Test void acknowledgedIntentSurvivesLaterDatabaseRollbackWithoutFalseSqlSuccess() {
        Item first=create("보존할 글");var intents=new ArrayList<String>();
        service=new ReviewService(new ReviewRepository(jdbc),policies,new ReviewConfiguration.ReviewSettings(true,60,10),clock,intents::add);
        assertThrows(IllegalStateException.class,()->call(()->{service.detach(author,id(first),new Detach(0L,true));throw new IllegalStateException("synthetic rollback");}));
        assertEquals(1,intents.size());assertEquals(1L,jdbc.queryForObject("SELECT author_user_id FROM toilet_review",Long.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission",Integer.class));
    }
    @Test void guardCleanupRemovesOnlyExpiredCooldownMetadataAndIsBounded() {
        Item first=create("평가와 글 보존");clock.advance(86340);
        Item second=createAt(3,"아직 제한 중");clock.advance(60);
        var cleanup=new ReviewGuardCleanup(jdbc,clock);
        assertEquals(1,call(cleanup::deleteExpired));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_write_guard",Integer.class));
        assertEquals(3,jdbc.queryForObject("SELECT toilet_id FROM toilet_review_toilet_guard",Integer.class));
        assertEquals(first.comment(),call(()->service.mineDetail(author,id(first))).comment());
        assertEquals(second.comment(),call(()->service.mineDetail(author,id(second))).comment());
        assertEquals(0,call(cleanup::deleteExpired));
        failure("REVIEW_ALREADY_EXISTS",()->service.create(author,new Create(3L,4,5,true,20,"한도 유지",input("").position()),UUID.randomUUID().toString()));
        for(int i=100;i<1101;i++){
            jdbc.update("INSERT INTO toilet VALUES(?,'합성',36.3,127.3)",i);
            jdbc.update("INSERT INTO toilet_review_toilet_guard(user_id,toilet_id,next_allowed_at) VALUES(1,?,?)",i,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.ofHours(9)));
        }
        assertEquals(1000,call(cleanup::deleteExpired));assertEquals(1,call(cleanup::deleteExpired));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_toilet_guard",Integer.class));
    }
    @Test void ownershipVersionDeadlineAndNicknameAreServerControlled() {
        Item first=create("원문");
        failure("REVIEW_NOT_FOUND",()->service.mineDetail(other,id(first)));
        failure("REVIEW_NOT_FOUND",()->service.edit(other,id(first),new Edit(0L,5,5,true,0,"타인 수정")));
        clock.advance(604799);
        Item edited=call(()->service.edit(author,id(first),new Edit(0L,5,5,false,60,"수정")));
        assertEquals(1,edited.version());assertEquals(first.createdAt(),edited.createdAt());assertEquals(first.editableUntil(),edited.editableUntil());
        failure("REVIEW_CHANGED",()->service.edit(author,id(first),new Edit(0L,1,1,true,0,"오래된 화면")));
        jdbc.update("UPDATE app_user SET display_name='새 이름' WHERE user_id=1");
        assertEquals("새 이름",call(()->service.publicPage(1,null,10)).items().getFirst().authorDisplayName());
        clock.advance(1);
        failure("REVIEW_EDIT_EXPIRED",()->service.edit(author,id(first),new Edit(1L,1,1,true,0,"기한 후")));
        failure("REVIEW_EDIT_EXPIRED",()->service.detach(author,id(first),new Detach(1L,true)));
    }
    @Test void withdrawalRecoveryAndFinalDeletionAreDistinctFromExplicitUnlink() {
        Item first=create("회원 탈퇴에도 평가 보존");
        jdbc.update("UPDATE app_user SET status='WITHDRAWN',auth_version=1,display_name='탈퇴한 사용자' WHERE user_id=1");
        assertEquals("탈퇴한 사용자",call(()->service.publicPage(1,null,10)).items().getFirst().authorDisplayName());
        failure("AUTHENTICATION_REQUIRED",()->service.mine(author,null,null,null,10));
        jdbc.update("UPDATE app_user SET status='ACTIVE',auth_version=2,display_name='복구 이름' WHERE user_id=1");
        assertEquals(first.id(),call(()->service.mine(new ReviewService.Actor(1,2),null,null,null,10)).items().getFirst().id());
        jdbc.update("DELETE FROM app_user WHERE user_id=1");
        Item retained=call(()->service.publicPage(1,null,10)).items().getFirst();
        assertEquals("탈퇴한 사용자",retained.authorDisplayName());assertEquals(first.comment(),retained.comment());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_write_guard",Integer.class));
        assertNull(jdbc.queryForObject("SELECT author_user_id FROM toilet_review",Long.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_toilet_guard",Integer.class));
    }
    @Test void staleLocationInvalidTargetAndPolicyFailuresNeverWrite() {
        var bad=new Create(1L,4,5,true,0,"",new ReviewRules.Position(36.3,127.3,51.0,clock.instant()));
        assertThrows(IllegalArgumentException.class,()->call(()->service.create(author,bad,UUID.randomUUID().toString())));
        var absent=new Create(2L,4,5,true,0,"",input("").position());
        assertThrows(IllegalArgumentException.class,()->call(()->service.create(author,absent,UUID.randomUUID().toString())));
        doThrow(new com.example.toiletapi.policy.service.PolicyConsentRequiredException()).when(policies).requireEligibleUser(1L);
        assertThrows(com.example.toiletapi.policy.service.PolicyConsentRequiredException.class,()->create("미동의"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
    }
    @Test void disabledBeforeAnyDatabaseUse() {
        var disabled=new ReviewService(mock(ReviewRepository.class),policies,new ReviewConfiguration.ReviewSettings(false,60,10),clock,key->{});
        failure("REVIEWS_DISABLED",()->disabled.create(author,input(""),UUID.randomUUID().toString()));
        failure("REVIEWS_DISABLED",()->disabled.publicPage(1,null,10));
    }
    @Test void dailyLimitAndDateFilterUseKoreanCalendarAndUnlinkDoesNotResetCounters() {
        for(int i=0;i<10;i++){createAt(i+3,"리뷰 "+i);clock.advance(60);}
        failure("REVIEW_DAILY_LIMIT",()->service.create(author,input("열한 번째"),UUID.randomUUID().toString()));
        assertEquals(10,call(()->service.mine(author,LocalDate.parse("2026-09-11"),LocalDate.parse("2026-09-11"),null,10)).items().size());
        assertTrue(call(()->service.mine(author,LocalDate.parse("2026-09-12"),null,null,10)).items().isEmpty());
        clock.now=Instant.parse("2026-09-11T15:00:00Z");create("한국시간 다음 날");
        assertEquals(1,jdbc.queryForObject("SELECT daily_count FROM toilet_review_write_guard WHERE user_id=1",Integer.class));
        assertThrows(IllegalArgumentException.class,()->call(()->service.mine(author,null,null,"bad-cursor",10)));
    }
    @Test void concurrentIdenticalPostsAreOneReviewAndOneQuotaUse() throws Exception {
        String key=UUID.randomUUID().toString();var input=input("중복 전송");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<String> submit=()->{start.await();return call(()->service.create(author,input,key)).id();};
            var a=pool.submit(submit);var b=pool.submit(submit);start.countDown();
            assertEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT daily_count FROM toilet_review_write_guard WHERE user_id=1",Integer.class));
    }
    @Test void transactionFailureRollsBackReviewAndQuotaTogether() {
        assertThrows(IllegalStateException.class,()->call(()->{service.create(author,input("롤백"),UUID.randomUUID().toString());throw new IllegalStateException("synthetic failure");}));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_write_guard",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_toilet_guard",Integer.class));
    }
    @Test void concurrentDifferentRequestsCannotBypassCooldown() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<String> submit=()->{start.await();try{return create("동시 등록").id();}catch(ReviewFailure error){return error.code();}};
            var a=pool.submit(submit);var b=pool.submit(submit);start.countDown();
            assertTrue(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).contains("REVIEW_ALREADY_EXISTS"));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
    }
    @Test void concurrentEditsRequireFreshVersion() throws Exception {
        Item first=create("동시 수정");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<String> edit=()->{start.await();try{return call(()->service.edit(author,id(first),new Edit(0L,5,5,true,0,"수정"))).id();}catch(ReviewFailure error){return error.code();}};
            var a=pool.submit(edit);var b=pool.submit(edit);start.countDown();
            assertTrue(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).contains("REVIEW_CHANGED"));
        }
        assertEquals(1,jdbc.queryForObject("SELECT version FROM toilet_review",Long.class));
    }
    @Test void verifiedAccountReplayAlsoClearsReviewReferencesWithoutChangingSharedErasureSql() {
        Item first=create("개인정보 없는 합성 리뷰 본문");
        // Empty legacy tables are sufficient to execute the unchanged production erasure contract.
        for(String ddl:List.of(
                "CREATE TABLE audit_log(actor_user_id BIGINT,actor_erased BOOLEAN,target_type VARCHAR(50),target_id BIGINT,detail_json VARCHAR(200))",
                "CREATE TABLE toilet_report(report_id BIGINT,reporter_user_id BIGINT,reviewed_by_user_id BIGINT,reason VARCHAR(100),review_note VARCHAR(100),active_request_key VARCHAR(100))",
                "CREATE TABLE coordinate_revision(applied_by_user_id BIGINT)",
                "CREATE TABLE coordinate_quality_review(reviewed_by_user_id BIGINT,review_note VARCHAR(100))",
                "CREATE TABLE user_role(user_id BIGINT,granted_by_user_id BIGINT)",
                "CREATE TABLE user_notification(user_id BIGINT)","CREATE TABLE user_policy_consent(user_id BIGINT)",
                "CREATE TABLE user_social_account(user_id BIGINT)","CREATE TABLE account_withdrawal(user_id BIGINT)",
                "ALTER TABLE app_user ADD created_at DATETIME DEFAULT '2026-09-10 09:00:00'"))jdbc.execute(ddl);
        var restore=new com.geupddong.account.AccountErasureRestore(jdbc,new DataSourceTransactionManager(jdbc.getDataSource()));
        var record=new com.geupddong.account.ErasureRecord(1,"synthetic-review",1,"2026-09-10T09:00:00",UUID.randomUUID().toString(),"2026-09-11T09:00:00");
        var result=restore.replay(List.of(record),"synthetic-review",LocalDateTime.parse("2026-09-11T09:00:00"),true);
        assertEquals(1,result.erased());assertNull(jdbc.queryForObject("SELECT author_user_id FROM toilet_review",Long.class));
        assertEquals(first.comment(),jdbc.queryForObject("SELECT comment FROM toilet_review",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_submission",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_write_guard",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM app_user",Integer.class)); // unrelated author survives
    }
    @Test void oldWaitAndPaperAreNotMadeFreshByEditsOrDetach() {
        Item first=create("원문");assertEquals(20,call(()->service.summary(1)).latestWaitMinutes());
        clock.advance(3601);
        call(()->service.edit(author,id(first),new Edit(0L,5,5,false,60,"수정")));
        assertNull(call(()->service.summary(1)).latestWaitMinutes());
        clock.advance(7*86400);
        assertEquals(0,call(()->service.summary(1)).paperSampleCount());
        assertNull(call(()->service.summary(1)).paperPercent());
        assertEquals(5.0,call(()->service.summary(1)).averageRating());
    }
    @Test void rollingDayIsPerToiletAndOwnerWithExistingReviewNavigationBeforeLocation() {
        clock.now=Instant.parse("2026-09-11T14:59:30Z"); // KST midnight must not reset the window.
        Item first=create("첫 리뷰");
        var initial=call(()->service.creationStatus(author,1));
        assertFalse(initial.canCreate());assertEquals(first.id(),initial.existingReviewId());
        assertEquals(first.createdAt().plusHours(24),initial.nextAllowedAt());
        assertTrue(call(()->service.creationStatus(other,1)).canCreate());
        assertTrue(call(()->service.creationStatus(author,3)).canCreate());
        var noLocation=new Create(1L,4,5,true,0,"",null);
        var error=assertThrows(ReviewFailure.class,()->call(()->service.create(author,noLocation,UUID.randomUUID().toString())));
        assertEquals("REVIEW_ALREADY_EXISTS",error.code());assertEquals(first.id(),error.creationStatus().existingReviewId());
        clock.advance(60);createAt(3,"다른 화장실 허용");
        failure("REVIEW_ALREADY_EXISTS",()->service.create(author,input("자정 후 우회"),UUID.randomUUID().toString()));
        clock.advance(86339);
        call(()->service.edit(author,id(first),new Edit(0L,5,5,true,0,"기존 리뷰 수정")));
        assertFalse(call(()->service.creationStatus(author,1)).canCreate());
        clock.advance(1);
        assertTrue(call(()->service.creationStatus(author,1)).canCreate());
        Item next=create("정확히 24시간 후");assertNotEquals(first.id(),next.id());
        assertEquals(next.id(),call(()->service.creationStatus(author,1)).existingReviewId());
    }
    @Test void unlinkKeepsOnlyThrottleAndCannotReturnOrReconnectTheAnonymousReview() {
        Item first=create("익명 본문 유지");
        call(()->service.detach(author,id(first),new Detach(0L,true)));
        clock.advance(60);
        var state=call(()->service.creationStatus(author,1));
        assertFalse(state.canCreate());assertNull(state.existingReviewId());
        var error=assertThrows(ReviewFailure.class,()->create("재작성 우회"));
        assertNull(error.creationStatus().existingReviewId());
        assertTrue(call(()->service.mine(author,null,null,null,10)).items().isEmpty());
        clock.advance(86340);create("24시간 후 새 리뷰");
        assertNull(jdbc.queryForObject("SELECT author_user_id FROM toilet_review WHERE review_id=?",Long.class,id(first)));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review_toilet_guard",Integer.class));
    }
    @Test void accountWideCooldownStillAppliesAcrossDifferentToilets() {
        create("첫 리뷰");
        failure("REVIEW_COOLDOWN",()->service.create(author,new Create(3L,4,5,true,0,"",input("").position()),UUID.randomUUID().toString()));
        clock.advance(60);createAt(3,"다른 화장실");
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-09-11T00:00:00Z");
        void advance(long seconds){now=now.plusSeconds(seconds);}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
}
