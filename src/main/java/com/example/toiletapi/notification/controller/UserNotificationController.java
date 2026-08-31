package com.example.toiletapi.notification.controller;

import com.example.toiletapi.notification.dto.*;
import com.example.toiletapi.notification.service.UserNotificationService;
import com.example.toiletapi.policy.service.PolicyConsentService;
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
    private final PolicyConsentService policyConsentService;

    @GetMapping
    public UserNotificationPageResponse mine(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @AuthenticationPrincipal Jwt jwt) {
        Long userId = userId(jwt); policyConsentService.requireEligibleUser(userId);
        return service.mine(userId, unreadOnly, page, size);
    }

    @GetMapping("/unread-count")
    public UnreadNotificationCountResponse unreadCount(@AuthenticationPrincipal Jwt jwt) {
        Long userId = userId(jwt); policyConsentService.requireEligibleUser(userId);
        return service.unreadCount(userId);
    }

    @PatchMapping("/{notificationId}/read")
    public UserNotificationResponse markRead(@PathVariable Long notificationId, @AuthenticationPrincipal Jwt jwt) {
        Long userId = userId(jwt); policyConsentService.requireEligibleUser(userId);
        return service.markRead(userId, notificationId);
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Jwt jwt) {
        Long userId = userId(jwt); policyConsentService.requireEligibleUser(userId);
        service.markAllRead(userId);
    }

    private Long userId(Jwt jwt) {
        try { return Long.valueOf(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("인증 사용자 식별자가 올바르지 않습니다."); }
    }
}
