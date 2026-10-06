package com.example.toiletapi.report.dto;

import java.math.BigDecimal;
import com.example.toiletapi.report.model.NewFacilityInfo;

/** Public proposals only. Coordinates/address are not authoritative until admin approval. */
public record QuickToiletReportRequest(Long toiletId, String reportType, BigDecimal latitude,
        BigDecimal longitude, String roadAddress, String name, String reason, NewFacilityInfo facilityInfo) {
    public QuickToiletReportRequest(Long toiletId, String reportType, BigDecimal latitude,
            BigDecimal longitude, String roadAddress, String name, String reason) {
        this(toiletId, reportType, latitude, longitude, roadAddress, name, reason, null);
    }
    /** Preserve retry fingerprints for requests created before this additive schema change. */
    public String fingerprintInput() {
        return facilityInfo != null ? toString() : "QuickToiletReportRequest[toiletId=" + toiletId
                + ", reportType=" + reportType + ", latitude=" + latitude + ", longitude=" + longitude
                + ", roadAddress=" + roadAddress + ", name=" + name + ", reason=" + reason + "]";
    }
}
