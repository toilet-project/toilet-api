package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.model.AuditAction;
import com.example.toiletapi.auth.model.AuditLog;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 개인정보나 토큰 원문 없이 관리자·보안 행위를 기록하는 공통 감사 경계다. */
@Service
public class AuditLogService {
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void recordRoleGranted(Long actorUserId, Long targetUserId, Role role) {
        record(actorUserId, AuditAction.ROLE_GRANTED, "USER", targetUserId, Map.of("role", role.name()));
    }

    @Transactional
    public void recordRoleRevoked(Long actorUserId, Long targetUserId, Role role) {
        record(actorUserId, AuditAction.ROLE_REVOKED, "USER", targetUserId, Map.of("role", role.name()));
    }

    @Transactional
    public void recordReportDecision(Long actorUserId, Long reportId, AuditAction action, Map<String, ?> details) {
        if (action != AuditAction.REPORT_APPROVED && action != AuditAction.REPORT_REJECTED) {
            throw new IllegalArgumentException("제보 감사 로그에는 승인 또는 반려 이벤트만 기록할 수 있습니다.");
        }
        record(actorUserId, action, "TOILET_REPORT", reportId, details);
    }

    @Transactional
    public void record(Long actorUserId, AuditAction action, String targetType, Long targetId, Map<String, ?> details) {
        auditLogRepository.save(AuditLog.record(actorUserId, action, targetType, targetId, toMaskedJson(details)));
    }

    private String toMaskedJson(Map<String, ?> details) {
        Map<String, Object> masked = new LinkedHashMap<>();
        details.forEach((key, value) -> masked.put(key, isSensitive(key) ? "[REDACTED]" : value));
        try {
            return objectMapper.writeValueAsString(masked);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("감사 로그 상세 정보를 직렬화할 수 없습니다.", exception);
        }
    }

    private boolean isSensitive(String key) {
        String normalized = key.toLowerCase();
        return normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")
                || normalized.contains("authorization") || normalized.contains("email");
    }
}
