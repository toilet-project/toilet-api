package com.example.toiletapi.photo;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The permission, migration and erasure suite also runs against disposable MySQL. */
@Testcontainers(disabledWithoutDocker=true)
class PhotoContainerMySqlTest extends PhotoServiceTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0.40");
    @Override @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        var fixture=new JdbcTemplate(ds);
        // This container is exclusive to this test; never use a service database here.
        for(String table:new String[]{"profile_photo","profile_photo_object","toilet_review","app_user"})
            fixture.execute("DROP TABLE IF EXISTS "+table);
        setup(ds);
    }
}
