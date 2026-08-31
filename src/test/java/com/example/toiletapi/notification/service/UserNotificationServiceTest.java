package com.example.toiletapi.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.toiletapi.notification.model.NotificationType;
import com.example.toiletapi.notification.model.UserNotification;
import com.example.toiletapi.notification.repository.UserNotificationRepository;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.model.ToiletReport;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class UserNotificationServiceTest {
    private final UserNotificationRepository repository = mock(UserNotificationRepository.class);
    private final UserNotificationService service = new UserNotificationService(repository);

    @Test
    void createsApprovedReportNotificationOnce() {
        ToiletReport report = mock(ToiletReport.class);
        when(report.getId()).thenReturn(20L);
        when(report.getReporterUserId()).thenReturn(3L);
        when(report.getStatus()).thenReturn(ReportStatus.APPROVED);
        when(repository.existsByUserIdAndTypeAndReferenceTypeAndReferenceId(3L, NotificationType.REPORT_APPROVED, "TOILET_REPORT", 20L)).thenReturn(false);

        service.createReportDecision(report, "시청 화장실");

        verify(repository).save(any(UserNotification.class));
    }

    @Test
    void doesNotCreateDuplicateDecisionNotification() {
        ToiletReport report = mock(ToiletReport.class);
        when(report.getId()).thenReturn(20L);
        when(report.getReporterUserId()).thenReturn(3L);
        when(report.getStatus()).thenReturn(ReportStatus.REJECTED);
        when(repository.existsByUserIdAndTypeAndReferenceTypeAndReferenceId(3L, NotificationType.REPORT_REJECTED, "TOILET_REPORT", 20L)).thenReturn(true);

        service.createReportDecision(report, "시청 화장실");

        verify(repository, never()).save(any());
    }

    @Test
    void listsOnlyOwnersNotificationsAndLimitsPageSize() {
        when(repository.findMine(eq(3L), eq(true), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
        assertThat(service.mine(3L, true, 0, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.mine(3L, false, 0, 51)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotReadAnotherUsersNotification() {
        when(repository.findByIdAndUserId(10L, 3L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.markRead(3L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("찾을 수 없습니다");
    }
}
