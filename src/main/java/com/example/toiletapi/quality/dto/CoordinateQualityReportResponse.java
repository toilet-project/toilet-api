package com.example.toiletapi.quality.dto;

import java.time.LocalDateTime;

public record CoordinateQualityReportResponse(Long reportId, Long toiletId, String toiletName, LocalDateTime createdAt) {
}
