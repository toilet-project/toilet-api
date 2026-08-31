package com.example.toiletapi.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.report.dto.CreateToiletReportRequest;
import com.example.toiletapi.report.dto.ReviewToiletReportRequest;
import com.example.toiletapi.report.dto.ToiletReportDashboardResponse;
import com.example.toiletapi.report.dto.ToiletReportPageResponse;
import com.example.toiletapi.report.dto.ToiletReportResponse;
import com.example.toiletapi.report.model.ToiletReport;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ToiletReportServiceTest {

    @Mock private ToiletReportRepository reportRepository;
    @Mock private CoordinateRevisionRepository revisionRepository;
    @Mock private ToiletRepository toiletRepository;
    @Mock private AppUserRepository userRepository;
    @Mock private AuditLogService auditLogService;
    @InjectMocks private ToiletReportService service;

    @Test
    void shouldStoreCoordinateReportWithRoadAddress() {
        Toilet toilet = mock(Toilet.class); when(toilet.getName()).thenReturn("시청 공중화장실");
        when(toiletRepository.findById(10L)).thenReturn(Optional.of(toilet));
        when(userRepository.findById(3L)).thenReturn(Optional.of(mock(AppUser.class)));
        when(reportRepository.existsByActiveRequestKey(any())).thenReturn(false);
        when(reportRepository.save(any(ToiletReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ToiletReportResponse response = service.submit(3L, new CreateToiletReportRequest(10L, "COORDINATE_CORRECTION", new BigDecimal("36.3500000"), new BigDecimal("127.3800000"), "대전광역시 서구 둔산대로 100", null, "출입구 위치가 다릅니다."));

        ArgumentCaptor<ToiletReport> captor = ArgumentCaptor.forClass(ToiletReport.class);
        verify(reportRepository).save(captor.capture());
        assertEquals("COORDINATE_CORRECTION", captor.getValue().getReportType());
        assertEquals("대전광역시 서구 둔산대로 100", captor.getValue().getProposedRoadAddress());
        assertEquals("시청 공중화장실", response.toiletName());
    }

    @Test
    void shouldStoreOpenTimeReportSeparatelyFromCoordinates() {
        when(toiletRepository.findById(10L)).thenReturn(Optional.of(mock(Toilet.class)));
        when(userRepository.findById(3L)).thenReturn(Optional.of(mock(AppUser.class)));
        when(reportRepository.existsByActiveRequestKey(any())).thenReturn(false);
        when(reportRepository.save(any(ToiletReport.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.submit(3L, new CreateToiletReportRequest(10L, "OPEN_TIME_CORRECTION", null, null, null, "09:00 ~ 18:00", "현장 안내문을 확인했습니다."));

        ArgumentCaptor<ToiletReport> captor = ArgumentCaptor.forClass(ToiletReport.class);
        verify(reportRepository).save(captor.capture());
        assertEquals("OPEN_TIME_CORRECTION", captor.getValue().getReportType());
        assertEquals("09:00 ~ 18:00", captor.getValue().getProposedOpenTime());
    }

    @Test
    void shouldRejectLocationReportWithoutConfirmedRoadAddress() {
        assertThrows(IllegalArgumentException.class, () -> service.submit(3L,
                new CreateToiletReportRequest(10L, "COORDINATE_CORRECTION", new BigDecimal("36.3500000"), new BigDecimal("127.3800000"), "", null, "주소가 없습니다.")));

        verifyNoInteractions(toiletRepository, userRepository, reportRepository);
    }

    @Test
    void shouldApplyAdministratorAdjustedCoordinateWithoutChangingOriginalReport() {
        ToiletReport report = ToiletReport.createCoordinateCorrection(10L, 3L, new BigDecimal("36.3500000"), new BigDecimal("127.3800000"), "제보 주소", "사유", "key");
        Toilet toilet = mock(Toilet.class); when(toilet.getLatitude()).thenReturn(new BigDecimal("36.3400000")); when(toilet.getLongitude()).thenReturn(new BigDecimal("127.3700000"));
        when(toilet.getRoadAddress()).thenReturn("기존 주소"); when(toilet.getName()).thenReturn("시청 공중화장실");
        when(reportRepository.findByIdForUpdate(12L)).thenReturn(Optional.of(report)); when(toiletRepository.findById(10L)).thenReturn(Optional.of(toilet));

        service.approve(9L, 12L, new ReviewToiletReportRequest("현장 확인", new BigDecimal("36.3510000"), new BigDecimal("127.3810000"), "관리자 보정 주소"));

        verify(toilet).applyAdminConfirmedCoordinates(new BigDecimal("36.3510000"), new BigDecimal("127.3810000"), "관리자 보정 주소");
        assertEquals(new BigDecimal("36.3500000"), report.getProposedLatitude());
        verify(revisionRepository).save(any());
    }

    @Test
    void shouldReturnOnlyRecentPendingReportsForDashboard() {
        ToiletReport report = report(12L, 10L, "COORDINATE_CORRECTION");
        Toilet toilet = mock(Toilet.class); when(toilet.getId()).thenReturn(10L); when(toilet.getName()).thenReturn("시청 공중화장실");
        when(reportRepository.findTop5ByStatusOrderByCreatedAtAsc(ReportStatus.PENDING)).thenReturn(List.of(report));
        when(reportRepository.countByStatus(ReportStatus.PENDING)).thenReturn(8L);
        when(toiletRepository.findAllById(any())).thenReturn(List.of(toilet));

        ToiletReportDashboardResponse response = service.pendingDashboard();

        assertEquals(8L, response.pendingCount());
        assertEquals(1, response.recentReports().size());
        assertEquals("시청 공중화장실", response.recentReports().getFirst().toiletName());
    }

    @Test
    void shouldReturnPendingReportsAsPageInsteadOfFullList() {
        ToiletReport report = report(12L, 10L, "OPEN_TIME_CORRECTION");
        Toilet toilet = mock(Toilet.class); when(toilet.getId()).thenReturn(10L); when(toilet.getName()).thenReturn("시청 공중화장실");
        when(reportRepository.findPendingByToiletName(org.mockito.ArgumentMatchers.eq(ReportStatus.PENDING), org.mockito.ArgumentMatchers.eq("시청"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(report), org.springframework.data.domain.PageRequest.of(0, 20), 41));
        when(toiletRepository.findAllById(any())).thenReturn(List.of(toilet));

        ToiletReportPageResponse response = service.pendingPage("시청", 0, 20);

        assertEquals(41L, response.totalElements());
        assertEquals(3, response.totalPages());
        assertEquals(1, response.items().size());
    }

    private ToiletReport report(Long id, Long toiletId, String type) {
        ToiletReport report = mock(ToiletReport.class);
        when(report.getId()).thenReturn(id); when(report.getToiletId()).thenReturn(toiletId); when(report.getReportType()).thenReturn(type);
        when(report.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 8, 31, 2, 0));
        return report;
    }
}
