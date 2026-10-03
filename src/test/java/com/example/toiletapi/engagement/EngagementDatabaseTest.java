package com.example.toiletapi.engagement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.policy.service.PolicyConsentService;
import com.example.toiletapi.toilet.translation.ToiletTranslationService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class EngagementDatabaseTest {
    JdbcTemplate jdbc; TransactionTemplate tx; EngagementService service; EngagementRepository repository;
    MutableClock clock=new MutableClock();
    PolicyConsentService policies;
    final EngagementService.Actor user=new EngagementService.Actor(1,2);
    @BeforeEach void setup() throws Exception { setup(new DriverManagerDataSource("jdbc:h2:mem:engagement_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","sa","")); }
    void setup(DataSource source) throws Exception {
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY,status VARCHAR(20),auth_version BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),toilet_type VARCHAR(30),latitude DECIMAL(10,7),longitude DECIMAL(10,7),visibility_status VARCHAR(30),updated_at DATETIME(6),region_revision BIGINT)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V36__toilet_engagement.sql")).execute(source);
        jdbc.update("INSERT INTO app_user VALUES(1,'ACTIVE',2),(2,'ACTIVE',0),(3,'WITHDRAWN',1)");
        jdbc.update("INSERT INTO toilet VALUES(1,'첫 화장실','공중화장실',37.5000000,127.0000000,'VISIBLE','2026-09-01 00:00:00',42),"
                + "(2,'둘째 화장실','개방화장실',37.6000000,127.0000000,'VISIBLE','2026-09-01 00:00:00',42),"
                + "(3,'숨김 화장실','공중화장실',37.4000000,127.0000000,'HIDDEN_DUPLICATE','2026-09-01 00:00:00',42)");
        repository=new EngagementRepository(jdbc);policies=mock(PolicyConsentService.class);
        var translations=mock(ToiletTranslationService.class);
        when(translations.currentMarkerNames(anyCollection())).thenReturn(Map.of(1L,Map.of("en","First restroom")));
        service=new EngagementService(repository,policies,translations,
                new EngagementConfiguration.Settings(true,"fixture-only-secret-at-least-32-characters",Set.of("https://geupddong.com")),clock);
    }
    EngagementService.ViewRequest event(String session) {return new EngagementService.ViewRequest(session,UUID.randomUUID().toString());}
    EngagementService.ViewResult view(long id,EngagementService.ViewRequest event) {return tx.execute(s->service.view(id,event,false));}
    EngagementService.LikeState like(boolean value) {return tx.execute(s->service.setLike(1,user,value));}
    @Test void thirtyMinuteWindowAndRetriesPreserveCacheSource() {
        var first=event(UUID.randomUUID().toString());
        assertTrue(view(1,first).counted());
        assertFalse(view(1,first).counted());
        clock.advance(1799);assertFalse(view(1,event(first.sessionId())).counted());
        clock.advance(1);assertTrue(view(1,event(first.sessionId())).counted());
        assertFalse(view(1,first).counted());
        assertEquals(2,service.counts(1).views());
        assertEquals(2,jdbc.queryForObject("SELECT SUM(views) FROM toilet_view_daily",Long.class));
        assertEquals(42,jdbc.queryForObject("SELECT region_revision FROM toilet WHERE toilet_id=1",Long.class));
        assertEquals(LocalDateTime.parse("2026-09-01T00:00:00"),jdbc.queryForObject("SELECT updated_at FROM toilet WHERE toilet_id=1",LocalDateTime.class));
    }
    @Test void facilityAndEventScopeBotAndRollback() {
        var e=event(UUID.randomUUID().toString());view(1,e);
        assertEquals(409,assertThrows(EngagementFailure.class,()->view(2,e)).status());
        assertEquals(404,assertThrows(EngagementFailure.class,()->view(3,event(e.sessionId()))).status());
        assertFalse(tx.execute(s->service.view(2,event(e.sessionId()),true)).counted());
        assertEquals(0,service.counts(2).views());
        assertThrows(IllegalStateException.class,()->tx.execute(s->{service.view(2,event(e.sessionId()),false);throw new IllegalStateException("fixture rollback");}));
        assertEquals(0,service.counts(2).views());
    }
    @Test void concurrentDistinctViewsHaveNoLostIncrementsAndSameSessionCountsOnce() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            var same=event(UUID.randomUUID().toString());
            var jobs=new ArrayList<Callable<Boolean>>();
            for(int i=0;i<16;i++)jobs.add(()->view(1,same).counted());
            int added=0;for(var result:pool.invokeAll(jobs))if(result.get())added++;
            assertEquals(1,added);
            jobs.clear();for(int i=0;i<16;i++)jobs.add(()->view(1,event(UUID.randomUUID().toString())).counted());
            for(var result:pool.invokeAll(jobs))assertTrue(result.get());
            assertEquals(17,service.counts(1).views());
        } finally {pool.shutdownNow();}
    }
    @Test void likesAreIdempotentAccountBoundAndEraseWithUser() {
        assertTrue(like(true).liked());assertEquals(1,like(true).likes());
        assertEquals(1,tx.execute(s->service.mine(1,user)).likes());
        assertEquals(0,like(false).likes());assertFalse(like(false).liked());
        like(true);
        assertEquals(401,assertThrows(EngagementFailure.class,()->tx.execute(s->service.setLike(1,new EngagementService.Actor(1,1),true))).status());
        assertEquals(403,assertThrows(EngagementFailure.class,()->tx.execute(s->service.setLike(1,new EngagementService.Actor(3,1),true))).status());
        jdbc.update("UPDATE app_user SET status='WITHDRAWN' WHERE user_id=1");
        assertEquals(0,service.counts(1).likes());
        jdbc.update("DELETE FROM app_user WHERE user_id=1");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_like",Integer.class));
        verify(policies,atLeastOnce()).requireEligibleUser(1L);
    }
    @Test void ownLikesArePagedSortedAndNeverExposeOtherUsersOrHiddenFacilities() {
        like(true);clock.advance(60);tx.executeWithoutResult(s->service.setLike(2,user,true));
        jdbc.update("INSERT INTO toilet_like VALUES(1,3,'2026-10-01 00:02:00')");
        jdbc.update("INSERT INTO toilet_like VALUES(2,1,'2026-10-01 00:03:00')");
        var recent=tx.execute(s->service.likedToilets(user,"newest",0,1,null,null));
        assertEquals(2,recent.total());assertEquals(List.of(2L),recent.items().stream().map(EngagementService.LikedToilet::id).toList());
        assertEquals("First restroom",tx.execute(s->service.likedToilets(user,"oldest",0,1,null,null))
                .items().getFirst().translations().get("en"));
        assertEquals(List.of(1L,2L),tx.execute(s->service.likedToilets(user,"distance",0,20,37.5,127.0))
                .items().stream().map(EngagementService.LikedToilet::id).toList());
        assertEquals(400,assertThrows(EngagementFailure.class,()->tx.execute(s->service.likedToilets(user,"distance",0,20,null,null))).status());
        assertEquals(401,assertThrows(EngagementFailure.class,()->tx.execute(s->service.likedToilets(new EngagementService.Actor(1,1),"newest",0,20,null,null))).status());
        tx.executeWithoutResult(s->service.setLike(2,user,false));
        assertEquals(1,tx.execute(s->service.likedToilets(user,"newest",0,20,null,null)).total());
    }
    @Test void boundedCleanupKeepsAggregateTotals() {
        view(1,event(UUID.randomUUID().toString()));clock.advance(86401);
        repository.cleanup(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.ofHours(9)));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_view_receipt",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_view_guard",Integer.class));
        assertEquals(1,service.counts(1).views());
    }
    static class MutableClock extends Clock {
        Instant value=Instant.parse("2026-10-01T00:00:00Z");
        void advance(long seconds) {value=value.plusSeconds(seconds);}
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return value;}
    }
}
