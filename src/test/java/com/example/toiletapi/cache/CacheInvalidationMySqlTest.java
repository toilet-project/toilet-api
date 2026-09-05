package com.example.toiletapi.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
        jdbc.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),latitude DECIMAL(10,7))");
        jdbc.execute("CREATE TABLE toilet_region (toilet_id BIGINT PRIMARY KEY,status VARCHAR(30))");
        // DDL is an explicit DBA operation; application writes below keep the regular test user.
        var ddlDataSource=new DriverManagerDataSource(mysql.getJdbcUrl(),"root",mysql.getPassword());
        Flyway.configure().dataSource(ddlDataSource).baselineOnMigrate(true).baselineVersion("0")
                .locations("classpath:db/cache-revalidation").load().migrate();
        repository=new CacheInvalidationRepository(jdbc);
    }
    @BeforeEach void clear() {jdbc.update("DELETE FROM toilet_region");jdbc.update("DELETE FROM toilet");jdbc.update("DELETE FROM web_cache_invalidation");}
    @Test void queueIsCommittedAndRolledBackWithTheToiletMutation() {
        var tx=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.execute(status->{
            jdbc.update("INSERT INTO toilet VALUES (1,'sample',37)");
            assertEquals(1,repository.due().size());
            assertTrue(CompletableFuture.supplyAsync(repository::due).join().isEmpty(),"dispatcher cannot observe an uncommitted event");
            status.setRollbackOnly();return null;
        });
        assertTrue(repository.due().isEmpty());
        tx.execute(status->{jdbc.update("INSERT INTO toilet VALUES (1,'sample',37)");return null;});
        assertEquals(1,repository.due().size());
    }
    @Test void repeatedMutationsCoalesceButOldAckCannotEraseANewerEvent() {
        jdbc.update("INSERT INTO toilet VALUES (1,'sample',37)"); var old=repository.due().getFirst();
        jdbc.update("UPDATE toilet SET name='new name' WHERE toilet_id=1"); var latest=repository.due().getFirst();
        assertNotEquals(old.eventId(),latest.eventId()); assertEquals(1,repository.pendingCount());
        repository.acknowledge(old); repository.retry(old,"HTTP_503");
        assertEquals(1,repository.pendingCount()); assertEquals(0,repository.due().getFirst().attempts());
        repository.acknowledge(latest); assertTrue(repository.due().isEmpty());
        jdbc.update("UPDATE toilet SET latitude=38 WHERE toilet_id=1");
        repository.acknowledge(latest); assertEquals(1,repository.pendingCount(),"new event after deletion must survive old ACK (ABA)");
    }
    @Test void regionCompletionRemovalAndToiletDeletionAreAllCaptured() {
        jdbc.update("INSERT INTO toilet VALUES (1,'sample',37)"); repository.acknowledge(repository.due().getFirst());
        jdbc.update("INSERT INTO toilet_region VALUES (1,'VERIFIED')"); assertEquals(1,repository.pendingCount());
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("UPDATE toilet_region SET status='REVIEW_REQUIRED' WHERE toilet_id=1"); assertEquals(1,repository.pendingCount());
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("DELETE FROM toilet_region WHERE toilet_id=1"); assertEquals(1,repository.pendingCount());
        repository.acknowledge(repository.due().getFirst());
        jdbc.update("DELETE FROM toilet WHERE toilet_id=1"); assertEquals(1,repository.pendingCount());
    }
    @Test void failedDeliveryRemainsDurableAndIsDeferred() {
        jdbc.update("INSERT INTO toilet VALUES (1,'sample',37)"); var item=repository.due().getFirst();
        repository.retry(item,"HTTP_503");
        assertTrue(repository.due().isEmpty());
        assertEquals(1,new CacheInvalidationRepository(new JdbcTemplate(dataSource)).pendingCount());
        assertEquals(1,jdbc.queryForObject("SELECT attempts FROM web_cache_invalidation",Integer.class));
        assertEquals("HTTP_503",jdbc.queryForObject("SELECT last_error_code FROM web_cache_invalidation",String.class));
        jdbc.update("UPDATE web_cache_invalidation SET next_attempt_at=UTC_TIMESTAMP(6)");
        repository.acknowledge(repository.due().getFirst()); assertEquals(0,repository.pendingCount());
    }
}
