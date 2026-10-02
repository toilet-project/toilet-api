package com.example.toiletapi.report;

import com.example.toiletapi.auth.config.*;
import com.example.toiletapi.auth.support.NativeMySqlFixture;
import com.example.toiletapi.report.controller.ToiletReportController;
import com.example.toiletapi.report.service.ToiletReportService;
import com.example.toiletapi.auth.service.AuditLogService;
import com.example.toiletapi.geocoding.CoordinateAddressResolver;
import com.example.toiletapi.notification.service.UserNotificationService;
import com.example.toiletapi.policy.service.PolicyConsentService;
import com.example.toiletapi.toilet.openinghours.*;
import com.example.toiletapi.toilet.translation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.*;

/** Isolated MySQL + actual report controller/service/repository/security, never included in bootJar.
 * Public source snapshots are imported through an ADMIN-only fixture route. No production DB/config/token.
 * OAuth is not simulated as a real login: member tests use explicitly synthetic identities.
 */
public class ReportPreviewServer {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !NativeMySqlFixture.enabled()) throw new IllegalArgumentException("Isolated fixture required");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        if (!output.getParent().getFileName().toString().equals("account-retention-mysql-" + System.getenv("ACCOUNT_RETENTION_MYSQL_MARKER")))
            throw new IllegalArgumentException("Invalid fixture metadata path");
        var app = new SpringApplication(Config.class);
        var context = app.run("--spring.config.location=optional:classpath:report-preview-only.properties", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.flyway.enabled=false", "--spring.data.redis.repositories.enabled=false", "--spring.jpa.open-in-view=false",
                "--reports.quick-enabled=true", "--kakao.api.key=preview-no-provider-secret", "--logging.level.root=WARN");
        var expires = Instant.now().plus(Duration.ofHours(2));
        var encoder = context.getBean(JwtEncoder.class);
        Map<String, String> tokens = new LinkedHashMap<>();
        for (int id = 1; id <= 3; id++) {
            var claims = JwtClaimsSet.builder().subject(String.valueOf(id)).issuedAt(Instant.now()).expiresAt(expires)
                    .claim("auth_version", 0).claim("roles", id == 3 ? List.of("ADMIN", "USER") : List.of("USER")).build();
            tokens.put(String.valueOf(id), encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue());
        }
        var metadata = Map.of("port", ((WebServerApplicationContext) context).getWebServer().getPort(), "tokens", tokens,
                "expiresAt", expires.toString(), "marker", System.getenv("ACCOUNT_RETENTION_MYSQL_MARKER"),
                "jdbcUrl", ((org.springframework.jdbc.datasource.DriverManagerDataSource) context.getBean(DataSource.class)).getUrl());
        Files.writeString(output, new ObjectMapper().writeValueAsString(metadata), StandardOpenOption.CREATE_NEW);
        Thread.ofPlatform().daemon(true).start(() -> { try { Thread.sleep(Duration.ofHours(2)); context.close(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
        System.out.println("REPORT_PREVIEW_READY isolated=true publicSource=true lifetime=2h");
    }

    @org.springframework.boot.test.context.TestConfiguration @EnableAutoConfiguration @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = {"com.example.toiletapi.auth.repository", "com.example.toiletapi.policy.repository", "com.example.toiletapi.report.repository", "com.example.toiletapi.toilet.repository", "com.example.toiletapi.notification.repository"})
    @Import({ToiletReportController.class, ToiletReportService.class, QuickReportBoundaryFilter.class, SecurityConfig.class, JwtConfig.class,
            com.example.toiletapi.global.config.CorsConfig.class, com.example.toiletapi.global.exception.GlobalExceptionHandler.class,
            PolicyConsentService.class, AuditLogService.class, UserNotificationService.class, CoordinateAddressResolver.class,
            ToiletTranslationService.class, ToiletTranslationRepository.class, OpeningHoursService.class, OpeningHoursRepository.class, OpeningHoursParser.class, SourceImport.class})
    static class Config {
        @Bean DataSource dataSource() {
            var source = NativeMySqlFixture.create();
            new ResourceDatabasePopulator(new ClassPathResource("report-preview-toilet.sql")).execute(source);
            for (String file : List.of("V1__create_auth_data_model.sql", "V2__create_toilet_report_and_coordinate_revision.sql", "V4__create_user_notification.sql",
                    "V5__create_coordinate_quality_review.sql", "V7__create_policy_consent_model.sql", "V9__separate_coordinate_report_addresses.sql",
                    "V11__account_withdrawal_retention.sql", "V34__prepare_email_encryption.sql", "V26__create_toilet_translation.sql",
                    "V27__normalize_toilet_opening_hours.sql", "V29__track_translation_address_status.sql", "V37__add_quick_toilet_reports.sql"))
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + file)).execute(source);
            var jdbc = new JdbcTemplate(source);
            for (int id = 1; id <= 3; id++) {
                jdbc.update("INSERT INTO app_user(user_id,status,display_name) VALUES(?,'ACTIVE',?)", id, "격리 시험 계정 " + id);
                jdbc.update("INSERT INTO user_policy_consent(user_id,policy_document_id,consent_source) SELECT ?,policy_document_id,'WEB_OAUTH_ONBOARDING' FROM policy_document WHERE required=true", id);
            }
            return source;
        }
        @Bean ObjectMapper mapper() { return new ObjectMapper(); }
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
            factory.setPackagesToScan("com.example.toiletapi.auth.model", "com.example.toiletapi.policy.model", "com.example.toiletapi.report.model", "com.example.toiletapi.toilet.model", "com.example.toiletapi.notification.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter()); factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); return factory;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory emf) { return new JpaTransactionManager(emf); }
        @Bean AuthTokenProperties authTokenProperties() {
            byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
            return new AuthTokenProperties(Base64.getEncoder().encodeToString(bytes), Duration.ofHours(2), Duration.ofHours(2), Duration.ofHours(2));
        }
        @Bean ClientRegistrationRepository clientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("fixture").clientId("test-only").clientSecret("unused")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("http://127.0.0.1/unused").authorizationUri("http://127.0.0.1/unused")
                    .tokenUri("http://127.0.0.1/unused").userInfoUri("http://127.0.0.1/unused").userNameAttributeName("sub").build());
        }
        @Bean OAuthLoginSuccessHandler oauthLoginSuccessHandler() {
            return new OAuthLoginSuccessHandler(null, null, "http://127.0.0.1", null, null, null) {
                @Override public void onAuthenticationSuccess(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
                        org.springframework.security.core.Authentication authentication) throws java.io.IOException { response.sendError(403); }
            };
        }
    }
    @org.springframework.boot.test.context.TestComponent @RestController
    static class SourceImport {
        private final JdbcTemplate jdbc;
        SourceImport(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @PostMapping("/api/admin/preview/source") Map<String, Object> seed(@RequestBody Map<String, Object> source) {
            long id = ((Number) source.get("id")).longValue();
            if (id <= 0 || id > 1_000_000 || !(source.get("name") instanceof String)) throw new IllegalArgumentException();
            // Import once: later test decisions must not be silently overwritten by a source refresh.
            jdbc.update("INSERT IGNORE INTO toilet(toilet_id,name,latitude,longitude,road_address,jibun_address,open_time,open_time_detail,toilet_type,data_source) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, source.get("name"), source.get("latitude"), source.get("longitude"), source.get("roadAddress"), source.get("jibunAddress"), source.get("openTime"), source.get("openTimeDetail"), source.get("toiletType"), "PUBLIC_SNAPSHOT");
            return Map.of("id", id);
        }
    }
}
