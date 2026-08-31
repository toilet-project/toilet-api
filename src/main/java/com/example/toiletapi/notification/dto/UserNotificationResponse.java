package com.example.toiletapi.notification.dto;

import com.example.toiletapi.notification.model.UserNotification;
import java.time.LocalDateTime;

public record UserNotificationResponse(
        Long id,
        String type,
        String referenceType,
        Long referenceId,
        String title,
        String message,
        boolean read,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {
    public static UserNotificationResponse from(UserNotification notification) {
        return new UserNotificationResponse(notification.getId(), notification.getType().name(),
                notification.getReferenceType(), notification.getReferenceId(), notification.getTitle(),
                notification.getMessage(), notification.getReadAt() != null, notification.getReadAt(), notification.getCreatedAt());
    }
}
