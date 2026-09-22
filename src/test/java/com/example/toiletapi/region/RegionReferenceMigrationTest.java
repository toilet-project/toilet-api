package com.example.toiletapi.region;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class RegionReferenceMigrationTest {
    @Test void seedsEveryOfficialLeafDistrictAndExcludesParentCities() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:region-reference;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V17__create_sigungu_reference.sql")).execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);

        assertEquals(287, jdbc.queryForObject(
                "SELECT COUNT(*) FROM region_sigungu_reference WHERE is_active=1", Integer.class));
        assertEquals("서울특별시 동대문구", jdbc.queryForObject(
                "SELECT display_name FROM region_sigungu_reference WHERE sigungu_code='11230'", String.class));
        assertEquals("경기도 수원시 장안구", jdbc.queryForObject(
                "SELECT display_name FROM region_sigungu_reference WHERE sigungu_code='41111'", String.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM region_sigungu_reference WHERE sigungu_code IN ('41110','41130','41460')", Integer.class));
        assertNull(jdbc.queryForObject(
                "SELECT sigungu_name FROM region_sigungu_reference WHERE sigungu_code='36110'", String.class));

        var service = new RegionReviewService(new NamedParameterJdbcTemplate(dataSource), null, null, null);
        assertEquals("11230", service.options("서울특별시 동대문구", 20).getFirst().region().sigunguCode());
        assertEquals("11230", service.options("서울특별시동대문구", 20).getFirst().region().sigunguCode());
        assertEquals("11230", service.options("11230", 20).getFirst().region().sigunguCode());
    }

    @Test void localizedDistrictNamesStayAttachedToCanonicalCodes() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:region-translations;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var migrations = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V17__create_sigungu_reference.sql"),
                new ClassPathResource("db/migration/V31__create_region_sigungu_translation.sql"),
                new ClassPathResource("db/migration/V32__seed_region_sigungu_translation.sql"));
        migrations.execute(dataSource);
        var jdbc = new JdbcTemplate(dataSource);

        assertEquals(287 * 5, jdbc.queryForObject("SELECT COUNT(*) FROM region_sigungu_translation", Integer.class));
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT r.sigungu_code FROM region_sigungu_reference r
                    LEFT JOIN region_sigungu_translation t ON t.sigungu_code=r.sigungu_code
                    GROUP BY r.sigungu_code HAVING COUNT(t.locale)<>5
                ) missing
                """, Integer.class));
        assertEquals("Jung-gu", jdbc.queryForObject("""
                SELECT display_name FROM region_sigungu_translation
                WHERE sigungu_code='11140' AND locale='en'
                """, String.class));
        assertEquals("中区", jdbc.queryForObject("""
                SELECT display_name FROM region_sigungu_translation
                WHERE sigungu_code='11140' AND locale='zh-CN'
                """, String.class));
    }
}
