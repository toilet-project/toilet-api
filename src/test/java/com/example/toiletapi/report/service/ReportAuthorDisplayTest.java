package com.example.toiletapi.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.notification.service.UserNotificationService;
import com.example.toiletapi.report.dto.ToiletReportResponse;
import com.example.toiletapi.report.model.ToiletReport;
import com.example.toiletapi.report.repository.CoordinateRevisionRepository;
import com.example.toiletapi.report.repository.ToiletReportRepository;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportAuthorDisplayTest {
    private final ToiletReportRepository reports = mock(ToiletReportRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final ToiletRepository toilets = mock(ToiletRepository.class);
    private final ToiletReportService service = new ToiletReportService(reports,
            mock(CoordinateRevisionRepository.class), toilets, users, mock(AuditLogService.class),
            mock(UserNotificationService.class), mock(CoordinateAddressResolver.class));

    private void detailFixture(Long reporterId) {
        var report = ToiletReport.createOpenTimeCorrection(10L, reporterId, "09:00", "합성 사유", "synthetic");
        when(reports.findById(20L)).thenReturn(Optional.of(report));
        when(toilets.findById(10L)).thenReturn(Optional.of(mock(Toilet.class)));
    }

    @Test void activeAuthorUsesCurrentNicknameAndNotAStoredReportSnapshot() {
        detailFixture(30L);
        var user = AppUser.create("첫 닉네임", "synthetic@example.invalid", true);
        user.activateAfterConsent();
        when(users.findById(30L)).thenReturn(Optional.of(user));
        assertThat(service.pendingDetail(20L).reporterDisplayName()).isEqualTo("첫 닉네임");
        user.changeDisplayName("수정 닉네임");
        assertThat(service.pendingDetail(20L).reporterDisplayName()).isEqualTo("수정 닉네임");
        verify(users, times(2)).findById(30L);
        verify(reports, never()).save(any());
    }

    @Test void withdrawalHidesNameAndExplicitRecoveryShowsRestoredName() {
        detailFixture(30L);
        var user = AppUser.create("이전 이름", null, false);
        when(users.findById(30L)).thenReturn(Optional.of(user));
        user.withdraw();
        user.changeDisplayName("노출하면 안 되는 잔여 이름");
        assertThat(service.pendingDetail(20L).reporterDisplayName()).isEqualTo("탈퇴한 사용자");
        user.restore("복구한 이름");
        user.activateAfterConsent();
        assertThat(service.pendingDetail(20L).reporterDisplayName()).isEqualTo("복구한 이름");
    }

    @Test void erasedForeignKeyDisplaysWithdrawnWithoutUserLookup() {
        detailFixture(null);
        assertThat(service.pendingDetail(20L).reporterDisplayName()).isEqualTo("탈퇴한 사용자");
        verifyNoInteractions(users);
    }

    @Test void brokenNonNullReferenceIsNotMisclassifiedAsWithdrawal() {
        detailFixture(30L);
        when(users.findById(30L)).thenReturn(Optional.empty());
        assertThat(service.pendingDetail(20L).reporterDisplayName()).isEqualTo("작성자 정보 없음");
    }

    @Test void blankAndMissingNamesUseGenericLabel() {
        detailFixture(30L);
        var user = AppUser.create(null, null, false);
        when(users.findById(30L)).thenReturn(Optional.of(user));
        assertThat(service.pendingDetail(20L).reporterDisplayName()).isEqualTo("급똥 사용자");
        user.changeDisplayName("   ");
        assertThat(service.pendingDetail(20L).reporterDisplayName()).isEqualTo("급똥 사용자");
    }

    @Test void adminJsonAddsOnlyLabelAndDoesNotExposeAccountMetadata() throws Exception {
        detailFixture(30L);
        when(users.findById(30L)).thenReturn(Optional.of(AppUser.create("합성 닉네임", "synthetic@example.invalid", true)));
        var json = new ObjectMapper().valueToTree(service.pendingDetail(20L));
        assertThat(json.size()).isEqualTo(3);
        assertThat(json.has("report")).isTrue();
        assertThat(json.has("toilet")).isTrue();
        assertThat(json.path("reporterDisplayName").asText()).isEqualTo("합성 닉네임");
        assertThat(json.toString()).doesNotContain("synthetic@example.invalid", "reporterUserId", "authVersion", "email", "provider");
        assertThat(List.of(ToiletReportResponse.class.getRecordComponents()).stream().map(java.lang.reflect.RecordComponent::getName))
                .doesNotContain("reporterDisplayName", "reporterUserId", "email");
    }

    @Test void myReportsDoesNotAddAuthorLookups() {
        when(reports.findByReporterUserIdOrderByCreatedAtDesc(30L)).thenReturn(List.of(
                ToiletReport.createOpenTimeCorrection(10L, 30L, "09:00", "합성 사유", "synthetic")));
        when(toilets.findAllById(any())).thenReturn(List.of());
        assertThat(service.mine(30L)).hasSize(1);
        verifyNoInteractions(users);
    }

    @Test void dashboardDoesNotAddAuthorLookups() {
        when(reports.findTop5ByStatusOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(toilets.findAllById(any())).thenReturn(List.of());
        service.pendingDashboard();
        verifyNoInteractions(users);
    }
}
