package com.example.toiletapi.quality.dto;

import com.example.toiletapi.quality.model.CoordinateQualityStatus;
import java.math.BigDecimal;

public record DuplicateCoordinateGroupResponse(
        String groupKey,
        BigDecimal latitude,
        BigDecimal longitude,
        long toiletCount,
        String representativeName,
        String region,
        CoordinateQualityStatus status,
        long pendingReportCount
) {
}
