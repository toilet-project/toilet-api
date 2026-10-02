package com.example.toiletapi.report.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.notification.service.UserNotificationService;
import com.example.toiletapi.report.model.ToiletReport;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import com.example.toiletapi.toilet.openinghours.OpeningHoursService;
import com.example.toiletapi.toilet.translation.ToiletTranslationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ReportResolutionServiceTest {
    final ToiletReportRepository reports = mock(ToiletReportRepository.class);
    final ToiletRepository toilets = mock(ToiletRepository.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final OpeningHoursService hours = mock(OpeningHoursService.class);
    final CoordinateAddressResolver resolver = mock(CoordinateAddressResolver.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final UserNotificationService notifications = mock(UserNotificationService.class);
    final ReportResolutionService service = new ReportResolutionService(reports, toilets, jdbc, hours, resolver,
            mock(ToiletTranslationService.class), audit, notifications, new ObjectMapper());
    ReportResolutionService.Request request(String reason, String state, String requestId) {
        return new ReportResolutionService.Request(ReportResolutionService.Action.HIDE_TEMPORARILY, reason, state, requestId, null, null, null);
    }
    @Test void invalidRequestsNeverReadOrWriteFacilities() {
        for (var value : List.of(request("", "a".repeat(64), UUID.randomUUID().toString()),
                request("x".repeat(501), "a".repeat(64), UUID.randomUUID().toString()),
                request("근거", "stale", UUID.randomUUID().toString()), request("근거", "a".repeat(64), "bad"))) {
            assertThatThrownBy(() -> service.apply(3, 1, value)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(reports, jdbc, toilets, resolver, hours, audit, notifications);
    }
    @Test void rejectedReportDoesNotMutateFacilityOrCreateAnotherDecision() {
        var report = ToiletReport.quick(1L, null, "FACILITY_MISSING", "화장실", null, null, null, null, "", "a", "b", "c");
        report.reject(3L, "기존 반려");
        when(reports.findByIdForUpdate(1L)).thenReturn(Optional.of(report));
        assertThatThrownBy(() -> service.apply(3, 1, request("근거", "a".repeat(64), UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("대기·승인");
        verifyNoInteractions(toilets, resolver, hours, audit, notifications);
    }
    @Test void unregisteredNewFacilityRequiresNormalApprovalFirst() {
        var report = ToiletReport.quick(null, null, "NEW_FACILITY", "새 시설", null, null, null, null, "", "a", "b", "c");
        when(reports.findByIdForUpdate(1L)).thenReturn(Optional.of(report));
        assertThatThrownBy(() -> service.apply(3, 1, request("근거", "a".repeat(64), UUID.randomUUID().toString())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(toilets, resolver, hours, audit, notifications);
    }
    @Test void reuseOfRequestIdWithDifferentPayloadIsRejectedBeforeFacilityAccess() {
        var request = request("근거", "a".repeat(64), UUID.randomUUID().toString());
        when(reports.findByIdForUpdate(1L)).thenReturn(Optional.of(mock(ToiletReport.class)));
        when(jdbc.queryForList("SELECT request_hash FROM toilet_report_resolution WHERE report_id=? AND request_id=?", 1L, request.requestId()))
                .thenReturn(List.of(Map.of("request_hash", "different")));
        assertThatThrownBy(() -> service.apply(3, 1, request)).isInstanceOf(ReportResolutionService.Conflict.class);
        verifyNoInteractions(toilets, resolver, hours, audit, notifications);
    }
}
