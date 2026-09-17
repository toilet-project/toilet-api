package com.example.toiletapi.region;

import com.example.toiletapi.quality.service.CoordinateQualityService;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.toiletapi.region.RegionReviewModels.*;

class RegionReviewServiceTest {
    NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    ToiletRepository toilets = mock(ToiletRepository.class);
    CoordinateQualityService corrections = mock(CoordinateQualityService.class);
    AuditLogService audit = mock(AuditLogService.class);
    Toilet toilet = mock(Toilet.class);
    RegionReviewService service = new RegionReviewService(jdbc, toilets, corrections, audit);
    @BeforeEach void setup() { when(toilets.findByIdForUpdate(1L)).thenReturn(Optional.of(toilet)); }

    @Test void missingCoordinatesCanBeSetWithUnchangedNullSnapshot() {
        var request = new Correction(BigDecimal.ONE, BigDecimal.TEN, "관리자 현장 확인", new Location(null,null,null,null));
        service.correct(9,1,request);
        verify(corrections).correctToilet(eq(9L),eq(1L),argThat(r -> r.latitude().equals(BigDecimal.ONE) && r.roadAddress() == null));
        verifyNoInteractions(jdbc);
    }
    @Test void concurrentlyChangedCoordinateIsRejectedBeforeAnyGeocodingOrWrite() {
        when(toilet.getLatitude()).thenReturn(BigDecimal.ONE);
        var error = assertThrows(ResponseStatusException.class, () -> service.correct(9,1,
                new Correction(BigDecimal.TEN,BigDecimal.TEN,"확인",new Location(null,null,null,null))));
        assertEquals(409,error.getStatusCode().value());
        verifyNoInteractions(corrections);
    }
    @Test void concurrentlyChangedAddressIsRejected() {
        when(toilet.getJibunAddress()).thenReturn("새 지번");
        assertThrows(ResponseStatusException.class, () -> service.correct(9,1,
                new Correction(BigDecimal.ONE,BigDecimal.TEN,"확인",new Location(null,null,null,"이전 지번"))));
        verifyNoInteractions(corrections);
    }
    @Test void decimalScaleDoesNotCauseFalseConflict() {
        assertTrue(RegionReviewService.same(new BigDecimal("37.5000000"),new BigDecimal("37.5")));
        assertFalse(RegionReviewService.same(null,BigDecimal.ZERO));
    }
    @Test void invalidPaginationAndOversizedKeywordsAreRejectedBeforeSql() {
        assertThrows(IllegalArgumentException.class, () -> service.search(Filter.REVIEW,"",-1,20));
        assertThrows(IllegalArgumentException.class, () -> service.search(Filter.REVIEW,"",0,101));
        assertThrows(IllegalArgumentException.class, () -> service.search(Filter.REVIEW,"a".repeat(101),0,20));
        assertThrows(IllegalArgumentException.class, () -> service.history(1,-1,10));
        verifyNoInteractions(jdbc);
    }

    @Test void districtConfirmationRejectsAChangedSourceBeforeLookup() {
        when(toilet.getRoadAddress()).thenReturn("새 주소");
        var request = new RegionConfirmation("41170", "지도 확인", new Location(null,null,"이전 주소",null));
        var error = assertThrows(ResponseStatusException.class, () -> service.confirmDistrict(9,1,request));
        assertEquals(409, error.getStatusCode().value());
        verifyNoInteractions(jdbc, audit);
    }

    @Test void districtConfirmationUsesCanonicalRegionAndWritesAudit() {
        var region = new RegionValue("경기도","41","용인시 수지구","41465","용인시","수지구");
        doReturn(java.util.List.of(region)).when(jdbc).query(contains("FROM region_sigungu_reference WHERE"),
                any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class), any(RowMapper.class));
        when(jdbc.update(contains("INSERT INTO toilet_region_override"),
                any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class))).thenReturn(1);

        var result = service.confirmDistrict(9,1,
                new RegionConfirmation("41465", " 지도 위치 확인 ", new Location(null,null,null,null)));

        assertEquals("용인시 수지구", result.region().sigunguName());
        assertEquals("지도 위치 확인", result.note());
        verify(jdbc).update(contains("INSERT INTO toilet_region_override"),
                any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
        verify(jdbc).update(contains("INSERT INTO toilet_region_decision"),
                any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
        verify(audit).record(9L, AuditAction.TOILET_REGION_CONFIRMED, "TOILET", 1L,
                java.util.Map.of("sigunguCode","41465","regionName","경기도 용인시 수지구"));
    }

    @Test void regionOptionInputIsBoundedBeforeSql() {
        assertThrows(IllegalArgumentException.class, () -> service.options("a".repeat(51),20));
        assertThrows(IllegalArgumentException.class, () -> service.options("수원",51));
        verifyNoInteractions(jdbc);
    }

    @Test void regionOptionSearchUsesTheOfficialReferenceAndCombinedDisplayName() {
        doReturn(java.util.List.of()).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));

        service.options(" 서울특별시   동대문구 ", 20);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        var params = org.mockito.ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(sql.capture(), params.capture(), any(RowMapper.class));
        assertTrue(sql.getValue().contains("FROM region_sigungu_reference"));
        assertTrue(sql.getValue().contains("display_name LIKE"));
        assertEquals("%서울특별시 동대문구%", params.getValue().getValue("keyword"));
        assertEquals("%서울특별시동대문구%", params.getValue().getValue("compactKeyword"));
    }

    @Test void reviewQueueReadsTheNarrowAssignmentAndCanonicalReference() {
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        doReturn(java.util.List.of()).when(jdbc).query(anyString(), any(SqlParameterSource.class), any(RowMapper.class));

        service.search(Filter.REVIEW, "", 0, 20);

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        assertTrue(sql.getValue().contains("LEFT JOIN toilet_region_assignment a"));
        assertTrue(sql.getValue().contains("LEFT JOIN region_sigungu_reference ar"));
        assertTrue(sql.getValue().contains("a.source_revision <> t.region_revision"));
        assertFalse(sql.getValue().contains("LEFT JOIN toilet_region r"));
    }

    @Test void missingCoordinatesRemainUnfinishedEvenAfterDistrictConfirmation() {
        var db = new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:region-status-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        // H2 spells MySQL's null-safe equality differently; the production CASE order is unchanged.
        var expression = RegionReviewService.EFFECTIVE_STATUS.replace("<=>", "IS NOT DISTINCT FROM");
        db.execute("CREATE TABLE t(latitude DECIMAL(10,7),longitude DECIMAL(10,7),region_revision BIGINT)");
        db.execute("CREATE TABLE d(toilet_id BIGINT)");
        db.execute("CREATE TABLE a(toilet_id BIGINT,source_revision BIGINT,status VARCHAR(30),evaluated_latitude DECIMAL(10,7),evaluated_longitude DECIMAL(10,7))");
        db.update("INSERT INTO t VALUES(NULL,NULL,1)");
        db.update("INSERT INTO d VALUES(1)");
        db.update("INSERT INTO a VALUES(1,1,'NO_COORDINATE',NULL,NULL)");
        assertEquals("NO_COORDINATE", db.queryForObject("SELECT " + expression + " FROM t,d,a", String.class));
        db.update("UPDATE t SET latitude=37");
        assertEquals("NO_COORDINATE", db.queryForObject("SELECT " + expression + " FROM t,d,a", String.class));
        db.update("UPDATE t SET longitude=127");
        assertEquals("VERIFIED", db.queryForObject("SELECT " + expression + " FROM t,d,a", String.class));
        db.execute("SHUTDOWN");
    }
}
