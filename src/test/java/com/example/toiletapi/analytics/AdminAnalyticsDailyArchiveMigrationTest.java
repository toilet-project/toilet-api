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

class AdminAnalyticsDailyArchiveMigrationTest {

    @Test
    void createsIdempotentDailyAggregatesAndCollectionHistory() throws Exception {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-analytics-v21;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        String migration = new ClassPathResource(
                "db/migration/V21__create_admin_analytics_daily_archive.sql")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace(" BIGINT UNSIGNED ", " BIGINT ")
                .replace(" INT UNSIGNED ", " INT ");
        new ResourceDatabasePopulator(new ByteArrayResource(
                migration.getBytes(StandardCharsets.UTF_8))).execute(dataSource);

        var db = new JdbcTemplate(dataSource);
        Timestamp collectedAt = Timestamp.from(Instant.parse("2026-09-17T07:30:00Z"));
        String propertyHash = "a".repeat(64);
        db.update("""
                INSERT INTO admin_analytics_daily_summary(
                    property_id_hash, report_date, report_timezone, active_users, total_users,
                    new_users, sessions, engaged_sessions, views, event_count, key_events,
                    engagement_seconds, data_status, collected_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, propertyHash, java.sql.Date.valueOf("2026-09-16"), "Asia/Seoul",
                120, 130, 35, 150, 110, 310, 600, 4.5, 9200.25, "PROVISIONAL", collectedAt);
        db.update("""
                INSERT INTO admin_analytics_daily_breakdown(
                    property_id_hash, report_date, breakdown_type, dimension_hash,
                    dimension_value, dimension_label, dimension_detail, active_users, views
                ) VALUES(?,?,?,?,?,?,?,?,?)
                """, propertyHash, java.sql.Date.valueOf("2026-09-16"), "PAGE", "b".repeat(64),
                "/", "급똥", null, 100, 250);
        db.update("""
                INSERT INTO admin_analytics_collection_run(
                    property_id_hash, target_date, range_start, range_end, run_type,
                    status, started_at, finished_at, api_request_count, stored_row_count
                ) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, propertyHash, java.sql.Date.valueOf("2026-09-16"),
                java.sql.Date.valueOf("2026-09-03"), java.sql.Date.valueOf("2026-09-16"),
                "DAILY", "SUCCESS", collectedAt, collectedAt, 3, 2);

        assertThat(db.queryForObject(
                "SELECT active_users FROM admin_analytics_daily_summary WHERE property_id_hash=? AND report_date=?",
                Long.class, propertyHash, java.sql.Date.valueOf("2026-09-16"))).isEqualTo(120);
        assertThat(db.queryForObject(
                "SELECT COUNT(*) FROM admin_analytics_daily_breakdown WHERE breakdown_type='PAGE'",
                Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject(
                "SELECT status FROM admin_analytics_collection_run WHERE target_date=?",
                String.class, java.sql.Date.valueOf("2026-09-16"))).isEqualTo("SUCCESS");
    }
}
