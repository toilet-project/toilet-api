package com.example.toiletapi.auth.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "audit_log")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "audit_log_id") private Long id;
    @Column(name = "actor_user_id") private Long actorUserId;
    @Column(nullable = false, length = 100) private String action;
    @Column(name = "target_type", nullable = false, length = 50) private String targetType;
    @Column(name = "target_id") private Long targetId;
    @Column(name = "detail_json", columnDefinition = "json") private String detailJson;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @PrePersist void onCreate() { createdAt = LocalDateTime.now(); }

    public static AuditLog record(Long actorUserId, AuditAction action, String targetType, Long targetId, String detailJson) {
        AuditLog log = new AuditLog();
        log.actorUserId = actorUserId;
        log.action = action.name();
        log.targetType = targetType;
        log.targetId = targetId;
        log.detailJson = detailJson;
        return log;
    }
}
