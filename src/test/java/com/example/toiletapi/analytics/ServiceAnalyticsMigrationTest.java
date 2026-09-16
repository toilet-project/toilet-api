package com.example.toiletapi.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
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

    private static void execute(DataSource dataSource, String path) throws Exception {
        String migration = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8)
                .replace(" INT UNSIGNED ", " INT ")
                .replace(" SMALLINT UNSIGNED ", " SMALLINT ");
        new ResourceDatabasePopulator(new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)))
                .execute(dataSource);
    }
}
