package com.example.toiletapi.analytics;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnalyticsEventRequest(
        @NotBlank @Size(max = 40) String event,
        @Size(max = 200) String path,
        @Size(max = 24) String source,
        @Size(max = 16) String resultCountBucket,
        @Min(0) @Max(3600) Integer engagementSeconds,
        @Min(0) @Max(100) Integer scrollPercent,
        @Size(max = 64) String sessionId,
        @Size(max = 40) String detail,
        Boolean success,
        Boolean newVisitor
) { }
