package com.example.toiletapi.toilet.dto;

import com.example.toiletapi.toilet.model.Toilet;

/**
 * 지도 마커 표시에 필요한 화장실 기본 정보 응답입니다.
 *
 * @param id 화장실 식별자
 * @param name 화장실 이름
 * @param toiletType 화장실 구분
 * @param latitude 위도
 * @param longitude 경도
 * @param displayGroupId 관리자가 지정한 지도 노출 그룹 식별자
 * @param displayGroupName 지도에 표시할 그룹 이름
 */
public record ToiletMapResponse(
        Long id,
        String name,
        String toiletType,
        double latitude,
        double longitude,
        Long displayGroupId,
        String displayGroupName
) {

    /**
     * 엔티티를 지도 조회 응답으로 변환합니다.
     *
     * @param toilet 화장실 엔티티
     * @return 지도 조회 응답
     */
    public static ToiletMapResponse from(Toilet toilet) {
        return from(toilet, null, null);
    }

    public static ToiletMapResponse from(Toilet toilet, Long displayGroupId, String displayGroupName) {
        return new ToiletMapResponse(
                toilet.getId(),
                toilet.getName(),
                toilet.getToiletType(),
                toilet.getLatitude().doubleValue(),
                toilet.getLongitude().doubleValue(),
                displayGroupId,
                displayGroupName
        );
    }
}
