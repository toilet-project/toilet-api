package com.example.toiletapi.quality.dto;

import java.util.List;

public record ToiletDisplayGroupResponse(
        Long id,
        String displayName,
        String englishDisplayName,
        List<Long> toiletIds
) {
}
