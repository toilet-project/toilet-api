package com.example.toiletapi.notification.repository;

import com.example.toiletapi.notification.model.NotificationType;
import com.example.toiletapi.notification.model.UserNotification;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {
    @Query("""
            select notification from UserNotification notification
            where notification.userId = :userId
              and (:unreadOnly = false or notification.readAt is null)
            """)
    Page<UserNotification> findMine(@Param("userId") Long userId, @Param("unreadOnly") boolean unreadOnly, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    Optional<UserNotification> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndTypeAndReferenceTypeAndReferenceId(Long userId, NotificationType type, String referenceType, Long referenceId);

    @Modifying(clearAutomatically = true)
    @Query("update UserNotification notification set notification.readAt = :readAt where notification.userId = :userId and notification.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);
}
