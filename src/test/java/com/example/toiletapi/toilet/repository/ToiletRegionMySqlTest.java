package com.example.toiletapi.toilet.repository;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** Exercise the shipped V8 view and actual Spring Data native projection, not a mock. */
@Testcontainers
class ToiletRegionMySqlTest {
    @Container static MySQLContainer mysql = new MySQLContainer("mysql:8.0");
    static JdbcTemplate jdbc;
    static LocalContainerEntityManagerFactoryBean factory;
    static EntityManager entityManager;
    static ToiletRepository repository;

    @BeforeAll static void schema() throws Exception {
        var dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE toilet (toilet_id BIGINT PRIMARY KEY, latitude DECIMAL(10,7), longitude DECIMAL(10,7), road_address VARCHAR(255), jibun_address VARCHAR(255))");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V8__create_toilet_region.sql"));
        }
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
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

    @BeforeEach void fixture() {
        jdbc.update("DELETE FROM toilet_region");
        jdbc.update("DELETE FROM toilet");
        jdbc.update("INSERT INTO toilet VALUES (1,36.8,127.1,'Road A',NULL)");
        jdbc.update("""
                INSERT INTO toilet_region (toilet_id,sido_name,sido_code,sigungu_name,sigungu_code,
                    city_name,district_name,region_source,status,reason,source_hash,
                    source_latitude,source_longitude,source_road_address,source_jibun_address,
                    evaluated_latitude,evaluated_longitude,result_json,checked_at)
                VALUES (1,'충청남도','44','천안시 서북구','44133','천안시','서북구',
                    'KAKAO','VERIFIED','MATCH',REPEAT('a',64),36.8,127.1,'Road A',NULL,36.8,127.1,'{}',NOW())
                """);
    }

    @Test void nativeProjectionPreservesCityDistrictAndCodes() {
        var region = repository.findCurrentRegion(1L).orElseThrow();
        assertEquals("충청남도", region.getSidoName());
        assertEquals("44", region.getSidoCode());
        assertEquals("천안시 서북구", region.getSigunguName());
        assertEquals("44133", region.getSigunguCode());
        assertEquals("천안시", region.getCityName());
        assertEquals("서북구", region.getDistrictName());
        assertTrue(repository.findCurrentRegion(2L).isEmpty());
    }

    @Test void staleCoordinateOrAddressNeverLeaksAsVerified() {
        for (var update : new String[]{
                "UPDATE toilet SET latitude=36.9",
                "UPDATE toilet SET longitude=127.2",
                "UPDATE toilet SET latitude=NULL",
                "UPDATE toilet SET road_address='road a'",
                "UPDATE toilet SET road_address=NULL",
                "UPDATE toilet SET jibun_address='new address'",
                "UPDATE toilet_region SET evaluated_latitude=36.9",
                "UPDATE toilet_region SET evaluated_longitude=127.2"}) {
            fixture();
            jdbc.update(update);
            assertTrue(repository.findCurrentRegion(1L).isEmpty(), update);
        }
    }

    @Test void unverifiedStatusesNeverLeak() {
        for (var status : new String[]{"MISMATCH","ADDRESS_UNVERIFIED","REVERSE_FAILED","NO_COORDINATE"}) {
            jdbc.update("UPDATE toilet_region SET status=?", status);
            assertTrue(repository.findCurrentRegion(1L).isEmpty(), status);
        }
    }

    @Test void nullAddressAndSejongHierarchyAreNotInvented() {
        jdbc.update("UPDATE toilet SET road_address=NULL");
        jdbc.update("UPDATE toilet_region SET source_road_address=NULL,sido_name='세종특별자치시',sido_code='36',sigungu_name=NULL,sigungu_code=NULL,city_name=NULL,district_name=NULL");
        var region = repository.findCurrentRegion(1L).orElseThrow();
        assertEquals("세종특별자치시", region.getSidoName());
        assertEquals("36", region.getSidoCode());
        assertNull(region.getCityName());
        assertNull(region.getDistrictName());
        assertNull(region.getSigunguName());
    }
}
