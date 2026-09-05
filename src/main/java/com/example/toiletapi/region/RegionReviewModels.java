package com.example.toiletapi.region;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class RegionReviewModels {
    private RegionReviewModels() {}

    public enum Filter { REVIEW, ALL, VERIFIED, MISMATCH, ADDRESS_UNVERIFIED, REVERSE_FAILED, NO_COORDINATE, STALE, UNASSESSED }
    public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
    public record Location(BigDecimal latitude, BigDecimal longitude, String roadAddress, String jibunAddress) {}
    public record Item(long toiletId, String name, String managementNumber, Location location,
                       String status, String assessmentStatus, String reason, String sidoName, String sidoCode,
                       String sigunguName, String sigunguCode, String cityName, String districtName,
                       OffsetDateTime checkedAt) {}
    public record Detail(Item toilet, Location assessedSource, BigDecimal evaluatedLatitude,
                         BigDecimal evaluatedLongitude, String evidenceJson) {}
    public record History(long assessmentId, String status, String reason, String algorithmVersion,
                          OffsetDateTime checkedAt, String evidenceJson) {}
    public record Correction(
            @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @NotBlank @Size(max = 500) String note,
            @NotNull @Valid Location expectedLocation) {}
}
