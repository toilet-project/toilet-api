package com.example.toiletapi.toilet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 지도 조회 결과를 해석하기 위한 메타데이터입니다.
 *
 * @param mapLevel 조회에 사용한 카카오맵 레벨
 * @param displayType 현재 표시 단위
 * @param totalCount 화면 영역에 포함된 전체 화장실 수
 * @param resultCount 반환된 마커 또는 클러스터 수
 */
public record ToiletMapMeta(
        @JsonProperty("map_level") int mapLevel,
        @JsonProperty("display_type") ToiletMapDisplayType displayType,
        @JsonProperty("total_count") long totalCount,
        @JsonProperty("result_count") int resultCount
) {
}
