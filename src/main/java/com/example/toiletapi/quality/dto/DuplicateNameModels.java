package com.example.toiletapi.quality.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class DuplicateNameModels {
    private DuplicateNameModels() {}
    public enum Comparison { ALL, COORDINATES, DISTRICT }
    public enum WorkVisibility { VISIBLE, HIDDEN, ALL }
    public record Group(String name, long total, long hidden, BigDecimal latitude, BigDecimal longitude,
                        String sigunguCode, String regionName, boolean workHidden, long workVersion) {}
    public record WorkVisibilityRequest(@NotBlank @Size(max=100) String name, @NotNull Boolean hidden,
                                        @PositiveOrZero long expectedVersion) {}
    public record WorkVisibilityResult(boolean hidden, long version) {}
    public record Page(List<Group> items, int page, int size, long totalElements) {}
    public record Facility(long id, String name, String managementNumber, String roadAddress,
                           String jibunAddress, BigDecimal latitude, BigDecimal longitude,
                           String coordinateSource, String openTime, String dataSource,
                           String visibilityStatus, Long representativeToiletId, long version,
                           String hiddenReason, LocalDateTime hiddenAt, String sigunguCode, String regionName) {}
    public record HideRequest(@Positive long representativeId,
                              @NotEmpty @Size(max=100) List<@NotNull @Positive Long> toiletIds,
                              @NotEmpty @Size(max=101) Map<@Positive Long, @NotNull @PositiveOrZero Long> expectedVersions,
                              @NotBlank @Size(max=500) String reason) {}
    public record ExactDuplicateCleanupPreview(long groups, long facilitiesToHide, long blockedGroups) {}
    public record ExactDuplicateCleanupRequest(@Min(1) @Max(50) int maxGroups,
                                               @NotBlank @Size(max=500) String reason) {}
    public record ExactDuplicateCleanupResult(long processedGroups, long hiddenFacilities,
                                              ExactDuplicateCleanupPreview remaining) {}
    public record RestoreRequest(@PositiveOrZero long expectedVersion, @NotBlank @Size(max=500) String reason) {}
    public record Event(long id, long toiletId, Long representativeId, String action, String reason,
                        LocalDateTime occurredAt, String snapshotName, String snapshotRoadAddress) {}
}
