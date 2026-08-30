package com.example.toiletapi.report.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "toilet_report")
public class ToiletReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "report_id") private Long id;
    @Column(name = "toilet_id", nullable = false) private Long toiletId;
    @Column(name = "reporter_user_id", nullable = false) private Long reporterUserId;
    @Column(name = "report_type", nullable = false, length = 30) private String reportType;
    @Column(name = "proposed_latitude", precision = 10, scale = 7) private BigDecimal proposedLatitude;
    @Column(name = "proposed_longitude", precision = 10, scale = 7) private BigDecimal proposedLongitude;
    @Column(name = "proposed_road_address", length = 255) private String proposedRoadAddress;
    @Column(name = "proposed_open_time", length = 50) private String proposedOpenTime;
    @Column(nullable = false, length = 500) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReportStatus status = ReportStatus.PENDING;
    @Column(name = "active_request_key", length = 64) private String activeRequestKey;
    @Column(name = "reviewed_by_user_id") private Long reviewedByUserId;
    @Column(name = "reviewed_at") private LocalDateTime reviewedAt;
    @Column(name = "review_note", length = 500) private String reviewNote;
    @Column(name = "created_at", updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @PrePersist void created() { var now = LocalDateTime.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void updated() { updatedAt = LocalDateTime.now(); }
    public static ToiletReport createCoordinateCorrection(Long toiletId, Long reporterUserId, BigDecimal latitude, BigDecimal longitude, String roadAddress, String reason, String activeKey) {
        ToiletReport report = new ToiletReport(); report.toiletId = toiletId; report.reporterUserId = reporterUserId;
        report.reportType = "COORDINATE_CORRECTION"; report.proposedLatitude = latitude; report.proposedLongitude = longitude;
        report.proposedRoadAddress = roadAddress; report.reason = reason; report.activeRequestKey = activeKey; return report;
    }
    public static ToiletReport createOpenTimeCorrection(Long toiletId, Long reporterUserId, String openTime, String reason, String activeKey) {
        ToiletReport report = new ToiletReport(); report.toiletId = toiletId; report.reporterUserId = reporterUserId;
        report.reportType = "OPEN_TIME_CORRECTION"; report.proposedOpenTime = openTime;
        report.reason = reason; report.activeRequestKey = activeKey; return report;
    }
    public void approve(Long adminId, String note) { review(adminId, note, ReportStatus.APPROVED); }
    public void reject(Long adminId, String note) { review(adminId, note, ReportStatus.REJECTED); }
    private void review(Long adminId, String note, ReportStatus next) {
        if (status != ReportStatus.PENDING) throw new IllegalArgumentException("대기 중인 제보만 처리할 수 있습니다.");
        status = next; reviewedByUserId = adminId; reviewedAt = LocalDateTime.now(); reviewNote = note; activeRequestKey = null;
    }
}
