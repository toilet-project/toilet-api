package com.example.toiletapi.quality.dto;

import java.util.List;

public record DuplicateCoordinateGroupPageResponse(
        List<DuplicateCoordinateGroupResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
