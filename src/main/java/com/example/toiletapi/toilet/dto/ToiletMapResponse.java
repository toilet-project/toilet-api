package com.example.toiletapi.toilet.dto;

import com.example.toiletapi.toilet.model.Toilet;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

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
        String displayGroupName,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, String> displayGroupTranslations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, ToiletTranslationResponse> translations
) {

    public ToiletMapResponse {
        displayGroupTranslations = displayGroupTranslations == null ? Map.of() : Map.copyOf(displayGroupTranslations);
        translations = translations == null ? Map.of() : Map.copyOf(translations);
    }

    public ToiletMapResponse(
            Long id,
            String name,
            String toiletType,
            double latitude,
            double longitude,
            Long displayGroupId,
            String displayGroupName,
            Map<String, ToiletTranslationResponse> translations
    ) {
        this(id, name, toiletType, latitude, longitude, displayGroupId, displayGroupName, Map.of(), translations);
    }

    /**
     * 엔티티를 지도 조회 응답으로 변환합니다.
     *
     * @param toilet 화장실 엔티티
     * @return 지도 조회 응답
     */
    public static ToiletMapResponse from(Toilet toilet) {
        return from(toilet, null, null, Map.of(), Map.of());
    }

    public static ToiletMapResponse from(Toilet toilet, Long displayGroupId, String displayGroupName) {
        return from(toilet, displayGroupId, displayGroupName, Map.of(), Map.of());
    }

    public static ToiletMapResponse from(
            Toilet toilet,
            Long displayGroupId,
            String displayGroupName,
            Map<String, String> displayGroupTranslations,
            Map<String, ToiletTranslationResponse> translations
    ) {
        return new ToiletMapResponse(
                toilet.getId(),
                toilet.getName(),
                toilet.getToiletType(),
                toilet.getLatitude().doubleValue(),
                toilet.getLongitude().doubleValue(),
                displayGroupId,
                displayGroupName,
                displayGroupTranslations,
                translations
        );
    }
}
