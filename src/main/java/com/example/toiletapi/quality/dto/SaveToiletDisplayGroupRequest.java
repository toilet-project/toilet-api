package com.example.toiletapi.quality.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SaveToiletDisplayGroupRequest(
        @Positive Long displayGroupId,
        @NotBlank @Size(max = 100) String displayName,
        @NotEmpty @Size(min = 2, max = 100) List<@NotNull @Positive Long> toiletIds
) {
}
