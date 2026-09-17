package com.example.toiletapi.quality.service;

import static org.junit.jupiter.api.Assertions.*;
import com.example.toiletapi.quality.dto.DuplicateNameModels.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Testcontainers
class DuplicateNameCollationMySqlTest {
    @Container static final MySQLContainer mysql = new MySQLContainer("mysql:8.0");

    @ParameterizedTest
    @ValueSource(strings={"utf8mb4_unicode_ci","utf8mb4_0900_ai_ci"})
    void migrationAlignsWithExistingNamesAndPreservesWorkPreferences(String collation) throws Exception {
        var ds = new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP TABLE IF EXISTS duplicate_name_work_visibility");
        jdbc.execute("DROP TABLE IF EXISTS toilet");
        jdbc.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY,name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE "+collation+
            ",visibility_status VARCHAR(24) DEFAULT 'VISIBLE',latitude DECIMAL(10,7),longitude DECIMAL(10,7),road_address VARCHAR(255),mng_no VARCHAR(100))");
        String other = collation.equals("utf8mb4_unicode_ci") ? "utf8mb4_0900_ai_ci" : "utf8mb4_unicode_ci";
        jdbc.execute("CREATE TABLE duplicate_name_work_visibility (admin_user_id BIGINT,group_name VARCHAR(100) CHARACTER SET utf8mb4 COLLATE "+other+
            " NOT NULL,work_hidden BOOLEAN,version BIGINT,updated_at DATETIME,PRIMARY KEY(admin_user_id,group_name))");
        jdbc.update("INSERT INTO toilet(toilet_id,name,latitude,longitude) VALUES (1,'공원',37,127),(2,'공원',37,127)");
        jdbc.update("INSERT INTO duplicate_name_work_visibility VALUES (7,'공원',TRUE,3,NOW())");
        var service = new DuplicateNameService(new NamedParameterJdbcTemplate(jdbc));
        assertThrows(DataAccessException.class, () -> service.groups("",false,0,20,Comparison.ALL,7,WorkVisibility.ALL));
        try (var connection=ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V25__align_duplicate_work_name_collation.sql"));
        }
        assertEquals(collation,jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='duplicate_name_work_visibility' AND COLUMN_NAME='group_name'",String.class));
        var page = service.groups("",false,0,20,Comparison.ALL,7,WorkVisibility.ALL);
        assertEquals(1,page.totalElements());
        assertTrue(page.items().getFirst().workHidden());
        assertEquals(3,page.items().getFirst().workVersion());
        assertEquals(0,service.groups("",false,0,20,Comparison.ALL,7,WorkVisibility.VISIBLE).totalElements());
        assertEquals(1,service.groups("",false,0,20,Comparison.COORDINATES,8,WorkVisibility.VISIBLE).totalElements());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM toilet WHERE visibility_status='VISIBLE'",Integer.class));
    }
}
