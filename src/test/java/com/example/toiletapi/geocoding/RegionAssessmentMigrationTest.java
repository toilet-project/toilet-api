package com.example.toiletapi.geocoding;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class RegionAssessmentMigrationTest {
    @Test void v10PreservesCurrentRowsAndCreatesReplaySafeHistory() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:region-history-v10;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE toilet_region(toilet_id BIGINT PRIMARY KEY, status VARCHAR(30), checked_at TIMESTAMP)");
        db.update("INSERT INTO toilet_region VALUES(1,'ADDRESS_UNVERIFIED',CURRENT_TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V10__create_toilet_region_assessment_history.sql")).execute(ds);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM toilet_region", Integer.class));
        String insert = """
                INSERT INTO toilet_region_assessment_history
                (toilet_id, source_hash, algorithm_version, status, reason, result_json, checked_epoch_millis, checked_at)
                VALUES(1,REPEAT('a',64),'kakao-b-v2','ADDRESS_UNVERIFIED','TEST','{}',1000,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE assessment_id=assessment_id
                """;
        db.update(insert); db.update(insert);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM toilet_region_assessment_history", Integer.class));
        db.update("DELETE FROM toilet_region WHERE toilet_id=1");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM toilet_region_assessment_history", Integer.class));
        // Disposable test schema only: rollback must not require changes to the original toilet table.
        db.execute("DROP INDEX idx_toilet_region_review");
        db.execute("DROP TABLE toilet_region_assessment_history");
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM toilet_region", Integer.class));
    }
}
