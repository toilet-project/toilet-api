package com.example.toiletapi.notification.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record UserNotificationPageResponse(
        List<UserNotificationResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static UserNotificationPageResponse from(Page<UserNotificationResponse> page) {
        return new UserNotificationPageResponse(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }
}
