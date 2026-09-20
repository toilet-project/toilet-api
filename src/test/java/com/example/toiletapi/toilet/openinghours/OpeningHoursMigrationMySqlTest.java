package com.example.toiletapi.toilet.openinghours;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpeningHoursMigrationMySqlTest {
    @Container
    final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    JdbcTemplate jdbc;
    OpeningHoursService service;

    @BeforeEach
    void setup() {
        DataSource source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS toilet_opening_schedule");
        jdbc.execute("DROP TABLE IF EXISTS toilet_opening_hours");
        jdbc.execute("DROP TABLE IF EXISTS toilet");
        jdbc.execute("""
                CREATE TABLE toilet(
                    toilet_id BIGINT NOT NULL PRIMARY KEY,
                    name VARCHAR(200) NOT NULL,
                    visibility_status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
                    open_time VARCHAR(50),open_time_detail VARCHAR(255)
                )
                """);
        jdbc.update("""
                INSERT INTO toilet(toilet_id,name,open_time,open_time_detail) VALUES
                    (1,'첫 번째','정시','24시간'),
                    (2,'두 번째','상시','연중무휴 09:00~18:00'),
                    (3,'세 번째','미개방',NULL),
                    (4,'네 번째','상시',NULL)
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V27__normalize_toilet_opening_hours.sql"))
                .execute(source);
        var repository = new OpeningHoursRepository(new NamedParameterJdbcTemplate(source));
        service = new OpeningHoursService(new OpeningHoursParser(), repository,
                org.mockito.Mockito.mock(com.example.toiletapi.auth.service.AuditLogService.class));
    }

    @Test
    void migrationOnlyConfirmsUnambiguousValues() {
        assertTrue(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours WHERE toilet_id=1", Boolean.class));
        assertEquals("PARSED", jdbc.queryForObject(
                "SELECT normalization_status FROM toilet_opening_hours WHERE toilet_id=1", String.class));
        assertNull(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours WHERE toilet_id=2", Boolean.class));
        assertFalse(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours WHERE toilet_id=3", Boolean.class));
        assertNull(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours WHERE toilet_id=4", Boolean.class));
    }

    @Test
    void javaNormalizerStoresLanguageNeutralWeeklySchedule() {
        service.synchronize(2L, "상시", "연중무휴 09:00~18:00");

        assertFalse(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours WHERE toilet_id=2", Boolean.class));
        assertEquals("SCHEDULED", jdbc.queryForObject(
                "SELECT opening_policy FROM toilet_opening_hours WHERE toilet_id=2", String.class));
        assertEquals(7, jdbc.queryForObject(
                "SELECT COUNT(*) FROM toilet_opening_schedule WHERE toilet_id=2", Integer.class));
        assertEquals("09:00:00", jdbc.queryForObject(
                "SELECT CAST(start_time AS CHAR) FROM toilet_opening_schedule WHERE toilet_id=2 AND day_of_week=1",
                String.class));
    }

    @Test
    void sourceChangeDoesNotOverwriteManualConfirmation() {
        jdbc.update("""
                UPDATE toilet_opening_hours
                   SET opening_policy='ALWAYS',is_open_24h=TRUE,normalization_status='CONFIRMED',
                       manual_override=TRUE,confirmed_by_user_id=9,confirmed_at=NOW()
                 WHERE toilet_id=4
                """);

        service.synchronize(4L, "정시", "09:00~18:00");

        assertTrue(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours WHERE toilet_id=4", Boolean.class));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT normalization_status FROM toilet_opening_hours WHERE toilet_id=4", String.class));
        assertTrue(jdbc.queryForObject(
                "SELECT source_changed FROM toilet_opening_hours WHERE toilet_id=4", Boolean.class));
    }

    @Test
    void patternQueueOrdersByTheSameNormalizedExpressionsUsedForGrouping() {
        var page = service.patterns("ALL", "", 0, 15);

        assertEquals(4, page.totalElements());
        assertEquals(4, page.items().size());
    }
}
