package com.example.toiletapi.toilet.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ToiletSitemapMySqlTest {
    @Container static MySQLContainer mysql=new MySQLContainer("mysql:8.0");
    static JdbcTemplate jdbc;
    static ToiletSitemapService service;
    @BeforeAll static void schema() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()));
        jdbc.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY)");
        service=new ToiletSitemapService(jdbc);
    }
    @BeforeEach void clear() { jdbc.update("DELETE FROM toilet"); }
    @Test void sparseIdsAndBoundariesAreNotOffsetPages() {
        for(long id: new long[]{1,10000,10001,20000,900001,ToiletSitemapService.MAX_ID})
            jdbc.update("INSERT INTO toilet VALUES (?)",id);
        assertEquals(List.of(0L,1L,90L,900719925474L),service.shards());
        assertEquals(List.of(1L,10000L),service.ids(0));
        assertEquals(List.of(10001L,20000L),service.ids(1));
        assertEquals(List.of(ToiletSitemapService.MAX_ID),service.ids(900719925474L));
        jdbc.update("DELETE FROM toilet WHERE toilet_id=1");
        assertEquals(List.of(10001L,20000L),service.ids(1));
        jdbc.update("DELETE FROM toilet WHERE toilet_id=900001");
        assertFalse(service.shards().contains(90L));
        assertTrue(service.ids(90).isEmpty());
    }
    @Test void emptyAndInvalidRequests() {
        assertTrue(service.shards().isEmpty());
        assertTrue(service.ids(0).isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.ids(-1));
        assertThrows(IllegalArgumentException.class,()->service.ids(Long.MAX_VALUE));
    }
    @Test void fullShardIsBoundedAndUsesPrimaryKeyRange() {
        jdbc.batchUpdate("INSERT INTO toilet VALUES (?)", java.util.stream.LongStream.rangeClosed(1,10001)
                .mapToObj(id->new Object[]{id}).toList());
        assertEquals(10000,service.ids(0).size());
        assertEquals(List.of(10001L),service.ids(1));
        var plan=jdbc.queryForMap("EXPLAIN SELECT toilet_id FROM toilet WHERE toilet_id > 10000 AND toilet_id <= 20000 ORDER BY toilet_id LIMIT 10000");
        assertEquals("PRIMARY",plan.get("key"));
        assertEquals("range",plan.get("type"));
    }
}
