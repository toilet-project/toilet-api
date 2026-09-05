package com.example.toiletapi.toilet.repository;

/** 공개 상세에 필요한 현재 검증된 지역정보만 조회합니다. */
public interface ToiletRegionProjection {
    String getSidoName();
    String getSidoCode();
    String getSigunguName();
    String getSigunguCode();
    String getCityName();
    String getDistrictName();
}
