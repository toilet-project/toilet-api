package com.example.toiletapi.report;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddress;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.notification.service.UserNotificationService;
import com.example.toiletapi.report.dto.QuickToiletReportRequest;
import com.example.toiletapi.report.dto.ReviewToiletReportRequest;
import com.example.toiletapi.report.model.NewFacilityInfo;
import com.example.toiletapi.report.service.ToiletReportService;
import com.example.toiletapi.toilet.openinghours.*;
import com.example.toiletapi.toilet.translation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Production's legacy NOT NULL schema, real JPA/service transactions, synthetic data only. */
@Testcontainers(disabledWithoutDocker = true)
class NewFacilityCountMigrationMySqlTest {
    private static final List<String> COUNTS = List.of("male_toilet_count", "male_urinal_count",
            "male_disabled_toilet_count", "male_disabled_urinal_count", "male_child_toilet_count",
            "male_child_urinal_count", "female_toilet_count", "female_disabled_toilet_count", "female_child_toilet_count");
    @Container final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    private DataSource source;
    private JdbcTemplate jdbc;
    private AnnotationConfigApplicationContext context;

    @BeforeEach void setup() {
        String schema = "new_facility_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()));
        admin.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        setup(new DriverManagerDataSource(mysql.getJdbcUrl().replace("/" + mysql.getDatabaseName(), "/" + schema),
                "root", mysql.getPassword()));
    }

    void setup(DataSource source) {
        this.source = ReportPreviewServer.initializeSchema(source);
        jdbc = new JdbcTemplate(source);
        // Start every regression from the pre-fix production constraint, never Hibernate-generated DDL.
        for (String column : COUNTS) {
            String comment = jdbc.queryForObject("SELECT column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='toilet' AND column_name=?", String.class, column);
            jdbc.execute("ALTER TABLE toilet MODIFY COLUMN " + column + " INT NOT NULL DEFAULT 0 COMMENT '" + comment + "'");
        }
    }

    @AfterEach void close() { if (context != null) context.close(); }

    private void migrate() {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V43__allow_unknown_toilet_counts.sql")).execute(source);
    }

    @Test void migrationPreservesExistingCountsAndOmittedColumnDefaults() {
        jdbc.update("INSERT INTO toilet(name) VALUES('기존 기본값')");
        jdbc.update("INSERT INTO toilet(name," + String.join(",", COUNTS) + ") VALUES('기존 확인값',1,2,3,4,5,6,7,8,9)");
        var before = jdbc.queryForList("SELECT * FROM toilet ORDER BY toilet_id");
        var comments = jdbc.queryForList("SELECT column_name,column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='toilet' ORDER BY ordinal_position");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("INSERT INTO toilet(name,female_child_toilet_count) VALUES('미확인',NULL)"));

        migrate();

        assertEquals(before, jdbc.queryForList("SELECT * FROM toilet ORDER BY toilet_id"));
        assertEquals(comments, jdbc.queryForList("SELECT column_name,column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='toilet' ORDER BY ordinal_position"));
        for (String column : COUNTS) {
            assertEquals("YES", jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='toilet' AND column_name=?", String.class, column));
            assertEquals("0", jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='toilet' AND column_name=?", String.class, column));
        }
        jdbc.update("INSERT INTO toilet(name) VALUES('기존 수집 방식')");
        jdbc.update("INSERT INTO toilet(name," + String.join(",", COUNTS) + ") VALUES('미확인',NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL)");
        for (String column : COUNTS) {
            assertEquals(0, jdbc.queryForObject("SELECT " + column + " FROM toilet WHERE name='기존 수집 방식'", Integer.class));
            assertNull(jdbc.queryForObject("SELECT " + column + " FROM toilet WHERE name='미확인'", Integer.class));
        }
    }

    @Test void failedLegacyApprovalRollsBackAndTheSamePendingReportCanBeApprovedAfterMigration() {
        var service = services();
        var report = service.submitQuick(1L, null, UUID.randomUUID().toString(), proposal(null));

        assertThrows(DataIntegrityViolationException.class, () -> service.approve(3L, report.id(), null));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM toilet_report WHERE report_id=?", String.class, report.id()));
        assertNull(jdbc.queryForObject("SELECT toilet_id FROM toilet_report WHERE report_id=?", Long.class, report.id()));
        for (String table : List.of("toilet", "toilet_translation", "toilet_opening_hours", "audit_log", "user_notification"))
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));

