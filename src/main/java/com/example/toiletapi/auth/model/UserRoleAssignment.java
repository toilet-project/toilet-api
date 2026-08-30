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
}
