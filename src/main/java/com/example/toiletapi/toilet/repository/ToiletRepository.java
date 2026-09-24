package com.example.toiletapi.toilet.repository;

import com.example.toiletapi.toilet.model.Toilet;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

/**
 * 화장실 데이터 조회를 담당합니다.
 */
public interface ToiletRepository extends JpaRepository<Toilet, Long> {

    // The view excludes unverified results and results whose source coordinates/addresses have changed.
    @Query(value = """
            SELECT sido_name AS sidoName, sido_code AS sidoCode,
                   sigungu_name AS sigunguName, sigungu_code AS sigunguCode,
                   city_name AS cityName, district_name AS districtName
            FROM current_toilet_region
            WHERE toilet_id = :toiletId
            """, nativeQuery = true)
    Optional<ToiletRegionProjection> findCurrentRegion(@Param("toiletId") Long toiletId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select toilet from Toilet toilet where toilet.id = :id")
    Optional<Toilet> findByIdForUpdate(@Param("id") Long id);

    /**
     * 지정한 위도·경도 사각형 안에 있는 화장실을 조회합니다.
     *
     * @param southLat 최남단 위도
     * @param northLat 최북단 위도
     * @param westLng 최서단 경도
     * @param eastLng 최동단 경도
     * @return 지도 영역 안의 화장실 목록
     */
    @Query("select t from Toilet t where t.visibilityStatus='VISIBLE' and t.latitude between :southLat and :northLat and t.longitude between :westLng and :eastLng")
    List<Toilet> findByLatitudeBetweenAndLongitudeBetween(
            BigDecimal southLat,
            BigDecimal northLat,
            BigDecimal westLng,
            BigDecimal eastLng
    );

    @Query("""
            select t.id as id, t.name as name, t.toiletType as toiletType,
                   t.latitude as latitude, t.longitude as longitude
            from Toilet t
            where t.visibilityStatus='VISIBLE'
              and t.latitude between :southLat and :northLat
              and t.longitude between :westLng and :eastLng
            """)
    List<ToiletMarkerProjection> findMarkerRowsByBounds(
            @Param("southLat") BigDecimal southLat,
            @Param("northLat") BigDecimal northLat,
            @Param("westLng") BigDecimal westLng,
            @Param("eastLng") BigDecimal eastLng
    );

    @Query(value = """
            SELECT t.* FROM toilet t
            JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id
            WHERE t.visibility_status='VISIBLE'
              AND oh.is_open_24h=TRUE
              AND oh.normalization_status IN ('PARSED','CONFIRMED')
              AND oh.source_changed=FALSE
              AND t.latitude BETWEEN :southLat AND :northLat
              AND t.longitude BETWEEN :westLng AND :eastLng
            """, nativeQuery = true)
    List<Toilet> findOpen24hByBounds(
            @Param("southLat") BigDecimal southLat,
            @Param("northLat") BigDecimal northLat,
            @Param("westLng") BigDecimal westLng,
            @Param("eastLng") BigDecimal eastLng
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
            WHERE visibility_status='VISIBLE' AND latitude BETWEEN :southLat AND :northLat
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

    @Query(value = """
            SELECT AVG(t.latitude) AS latitude,
                   AVG(t.longitude) AS longitude,
                   COUNT(*) AS toiletCount
            FROM toilet t
            JOIN toilet_opening_hours oh ON oh.toilet_id=t.toilet_id
            WHERE t.visibility_status='VISIBLE'
              AND oh.is_open_24h=TRUE
              AND oh.normalization_status IN ('PARSED','CONFIRMED')
              AND oh.source_changed=FALSE
              AND t.latitude BETWEEN :southLat AND :northLat
              AND t.longitude BETWEEN :westLng AND :eastLng
            GROUP BY FLOOR(t.latitude / :gridSize), FLOOR(t.longitude / :gridSize)
            """, nativeQuery = true)
    List<ToiletClusterProjection> findOpen24hClustersByBounds(
            @Param("southLat") BigDecimal southLat,
            @Param("northLat") BigDecimal northLat,
            @Param("westLng") BigDecimal westLng,
            @Param("eastLng") BigDecimal eastLng,
            @Param("gridSize") BigDecimal gridSize
    );
}
