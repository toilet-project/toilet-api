package com.example.toiletapi.report.dto;
import com.example.toiletapi.report.model.ToiletReport;
import java.math.BigDecimal; import java.time.LocalDateTime;
public record ToiletReportResponse(Long id, Long toiletId, String toiletName, String reportType, BigDecimal latitude, BigDecimal longitude, String roadAddress, String openTime, String reason, String status, String reviewNote, LocalDateTime createdAt, LocalDateTime reviewedAt, String jibunAddress) {
    public static ToiletReportResponse from(ToiletReport report, String toiletName) { return new ToiletReportResponse(report.getId(), report.getToiletId(), toiletName, report.getReportType(), report.getProposedLatitude(), report.getProposedLongitude(), report.getProposedRoadAddress(), report.getProposedOpenTime(), report.getReason(), report.getStatus().name(), report.getReviewNote(), report.getCreatedAt(), report.getReviewedAt(), report.getProposedJibunAddress()); }
}
