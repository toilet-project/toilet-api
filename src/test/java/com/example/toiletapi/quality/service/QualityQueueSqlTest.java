package com.example.toiletapi.quality.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.quality.repository.CoordinateQualityReviewRepository;
import com.example.toiletapi.quality.repository.ToiletDisplayGroupRepository;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/** Runs the actual queue SQL against disposable in-memory rows; no server or Docker required. */
public class QualityQueueSqlTest {
    JdbcTemplate db;
    CoordinateQualityService service;
    boolean h2;

    public static String sha2(String input, int bits) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @BeforeEach void setup() {
        var source = dataSource();
        h2 = source.getUrl().startsWith("jdbc:h2:");
        db = new JdbcTemplate(source);
        if (h2) db.execute("CREATE ALIAS SHA2 FOR 'com.example.toiletapi.quality.service.QualityQueueSqlTest.sha2'");
        db.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY, name VARCHAR(100), mng_no VARCHAR(100), toilet_type VARCHAR(30), road_address VARCHAR(255), jibun_address VARCHAR(255), coordinate_source VARCHAR(30), latitude DECIMAL(10,7), longitude DECIMAL(10,7), visibility_status VARCHAR(30) DEFAULT 'VISIBLE')");
        db.execute("CREATE TABLE toilet_display_group (group_id BIGINT PRIMARY KEY, display_name VARCHAR(100), latitude DECIMAL(10,7), longitude DECIMAL(10,7))");
        db.execute("CREATE TABLE toilet_display_group_member (toilet_id BIGINT PRIMARY KEY, group_id BIGINT, sort_order INT)");
        db.execute("CREATE TABLE coordinate_quality_review (group_key VARCHAR(64), status VARCHAR(30))");
        db.execute("CREATE TABLE toilet_report (toilet_id BIGINT, status VARCHAR(30), report_type VARCHAR(40))");
        // H2 defaults CAST AS CHAR to one character; MySQL uses the full value.
        var jdbc = new NamedParameterJdbcTemplate(source) {
            @Override public <T> List<T> query(String sql, SqlParameterSource params, RowMapper<T> mapper) {
                return super.query(h2 ? sql.replace(" AS CHAR)", " AS VARCHAR)") : sql, params, mapper);
            }
            @Override public <T> T queryForObject(String sql, SqlParameterSource params, Class<T> type) {
                return super.queryForObject(h2 ? sql.replace(" AS CHAR)", " AS VARCHAR)") : sql, params, type);
            }
        };
        service = new CoordinateQualityService(jdbc, mock(CoordinateQualityReviewRepository.class),
                mock(ToiletRepository.class), mock(ToiletReportRepository.class), mock(CoordinateRevisionRepository.class),
                mock(AuditLogService.class), mock(CoordinateAddressResolver.class), mock(ToiletDisplayGroupRepository.class));
        db.update("INSERT INTO toilet(toilet_id,name,latitude,longitude) VALUES (1,'1층',37,127),(2,'2층',37,127),(3,'미처리',37,127)");
    }

    @AfterEach void close() {
        if (db == null) return;
        if (h2) db.execute("SHUTDOWN");
        else for (String table : List.of("toilet_report", "coordinate_quality_review", "toilet_display_group_member", "toilet_display_group", "toilet"))
            db.execute("DROP TABLE IF EXISTS " + table);
    }

    void groupTwo() {
        db.update("INSERT INTO toilet_display_group VALUES (9,'문화원',37,127)");
        db.update("INSERT INTO toilet_display_group_member VALUES (1,9,0),(2,9,1)");
    }

    @Test void groupedFloorsAreExcludedButOneUnprocessedNeighbourRemains() {
        groupTwo();
        var queue = service.search("", null, 0, 20);
        assertEquals(1, queue.totalElements());
        assertEquals(1, queue.items().getFirst().toiletCount());
        var detail = service.detail(queue.items().getFirst().groupKey());
        assertEquals(List.of(3L), detail.toilets().stream().map(t -> t.id()).toList());
    }

    @Test void completedGroupsDisappearWithoutDeletingFacilitiesAndReappearOnUngroup() {
        groupTwo();
        db.update("INSERT INTO toilet_display_group_member VALUES (3,9,2)");
        assertEquals(0, service.search("", null, 0, 20).totalElements());
        assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM toilet", Integer.class));
        db.update("DELETE FROM toilet_display_group_member");
        assertEquals(3, service.search("", null, 0, 20).items().getFirst().toiletCount());
    }

    @Test void detailRemainsAvailableAtOneFacilityUntilZero() {
        var key = service.search("", null, 0, 20).items().getFirst().groupKey();
        db.update("UPDATE toilet SET latitude=36 WHERE toilet_id IN (1,2)");
        assertEquals(1, service.detail(key).group().toiletCount());
        assertEquals(List.of(3L), service.detail(key).toilets().stream().map(t -> t.id()).toList());
        db.update("UPDATE toilet SET latitude=35 WHERE toilet_id=3");
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.detail(key)).getStatusCode().value());
    }

    @Test void staleMembershipDoesNotHideChangedCoordinates() {
        groupTwo();
        db.update("UPDATE toilet_display_group SET latitude=36 WHERE group_id=9");
        assertEquals(3, service.search("", null, 0, 20).items().getFirst().toiletCount());
    }

    @Test void fullyGroupedDetailReturnsZeroForThePinnedWorkspace() {
        var key = service.search("", null, 0, 20).items().getFirst().groupKey();
        groupTwo();
        db.update("INSERT INTO toilet_display_group_member VALUES (3,9,2)");
        assertEquals(0, service.detail(key).group().toiletCount());
        assertTrue(service.detail(key).toilets().isEmpty());
    }
}
