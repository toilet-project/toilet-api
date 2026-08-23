package com.example.toiletapi.toilet.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 지도 레벨에 맞춘 마커 또는 클러스터 조회 응답입니다.
 *
 * @param meta 지도 조회 메타데이터
 * @param toilets 개별 화장실 마커 목록
 * @param clusters 클러스터 목록
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ToiletMapSearchResponse(
        ToiletMapMeta meta,
        List<ToiletMapResponse> toilets,
        List<ToiletClusterResponse> clusters
) {

    /**
     * 개별 마커 조회 결과를 생성합니다.
     *
     * @param zoom 카카오맵 레벨
     * @param toilets 화장실 마커 목록
     * @return 지도 조회 응답
     */
    public static ToiletMapSearchResponse markers(int zoom, List<ToiletMapResponse> toilets) {
        return new ToiletMapSearchResponse(
                new ToiletMapMeta(zoom, ToiletMapDisplayType.MARKER, toilets.size(), toilets.size()),
                toilets,
                null
        );
    }

    /**
     * 클러스터 조회 결과를 생성합니다.
     *
     * @param zoom 카카오맵 레벨
     * @param clusters 클러스터 목록
     * @return 지도 조회 응답
     */
    public static ToiletMapSearchResponse clusters(int zoom, List<ToiletClusterResponse> clusters) {
        long totalCount = clusters.stream()
                .mapToLong(ToiletClusterResponse::count)
                .sum();

        return new ToiletMapSearchResponse(
                new ToiletMapMeta(zoom, ToiletMapDisplayType.CLUSTER, totalCount, clusters.size()),
                null,
                clusters
        );
    }
}