        migrate();
        var approved = service.approve(3L, report.id(), null);

        assertEquals("APPROVED", approved.status());
        assertNotNull(approved.toiletId());
        for (String column : COUNTS)
            assertNull(jdbc.queryForObject("SELECT " + column + " FROM toilet WHERE toilet_id=?", Integer.class, approved.toiletId()));
        assertNull(jdbc.queryForObject("SELECT proposed_facility_info FROM toilet_report WHERE report_id=?", String.class, report.id()));
        assertEquals("격리 신규 화장실", jdbc.queryForObject("SELECT name FROM toilet_translation WHERE toilet_id=? AND locale='ko'", String.class, approved.toiletId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM toilet_opening_hours WHERE toilet_id=?", Integer.class, approved.toiletId()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='REPORT_APPROVED' AND target_id=?", Integer.class, report.id()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_notification WHERE user_id=1 AND reference_id=?", Integer.class, report.id()));
        assertThrows(IllegalArgumentException.class, () -> service.approve(3L, report.id(), null));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM toilet", Integer.class));
    }

    @Test void confirmedZeroAndPositiveCountsStayDistinctFromUnknownAndTheProposalIsPreserved() {
        migrate();
        var service = services();
        var original = NewFacilityInfo.legacy("격리 신규 화장실", "24시간");
        var report = service.submitQuick(1L, null, UUID.randomUUID().toString(), proposal(original));
        String evidence = jdbc.queryForObject("SELECT proposed_facility_info FROM toilet_report WHERE report_id=?", String.class, report.id());
        var confirmed = new NewFacilityInfo("관리자 확정 화장실", "개방", "09:00~18:00", "주말 휴무",
                null, null, null, false, true, 2, 0);

        var approved = service.approve(3L, report.id(), new ReviewToiletReportRequest(null, null, null, null, confirmed));

        assertEquals("APPROVED", approved.status());
        for (String column : COUNTS) {
            Integer value = jdbc.queryForObject("SELECT " + column + " FROM toilet WHERE toilet_id=?", Integer.class, approved.toiletId());
            if (column.equals("male_disabled_toilet_count")) assertEquals(2, value);
            else if (column.equals("female_disabled_toilet_count")) assertEquals(0, value);
            else assertNull(value);
        }
        assertEquals(evidence, jdbc.queryForObject("SELECT proposed_facility_info FROM toilet_report WHERE report_id=?", String.class, report.id()));
        assertEquals("관리자 확정 화장실", jdbc.queryForObject("SELECT name FROM toilet WHERE toilet_id=?", String.class, approved.toiletId()));
        assertNull(jdbc.queryForObject("SELECT has_emergency_bell FROM toilet WHERE toilet_id=?", String.class, approved.toiletId()));
        assertEquals("N", jdbc.queryForObject("SELECT has_cctv FROM toilet WHERE toilet_id=?", String.class, approved.toiletId()));
        assertEquals("Y", jdbc.queryForObject("SELECT has_diaper_table FROM toilet WHERE toilet_id=?", String.class, approved.toiletId()));
    }

    private QuickToiletReportRequest proposal(NewFacilityInfo info) {
        return new QuickToiletReportRequest(null, "NEW_FACILITY", new BigDecimal("36.3627524"),
                new BigDecimal("127.3556413"), "격리 시험 주소", "격리 신규 화장실", "격리 회귀 검증", info);
    }

    private ToiletReportService services() {
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> source);
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(source));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(CoordinateAddressResolver.class, () -> {
            var resolver = mock(CoordinateAddressResolver.class);
            when(resolver.resolve(any(), any())).thenAnswer(call -> new CoordinateAddress(call.getArgument(0), call.getArgument(1), "격리 시험 주소", null));
            return resolver;
        });
        context.register(PersistenceConfig.class);
        context.refresh();
        return context.getBean(ToiletReportService.class);
    }

    @Configuration @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {"com.example.toiletapi.auth.repository", "com.example.toiletapi.report.repository",
            "com.example.toiletapi.toilet.repository", "com.example.toiletapi.notification.repository"})
    @Import({ToiletReportService.class, AuditLogService.class, UserNotificationService.class,
            ToiletTranslationService.class, ToiletTranslationRepository.class,
            OpeningHoursService.class, OpeningHoursRepository.class, OpeningHoursParser.class})
    static class PersistenceConfig {
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            return new ReportPreviewServer.Config().entityManagerFactory(source);
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
