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
    void repairMigrationUpdatesOnlyAutomaticPure24HourSources() {
        jdbc.update("""
                INSERT INTO toilet(toilet_id,name,open_time,open_time_detail) VALUES
                    (5,'24시간 개방','정시','24시간 개방'),
                    (6,'공백 포함','', '00:00 ~ 24:00'),
                    (7,'정시 공백 포함','정시','00:00 ~ 24:00'),
                    (8,'23시 59분','정시','00:00~23:59'),
                    (9,'공백 23시 59분','', '00:00~23:59'),
                    (10,'관리자 확정 보호','정시','24시간 개방'),
                    (11,'복합 문구 제외','정시','24시간 개방, 공휴일 제외')
                """);
        jdbc.update("""
                INSERT INTO toilet_opening_hours
                    (toilet_id,source_hash,opening_policy,is_open_24h,normalization_status,
                     confidence,parser_version,holiday_policy,manual_override,source_changed,
                     confirmed_by_user_id,confirmed_at,created_at,updated_at)
                SELECT toilet_id,
                       SHA2(CONCAT(COALESCE(TRIM(open_time), ''), CHAR(31),
                                   COALESCE(TRIM(open_time_detail), '')), 256),
                       IF(toilet_id=10,'CLOSED','UNKNOWN'),IF(toilet_id=10,FALSE,NULL),
                       IF(toilet_id=10,'CONFIRMED','REVIEW_REQUIRED'),
                       IF(toilet_id=10,1.0000,NULL),'bootstrap-v1','UNKNOWN',
                       toilet_id=10,FALSE,IF(toilet_id=10,9,NULL),
                       IF(toilet_id=10,CURRENT_TIMESTAMP,NULL),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
                  FROM toilet WHERE toilet_id BETWEEN 5 AND 11
                """);
        jdbc.update("""
                INSERT INTO toilet_opening_schedule
                    (toilet_id,day_of_week,slot_index,start_time,end_time,crosses_midnight,is_closed)
                VALUES (5,1,0,'09:00:00','18:00:00',FALSE,FALSE)
                """);

        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V28__repair_unambiguous_24h_opening_hours.sql"))
                .execute(jdbc.getDataSource());

        assertEquals(5, jdbc.queryForObject("""
                SELECT COUNT(*) FROM toilet_opening_hours
                 WHERE toilet_id BETWEEN 5 AND 9
                   AND opening_policy='ALWAYS' AND is_open_24h=TRUE
                   AND normalization_status='PARSED' AND confidence=1.0000
                   AND parser_version='v1' AND manual_override=FALSE
                """, Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM toilet_opening_schedule WHERE toilet_id BETWEEN 5 AND 9",
                Integer.class));
        assertFalse(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours WHERE toilet_id=10", Boolean.class));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT normalization_status FROM toilet_opening_hours WHERE toilet_id=10", String.class));
        assertNull(jdbc.queryForObject(
                "SELECT is_open_24h FROM toilet_opening_hours WHERE toilet_id=11", Boolean.class));
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
