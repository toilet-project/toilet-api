package com.example.toiletapi.quality.dto;

import com.example.toiletapi.quality.model.CoordinateQualityStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ReviewCoordinateGroupRequest(
        @NotNull BigDecimal latitude,
        @NotNull BigDecimal longitude,
        @NotNull CoordinateQualityStatus status,
        @Size(max = 500) String note
) {
}
