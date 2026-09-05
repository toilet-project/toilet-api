package com.example.toiletapi.quality.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CoordinateQualityRevisionResponse(
        Long revisionId,
        Long toiletId,
        Long reportId,
        BigDecimal previousLatitude,
        BigDecimal previousLongitude,
        BigDecimal appliedLatitude,
        BigDecimal appliedLongitude,
        String appliedRoadAddress,
        Long appliedByUserId,
        LocalDateTime appliedAt,
        String source,
        String appliedJibunAddress
) {
}
