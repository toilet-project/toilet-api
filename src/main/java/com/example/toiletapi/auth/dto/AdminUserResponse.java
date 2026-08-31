package com.example.toiletapi.auth.dto;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import java.time.LocalDateTime;
import java.util.Set;

public record AdminUserResponse(
        Long id,
        String displayName,
        String email,
        String status,
        Set<Role> roles,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt
) {
    public static AdminUserResponse from(AppUser user, Set<Role> roles) {
        return new AdminUserResponse(user.getId(), user.getDisplayName(), user.getEmail(), user.getStatus().name(),
                roles, user.getLastLoginAt(), user.getCreatedAt());
    }
}
