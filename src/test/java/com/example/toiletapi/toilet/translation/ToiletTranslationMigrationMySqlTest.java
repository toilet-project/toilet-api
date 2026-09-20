package com.example.toiletapi.toilet.translation;

import static org.junit.jupiter.api.Assertions.*;

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
class ToiletTranslationMigrationMySqlTest {
    @Container
    final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    JdbcTemplate jdbc;
    ToiletTranslationRepository repository;

    @BeforeEach void setup() {
        DataSource source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS toilet_translation");
        jdbc.execute("DROP TABLE IF EXISTS toilet");
        jdbc.execute("""
                CREATE TABLE toilet(
                    toilet_id BIGINT NOT NULL PRIMARY KEY,
                    name VARCHAR(100), road_address VARCHAR(255), jibun_address VARCHAR(255),
                    open_time VARCHAR(50), open_time_detail VARCHAR(255),
                    visibility_status VARCHAR(24) NOT NULL DEFAULT 'VISIBLE'
                )
                """);
        jdbc.update("INSERT INTO toilet(toilet_id,name,road_address,jibun_address,open_time) VALUES(1,?,?,?,?)",
                "서울역 화장실", "서울특별시 중구 한강대로 405", "서울특별시 중구 봉래동2가 122-21", "24시간");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V26__create_toilet_translation.sql"))
                .execute(source);
        repository = new ToiletTranslationRepository(new NamedParameterJdbcTemplate(source));
    }

    @Test void migrationBackfillsKoreanAndExplicitSyncKeepsSourceCurrent() {
        assertEquals("서울역 화장실", jdbc.queryForObject(
                "SELECT name FROM toilet_translation WHERE toilet_id=1 AND locale='ko'", String.class));
        assertEquals("SOURCE", jdbc.queryForObject(
                "SELECT translation_status FROM toilet_translation WHERE toilet_id=1 AND locale='ko'", String.class));
        String originalHash = jdbc.queryForObject(
                "SELECT source_hash FROM toilet_translation WHERE toilet_id=1 AND locale='ko'", String.class);
        assertNotNull(originalHash); assertEquals(64, originalHash.length());

        jdbc.update("INSERT INTO toilet(toilet_id,name,open_time) VALUES(2,'신규 화장실','09:00~18:00')");
        repository.synchronizeKoreanSource(2, java.time.LocalDateTime.now());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM toilet_translation WHERE toilet_id=2 AND locale='ko'", Integer.class));

        jdbc.update("""
                INSERT INTO toilet_translation(toilet_id,locale,name,source_hash,translation_status,
                    translation_source,manual_override,created_at,updated_at)
                VALUES(1,'en','Reviewed restroom',?,'REVIEWED','MANUAL',TRUE,NOW(),NOW())
                """, originalHash);
        jdbc.update("UPDATE toilet SET name='서울역 공중화장실' WHERE toilet_id=1");
        repository.synchronizeKoreanSource(1, java.time.LocalDateTime.now());
        String changedHash = jdbc.queryForObject(
                "SELECT source_hash FROM toilet_translation WHERE toilet_id=1 AND locale='ko'", String.class);
        assertNotEquals(originalHash, changedHash);
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM toilet_translation WHERE toilet_id=1 AND locale='ko'", Long.class));
        assertEquals("Reviewed restroom", jdbc.queryForObject(
                "SELECT name FROM toilet_translation WHERE toilet_id=1 AND locale='en'", String.class));
        assertTrue(jdbc.queryForObject(
                "SELECT manual_override FROM toilet_translation WHERE toilet_id=1 AND locale='en'", Boolean.class));
        assertNotEquals(changedHash, jdbc.queryForObject(
                "SELECT source_hash FROM toilet_translation WHERE toilet_id=1 AND locale='en'", String.class));

        jdbc.update("UPDATE toilet SET visibility_status='HIDDEN_DUPLICATE' WHERE toilet_id=1");
        repository.synchronizeKoreanSource(1, java.time.LocalDateTime.now());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM toilet_translation WHERE toilet_id=1 AND locale='ko'", Long.class));

        jdbc.update("UPDATE toilet SET open_time='상시',open_time_detail='24시간' WHERE toilet_id=1");
        repository.synchronizeKoreanSource(1, java.time.LocalDateTime.now());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT version FROM toilet_translation WHERE toilet_id=1 AND locale='ko'", Long.class));

        jdbc.update("DELETE FROM toilet WHERE toilet_id=1");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM toilet_translation WHERE toilet_id=1", Integer.class));
    }
}
