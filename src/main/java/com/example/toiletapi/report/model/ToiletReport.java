package com.example.toiletapi.report.model;

import com.example.toiletapi.global.time.KoreanTime;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "toilet_report")
public class ToiletReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "report_id") private Long id;
    @Column(name = "toilet_id") private Long toiletId;
    @Column(name = "reporter_kind", nullable = false, length = 10) private String reporterKind = "MEMBER";
    @Column(name = "proposed_name", length = 100) private String proposedName;
    @Column(name = "observed_at") private LocalDateTime observedAt;
    @Column(name = "observed_open_time_detail", columnDefinition = "TEXT") private String observedOpenTimeDetail;
    @Column(name = "submission_key", length = 64) private String submissionKey;
    @Column(name = "submission_fingerprint", length = 64) private String submissionFingerprint;
    @Column(name = "reporter_user_id") private Long reporterUserId;
    @Column(name = "report_type", nullable = false, length = 30) private String reportType;
    @Column(name = "proposed_latitude", precision = 10, scale = 7) private BigDecimal proposedLatitude;
    @Column(name = "proposed_longitude", precision = 10, scale = 7) private BigDecimal proposedLongitude;
    @Column(name = "proposed_road_address", length = 255) private String proposedRoadAddress;
    @Column(name = "proposed_jibun_address", length = 255) private String proposedJibunAddress;
    @Column(name = "proposed_open_time", length = 50) private String proposedOpenTime;
    @Column(nullable = false, length = 500) private String reason;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReportStatus status = ReportStatus.PENDING;
    @Column(name = "active_request_key", length = 64) private String activeRequestKey;
    @Column(name = "reviewed_by_user_id") private Long reviewedByUserId;
    @Column(name = "reviewed_at") private LocalDateTime reviewedAt;
    @Column(name = "review_note", length = 500) private String reviewNote;
    @Column(name = "created_at", updatable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @PrePersist void created() { var now = KoreanTime.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void updated() { updatedAt = KoreanTime.now(); }
    public static ToiletReport createCoordinateCorrection(Long toiletId, Long reporterUserId, BigDecimal latitude, BigDecimal longitude, String roadAddress, String jibunAddress, String reason, String activeKey) {
        ToiletReport report = new ToiletReport(); report.toiletId = toiletId; report.reporterUserId = reporterUserId;
        report.reportType = "COORDINATE_CORRECTION"; report.proposedLatitude = latitude; report.proposedLongitude = longitude;
        report.proposedRoadAddress = roadAddress; report.proposedJibunAddress = jibunAddress;
        report.reason = reason; report.activeRequestKey = activeKey; return report;
    }
    public static ToiletReport createOpenTimeCorrection(Long toiletId, Long reporterUserId, String openTime, String reason, String activeKey) {
        ToiletReport report = new ToiletReport(); report.toiletId = toiletId; report.reporterUserId = reporterUserId;
        report.reportType = "OPEN_TIME_CORRECTION"; report.proposedOpenTime = openTime;
        report.reason = reason; report.activeRequestKey = activeKey; return report;
    }
    public void approve(Long adminId, String note) { review(adminId, note, ReportStatus.APPROVED); }
    public static ToiletReport quick(Long toiletId, Long userId, String type, String name,
            BigDecimal latitude, BigDecimal longitude, String address, String openTime,
            String reason, String activeKey, String submissionKey, String fingerprint) {
        ToiletReport report = new ToiletReport();
        report.toiletId = toiletId; report.reporterUserId = userId;
        report.reporterKind = userId == null ? "GUEST" : "MEMBER";
        report.reportType = type; report.proposedName = name;
        report.proposedLatitude = latitude; report.proposedLongitude = longitude;
        report.proposedRoadAddress = address; report.proposedOpenTime = openTime;
        report.reason = reason; report.observedAt = KoreanTime.now();
        report.activeRequestKey = activeKey; report.submissionKey = submissionKey;
        report.submissionFingerprint = fingerprint;
        return report;
    }
    public void linkApprovedFacility(Long toiletId) {
        if (!"NEW_FACILITY".equals(reportType) || this.toiletId != null || status != ReportStatus.PENDING)
            throw new IllegalArgumentException("신규 시설 제보만 등록할 수 있습니다.");
        this.toiletId = toiletId;
    }
    public void captureObservationDetails(String openTimeDetail, String jibunAddress) {
        if (!"FACILITY_MISSING".equals(reportType) && !"TEMPORARILY_CLOSED".equals(reportType))
            throw new IllegalArgumentException("관찰 제보만 당시 정보를 보관할 수 있습니다.");
        this.observedOpenTimeDetail = openTimeDetail;
        this.proposedJibunAddress = jibunAddress;
    }
    public void reject(Long adminId, String note) { review(adminId, note, ReportStatus.REJECTED); }
    private void review(Long adminId, String note, ReportStatus next) {
        if (status != ReportStatus.PENDING) throw new IllegalArgumentException("대기 중인 제보만 처리할 수 있습니다.");
        status = next; reviewedByUserId = adminId; reviewedAt = KoreanTime.now(); reviewNote = note; activeRequestKey = null;
    }
}
