package com.example.toiletapi.report.dto;
import com.example.toiletapi.report.model.ToiletReport;
import java.math.BigDecimal; import java.time.LocalDateTime;
public record ToiletReportResponse(Long id, Long toiletId, String reportType, BigDecimal latitude, BigDecimal longitude, String roadAddress, String openTime, String reason, String status, String reviewNote, LocalDateTime createdAt, LocalDateTime reviewedAt) {
    public static ToiletReportResponse from(ToiletReport report) { return new ToiletReportResponse(report.getId(), report.getToiletId(), report.getReportType(), report.getProposedLatitude(), report.getProposedLongitude(), report.getProposedRoadAddress(), report.getProposedOpenTime(), report.getReason(), report.getStatus().name(), report.getReviewNote(), report.getCreatedAt(), report.getReviewedAt()); }
}
