package com.example.toiletapi.quality.dto;

import java.util.List;

public record ToiletDisplayGroupResponse(
        Long id,
        String displayName,
        List<Long> toiletIds
) {
}
