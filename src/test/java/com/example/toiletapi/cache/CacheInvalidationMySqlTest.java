package com.example.toiletapi.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

// Always requires disposable Docker MySQL. Never reads SPRING_DB_URL or production credentials.
@Testcontainers
class CacheInvalidationMySqlTest {
    @Container static final MySQLContainer mysql=new MySQLContainer("mysql:8.0");
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    static CacheInvalidationRepository repository;
    @BeforeAll static void schema() {
        dataSource=new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),latitude DECIMAL(10,7),visibility_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE')");
        jdbc.execute("CREATE TABLE toilet_region (toilet_id BIGINT PRIMARY KEY,status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_region_assignment (toilet_id BIGINT PRIMARY KEY,status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_region_decision (toilet_id BIGINT PRIMARY KEY,status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_opening_hours (toilet_id BIGINT PRIMARY KEY,is_open_24h BOOLEAN,normalization_status VARCHAR(24),source_changed BOOLEAN)");
        jdbc.execute("CREATE TABLE toilet_translation (toilet_id BIGINT NOT NULL,locale VARCHAR(12) NOT NULL,name VARCHAR(255),PRIMARY KEY(toilet_id,locale))");
        // DDL is an explicit DBA operation; application writes below keep the regular test user.
        var ddlDataSource=new DriverManagerDataSource(mysql.getJdbcUrl(),"root",mysql.getPassword());
        Flyway.configure().dataSource(ddlDataSource).baselineOnMigrate(true).baselineVersion("0")
                .locations("classpath:db/cache-revalidation").load().migrate();
        repository=new CacheInvalidationRepository(jdbc);
    }
    @BeforeEach void clear() {jdbc.update("DELETE FROM toilet_translation");jdbc.update("DELETE FROM toilet_region_decision");jdbc.update("DELETE FROM toilet_region_assignment");jdbc.update("DELETE FROM toilet_region");jdbc.update("DELETE FROM toilet_opening_hours");jdbc.update("DELETE FROM toilet");jdbc.update("DELETE FROM web_cache_invalidation");}
    @AfterAll static void rollbackRetainsQueueButRemovesOnlyOwnedTriggers() throws Exception {
        long before = repository.pendingCount();
        assertEquals(19, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()", Integer.class));
        try (var connection = new DriverManagerDataSource(mysql.getJdbcUrl(),"root",mysql.getPassword()).getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/cache-revalidation/rollback_triggers.sql"));
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()", Integer.class));
        assertEquals(before, repository.pendingCount(), "Rollback must retain queued events");
    }
    @Test void queueIsCommittedAndRolledBackWithTheToiletMutation() {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.execute(status->{
            jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)");
            assertEquals(1,repository.due().size());
            assertTrue(CompletableFuture.supplyAsync(repository::due).join().isEmpty(),"dispatcher cannot observe an uncommitted event");
            status.setRollbackOnly();return null;
        });
        assertTrue(repository.due().isEmpty());
        tx.execute(status->{jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)");return null;});
        assertEquals(1,repository.due().size());
    }
    @Test void repeatedMutationsCoalesceButOldAckCannotEraseANewerEvent() {
        jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)"); var old=repository.due().getFirst();
        jdbc.update("UPDATE toilet SET name='new name' WHERE toilet_id=1"); var latest=repository.due().getFirst();
        assertNotEquals(old.eventId(),latest.eventId()); assertEquals(old.revision()+1,latest.revision()); assertEquals(1,repository.pendingCount());
        repository.acknowledge(old); repository.retry(old,"HTTP_503");
        assertEquals(1,repository.pendingCount()); assertEquals(0,repository.due().getFirst().attempts());
        repository.acknowledge(latest); assertTrue(repository.due().isEmpty());
        jdbc.update("UPDATE toilet SET latitude=38 WHERE toilet_id=1");
        var afterAck=repository.due().getFirst();
        assertEquals(latest.revision()+1,afterAck.revision(),"delivered rows retain a monotonic revision");
        repository.acknowledge(latest); assertEquals(1,repository.pendingCount(),"new event after acknowledgement must survive old ACK (ABA)");
    }
    @Test void regionCompletionRemovalAndToiletDeletionAreAllCaptured() {
        jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)"); repository.acknowledge(repository.due().getFirst());
        jdbc.update("INSERT INTO toilet_region VALUES (1,'VERIFIED')"); assertEquals(1,repository.pendingCount());
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("UPDATE toilet_region SET status='REVIEW_REQUIRED' WHERE toilet_id=1"); assertEquals(1,repository.pendingCount());
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("DELETE FROM toilet_region WHERE toilet_id=1"); assertEquals(1,repository.pendingCount());
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("DELETE FROM toilet WHERE toilet_id=1"); assertEquals(1,repository.pendingCount());
        var deletion=repository.due().getFirst(); assertEquals(CacheInvalidationEvent.Action.DELETE,deletion.action()); assertTrue(deletion.catalogChanged());
    }
    @Test void normalizedRegionWritesRemainCapturedAfterLegacyRemoval() {
        jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)"); repository.acknowledge(repository.due().getFirst());
        jdbc.update("INSERT INTO toilet_region_assignment VALUES (1,'VERIFIED')"); var assignment=repository.due().getFirst();
        assertEquals(CacheInvalidationEvent.Action.UPSERT,assignment.action()); assertFalse(assignment.catalogChanged());
        repository.acknowledge(assignment);
        jdbc.update("INSERT INTO toilet_region_decision VALUES (1,'VERIFIED')"); assertEquals(1,repository.pendingCount());
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("DELETE FROM toilet_region_assignment WHERE toilet_id=1"); assertEquals(1,repository.pendingCount());
    }
    @Test void normalizedOpeningHoursRefreshDetailAndFilteredCatalog() {
        jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)");
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("INSERT INTO toilet_opening_hours VALUES (1,TRUE,'PARSED',FALSE)");
        var inserted=repository.due().getFirst();
        assertEquals(CacheInvalidationEvent.Action.UPSERT,inserted.action());
        assertTrue(inserted.catalogChanged());
        repository.acknowledge(inserted);
        jdbc.update("UPDATE toilet_opening_hours SET is_open_24h=FALSE WHERE toilet_id=1");
        assertTrue(repository.due().getFirst().catalogChanged());
    }
    @Test void visibilityChangesInvalidateBothDetailAndCatalog() {
        jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)");
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("UPDATE toilet SET visibility_status='HIDDEN_DUPLICATE' WHERE toilet_id=1");
        var hidden = repository.due().getFirst();
        assertEquals(CacheInvalidationEvent.Action.PRIVATE, hidden.action());
        assertTrue(hidden.catalogChanged());
        repository.acknowledge(hidden);
        jdbc.update("UPDATE toilet SET visibility_status='VISIBLE' WHERE toilet_id=1");
        var restored = repository.due().getFirst();
        assertEquals(CacheInvalidationEvent.Action.UPSERT, restored.action());
        assertTrue(restored.catalogChanged());
        assertEquals(hidden.revision()+1, restored.revision());
    }
    @Test void nonKoreanTranslationChangesInvalidateDetailAndCatalog() {
        jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)");
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("INSERT INTO toilet_translation VALUES (1,'ko','원문')");
        assertEquals(0, repository.pendingCount(), "Korean mirror writes are covered by the canonical toilet event");
        jdbc.update("INSERT INTO toilet_translation VALUES (1,'en','Restroom')");
        var inserted = repository.due().getFirst();
        assertEquals(CacheInvalidationEvent.Action.UPSERT, inserted.action());
        assertTrue(inserted.catalogChanged());
        repository.acknowledge(inserted);
        jdbc.update("UPDATE toilet_translation SET name='Public Restroom' WHERE toilet_id=1 AND locale='en'");
        assertTrue(repository.due().getFirst().catalogChanged());
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("DELETE FROM toilet_translation WHERE toilet_id=1 AND locale='en'");
        assertEquals(CacheInvalidationEvent.Action.UPSERT, repository.due().getFirst().action());
    }
    @Test void failedDeliveryRemainsDurableAndIsDeferred() {
        jdbc.update("INSERT INTO toilet (toilet_id,name,latitude) VALUES (1,'sample',37)"); var item=repository.due().getFirst();
        repository.retry(item,"HTTP_503");
        assertTrue(repository.due().isEmpty());
        assertEquals(1,new CacheInvalidationRepository(new JdbcTemplate(dataSource)).pendingCount());
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM web_cache_invalidation",Integer.class));
        assertEquals("HTTP_503",jdbc.queryForObject("SELECT last_error_code FROM web_cache_invalidation",String.class));
        jdbc.update("UPDATE web_cache_invalidation SET next_attempt_at=UTC_TIMESTAMP(6)");
        repository.acknowledge(repository.due().getFirst()); assertEquals(0,repository.pendingCount());
    }
}
