package com.example.toiletapi.toilet.dto;

import com.example.toiletapi.toilet.model.Toilet;
import java.math.BigDecimal;

/**
 * 화장실 상세 정보를 표현합니다.
 */
public record ToiletDetailResponse(
        Long id,
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
        String diaperTableLocation,
        String dataBaseDate,
        String dataSource,
        ToiletRegionResponse region
) {

    /**
     * 엔티티를 API 상세 응답으로 변환합니다.
     *
     * @param toilet 화장실 엔티티
     * @return 화장실 상세 응답
     */
    public static ToiletDetailResponse from(Toilet toilet) {
        return from(toilet, null);
    }

    public static ToiletDetailResponse from(Toilet toilet, ToiletRegionResponse region) {
        return new ToiletDetailResponse(
                toilet.getId(),
                toilet.getName(),
                toilet.getToiletType(),
                toilet.getRoadAddress(),
                toilet.getJibunAddress(),
                toilet.getLatitude(),
                toilet.getLongitude(),
                toilet.getMaleToiletCount(),
                toilet.getMaleUrinalCount(),
                toilet.getMaleDisabledToiletCount(),
                toilet.getMaleDisabledUrinalCount(),
                toilet.getMaleChildToiletCount(),
                toilet.getMaleChildUrinalCount(),
                toilet.getFemaleToiletCount(),
                toilet.getFemaleDisabledToiletCount(),
                toilet.getFemaleChildToiletCount(),
                toilet.getAgencyName(),
                toilet.getPhoneNumber(),
                toilet.getOpenTime(),
                toilet.getOpenTimeDetail(),
                toilet.getInstallationDate(),
                toilet.getHasEmergencyBell(),
                toilet.getEmergencyBellLocation(),
                toilet.getHasCctv(),
                toilet.getHasDiaperTable(),
                toilet.getDiaperTableLocation(),
                toilet.getDataBaseDate(),
                toilet.getDataSource(),
                region
        );
    }
}
