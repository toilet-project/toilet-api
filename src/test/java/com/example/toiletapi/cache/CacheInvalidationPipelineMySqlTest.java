package com.example.toiletapi.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Real Spring scheduler -> signed HTTP -> ACK marker in MySQL. No production settings. */
@Testcontainers
class CacheInvalidationPipelineMySqlTest {
    @Container static MySQLContainer mysql = new MySQLContainer("mysql:8.0");
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger responseStatus = new AtomicInteger(200);
    final AtomicInteger validSignatures = new AtomicInteger();
    final AtomicReference<JsonNode> lastPayload = new AtomicReference<>();
    HttpServer receiver;
    AnnotationConfigApplicationContext context;
    CacheInvalidationRepository repository;

    @BeforeAll static void schema() {
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),latitude DECIMAL(10,7),longitude DECIMAL(10,7),road_address VARCHAR(255),jibun_address VARCHAR(255),visibility_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE')");
        jdbc.execute("CREATE TABLE toilet_region (toilet_id BIGINT PRIMARY KEY,status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_region_assignment (toilet_id BIGINT PRIMARY KEY,status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_region_decision (toilet_id BIGINT PRIMARY KEY,status VARCHAR(30))");
        jdbc.execute("CREATE TABLE toilet_opening_hours (toilet_id BIGINT PRIMARY KEY,is_open_24h BOOLEAN,normalization_status VARCHAR(24),source_changed BOOLEAN)");
        jdbc.execute("CREATE TABLE toilet_translation (toilet_id BIGINT NOT NULL,locale VARCHAR(12) NOT NULL,name VARCHAR(255),PRIMARY KEY(toilet_id,locale))");
        jdbc.execute("CREATE TABLE toilet_display_group (group_id BIGINT PRIMARY KEY,display_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE toilet_display_group_member (group_id BIGINT,toilet_id BIGINT,sort_order INT DEFAULT 0,PRIMARY KEY(group_id,toilet_id),FOREIGN KEY(group_id) REFERENCES toilet_display_group(group_id) ON DELETE CASCADE)");
        jdbc.execute("CREATE TABLE toilet_display_group_translation (group_id BIGINT,locale VARCHAR(10),display_name VARCHAR(255),PRIMARY KEY(group_id,locale),FOREIGN KEY(group_id) REFERENCES toilet_display_group(group_id) ON DELETE CASCADE)");
        Flyway.configure().dataSource(mysql.getJdbcUrl(),"root",mysql.getPassword())
                .baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/cache-revalidation").load().migrate();
    }
    @BeforeEach void prepare() throws Exception {
        jdbc.update("DELETE FROM toilet_display_group_translation");
        jdbc.update("DELETE FROM toilet_display_group_member");
        jdbc.update("DELETE FROM toilet_display_group");
        jdbc.update("DELETE FROM toilet_translation");
        jdbc.update("DELETE FROM toilet_region_decision");
        jdbc.update("DELETE FROM toilet_region_assignment");
        jdbc.update("DELETE FROM toilet_region");
        jdbc.update("DELETE FROM toilet_opening_hours");
        jdbc.update("DELETE FROM toilet");
        jdbc.update("DELETE FROM web_cache_invalidation");
        repository = new CacheInvalidationRepository(jdbc);
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        receiver.createContext(CacheInvalidationClient.PATH, exchange -> {
            calls.incrementAndGet();
            var body = new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            int status = responseStatus.get();
            try {
                var timestamp = exchange.getRequestHeaders().getFirst("x-cache-timestamp");
                var expected = CacheInvalidationClient.signature(CacheInvalidationClientTest.SECRET,timestamp,body);
                if (!expected.equals(exchange.getRequestHeaders().getFirst("x-cache-signature"))) status=401;
                else validSignatures.incrementAndGet();
                var payload = new ObjectMapper().readTree(body);
                lastPayload.set(payload);
                var events = payload.get("events");
                var response = (events == null
                        ? "{\"ok\":true,\"acceptedIds\":"+payload.get("toiletIds")+"}"
                        : "{\"ok\":true,\"acceptedEvents\":"+events+"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception error) { throw new java.io.IOException("Test receiver failed"); }
            finally { exchange.close(); }
        });
        receiver.start();
    }
    void startSender() { startSender(2); }
    void startSender(int contractVersion) {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-only",Map.of(
                "web-cache.enabled","true", "web-cache.origin","http://127.0.0.1:"+receiver.getAddress().getPort(),
                "web-cache.secret",CacheInvalidationClientTest.SECRET, "web-cache.poll-ms","50",
                "web-cache.contract-version",String.valueOf(contractVersion))));
        context.registerBean(JdbcTemplate.class,()->jdbc);
        context.registerBean(SimpleMeterRegistry.class,SimpleMeterRegistry::new);
        // Closing the old process must finish its in-flight delivery before the test
        // advances the retry clock and starts the replacement process.
        context.registerBean("taskScheduler",ThreadPoolTaskScheduler.class,()->{
            var scheduler=new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setWaitForTasksToCompleteOnShutdown(true);
            scheduler.setAwaitTerminationSeconds(15);
            return scheduler;
        });
        context.register(CacheInvalidationRepository.class,CacheInvalidationDispatcher.class,CacheInvalidationConfiguration.class);
        context.refresh();
    }
    @AfterEach void close() { if (context!=null) context.close(); if(receiver!=null) receiver.stop(0); }
    void await(java.util.function.BooleanSupplier condition) throws Exception {
        // A CI runner may need longer than the client's 8-second HTTP timeout.
        long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),()->"Bounded pipeline wait expired: calls="+calls.get()
                +", signed="+validSignatures.get()+", queue="+jdbc.queryForList(
                    "SELECT toilet_id,attempts,last_error_code FROM web_cache_invalidation"));
    }
    @Test void committedChangeIsAutomaticallySignedAndAcknowledged() throws Exception {
        startSender();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status->{
            jdbc.update("INSERT INTO toilet (toilet_id,name) VALUES(13144,'fixture only')"); return null;
        });
        await(()->validSignatures.get()>0 && repository.pendingCount()==0);
        assertEquals("fixture only",jdbc.queryForObject("SELECT name FROM toilet WHERE toilet_id=13144",String.class));
    }
    @Test void v3SendsScopedCoordinatesAndAcknowledgesThem() throws Exception {
        startSender(3);
        jdbc.update("INSERT INTO toilet (toilet_id,name,latitude,longitude) VALUES(13144,'scoped',36.1,127.1)");
        await(()->validSignatures.get()>0 && repository.pendingCount()==0);
        var payload=lastPayload.get();
        assertEquals(3,payload.path("contractVersion").asInt());
        var event=payload.path("events").get(0);
        assertTrue(event.path("regionScopeComplete").asBoolean());
        assertEquals(127.1,event.path("regionBounds").path("west").asDouble(),0.0000001);
        assertEquals(36.1,event.path("regionBounds").path("south").asDouble(),0.0000001);
    }
    @Test void rollbackIsNeverSent() throws Exception {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status->{
            jdbc.update("INSERT INTO toilet (toilet_id,name) VALUES(13144,'fixture only')"); status.setRollbackOnly();return null;
        });
        startSender();
        context.getBean(CacheInvalidationDispatcher.class).dispatch();
        assertEquals(0,repository.pendingCount());
        assertEquals(0,calls.get());
    }
    @Test void failedReceiverAndProcessRestartRetainAndRetryCommittedEvent() throws Exception {
        responseStatus.set(503);
        jdbc.update("INSERT INTO toilet (toilet_id,name) VALUES(13144,'fixture only')");
        startSender();
        await(()->jdbc.queryForObject("SELECT attempts FROM web_cache_invalidation WHERE toilet_id=13144",Integer.class)>0);
        assertEquals(1,repository.pendingCount());
        context.close(); context=null;
        responseStatus.set(200);
        // Accelerate only the disposable test DB's backoff clock; production retains its delay.
        jdbc.update("UPDATE web_cache_invalidation SET next_attempt_at=UTC_TIMESTAMP(6)");
        startSender();
        await(()->repository.pendingCount()==0);
        assertTrue(validSignatures.get()>=2);
    }
}
