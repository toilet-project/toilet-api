package com.example.toiletapi.auth.repository;

import com.example.toiletapi.auth.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> { }
