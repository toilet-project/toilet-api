package com.example.toiletapi.growth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** MySQL validates actual RANK/CTE/LIKE/CASE SQL, not an in-memory approximation. */
@Testcontainers
class AdminGrowthRankingContainerMySqlTest extends AdminGrowthRankingDatabaseTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.0.40");
    DriverManagerDataSource fixtureSource;
    @Override @BeforeEach void setup() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        fixtureSource=source;
        var fixture=new JdbcTemplate(source);
        for(String table:new String[]{"growth_account","user_role","app_user"})fixture.execute("DROP TABLE IF EXISTS "+table);
        setup(source);
    }
    @Test void actualRankingSearchAndDistributionRunInNativeReadOnlyMysqlSessions() throws Exception {
        var source=new ReadOnlyGrowthRankingPreviewDataSource(fixtureSource::getConnection);source.verifyConnection();
        var live=new AdminGrowthRankingService(new AdminGrowthRankingRepository(new JdbcTemplate(source)),protection);
        assertEquals(7,live.search(new AdminGrowthRankingModels.SearchRequest(null,"xp_desc",0,10)).totalElements());
        assertEquals("2",live.search(new AdminGrowthRankingModels.SearchRequest("MEMBER2@EXAMPLE.TEST","xp_asc",0,10)).items().getFirst().id());
        assertEquals(7,live.distribution().totalMembers());
        try(var connection=source.getConnection()) {
            assertTrue(connection.isReadOnly());assertThrows(java.sql.SQLException.class,()->connection.prepareStatement("DELETE FROM growth_account"));
        }
        // Native proof uses only disposable fixture data; never execute it on the operational DB.
        try(var connection=fixtureSource.getConnection();var statement=connection.createStatement()) {
            statement.execute("SET SESSION TRANSACTION READ ONLY");
            assertEquals(1792,assertThrows(java.sql.SQLException.class,()->statement.executeUpdate("DELETE FROM growth_account WHERE user_id=2")).getErrorCode());
        }
        assertEquals(80,jdbc.queryForObject("SELECT total_xp FROM growth_account WHERE user_id=2",Long.class));
    }
}
