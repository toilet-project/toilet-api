package com.example.toiletapi.quality.service;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** CI-only disposable MySQL runs the same scenarios without the H2 dialect adapter. */
@Testcontainers
class QualityQueueMySqlTest extends QualityQueueSqlTest {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @Override DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
