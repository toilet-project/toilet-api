package com.example.toiletapi.auth.dto;

import com.example.toiletapi.auth.model.AuditLog;
import java.time.LocalDateTime;

public record AuditLogResponse(
        Long id,
        Long actorUserId,
        String actorDisplayName,
        String action,
        String targetType,
        Long targetId,
        String detailJson,
        LocalDateTime createdAt
) {
    public static AuditLogResponse from(AuditLog log, String actorDisplayName) {
        return new AuditLogResponse(log.getId(), log.getActorUserId(), actorDisplayName, log.getAction(),
                log.getTargetType(), log.getTargetId(), log.getDetailJson(), log.getCreatedAt());
    }
}
