package com.example.toiletapi.toilet.openinghours;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OpeningHoursReviewRepositoryTest {
    JdbcTemplate jdbc;
    OpeningHoursRepository repository;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:opening-hours-review;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS toilet_opening_schedule");
        jdbc.execute("DROP TABLE IF EXISTS toilet_opening_hours");
        jdbc.execute("DROP TABLE IF EXISTS toilet");
        jdbc.execute("""
                CREATE TABLE toilet(
                    toilet_id BIGINT PRIMARY KEY,name VARCHAR(100),mng_no VARCHAR(50),
                    road_address VARCHAR(255),jibun_address VARCHAR(255),open_time VARCHAR(50),
                    open_time_detail VARCHAR(255),visibility_status VARCHAR(24) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE toilet_opening_hours(
                    toilet_id BIGINT PRIMARY KEY,source_hash CHAR(64),opening_policy VARCHAR(24),
                    is_open_24h BOOLEAN,normalization_status VARCHAR(24),confidence DECIMAL(5,4),
                    parser_version VARCHAR(20),holiday_policy VARCHAR(16),manual_override BOOLEAN,
                    source_changed BOOLEAN)
                """);
        jdbc.execute("""
                CREATE TABLE toilet_opening_schedule(
                    toilet_id BIGINT,day_of_week INT,slot_index INT,start_time TIME,end_time TIME,
                    crosses_midnight BOOLEAN,is_closed BOOLEAN)
                """);
        jdbc.update("""
                INSERT INTO toilet VALUES
                    (1,'충남대학교 중앙도서관','A','대전광역시 유성구 대학로 99',NULL,'상시','연중무휴','VISIBLE'),
                    (2,'시청 공중화장실','B','대전광역시 서구 둔산로 100',NULL,'정시','24시간','VISIBLE'),
                    (3,'숨긴 시설','C',NULL,NULL,'정시','09:00~18:00','HIDDEN_DUPLICATE')
                """);
        jdbc.update("""
                INSERT INTO toilet_opening_hours VALUES
                    (1,'a','SCHEDULED',FALSE,'REVIEW_REQUIRED',NULL,'v1','OPEN',FALSE,FALSE),
                    (2,'b','ALWAYS',TRUE,'CONFIRMED',1.0,'v1','OPEN',TRUE,FALSE),
                    (3,'c','SCHEDULED',FALSE,'REVIEW_REQUIRED',NULL,'v1','UNKNOWN',FALSE,FALSE)
                """);
        repository = new OpeningHoursRepository(new NamedParameterJdbcTemplate(source));
    }

    @Test
    void reviewQueueSearchesRawFacilityDataAndExcludesHiddenRows() {
        var page = repository.reviews("REVIEW", "대학", 0, 15);

        assertEquals(1, page.totalElements());
        assertEquals("충남대학교 중앙도서관", page.items().getFirst().name());
        assertEquals("연중무휴", page.items().getFirst().openTimeDetail());
    }

    @Test
    void confirmedFilterAndDetailExposeNormalizedDecision() {
        var page = repository.reviews("CONFIRMED", "", 0, 15);
        var detail = repository.reviewItem(2L).orElseThrow();

        assertEquals(1, page.totalElements());
        assertTrue(detail.open24h());
        assertTrue(detail.manualOverride());
        assertFalse(detail.sourceChanged());
    }
}
