package com.example.toiletapi.toilet.model;

import java.math.BigDecimal;

/** 관리자 화면에서 직접 수정할 수 있는 화장실 필드 묶음입니다. */
public record ToiletEditableData(
        String name,
        String toiletType,
        String roadAddress,
        String jibunAddress,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer maleToiletCount,
        Integer maleUrinalCount,
        Integer maleDisabledToiletCount,
        Integer maleDisabledUrinalCount,
        Integer maleChildToiletCount,
        Integer maleChildUrinalCount,
        Integer femaleToiletCount,
        Integer femaleDisabledToiletCount,
        Integer femaleChildToiletCount,
        String agencyName,
        String phoneNumber,
        String openTime,
        String openTimeDetail,
        String installationDate,
        String hasEmergencyBell,
        String emergencyBellLocation,
        String hasCctv,
        String hasDiaperTable,
        String diaperTableLocation
) {
}
