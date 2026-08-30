package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.UserRoleAssignment;
import com.example.toiletapi.auth.model.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignment, UserRoleId> { }
