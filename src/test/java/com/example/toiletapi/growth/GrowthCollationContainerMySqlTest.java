package com.example.toiletapi.growth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** CI always runs this against disposable MySQL 8; no production JDBC settings are read. */
@Testcontainers
class GrowthCollationContainerMySqlTest {
    @Container static final MySQLContainer mysql=new MySQLContainer("mysql:8.0");

    @Test void v41RepairsV12V40MixedCollationsWithoutChangingExistingKeys() {
        var ds=new DriverManagerDataSource(mysql.getJdbcUrl(),"root",mysql.getPassword());
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        assertEquals("utf8mb4_0900_ai_ci",jdbc.queryForObject("""
                SELECT DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME=DATABASE()
                """,String.class));
        jdbc.execute("CREATE TABLE app_user(user_id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY)");
        jdbc.execute("""
                CREATE TABLE toilet_review(review_id BIGINT PRIMARY KEY,review_key CHAR(36) NOT NULL UNIQUE,
                    toilet_id BIGINT NOT NULL,author_user_id BIGINT)
                CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V40__member_growth.sql")).execute(ds);
        jdbc.update("INSERT INTO app_user VALUES(1)");
        jdbc.update("INSERT INTO toilet VALUES(1)");
        String key="00000000-0000-4000-8000-000000000001";
        jdbc.update("INSERT INTO toilet_review VALUES(1,?,1,1)",key);
        jdbc.update("INSERT INTO growth_review_evidence VALUES(?,1,1,'2026-10-01 12:00:00')",key);
        jdbc.update("INSERT INTO growth_review_exclusion VALUES(1,?,'fixture','2026-10-01 12:00:00')",key);
        String join="SELECT COUNT(*) FROM growth_review_evidence e JOIN toilet_review r ON r.review_key=e.review_key";
        var mismatch=assertThrows(UncategorizedSQLException.class,()->jdbc.queryForObject(join,Integer.class));
        assertEquals("HY000",mismatch.getSQLException().getSQLState());
        assertEquals(1267,mismatch.getSQLException().getErrorCode());

        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V41__align_growth_review_key_collation.sql"))
                .execute(ds);
        for(String table:List.of("toilet_review","growth_review_evidence","growth_review_exclusion"))
            assertEquals("utf8mb4_unicode_ci",jdbc.queryForObject("""
                    SELECT COLLATION_NAME FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='review_key'
                    """,String.class,table));
        assertEquals(1,jdbc.queryForObject(join,Integer.class));
        assertEquals(key,jdbc.queryForObject("SELECT review_key FROM growth_review_evidence",String.class));
        assertEquals(key,jdbc.queryForObject("SELECT review_key FROM growth_review_exclusion",String.class));
    }
}
