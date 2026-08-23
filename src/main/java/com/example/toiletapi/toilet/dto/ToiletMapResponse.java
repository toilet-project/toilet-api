package com.example.toiletapi.toilet.dto;

import com.example.toiletapi.toilet.model.Toilet;

/**
 * 지도 마커 표시에 필요한 화장실 기본 정보 응답입니다.
 *
 * @param id 화장실 식별자
 * @param name 화장실 이름
 * @param latitude 위도
 * @param longitude 경도
 */
public record ToiletMapResponse(
        Long id,
        String name,
        double latitude,
        double longitude
) {

    /**
     * 엔티티를 지도 조회 응답으로 변환합니다.
     *
     * @param toilet 화장실 엔티티
     * @return 지도 조회 응답
     */
    public static ToiletMapResponse from(Toilet toilet) {
        return new ToiletMapResponse(
                toilet.getId(),
                toilet.getName(),
                toilet.getLatitude().doubleValue(),
                toilet.getLongitude().doubleValue()
        );
    }
}
