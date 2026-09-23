package com.example.toiletapi.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class ServiceAnalyticsMigrationTest {

    @Test
    void addsOwnedEventAndDailyTablesWithoutBreakingTheRunningAdmin() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:service-analytics-v21;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        execute(dataSource, "db/migration/V20__create_admin_analytics_snapshot.sql");
        execute(dataSource, "db/migration/V21__replace_ga_snapshot_with_service_analytics.sql");

        JdbcTemplate db = new JdbcTemplate(dataSource);
        Integer ownedTables = db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema='PUBLIC'
                   AND table_name IN ('SERVICE_ANALYTICS_EVENT','SERVICE_ANALYTICS_DAILY_SUMMARY',
                                      'SERVICE_ANALYTICS_DAILY_DIMENSION','SERVICE_ANALYTICS_BATCH_RUN')
                """, Integer.class);
        Integer retiredTables = db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema='PUBLIC' AND table_name='ADMIN_ANALYTICS_SNAPSHOT'
                """, Integer.class);

        assertThat(ownedTables).isEqualTo(4);
        assertThat(retiredTables).isEqualTo(1);
    }

    @Test
    void removesTheRetiredGoogleAnalyticsSnapshotAfterAdminCutover() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:service-analytics-v22;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        execute(dataSource, "db/migration/V20__create_admin_analytics_snapshot.sql");
        execute(dataSource, "db/migration/V21__replace_ga_snapshot_with_service_analytics.sql");
        execute(dataSource, "db/migration/V22__drop_retired_admin_analytics_snapshot.sql");

        JdbcTemplate db = new JdbcTemplate(dataSource);
        Integer ownedTables = db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema='PUBLIC'
                   AND table_name IN ('SERVICE_ANALYTICS_EVENT','SERVICE_ANALYTICS_DAILY_SUMMARY',
                                      'SERVICE_ANALYTICS_DAILY_DIMENSION','SERVICE_ANALYTICS_BATCH_RUN')
                """, Integer.class);
        Integer retiredTables = db.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema='PUBLIC' AND table_name='ADMIN_ANALYTICS_SNAPSHOT'
                """, Integer.class);

        assertThat(ownedTables).isEqualTo(4);
        assertThat(retiredTables).isZero();
    }

    @Test
    void storesAnOwnedAnalyticsEventUsingTheMigratedSchema() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:service-analytics-insert;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        execute(dataSource, "db/migration/V21__replace_ga_snapshot_with_service_analytics.sql");

        byte[] visitorHash = new byte[32];
        byte[] sessionHash = new byte[32];
        Arrays.fill(visitorHash, (byte) 1);
        Arrays.fill(sessionHash, (byte) 2);
        AnalyticsRepository repository = new AnalyticsRepository(new JdbcTemplate(dataSource));

        repository.insert(new AnalyticsRepository.EventRow(
                Instant.parse("2026-09-17T00:10:00Z"), LocalDate.of(2026, 9, 17), "page_view", "/",
                "Direct", "direct", "desktop", "Windows", "Chrome", "KR", "Seoul",
                visitorHash, sessionHash, 0, "", "", null, true, false));

        JdbcTemplate db = new JdbcTemplate(dataSource);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM service_analytics_event", Integer.class)).isOne();
        assertThat(db.queryForObject("SELECT event_name FROM service_analytics_event", String.class))
                .isEqualTo("page_view");
    }

    @Test
    void acquisitionCountsOnlyEntrySessionsNotEveryAction() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:service-analytics-entry-count;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        execute(dataSource, "db/migration/V21__replace_ga_snapshot_with_service_analytics.sql");
        JdbcTemplate db = new JdbcTemplate(dataSource);
        AnalyticsRepository repository = new AnalyticsRepository(db);
        LocalDate date = LocalDate.of(2026, 9, 23);
        Instant now = Instant.parse("2026-09-23T03:00:00Z");
        byte[] visitor = new byte[32];
        byte[] enteredSession = new byte[32];
        byte[] straySession = new byte[32];
        Arrays.fill(enteredSession, (byte) 1);
        Arrays.fill(straySession, (byte) 2);

        repository.insert(event(now, date, "session_start", enteredSession, visitor));
        repository.insert(event(now, date, "page_view", enteredSession, visitor));
        repository.insert(event(now, date, "page_view", straySession, visitor));
        repository.aggregate(date, now, false);

        assertThat(db.queryForObject("SELECT sessions FROM service_analytics_daily_summary", Long.class)).isOne();
        assertThat(db.queryForObject("SELECT sessions FROM service_analytics_daily_dimension WHERE dimension_type='SOURCE'", Long.class)).isOne();
        assertThat(db.queryForObject("SELECT sessions FROM service_analytics_daily_dimension WHERE dimension_type='CHANNEL'", Long.class)).isOne();
    }

    private static AnalyticsRepository.EventRow event(Instant now, LocalDate date, String name,
                                                       byte[] session, byte[] visitor) {
        return new AnalyticsRepository.EventRow(now, date, name, "/", "Direct", "none",
                "mobile", "iOS", "Safari", "KR", "Seoul", visitor, session,
                0, "", "", null, false, false);
    }

    private static void execute(DataSource dataSource, String path) throws Exception {
        String migration = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8)
                .replace(" INT UNSIGNED ", " INT ")
                .replace(" SMALLINT UNSIGNED ", " SMALLINT ");
        new ResourceDatabasePopulator(new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
    }
}
