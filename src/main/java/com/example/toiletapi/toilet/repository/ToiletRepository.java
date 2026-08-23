package com.example.toiletapi.toilet.repository;

import com.example.toiletapi.toilet.model.Toilet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 지정한 지도 영역의 화장실을 격자 단위로 집계합니다.
     *
     * @param southLat 최남단 위도
     * @param northLat 최북단 위도
     * @param westLng 최서단 경도
     * @param eastLng 최동단 경도
     * @param gridSize 클러스터 격자 크기
     * @return 지도 영역의 클러스터 목록
     */
    @Query(value = """
            SELECT AVG(latitude) AS latitude,
                   AVG(longitude) AS longitude,
                   COUNT(*) AS toiletCount
            FROM toilet
            WHERE latitude BETWEEN :southLat AND :northLat
              AND longitude BETWEEN :westLng AND :eastLng
            GROUP BY FLOOR(latitude / :gridSize), FLOOR(longitude / :gridSize)
            """, nativeQuery = true)
    List<ToiletClusterProjection> findClustersByBounds(
            @Param("southLat") BigDecimal southLat,
            @Param("northLat") BigDecimal northLat,
            @Param("westLng") BigDecimal westLng,
            @Param("eastLng") BigDecimal eastLng,
            @Param("gridSize") BigDecimal gridSize
    );
}
