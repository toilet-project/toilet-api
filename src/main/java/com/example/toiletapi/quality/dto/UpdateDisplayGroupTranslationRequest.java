package com.example.toiletapi.quality.dto;

import jakarta.validation.constraints.Size;

public record UpdateDisplayGroupTranslationRequest(
        @Size(max = 100) String englishDisplayName
) {
}
