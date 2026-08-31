package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.AuditLog;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
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
