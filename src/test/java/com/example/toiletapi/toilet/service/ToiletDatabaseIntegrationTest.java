package com.example.toiletapi.toilet.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.toiletapi.toilet.dto.ToiletMapSearchResponse;
import com.example.toiletapi.toilet.model.Toilet;
import com.example.toiletapi.toilet.repository.ToiletRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 실제 읽기 전용 DB 연결로 지도 영역 조회를 검증합니다.
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false"
})
@EnabledIfEnvironmentVariable(named = "SPRING_DB_URL", matches = ".+")
class ToiletDatabaseIntegrationTest {

    private static final BigDecimal BOUNDARY_OFFSET = new BigDecimal("0.0001");

    @Autowired
    private ToiletRepository toiletRepository;

    @Autowired
    private ToiletService toiletService;

    @Test
    void shouldReturnExistingToiletWithinActualDatabaseBounds() {
        Toilet existingToilet = toiletRepository.findFirstByLatitudeIsNotNullAndLongitudeIsNotNull()
                .orElseThrow(() -> new IllegalStateException("좌표가 등록된 화장실 데이터가 없습니다."));

        ToiletMapSearchResponse response = toiletService.getToiletsInBounds(
                existingToilet.getLatitude().subtract(BOUNDARY_OFFSET),
                existingToilet.getLatitude().add(BOUNDARY_OFFSET),
                existingToilet.getLongitude().subtract(BOUNDARY_OFFSET),
                existingToilet.getLongitude().add(BOUNDARY_OFFSET),
                1
        );

        assertFalse(response.toilets().isEmpty());
        assertTrue(response.toilets().stream()
                .anyMatch(marker -> marker.id().equals(existingToilet.getId())));
    }

    @Test
    void shouldReturnClustersForWideMapLevel() {
        Toilet existingToilet = toiletRepository.findFirstByLatitudeIsNotNullAndLongitudeIsNotNull()
                .orElseThrow(() -> new IllegalStateException("좌표가 등록된 화장실 데이터가 없습니다."));

        ToiletMapSearchResponse response = toiletService.getToiletsInBounds(
                existingToilet.getLatitude().subtract(BOUNDARY_OFFSET),
                existingToilet.getLatitude().add(BOUNDARY_OFFSET),
                existingToilet.getLongitude().subtract(BOUNDARY_OFFSET),
                existingToilet.getLongitude().add(BOUNDARY_OFFSET),
                10
        );

        assertNull(response.toilets());
        assertFalse(response.clusters().isEmpty());
        assertTrue(response.clusters().stream().allMatch(cluster -> cluster.count() > 0));
    }
}
