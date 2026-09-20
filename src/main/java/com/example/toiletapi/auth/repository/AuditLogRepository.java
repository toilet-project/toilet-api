package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.AuditLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    @Query(value = """
            SELECT log.*
              FROM audit_log log
             WHERE log.action='TOILET_OPENING_HOURS_CONFIRMED'
               AND log.target_type='TOILET_OPENING_HOURS_PATTERN'
               AND JSON_UNQUOTE(JSON_EXTRACT(log.detail_json,'$.patternKey'))=:patternKey
             ORDER BY log.created_at DESC,log.audit_log_id DESC
            """, nativeQuery = true)
    List<AuditLog> findOpeningHoursPatternHistory(@Param("patternKey") String patternKey, Pageable pageable);

    @Query("""
            select log from AuditLog log
            where (:fromInclusive is null or log.createdAt >= :fromInclusive)
              and (:toExclusive is null or log.createdAt < :toExclusive)
              and (:action is null or log.action = :action)
              and (:actorUserId is null or log.actorUserId = :actorUserId)
              and (:targetType is null or log.targetType = :targetType)
              and (:targetId is null or log.targetId = :targetId)
            """)
    Page<AuditLog> search(
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive,
            @Param("action") String action,
            @Param("actorUserId") Long actorUserId,
            @Param("targetType") String targetType,
            @Param("targetId") Long targetId,
            Pageable pageable
    );
}
