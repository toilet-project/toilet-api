package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.model.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    @Query("""
            select distinct user from AppUser user
            where (:keyword is null
                or lower(coalesce(user.displayName, '')) like lower(concat('%', :keyword, '%'))
                or lower(coalesce(user.email, '')) like lower(concat('%', :keyword, '%')))
              and (:status is null or user.status = :status)
              and (:role is null or exists (
                    select assignment.userId from UserRoleAssignment assignment
                    where assignment.userId = user.id and assignment.role = :role
              ))
            """)
    Page<AppUser> searchAdminUsers(
            @Param("keyword") String keyword,
            @Param("status") UserStatus status,
            @Param("role") Role role,
            Pageable pageable
    );
}
