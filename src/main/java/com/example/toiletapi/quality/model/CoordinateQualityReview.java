package com.example.toiletapi.quality.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "coordinate_quality_review")
public class CoordinateQualityReview {
    @Id
    @Column(name = "group_key", length = 64)
    private String groupKey;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 10, scale = 7)
    private BigDecimal longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CoordinateQualityStatus status;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "reviewed_by_user_id")
    private Long reviewedByUserId;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    public static CoordinateQualityReview create(String groupKey, BigDecimal latitude, BigDecimal longitude) {
        CoordinateQualityReview review = new CoordinateQualityReview();
        review.groupKey = groupKey;
        review.latitude = latitude;
        review.longitude = longitude;
        review.status = CoordinateQualityStatus.PENDING;
        return review;
    }

    public void review(CoordinateQualityStatus status, String reviewNote, Long adminId) {
        this.status = status;
        this.reviewNote = reviewNote;
        this.reviewedByUserId = adminId;
        this.reviewedAt = LocalDateTime.now();
    }
}
