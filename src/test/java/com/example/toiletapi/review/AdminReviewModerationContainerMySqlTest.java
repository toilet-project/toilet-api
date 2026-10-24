package com.example.toiletapi.review;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Moderation consistency and rollback run against this disposable MySQL only. */
@Testcontainers
class AdminReviewModerationContainerMySqlTest extends AdminReviewModerationDatabaseTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.0.40");
    @Override @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var jdbc=new JdbcTemplate(source);
        var tables=jdbc.query("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()",(rs,n)->rs.getString(1));
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for(String table:tables) {if(!table.matches("[a-z_]+"))throw new IllegalStateException("Unexpected fixture table");statement.execute("DROP TABLE "+table);}
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        setup(source,false);
    }
}
