package com.example.toiletapi.toilet.repository;

import static org.junit.jupiter.api.Assertions.*;
import com.example.toiletapi.auth.support.NativeMySqlFixture;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import jakarta.persistence.EntityManager;

/** Executes the production native query strings on a guarded, newly created synthetic MySQL schema. */
@EnabledIfEnvironmentVariable(named = "ACCOUNT_RETENTION_MYSQL_MARKER", matches = "[a-f0-9]{10}")
class MapFilterNativeMySqlTest {
    static JdbcTemplate jdbc;
    static NamedParameterJdbcTemplate named;
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager entityManager;
    static ToiletRepository repository;

    @BeforeAll static void schema() {
        var source = NativeMySqlFixture.create();
        jdbc = new JdbcTemplate(source);
        named = new NamedParameterJdbcTemplate(jdbc);
        jdbc.execute("""
                CREATE TABLE toilet(toilet_id BIGINT PRIMARY KEY,name VARCHAR(255),toilet_type VARCHAR(80),
                visibility_status VARCHAR(24),latitude DECIMAL(10,7),longitude DECIMAL(10,7),
                has_cctv VARCHAR(10),has_diaper_table VARCHAR(10),has_emergency_bell VARCHAR(10))
                """);
        jdbc.execute("""
                CREATE TABLE toilet_opening_hours(toilet_id BIGINT PRIMARY KEY,is_open_24h BOOLEAN NULL,
                normalization_status VARCHAR(24),source_changed BOOLEAN NULL)
                """);
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setPackagesToScan("com.example.toiletapi.toilet.model");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        entityManager = factory.getObject().createEntityManager();
        repository = new JpaRepositoryFactory(entityManager).getRepository(ToiletRepository.class);
    }

    @AfterAll static void close() {
        if (entityManager != null) entityManager.close();
        if (factory != null) factory.destroy();
    }

    @BeforeEach void data() {
        jdbc.update("DELETE FROM toilet_opening_hours");
        jdbc.update("DELETE FROM toilet");
        for (long id = 1; id <= 9; id++) {
            jdbc.update("INSERT INTO toilet VALUES(?,?,'공중화장실',?,37.51,127.01,?,?,?)", id, "시설 " + id,
                    id == 7 ? "HIDDEN_DUPLICATE" : "VISIBLE", id == 3 ? null : "Y", "Y", "Y");
        }
        jdbc.update("UPDATE toilet SET has_cctv='y',has_diaper_table='Y ',has_emergency_bell='미확인' WHERE toilet_id=4");
        jdbc.update("UPDATE toilet SET has_cctv='N',has_diaper_table=NULL,has_emergency_bell='' WHERE toilet_id=5");
        jdbc.update("UPDATE toilet SET latitude=NULL WHERE toilet_id=8");
        jdbc.update("UPDATE toilet SET latitude=45 WHERE toilet_id=9");
        jdbc.update("INSERT INTO toilet_opening_hours VALUES(1,TRUE,'PARSED',FALSE),(2,TRUE,'CONFIRMED',FALSE),"
                + "(3,TRUE,'PARSED',TRUE),(4,TRUE,'NEEDS_REVIEW',FALSE),(5,NULL,'PARSED',FALSE),"
                + "(7,TRUE,'CONFIRMED',FALSE),(8,TRUE,'CONFIRMED',FALSE),(9,TRUE,'CONFIRMED',FALSE)");
    }

    private static String query(String method) {
        return java.util.Arrays.stream(ToiletRepository.class.getMethods())
                .filter(candidate -> candidate.getName().equals(method)).findFirst().orElseThrow()
                .getAnnotation(Query.class).value();
    }

    private static Map<String, Object> bounds(int flags) {
        return Map.of("southLat", new BigDecimal("37.50"), "northLat", new BigDecimal("37.55"),
                "westLng", new BigDecimal("127.00"), "eastLng", new BigDecimal("127.05"),
                "filterFlags", flags, "gridSize", new BigDecimal("0.01"));
    }

