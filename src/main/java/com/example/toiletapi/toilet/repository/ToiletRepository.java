package com.example.toiletapi.toilet.repository;

import com.example.toiletapi.toilet.model.Toilet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 화장실 데이터 조회를 담당합니다.
 */
public interface ToiletRepository extends JpaRepository<Toilet, Long> {

    /**
     * 지정한 위도·경도 사각형 안에 있는 화장실을 조회합니다.
     *
     * @param southLat 최남단 위도
     * @param northLat 최북단 위도
     * @param westLng 최서단 경도
     * @param eastLng 최동단 경도
     * @return 지도 영역 안의 화장실 목록
     */
    List<Toilet> findByLatitudeBetweenAndLongitudeBetween(
            BigDecimal southLat,
            BigDecimal northLat,
            BigDecimal westLng,
            BigDecimal eastLng
    );

    /**
     * 좌표가 등록된 화장실 한 건을 조회합니다.
     *
     * @return 좌표가 있는 화장실
     */
    Optional<Toilet> findFirstByLatitudeIsNotNullAndLongitudeIsNotNull();
}
