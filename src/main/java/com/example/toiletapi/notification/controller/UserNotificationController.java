package com.example.toiletapi.notification.controller;

import com.example.toiletapi.notification.dto.*;
import com.example.toiletapi.notification.service.UserNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class UserNotificationController {
    private final UserNotificationService service;

    @GetMapping
    public UserNotificationPageResponse mine(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @AuthenticationPrincipal Jwt jwt) {
        return service.mine(userId(jwt), unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadNotificationCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return service.unreadCount(userId(jwt));
    }

    @PatchMapping("/{notificationId}/read")
    public UserNotificationResponse markRead(@PathVariable Long notificationId, @AuthenticationPrincipal Jwt jwt) {
        return service.markRead(userId(jwt), notificationId);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(userId(jwt));
    }

    private Long userId(Jwt jwt) {
        try { return Long.valueOf(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("인증 사용자 식별자가 올바르지 않습니다."); }
    }
}
