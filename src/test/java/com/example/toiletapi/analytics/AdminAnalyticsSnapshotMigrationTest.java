package com.example.toiletapi.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class AdminAnalyticsSnapshotMigrationTest {

    @Test
    void keepsOneAggregateSnapshotPerReportKeyAndUpdatesItInPlace() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-analytics-v20;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        String migration = new ClassPathResource(
                "db/migration/V20__create_admin_analytics_snapshot.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace(" INT UNSIGNED ", " INT ");
        new ResourceDatabasePopulator(new ByteArrayResource(
                migration.getBytes(StandardCharsets.UTF_8))).execute(dataSource);

        var db = new JdbcTemplate(dataSource);
        Timestamp now = Timestamp.from(Instant.parse("2026-09-15T02:00:00Z"));
        db.update("""
                INSERT INTO admin_analytics_snapshot(
                    report_key, property_id_hash, range_start, range_end, payload_json,
                    fetched_at, expires_at, last_success_at, last_attempt_at, quota_json
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, "DETAIL_7D", "a".repeat(64), java.sql.Date.valueOf("2026-09-09"),
                java.sql.Date.valueOf("2026-09-15"), "{\"activeUsers\":12}", now, now, now, now,
                "{\"remaining\":199999}");

        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM admin_analytics_snapshot WHERE report_key='DETAIL_7D'",
                Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject(
                "SELECT CAST(payload_json AS VARCHAR) FROM admin_analytics_snapshot WHERE report_key='DETAIL_7D'",
                String.class)).contains("activeUsers").contains("12");
    }
}
