package com.example.toiletapi.auth.repository;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AccountWithdrawalMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @Test void realMysqlAcceptsMigrationAndRetainsReportAfterAuthorDeletion() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT NOT NULL PRIMARY KEY)");
        for (String file : new String[]{"V1__create_auth_data_model.sql", "V2__create_toilet_report_and_coordinate_revision.sql",
                "V4__create_user_notification.sql", "V5__create_coordinate_quality_review.sql", "V7__create_policy_consent_model.sql",
                "V11__account_withdrawal_retention.sql"}) {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + file)).execute(ds);
        }
        jdbc.update("INSERT INTO app_user(user_id,status) VALUES(1,'WITHDRAWN')");
        jdbc.update("INSERT INTO toilet(toilet_id) VALUES(1)");
        jdbc.update("INSERT INTO toilet_report(toilet_id,reporter_user_id,report_type,reason) VALUES(1,1,'OPEN_TIME_CORRECTION','제보')");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM app_user WHERE user_id=1")).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("UPDATE toilet_report SET reporter_user_id=NULL WHERE reporter_user_id=1");
        jdbc.update("DELETE FROM app_user WHERE user_id=1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM toilet_report", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reporter_user_id FROM toilet_report", Long.class)).isNull();
    }
}
