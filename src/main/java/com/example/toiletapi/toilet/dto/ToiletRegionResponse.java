package com.example.toiletapi.toilet.dto;

import com.example.toiletapi.toilet.repository.ToiletRegionProjection;

/** 시·도 → 시·군·구, 일반 시의 구 계층과 세종의 빈 하위 계층을 그대로 보존합니다. */
public record ToiletRegionResponse(
        String sidoName, String sidoCode, String sigunguName, String sigunguCode,
        String cityName, String districtName
) {
    public static ToiletRegionResponse from(ToiletRegionProjection region) {
        return new ToiletRegionResponse(region.getSidoName(), region.getSidoCode(),
                region.getSigunguName(), region.getSigunguCode(), region.getCityName(), region.getDistrictName());
    }
}
