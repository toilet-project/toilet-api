package com.example.toiletapi.quality.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record CreateMapDisplayGroupRequest(
        @NotNull Direction direction,
        @NotBlank @Size(max = 100) String displayName,
        @Size(max = 100) String englishDisplayName,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal currentLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal currentLongitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal markerLatitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal markerLongitude,
        @NotEmpty @Size(max = 99) List<@NotNull @Positive Long> markerToiletIds,
        @Size(max = 500) String note
) {
    public CreateMapDisplayGroupRequest(
            Direction direction,
            String displayName,
            BigDecimal currentLatitude,
            BigDecimal currentLongitude,
            BigDecimal markerLatitude,
            BigDecimal markerLongitude,
            List<Long> markerToiletIds,
            String note
    ) {
        this(direction, displayName, null, currentLatitude, currentLongitude,
                markerLatitude, markerLongitude, markerToiletIds, note);
    }

    public enum Direction {
        CURRENT_TO_MARKER,
        MARKERS_TO_CURRENT
    }
}
