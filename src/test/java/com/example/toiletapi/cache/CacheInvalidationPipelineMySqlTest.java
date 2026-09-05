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

/** Real Spring scheduler -> signed HTTP -> ACK -> MySQL deletion. No production settings. */
@Testcontainers
class CacheInvalidationPipelineMySqlTest {
    @Container static MySQLContainer mysql = new MySQLContainer("mysql:8.0");
    static DriverManagerDataSource dataSource;
    static JdbcTemplate jdbc;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger responseStatus = new AtomicInteger(200);
    final AtomicInteger validSignatures = new AtomicInteger();
    HttpServer receiver;
    AnnotationConfigApplicationContext context;
    CacheInvalidationRepository repository;

    @BeforeAll static void schema() {
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY,name VARCHAR(100))");
        jdbc.execute("CREATE TABLE toilet_region (toilet_id BIGINT PRIMARY KEY,status VARCHAR(30))");
        Flyway.configure().dataSource(mysql.getJdbcUrl(),"root",mysql.getPassword())
                .baselineOnMigrate(true).baselineVersion("0").locations("classpath:db/cache-revalidation").load().migrate();
    }
    @BeforeEach void prepare() throws Exception {
        jdbc.update("DELETE FROM toilet_region");
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
                var ids = new ObjectMapper().readTree(body).get("toiletIds");
                var response = ("{\"ok\":true,\"acceptedIds\":"+ids+"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,response.length);
                exchange.getResponseBody().write(response);
            } catch (Exception error) { throw new java.io.IOException("Test receiver failed"); }
            finally { exchange.close(); }
        });
        receiver.start();
    }
    void startSender() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-only",Map.of(
                "web-cache.enabled","true", "web-cache.origin","http://127.0.0.1:"+receiver.getAddress().getPort(),
                "web-cache.secret",CacheInvalidationClientTest.SECRET, "web-cache.poll-ms","50")));
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
            jdbc.update("INSERT INTO toilet VALUES(13144,'fixture only')"); return null;
        });
        await(()->validSignatures.get()>0 && repository.pendingCount()==0);
        assertEquals("fixture only",jdbc.queryForObject("SELECT name FROM toilet WHERE toilet_id=13144",String.class));
    }
    @Test void rollbackIsNeverSent() throws Exception {
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).execute(status->{
            jdbc.update("INSERT INTO toilet VALUES(13144,'fixture only')"); status.setRollbackOnly();return null;
        });
        startSender();
        context.getBean(CacheInvalidationDispatcher.class).dispatch();
        assertEquals(0,repository.pendingCount());
        assertEquals(0,calls.get());
    }
    @Test void failedReceiverAndProcessRestartRetainAndRetryCommittedEvent() throws Exception {
        responseStatus.set(503);
        jdbc.update("INSERT INTO toilet VALUES(13144,'fixture only')");
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
