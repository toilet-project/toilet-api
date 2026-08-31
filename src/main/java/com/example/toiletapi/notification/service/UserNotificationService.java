package com.example.toiletapi.notification.service;

import com.example.toiletapi.global.time.KoreanTime;
import com.example.toiletapi.notification.dto.*;
import com.example.toiletapi.notification.model.NotificationType;
import com.example.toiletapi.notification.model.UserNotification;
import com.example.toiletapi.notification.repository.UserNotificationRepository;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.model.ToiletReport;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserNotificationService {
    private static final int MAX_PAGE_SIZE = 50;
    private final UserNotificationRepository repository;

    @Transactional
    public void createReportDecision(ToiletReport report, String toiletName) {
        NotificationType type = switch (report.getStatus()) {
            case APPROVED -> NotificationType.REPORT_APPROVED;
            case REJECTED -> NotificationType.REPORT_REJECTED;
            default -> throw new IllegalArgumentException("처리 완료된 제보만 알림을 만들 수 있습니다.");
        };
        if (!repository.existsByUserIdAndTypeAndReferenceTypeAndReferenceId(
                report.getReporterUserId(), type, "TOILET_REPORT", report.getId())) {
            repository.save(UserNotification.reportDecision(report.getReporterUserId(), report.getId(), type, toiletName));
        }
    }

    @Transactional(readOnly = true)
    public UserNotificationPageResponse mine(Long userId, boolean unreadOnly, int page, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("페이지 크기는 1~50이어야 합니다.");
        var pageable = PageRequest.of(Math.max(page, 0), size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return UserNotificationPageResponse.from(repository.findMine(userId, unreadOnly, pageable).map(UserNotificationResponse::from));
    }

    @Transactional(readOnly = true)
    public UnreadNotificationCountResponse unreadCount(Long userId) {
        return new UnreadNotificationCountResponse(repository.countByUserIdAndReadAtIsNull(userId));
    }

    @Transactional
    public UserNotificationResponse markRead(Long userId, Long notificationId) {
        UserNotification notification = repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다."));
        notification.markRead();
        return UserNotificationResponse.from(notification);
    }

    @Transactional
    public void markAllRead(Long userId) {
        repository.markAllRead(userId, KoreanTime.now());
    }
}
