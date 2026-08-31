package com.example.toiletapi.notification.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user_notification")
public class UserNotification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40)
    private NotificationType type;

    @Column(name = "reference_type", nullable = false, length = 30)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private Long referenceId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 500)
    private String message;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void created() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public static UserNotification reportDecision(Long userId, Long reportId, NotificationType type, String toiletName) {
        if (type != NotificationType.REPORT_APPROVED && type != NotificationType.REPORT_REJECTED) {
            throw new IllegalArgumentException("지원하지 않는 제보 알림 유형입니다.");
        }
        UserNotification notification = new UserNotification();
        notification.userId = userId;
        notification.type = type;
        notification.referenceType = "TOILET_REPORT";
        notification.referenceId = reportId;
        boolean approved = type == NotificationType.REPORT_APPROVED;
        notification.title = approved ? "제보가 승인되었어요" : "제보가 반려되었어요";
        String target = toiletName == null || toiletName.isBlank() ? "제보한 화장실" : toiletName;
        notification.message = target + " 정보 제보가 " + (approved ? "승인되었습니다." : "반려되었습니다.");
        return notification;
    }

    public void markRead() {
        if (readAt == null) readAt = LocalDateTime.now();
    }
}
