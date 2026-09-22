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
        jdbc.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY,visibility_status VARCHAR(24) DEFAULT 'VISIBLE',name VARCHAR(255),latitude DECIMAL(10,7),longitude DECIMAL(10,7))");
        jdbc.execute("CREATE TABLE toilet_translation (toilet_id BIGINT,locale VARCHAR(12),name VARCHAR(255),road_address VARCHAR(500),jibun_address VARCHAR(500),source_hash CHAR(64),PRIMARY KEY(toilet_id,locale))");
        service=new ToiletSitemapService(jdbc);
    }
    @BeforeEach void clear() { jdbc.update("DELETE FROM toilet_translation"); jdbc.update("DELETE FROM toilet"); }
    @Test void sparseIdsAndBoundariesAreNotOffsetPages() {
        for(long id: new long[]{1,10000,10001,20000,900001,ToiletSitemapService.MAX_ID})
            jdbc.update("INSERT INTO toilet(toilet_id) VALUES (?)",id);
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
        jdbc.batchUpdate("INSERT INTO toilet(toilet_id) VALUES (?)", java.util.stream.LongStream.rangeClosed(1,10001)
                .mapToObj(id->new Object[]{id}).toList());
        assertEquals(10000,service.ids(0).size());
        assertEquals(List.of(10001L),service.ids(1));
        var plan=jdbc.queryForMap("EXPLAIN SELECT toilet_id FROM toilet WHERE toilet_id > 10000 AND toilet_id <= 20000 ORDER BY toilet_id LIMIT 10000");
        assertEquals("PRIMARY",plan.get("key"));
        assertEquals("range",plan.get("type"));
    }

    @Test void localizedProjectionRequiresVisibleCurrentNameAndAddress() {
        for (long id : new long[]{1,2,3,4,10001}) {
            jdbc.update("INSERT INTO toilet(toilet_id,visibility_status,name,latitude,longitude) VALUES(?,?,?,?,?)",
                    id,id==3 ? "HIDDEN_DUPLICATE" : "VISIBLE","원문",36.3,127.3);
            jdbc.update("INSERT INTO toilet_translation(toilet_id,locale,name,source_hash) VALUES(?,'ko','원문','current')",id);
        }
        jdbc.update("INSERT INTO toilet_translation VALUES(1,'en','English restroom','English road',NULL,'current')");
        jdbc.update("INSERT INTO toilet_translation VALUES(2,'en','Stale','English road',NULL,'old')");
        jdbc.update("INSERT INTO toilet_translation VALUES(3,'en','Hidden','English road',NULL,'current')");
        jdbc.update("INSERT INTO toilet_translation VALUES(4,'en','No address',NULL,NULL,'current')");
        jdbc.update("INSERT INTO toilet_translation VALUES(10001,'en','Another restroom',NULL,'English lot','current')");
        jdbc.update("INSERT INTO toilet_translation VALUES(1,'ja','日本語のトイレ',NULL,NULL,'current')");
        assertEquals(List.of(0L,1L),service.localizedShards("en"));
        assertEquals(List.of(1L),service.entries(0,"en").stream().map(ToiletSitemapService.SitemapEntry::id).toList());
        assertEquals("English restroom",service.entries(0,"en").getFirst().name());
        assertTrue(service.localizedShards("ja").isEmpty());
        assertTrue(service.entries(0,"ja").isEmpty());
        assertEquals(4,service.entries(0,"ko").size());
        assertThrows(IllegalArgumentException.class,()->service.localizedShards("zh"));
        assertThrows(IllegalArgumentException.class,()->service.entries(-1,"en"));
    }
}
