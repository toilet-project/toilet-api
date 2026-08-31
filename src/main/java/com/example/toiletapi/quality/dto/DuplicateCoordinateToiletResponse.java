package com.example.toiletapi.quality.dto;

import java.math.BigDecimal;

public record DuplicateCoordinateToiletResponse(
        Long id,
        String managementNumber,
        String name,
        String toiletType,
        String roadAddress,
        String jibunAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        String coordinateSource
) {
}
