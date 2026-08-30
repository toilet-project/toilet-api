package com.example.toiletapi.report.dto;
import java.math.BigDecimal;
public record CreateToiletReportRequest(Long toiletId, String reportType, BigDecimal latitude, BigDecimal longitude, String roadAddress, String openTime, String reason) { }
