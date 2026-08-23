package com.example.toiletapi.toilet.dto;

import com.example.toiletapi.toilet.repository.ToiletClusterProjection;

/**
 * 넓은 지도 영역에서 여러 화장실을 묶어 표시하는 클러스터 응답입니다.
 *
 * @param latitude 클러스터 중심 위도
 * @param longitude 클러스터 중심 경도
 * @param count 클러스터에 포함된 화장실 수
 */
public record ToiletClusterResponse(
        double latitude,
        double longitude,
        long count
) {

    /**
     * 데이터베이스 집계 결과를 API 응답으로 변환합니다.
     *
     * @param projection 클러스터 집계 결과
     * @return 클러스터 응답
     */
    public static ToiletClusterResponse from(ToiletClusterProjection projection) {
        return new ToiletClusterResponse(
                projection.getLatitude().doubleValue(),
                projection.getLongitude().doubleValue(),
                projection.getToiletCount()
        );
    }
}
