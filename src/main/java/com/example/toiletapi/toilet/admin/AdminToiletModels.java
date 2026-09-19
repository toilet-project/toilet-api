package com.example.toiletapi.toilet.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class AdminToiletModels {
    private AdminToiletModels() {
    }

    public record Page<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    }

    public record Region(String sidoName, String sidoCode, String sigunguName, String sigunguCode,
                         String cityName, String districtName) {
    }

    public record RegionOption(String sidoName, String sidoCode, String sigunguName, String sigunguCode) {
    }

    public record Item(long id, String name, String managementNumber, String toiletType,
                       String roadAddress, String jibunAddress, BigDecimal latitude, BigDecimal longitude,
                       String visibilityStatus, Region region) {
    }

    public record Suggestion(long id, String name, String address) {
    }

    public record Detail(
            long id,
            String managementNumber,
            String visibilityStatus,
            String coordinateSource,
            long regionRevision,
            String dataBaseDate,
            String dataSource,
            Region region,
            String snapshotToken,
            Editable editable
    ) {
    }

    public record UpdateRequest(
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String snapshotToken,
            @NotNull @Valid Editable editable
    ) {
    }

    public record Editable(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 20) String toiletType,
            @Size(max = 255) String roadAddress,
            @Size(max = 255) String jibunAddress,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @PositiveOrZero Integer maleToiletCount,
            @PositiveOrZero Integer maleUrinalCount,
            @PositiveOrZero Integer maleDisabledToiletCount,
            @PositiveOrZero Integer maleDisabledUrinalCount,
            @PositiveOrZero Integer maleChildToiletCount,
            @PositiveOrZero Integer maleChildUrinalCount,
            @PositiveOrZero Integer femaleToiletCount,
            @PositiveOrZero Integer femaleDisabledToiletCount,
            @PositiveOrZero Integer femaleChildToiletCount,
            @Size(max = 100) String agencyName,
            @Size(max = 20) String phoneNumber,
            @Size(max = 50) String openTime,
            @Size(max = 255) String openTimeDetail,
            @Size(max = 20) String installationDate,
            @Size(max = 10) String hasEmergencyBell,
            @Size(max = 100) String emergencyBellLocation,
            @Size(max = 10) String hasCctv,
            @Size(max = 10) String hasDiaperTable,
            @Size(max = 100) String diaperTableLocation
    ) {
        @AssertTrue(message = "위도와 경도는 함께 입력하거나 함께 비워 주세요.")
        public boolean hasCoordinatePair() {
            return (latitude == null) == (longitude == null);
        }
    }
}
