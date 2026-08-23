package com.example.toiletapi.toilet.service;

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
    public List<ToiletMapResponse> getToiletsInBounds(
            BigDecimal southLat,
            BigDecimal northLat,
            BigDecimal westLng,
            BigDecimal eastLng
    ) {
        validateBounds(southLat, northLat, westLng, eastLng);

        return toiletRepository.findByLatitudeBetweenAndLongitudeBetween(
                        southLat, northLat, westLng, eastLng
                )
                .stream()
                .map(ToiletMapResponse::from)
                .toList();
    }

    private void validateBounds(
            BigDecimal southLat,
            BigDecimal northLat,
            BigDecimal westLng,
            BigDecimal eastLng
    ) {
        if (southLat.compareTo(northLat) > 0 || westLng.compareTo(eastLng) > 0) {
            throw new IllegalArgumentException("지도 영역의 남서쪽 좌표는 북동쪽 좌표보다 작아야 합니다.");
        }
    }
}
