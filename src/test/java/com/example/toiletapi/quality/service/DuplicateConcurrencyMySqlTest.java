package com.example.toiletapi.quality.service;

import static com.example.toiletapi.quality.dto.DuplicateNameModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Synthetic fixtures in a disposable CI MySQL only; never connects to production. */
@Testcontainers
class DuplicateConcurrencyMySqlTest {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.0");
    JdbcTemplate db;
    DuplicateNameService service;
    TransactionTemplate transaction;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        db = new JdbcTemplate(ds);
        db.execute("DROP TABLE IF EXISTS duplicate_name_work_visibility");
        db.execute("DROP TABLE IF EXISTS public_data_change_review");
        db.execute("DROP TABLE IF EXISTS toilet_visibility_event");
        db.execute("DROP TABLE IF EXISTS current_toilet_region");
        db.execute("DROP TABLE IF EXISTS toilet");
        db.execute("DROP TABLE IF EXISTS app_user");
        db.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY)");
        db.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),mng_no VARCHAR(50),road_address VARCHAR(255),jibun_address VARCHAR(255),latitude DECIMAL(10,7),longitude DECIMAL(10,7),coordinate_source VARCHAR(30),open_time VARCHAR(50),data_source VARCHAR(20))");
        db.execute("CREATE TABLE public_data_change_review(active_toilet_id BIGINT,status VARCHAR(20),status_reason VARCHAR(500),version BIGINT)");
        db.execute("CREATE TABLE current_toilet_region(toilet_id BIGINT PRIMARY KEY,sigungu_code VARCHAR(5),sido_name VARCHAR(50),sigungu_name VARCHAR(100))");
        try (var connection = ds.getConnection()) {
            for (var migration : List.of("V23__duplicate_facility_visibility.sql", "V24__duplicate_name_work_visibility.sql", "V25__align_duplicate_work_name_collation.sql")) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/" + migration));
            }
        }
        db.update("INSERT INTO app_user VALUES(7),(8)");
        db.update("INSERT INTO toilet(toilet_id,name) VALUES(1,'synthetic park'),(2,'synthetic park')");
        var manager = new DataSourceTransactionManager(ds);
        transaction = new TransactionTemplate(manager);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(new DuplicateNameService(new NamedParameterJdbcTemplate(ds)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(interceptor);
        service = (DuplicateNameService) proxy.getProxy();
    }

    private void assertSecondWriteWaitsThenConflicts(Supplier<?> firstWrite, Supplier<?> secondWrite) throws Exception {
        var firstWritten = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                var first = workers.submit(() -> transaction.execute(status -> {
                    firstWrite.get();
                    firstWritten.countDown();
                    try {
                        if (!releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return true;
                }));
                assertTrue(firstWritten.await(10, TimeUnit.SECONDS));
                var second = workers.submit(() -> {
                    secondStarted.countDown();
                    try { secondWrite.get(); return 200; }
                    catch (ResponseStatusException e) { return e.getStatusCode().value(); }
                });
                assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS));
                releaseFirst.countDown();
                assertTrue(first.get(10, TimeUnit.SECONDS));
                assertEquals(409, second.get(10, TimeUnit.SECONDS));
            } finally { releaseFirst.countDown(); workers.shutdownNow(); }
        }
    }

    @Test void concurrentFacilityHideCreatesExactlyOneEventThenRenamedFacilityCanRestore() throws Exception {
        var request = new HideRequest(1, List.of(2L), Map.of(1L,0L,2L,0L), "synthetic concurrency check");
        assertSecondWriteWaitsThenConflicts(() -> service.hide(7, request), () -> service.hide(8, request));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM toilet_visibility_event", Integer.class));
        assertEquals(1L, db.queryForObject("SELECT visibility_version FROM toilet WHERE toilet_id=2", Long.class));
        assertEquals("VISIBLE", db.queryForObject("SELECT visibility_status FROM toilet WHERE toilet_id=1", String.class));
        db.update("UPDATE toilet SET name='renamed synthetic park' WHERE toilet_id=2");
        assertEquals(1, service.groups("renamed synthetic", true, 0, 20).totalElements());
        service.restore(7, 2, new RestoreRequest(1, "synthetic restore after reviewed name change"));
        assertEquals("VISIBLE", db.queryForObject("SELECT visibility_status FROM toilet WHERE toilet_id=2", String.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM toilet_visibility_event", Integer.class));
    }

    @Test void concurrentPreferenceWriteRejectsStaleVersionWithoutChangingFacilities() throws Exception {
        var request = new WorkVisibilityRequest("synthetic park", true, 0);
        assertSecondWriteWaitsThenConflicts(() -> service.setWorkVisibility(7, request), () -> service.setWorkVisibility(7, request));
        assertEquals(1L, db.queryForObject("SELECT version FROM duplicate_name_work_visibility WHERE admin_user_id=7", Long.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM toilet WHERE visibility_status='VISIBLE'", Integer.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM toilet_visibility_event", Integer.class));
        assertEquals(0, service.groups("", false, 0, 20, Comparison.ALL, 7, WorkVisibility.VISIBLE).totalElements());
        assertEquals(1, service.groups("", false, 0, 20, Comparison.ALL, 8, WorkVisibility.VISIBLE).totalElements());
        assertFalse(service.setWorkVisibility(7, new WorkVisibilityRequest("synthetic park", false, 1)).hidden());
    }
}
