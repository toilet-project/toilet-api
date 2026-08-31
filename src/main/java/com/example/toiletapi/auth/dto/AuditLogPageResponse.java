package com.example.toiletapi.auth.dto;

import java.util.List;

public record AuditLogPageResponse(
        List<AuditLogResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) { }
