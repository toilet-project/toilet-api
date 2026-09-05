package com.example.toiletapi.geocoding;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class CoordinateAddressMigrationTest {
    @Test void migrationPreservesLegacyValuesAndAllowsJibunOnlyRevision() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:coordinate-address-v9;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE toilet_report (report_id BIGINT PRIMARY KEY, proposed_road_address VARCHAR(255))");
        db.execute("CREATE TABLE coordinate_revision (coordinate_revision_id BIGINT PRIMARY KEY, previous_road_address VARCHAR(255), applied_road_address VARCHAR(255) NOT NULL)");
        db.update("INSERT INTO toilet_report VALUES(1,'기존 제보 원문')");
        db.update("INSERT INTO coordinate_revision VALUES(1,'기존 변경 전','기존 적용 주소')");
        String migration = new ClassPathResource("db/migration/V9__separate_coordinate_report_addresses.sql").getContentAsString(StandardCharsets.UTF_8);
        // H2 lacks MySQL's multi-clause ALTER. Keep column definitions intact, split only the clauses.
        String h2Compatible = migration.replace(",\n    ADD COLUMN", ";\nALTER TABLE coordinate_revision ADD COLUMN")
                .replace(",\n    MODIFY COLUMN", ";\nALTER TABLE coordinate_revision MODIFY COLUMN");
        new ResourceDatabasePopulator(new ByteArrayResource(h2Compatible.getBytes(StandardCharsets.UTF_8))).execute(ds);
        assertEquals("기존 제보 원문", db.queryForObject("SELECT proposed_road_address FROM toilet_report WHERE report_id=1", String.class));
        assertEquals("기존 적용 주소", db.queryForObject("SELECT applied_road_address FROM coordinate_revision WHERE coordinate_revision_id=1", String.class));
        assertNull(db.queryForObject("SELECT proposed_jibun_address FROM toilet_report WHERE report_id=1", String.class));
        db.update("INSERT INTO coordinate_revision(coordinate_revision_id,previous_jibun_address,applied_road_address,applied_jibun_address) VALUES(2,'이전 지번',NULL,'새 지번')");
        assertEquals("새 지번", db.queryForObject("SELECT applied_jibun_address FROM coordinate_revision WHERE coordinate_revision_id=2", String.class));
        assertNull(db.queryForObject("SELECT applied_road_address FROM coordinate_revision WHERE coordinate_revision_id=2", String.class));
    }
}