    @Test void compactSourceUsesConservativeBitsAndOnlyVisibleKoreanCoordinates() {
        var rows = jdbc.queryForList(query("findPublicFilterPoints"));
        assertEquals(List.of(1L,2L,3L,4L,5L,6L), rows.stream().map(row -> ((Number) row.get("id")).longValue()).toList());
        assertEquals(List.of(15,15,12,0,0,14), rows.stream().map(row -> ((Number) row.get("filterFlags")).intValue()).toList());
        assertEquals(4, rows.getFirst().size());
    }

    @Test void realSpringDataNativeProjectionsHydrateFlagsAndCoordinates() {
        var points = repository.findPublicFilterPoints();
        assertEquals(15, points.getFirst().getFilterFlags());
        assertEquals(1L, points.getFirst().getId());
        assertEquals(0, points.getFirst().getLatitude().compareTo(new BigDecimal("37.51")));
        assertEquals(6, points.size());
        var rows = repository.findMarkerRowsByBounds(new BigDecimal("37.50"), new BigDecimal("37.55"),
                new BigDecimal("127.00"), new BigDecimal("127.05"));
        assertEquals(15, rows.stream().filter(row -> row.getId() == 1L).findFirst().orElseThrow().getFilterFlags());
        var flags = repository.findFilterFlagsByIds(List.of(1L, 4L, 7L));
        assertEquals(2, flags.size());
        assertTrue(flags.stream().anyMatch(row -> row.getId() == 4L && row.getFilterFlags() == 0));
        var clusters = repository.findFilteredClustersByBounds(new BigDecimal("37.50"), new BigDecimal("37.55"),
                new BigDecimal("127.00"), new BigDecimal("127.05"), new BigDecimal("0.01"), 15);
        assertEquals(2, clusters.stream().mapToLong(ToiletClusterProjection::getToiletCount).sum());
    }

    @Test void allMasksUseAndAndClustersCountOnlyMatchingRows() {
        for (int mask = 0; mask <= 15; mask++) {
            var markerRows = named.queryForList(query("findMarkerRowsByBounds"), bounds(mask));
            int required = mask;
            var expectedIds = markerRows.stream().filter(row -> (((Number) row.get("filterFlags")).intValue() & required) == required)
                    .map(row -> ((Number) row.get("id")).longValue()).sorted().toList();
            var filtered = named.queryForList(query("findFilteredByBounds"), bounds(mask));
            assertEquals(expectedIds, filtered.stream().map(row -> ((Number) row.get("toilet_id")).longValue()).sorted().toList());
            int clusterCount = named.queryForList(query("findFilteredClustersByBounds"), bounds(mask)).stream()
                    .mapToInt(row -> ((Number) row.get("toiletCount")).intValue()).sum();
            assertEquals(expectedIds.size(), clusterCount, "mask " + mask);
        }
    }

    @Test void bulkFlagsMatchCompactCellsAndLegacyOpen24h() {
        var flags = named.queryForList(query("findFilterFlagsByIds"), Map.of("ids", List.of(1L,2L,3L,4L,7L)));
        assertEquals(List.of(1L,2L,3L,4L), flags.stream().map(row -> ((Number) row.get("id")).longValue()).sorted().toList());
        var legacy = named.queryForList(query("findOpen24hByBounds"), bounds(1));
        var filtered = named.queryForList(query("findFilteredByBounds"), bounds(1));
        assertEquals(legacy.stream().map(row -> row.get("toilet_id")).sorted().toList(),
                filtered.stream().map(row -> row.get("toilet_id")).sorted().toList());
        assertEquals(2, legacy.size());
        assertEquals(6, named.queryForList(query("findMarkerRowsByBounds"), bounds(0)).size());
        assertEquals(6, jdbc.queryForList(query("findPublicClusterPoints")).size());
    }
}
