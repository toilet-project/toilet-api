package com.example.toiletapi.quality.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddress;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.geocoding.AddressLookupException;
import com.example.toiletapi.quality.dto.CorrectToiletCoordinateRequest;
import com.example.toiletapi.quality.dto.DuplicateCoordinateGroupResponse;
import com.example.toiletapi.quality.dto.SaveToiletDisplayGroupRequest;
import com.example.toiletapi.quality.model.CoordinateQualityStatus;
import com.example.toiletapi.quality.repository.CoordinateQualityReviewRepository;
import com.example.toiletapi.quality.repository.ToiletDisplayGroupRepository;
import com.example.toiletapi.report.model.CoordinateRevision;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

@ExtendWith(MockitoExtension.class)
class CoordinateQualityServiceTest {
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock CoordinateQualityReviewRepository reviewRepository;
    @Mock ToiletRepository toiletRepository;
    @Mock ToiletReportRepository reportRepository;
    @Mock CoordinateRevisionRepository revisionRepository;
    @Mock AuditLogService auditLogService;
    @Mock CoordinateAddressResolver addressResolver;
    @Mock ToiletDisplayGroupRepository displayGroupRepository;
    @Mock Toilet toilet;
    private CoordinateQualityService service;

    @BeforeEach
    void setUp() {
        service = new CoordinateQualityService(jdbc, reviewRepository, toiletRepository, reportRepository,
                revisionRepository, auditLogService, addressResolver, displayGroupRepository);
    }

    @Test
    void correctToiletRecordsRevisionAndProtectsCoordinateAsAdminConfirmed() {
        Long adminId = 7L;
        Long toiletId = 101L;
        BigDecimal latitude = new BigDecimal("36.3663613");
        BigDecimal longitude = new BigDecimal("127.3148032");
        when(toiletRepository.findByIdForUpdate(toiletId)).thenReturn(Optional.of(toilet));
        when(toilet.getId()).thenReturn(toiletId);
        when(toilet.getLatitude()).thenReturn(new BigDecimal("36.3660000"), latitude);
        when(toilet.getLongitude()).thenReturn(new BigDecimal("127.3140000"), longitude);
        when(toilet.getRoadAddress()).thenReturn("기존 주소", "대전광역시 유성구 노은로 101");
        when(toilet.getCoordinateSource()).thenReturn("ADMIN_CONFIRMED");
        when(addressResolver.resolve(latitude, longitude)).thenReturn(new CoordinateAddress(latitude, longitude, "대전광역시 유성구 노은로 101", "지번 주소"));

        service.correctToilet(adminId, toiletId, new CorrectToiletCoordinateRequest(
                latitude, longitude, "대전광역시 유성구 노은로 101", "관리자 현장 확인"));

        verify(toilet).applyAdminConfirmedCoordinates(latitude, longitude, "대전광역시 유성구 노은로 101", "지번 주소");
        verify(displayGroupRepository).removeToilet(toiletId);
        verify(revisionRepository).save(any(CoordinateRevision.class));
        verify(auditLogService).record(eq(adminId), eq(AuditAction.TOILET_COORDINATE_CORRECTED),
                eq("TOILET"), eq(toiletId), any(Map.class));
    }

    @Test
    void lookupFailureDoesNotLockOrChangeToilet() {
        when(addressResolver.resolve(any(), any())).thenThrow(new AddressLookupException());
        org.junit.jupiter.api.Assertions.assertThrows(AddressLookupException.class,
                () -> service.correctToilet(7L, 101L, new CorrectToiletCoordinateRequest(BigDecimal.ONE, BigDecimal.TEN, null, null)));
        org.mockito.Mockito.verifyNoInteractions(toiletRepository, revisionRepository, auditLogService);
    }

    @Test
    void savesSelectedToiletsAsNamedMapGroup() {
        BigDecimal latitude = new BigDecimal("36.4000000");
        BigDecimal longitude = new BigDecimal("127.3000000");
        var coordinateGroup = new DuplicateCoordinateGroupResponse("group-key", latitude, longitude, 3,
                "XXX문화원 1층", "대전광역시", CoordinateQualityStatus.PENDING, 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any()))
                .thenReturn(List.of(coordinateGroup));
        when(displayGroupRepository.matchingToiletIds(List.of(11L, 12L, 13L), latitude, longitude))
                .thenReturn(List.of(11L, 12L, 13L));
        when(displayGroupRepository.create("XXX문화원", latitude, longitude, 7L)).thenReturn(21L);

        var result = service.saveDisplayGroup(7L, "group-key",
                new SaveToiletDisplayGroupRequest(null, " XXX문화원 ", List.of(11L, 12L, 13L)));

        org.junit.jupiter.api.Assertions.assertEquals(21L, result.id());
        org.junit.jupiter.api.Assertions.assertEquals("XXX문화원", result.displayName());
        verify(displayGroupRepository).replaceMembers(21L, List.of(11L, 12L, 13L));
        verify(auditLogService).record(eq(7L), eq(AuditAction.TOILET_DISPLAY_GROUP_SAVED),
                eq("TOILET_DISPLAY_GROUP"), eq(21L), any(Map.class));
    }

    @Test
    void rejectsToiletsOutsideCurrentCoordinateGroup() {
        BigDecimal latitude = new BigDecimal("36.4000000");
        BigDecimal longitude = new BigDecimal("127.3000000");
        var coordinateGroup = new DuplicateCoordinateGroupResponse("group-key", latitude, longitude, 3,
                "XXX문화원 1층", "대전광역시", CoordinateQualityStatus.PENDING, 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any()))
                .thenReturn(List.of(coordinateGroup));
        when(displayGroupRepository.matchingToiletIds(List.of(11L, 99L), latitude, longitude))
                .thenReturn(List.of(11L));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.saveDisplayGroup(7L, "group-key",
                        new SaveToiletDisplayGroupRequest(null, "XXX문화원", List.of(11L, 99L))));

        verify(displayGroupRepository, never()).create(anyString(), any(), any(), any());
        verify(displayGroupRepository, never()).replaceMembers(any(), any());
    }

    @Test
    void updatesAnExistingMapGroupWithTheNewSelection() {
        BigDecimal latitude = new BigDecimal("36.4000000");
        BigDecimal longitude = new BigDecimal("127.3000000");
        var coordinateGroup = new DuplicateCoordinateGroupResponse("group-key", latitude, longitude, 4,
                "XXX문화원 1층", "대전광역시", CoordinateQualityStatus.PENDING, 0);
        when(jdbc.query(anyString(), any(SqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<DuplicateCoordinateGroupResponse>>any()))
                .thenReturn(List.of(coordinateGroup));
        when(displayGroupRepository.matchingToiletIds(List.of(11L, 12L, 13L, 14L), latitude, longitude))
                .thenReturn(List.of(11L, 12L, 13L, 14L));
        when(displayGroupRepository.belongsToCoordinates(21L, latitude, longitude)).thenReturn(true);

        var result = service.saveDisplayGroup(7L, "group-key",
                new SaveToiletDisplayGroupRequest(21L, "XXX문화원 본관", List.of(11L, 12L, 13L, 14L)));

        org.junit.jupiter.api.Assertions.assertEquals(21L, result.id());
        verify(displayGroupRepository).update(21L, "XXX문화원 본관", 7L);
        verify(displayGroupRepository).replaceMembers(21L, List.of(11L, 12L, 13L, 14L));
        verify(displayGroupRepository, never()).create(anyString(), any(), any(), any());
    }
}
