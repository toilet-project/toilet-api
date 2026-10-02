package com.example.toiletapi.report.dto;

import java.math.BigDecimal;

/** Public proposals only. Coordinates/address are not authoritative until admin approval. */
public record QuickToiletReportRequest(Long toiletId, String reportType, BigDecimal latitude,
        BigDecimal longitude, String roadAddress, String name, String reason) { }
