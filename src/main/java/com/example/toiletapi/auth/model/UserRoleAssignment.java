package com.example.toiletapi.auth.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Getter
@IdClass(UserRoleId.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "user_role")
public class UserRoleAssignment {
    @Id @Column(name = "user_id") private Long userId;
    @Id @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private Role role;
    @Column(name = "granted_at", nullable = false, updatable = false) private LocalDateTime grantedAt;
    @Column(name = "granted_by_user_id") private Long grantedByUserId;
    @PrePersist void onCreate() { grantedAt = LocalDateTime.now(); }

    public static UserRoleAssignment grant(Long userId, Role role, Long grantedByUserId) {
        UserRoleAssignment assignment = new UserRoleAssignment();
        assignment.userId = userId;
        assignment.role = role;
        assignment.grantedByUserId = grantedByUserId;
        return assignment;
    }
}
