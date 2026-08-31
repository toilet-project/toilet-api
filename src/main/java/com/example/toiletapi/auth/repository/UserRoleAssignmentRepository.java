package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.UserRoleAssignment;
import com.example.toiletapi.auth.model.UserRoleId;
import java.util.List;
import java.util.Collection;
import com.example.toiletapi.auth.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignment, UserRoleId> {
    List<UserRoleAssignment> findAllByUserId(Long userId);
    List<UserRoleAssignment> findAllByUserIdIn(Collection<Long> userIds);
    long countByRole(Role role);
    void deleteAllByUserId(Long userId);
}
