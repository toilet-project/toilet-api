package com.example.toiletapi.auth.dto;

import java.util.List;

public record AdminUserPageResponse(
        List<AdminUserResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) { }
