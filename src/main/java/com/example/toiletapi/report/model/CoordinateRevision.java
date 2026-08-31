package com.example.toiletapi.report.model;

import com.example.toiletapi.global.time.KoreanTime;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.*;

@Entity @Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coordinate_revision")
public class CoordinateRevision {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = "coordinate_revision_id") private Long id;
    @Column(name = "toilet_id", nullable = false) private Long toiletId;
    @Column(name = "report_id") private Long reportId;
    @Column(name = "previous_latitude", precision = 10, scale = 7) private BigDecimal previousLatitude;
    @Column(name = "previous_longitude", precision = 10, scale = 7) private BigDecimal previousLongitude;
    @Column(name = "applied_latitude", nullable = false, precision = 10, scale = 7) private BigDecimal appliedLatitude;
    @Column(name = "applied_longitude", nullable = false, precision = 10, scale = 7) private BigDecimal appliedLongitude;
    @Column(name = "previous_road_address", length = 255) private String previousRoadAddress;
    @Column(name = "applied_road_address", nullable = false, length = 255) private String appliedRoadAddress;
    @Column(name = "applied_by_user_id", nullable = false) private Long appliedByUserId;
    @Column(name = "applied_at", nullable = false) private LocalDateTime appliedAt;
    @Column(nullable = false, length = 30) private String source;
    public static CoordinateRevision create(ToiletReport report, BigDecimal previousLatitude, BigDecimal previousLongitude, String previousRoadAddress,
                                            BigDecimal appliedLatitude, BigDecimal appliedLongitude, String appliedRoadAddress, Long adminId) {
        CoordinateRevision revision = new CoordinateRevision(); revision.toiletId = report.getToiletId(); revision.reportId = report.getId();
        revision.previousLatitude = previousLatitude; revision.previousLongitude = previousLongitude;
        revision.appliedLatitude = appliedLatitude; revision.appliedLongitude = appliedLongitude;
        revision.previousRoadAddress = previousRoadAddress; revision.appliedRoadAddress = appliedRoadAddress;
        revision.appliedByUserId = adminId; revision.appliedAt = KoreanTime.now(); revision.source = "USER_REPORT_APPROVED"; return revision;
    }

    public static CoordinateRevision createAdminDirect(Long toiletId, BigDecimal previousLatitude, BigDecimal previousLongitude,
                                                       String previousRoadAddress, BigDecimal appliedLatitude,
                                                       BigDecimal appliedLongitude, String appliedRoadAddress, Long adminId) {
        CoordinateRevision revision = new CoordinateRevision();
        revision.toiletId = toiletId;
        revision.previousLatitude = previousLatitude;
        revision.previousLongitude = previousLongitude;
        revision.previousRoadAddress = previousRoadAddress;
        revision.appliedLatitude = appliedLatitude;
        revision.appliedLongitude = appliedLongitude;
        revision.appliedRoadAddress = appliedRoadAddress;
        revision.appliedByUserId = adminId;
        revision.appliedAt = KoreanTime.now();
        revision.source = "ADMIN_DIRECT";
        return revision;
    }
}
