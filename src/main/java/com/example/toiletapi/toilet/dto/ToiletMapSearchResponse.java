package com.example.toiletapi.toilet.dto;

import java.util.List;

/**
 * 지도 레벨에 맞춘 마커 또는 클러스터 조회 응답입니다.
 *
 * @param zoom 조회에 사용한 카카오맵 레벨
 * @param displayType 현재 표시 단위
 * @param markers 개별 마커 목록
 * @param clusters 클러스터 목록
 */
public record ToiletMapSearchResponse(
        int zoom,
        ToiletMapDisplayType displayType,
        List<ToiletMapResponse> markers,
        List<ToiletClusterResponse> clusters
) {

    /**
     * 개별 마커 조회 결과를 생성합니다.
     *
     * @param zoom 카카오맵 레벨
     * @param markers 마커 목록
     * @return 지도 조회 응답
     */
    public static ToiletMapSearchResponse markers(int zoom, List<ToiletMapResponse> markers) {
        return new ToiletMapSearchResponse(zoom, ToiletMapDisplayType.MARKER, markers, List.of());
    }

    /**
     * 클러스터 조회 결과를 생성합니다.
     *
     * @param zoom 카카오맵 레벨
     * @param clusters 클러스터 목록
     * @return 지도 조회 응답
     */
    public static ToiletMapSearchResponse clusters(int zoom, List<ToiletClusterResponse> clusters) {
        return new ToiletMapSearchResponse(zoom, ToiletMapDisplayType.CLUSTER, List.of(), clusters);
    }
}
