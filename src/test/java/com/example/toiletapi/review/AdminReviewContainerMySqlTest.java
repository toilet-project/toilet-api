package com.example.toiletapi.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Runs the same pagination, retention and session SQL against CI's disposable MySQL. */
@Testcontainers
class AdminReviewContainerMySqlTest extends AdminReviewDatabaseTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.0.40");
    DriverManagerDataSource fixtureSource;
    @Override @BeforeEach void setup() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        fixtureSource=source;
        var jdbc=new JdbcTemplate(source);
        for(String table:new String[]{"toilet_review","user_role","toilet","app_user"})jdbc.execute("DROP TABLE IF EXISTS "+table);
        setup(source);
    }
    @Test void livePreviewUsesActualRepositoryUnderVerifiedNativeReadOnlySession() throws Exception {
        insert(1,1,2L,false,now,"격리 검증 본문");insert(2,1,2L,false,now.plusNanos(1000),"더 최신");
        var source=new ReadOnlyReviewPreviewDataSource(fixtureSource::getConnection);
        source.verifyConnection();
        var live=new AdminReviewService(new AdminReviewRepository(new JdbcTemplate(source)));
        var first=live.page(null,1);assertEquals("2",first.items().getFirst().id());
        assertEquals("1",live.page(first.nextCursor(),1).items().getFirst().id());assertEquals("격리 검증 본문",live.detail(1).comment());
        try(var connection=source.getConnection()) {
            assertTrue(connection.isReadOnly());assertThrows(java.sql.SQLException.class,()->connection.prepareStatement("DELETE FROM toilet_review"));
        }
        // Independent native-server proof uses ONLY this class's disposable fixture DB.
        try(var connection=fixtureSource.getConnection();var statement=connection.createStatement()) {
            statement.execute("SET SESSION TRANSACTION READ ONLY");
            var denied=assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("DELETE FROM toilet_review WHERE review_id=1"));
            assertEquals(1792,denied.getErrorCode());
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet_review",Integer.class));
    }
}
