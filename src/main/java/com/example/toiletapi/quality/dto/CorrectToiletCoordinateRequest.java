package com.example.toiletapi.quality.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record CorrectToiletCoordinateRequest(
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @Size(max = 255) String roadAddress,
        @Size(max = 500) String note,
        @Positive Long displayGroupId,
        @Size(max = 100) String displayGroupName,
        @Size(min = 2, max = 100) List<@NotNull @Positive Long> displayGroupToiletIds
) {
    public CorrectToiletCoordinateRequest(BigDecimal latitude, BigDecimal longitude,
                                          String roadAddress, String note) {
        this(latitude, longitude, roadAddress, note, null, null, null);
    }
}
