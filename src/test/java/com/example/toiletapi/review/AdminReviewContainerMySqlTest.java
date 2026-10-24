package com.example.toiletapi.review;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Runs the same pagination, retention and session SQL against CI's disposable MySQL. */
@Testcontainers
class AdminReviewContainerMySqlTest extends AdminReviewDatabaseTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.0.40");
    @Override @BeforeEach void setup() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new JdbcTemplate(source);
        for(String table:new String[]{"toilet_review","user_role","toilet","app_user"})jdbc.execute("DROP TABLE IF EXISTS "+table);
        setup(source);
    }
}
