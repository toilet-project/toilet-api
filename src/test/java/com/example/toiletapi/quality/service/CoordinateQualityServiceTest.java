package com.example.toiletapi.quality.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.quality.dto.CorrectToiletCoordinateRequest;
import com.example.toiletapi.quality.repository.CoordinateQualityReviewRepository;
import com.example.toiletapi.report.model.CoordinateRevision;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@ExtendWith(MockitoExtension.class)
class CoordinateQualityServiceTest {
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock CoordinateQualityReviewRepository reviewRepository;
    @Mock ToiletRepository toiletRepository;
    @Mock ToiletReportRepository reportRepository;
    @Mock CoordinateRevisionRepository revisionRepository;
    @Mock AuditLogService auditLogService;
    @Mock Toilet toilet;
    private CoordinateQualityService service;

    @BeforeEach
    void setUp() {
        service = new CoordinateQualityService(jdbc, reviewRepository, toiletRepository, reportRepository,
                revisionRepository, auditLogService);
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

        service.correctToilet(adminId, toiletId, new CorrectToiletCoordinateRequest(
                latitude, longitude, "대전광역시 유성구 노은로 101", "관리자 현장 확인"));

        verify(toilet).applyAdminConfirmedCoordinates(latitude, longitude, "대전광역시 유성구 노은로 101");
        verify(revisionRepository).save(any(CoordinateRevision.class));
        verify(auditLogService).record(eq(adminId), eq(AuditAction.TOILET_COORDINATE_CORRECTED),
                eq("TOILET"), eq(toiletId), any(Map.class));
    }
}
