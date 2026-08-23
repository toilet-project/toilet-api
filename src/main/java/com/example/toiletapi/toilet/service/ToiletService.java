package com.example.toiletapi.toilet.service;

import com.example.toiletapi.toilet.dto.ToiletClusterResponse;
import com.example.toiletapi.toilet.dto.ToiletMapSearchResponse;
import com.example.toiletapi.toilet.dto.ToiletMapResponse;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 화장실 조회 비즈니스 로직을 제공합니다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToiletService {

    private static final int MIN_KAKAO_MAP_LEVEL = 1;
    private static final int MAX_KAKAO_MAP_LEVEL = 14;
    private static final int CLUSTER_MIN_ZOOM_LEVEL = 10;

    private final ToiletRepository toiletRepository;

    /**
     * 지도 화면의 경계 안에 있는 화장실 마커 정보를 조회합니다.
     *
     * @param southLat 최남단 위도
     * @param northLat 최북단 위도
     * @param westLng 최서단 경도
     * @param eastLng 최동단 경도
     * @return 지도 영역 안의 화장실 목록
     */
    public ToiletMapSearchResponse getToiletsInBounds(
            BigDecimal southLat,
            BigDecimal northLat,
            BigDecimal westLng,
            BigDecimal eastLng,
            Integer zoom
    ) {
        int mapLevel = normalizeMapLevel(zoom);
        validateBounds(southLat, northLat, westLng, eastLng, mapLevel);

        if (mapLevel >= CLUSTER_MIN_ZOOM_LEVEL) {
            List<ToiletClusterResponse> clusters = toiletRepository.findClustersByBounds(
                            southLat,
                            northLat,
                            westLng,
                            eastLng,
                            resolveGridSize(mapLevel)
                    )
                    .stream()
                    .map(ToiletClusterResponse::from)
                    .toList();

            return ToiletMapSearchResponse.clusters(mapLevel, clusters);
        }

        List<ToiletMapResponse> markers = toiletRepository.findByLatitudeBetweenAndLongitudeBetween(
                        southLat, northLat, westLng, eastLng
                )
                .stream()
                .map(ToiletMapResponse::from)
                .toList();

        return ToiletMapSearchResponse.markers(mapLevel, markers);
    }

    private void validateBounds(
            BigDecimal southLat,
            BigDecimal northLat,
            BigDecimal westLng,
            BigDecimal eastLng,
            int zoom
    ) {
        if (southLat.compareTo(northLat) > 0 || westLng.compareTo(eastLng) > 0) {
            throw new IllegalArgumentException("지도 영역의 남서쪽 좌표는 북동쪽 좌표보다 작아야 합니다.");
        }

        if (zoom < MIN_KAKAO_MAP_LEVEL || zoom > MAX_KAKAO_MAP_LEVEL) {
            throw new IllegalArgumentException("카카오맵 레벨은 1부터 14 사이여야 합니다.");
        }
    }

    private int normalizeMapLevel(Integer zoom) {
        return zoom == null ? MIN_KAKAO_MAP_LEVEL : zoom;
    }

    private BigDecimal resolveGridSize(int zoom) {
        return switch (zoom) {
            case 10 -> new BigDecimal("0.01");
            case 11 -> new BigDecimal("0.02");
            case 12 -> new BigDecimal("0.05");
            case 13 -> new BigDecimal("0.10");
            default -> new BigDecimal("0.20");
        };
    }
}
