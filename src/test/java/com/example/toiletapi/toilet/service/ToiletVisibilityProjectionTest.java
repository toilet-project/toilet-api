package com.example.toiletapi.toilet.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ToiletVisibilityProjectionTest {
    @Test void sitemapIdsExcludeHiddenWithoutRemovingRows() {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:visibility-projection;MODE=MySQL;DB_CLOSE_DELAY=-1","sa",""));
        jdbc.execute("CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,visibility_status VARCHAR(24))");
        jdbc.update("INSERT INTO toilet VALUES(1,'VISIBLE'),(2,'HIDDEN_DUPLICATE'),(10001,'VISIBLE')");
        var service=new ToiletSitemapService(jdbc);
        assertEquals(List.of(1L),service.ids(0));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM toilet",Integer.class));
        jdbc.update("UPDATE toilet SET visibility_status='VISIBLE' WHERE toilet_id=2");
        assertEquals(List.of(1L,2L),service.ids(0));
    }
}
