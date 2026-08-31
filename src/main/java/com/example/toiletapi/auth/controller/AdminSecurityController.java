package com.example.toiletapi.auth.controller;

import com.example.toiletapi.auth.dto.*;
import com.example.toiletapi.auth.model.*;
import com.example.toiletapi.auth.service.AdminSecurityService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/v1/security")
@RequiredArgsConstructor
public class AdminSecurityController {
    private final AdminSecurityService service;

    @GetMapping("/users")
    public AdminUserPageResponse users(@RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) UserStatus status,
                                       @RequestParam(required = false) Role role,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.users(keyword, status, role, page, size);
    }

    @PostMapping("/users/{userId}/admin-role")
    public AdminUserResponse grantAdmin(@PathVariable Long userId, @AuthenticationPrincipal Jwt jwt) {
        return service.grantAdmin(userId(jwt), userId);
    }

    @DeleteMapping("/users/{userId}/admin-role")
    public AdminUserResponse revokeAdmin(@PathVariable Long userId, @AuthenticationPrincipal Jwt jwt) {
        return service.revokeAdmin(userId(jwt), userId);
    }

    @GetMapping("/audit-logs")
    public AuditLogPageResponse auditLogs(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) Long actorUserId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(defaultValue = "NEWEST") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.auditLogs(from, to, action, actorUserId, targetType, targetId, sort, page, size);
    }

    private Long userId(Jwt jwt) {
        try { return Long.valueOf(jwt.getSubject()); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("인증 사용자 식별자가 올바르지 않습니다."); }
    }
}
