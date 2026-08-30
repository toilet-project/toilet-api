package com.example.toiletapi.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.model.SocialProvider;
import com.example.toiletapi.auth.model.UserSocialAccount;
import com.example.toiletapi.auth.service.UserRolePolicyService;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 MySQL과 Flyway V1 migration을 사용해 인증 데이터 모델을 검증합니다. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "server.port=0",
        "kakao.api.key=test-key",
        "spring.security.oauth2.client.registration.google.client-id=test-google-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
        "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret",
        "auth.jwt.secret=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "auth.admin-bootstrap.emails=admin@geupddong.com"
})
class AuthDataModelIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.40");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private UserSocialAccountRepository socialAccountRepository;

    @Autowired
    private UserRolePolicyService userRolePolicyService;

    @Test
    void migratesAuthTablesAndPersistsSocialIdentityAndBootstrapRoles() {
        AppUser user = userRepository.saveAndFlush(AppUser.create("운영자", "admin@geupddong.com", true));
        socialAccountRepository.saveAndFlush(UserSocialAccount.link(
                user,
                SocialProvider.GOOGLE,
                "a".repeat(64),
                "admin@geupddong.com"
        ));

        Set<Role> roles = userRolePolicyService.ensureInitialRoles(user);

        assertThat(socialAccountRepository
                .findByProviderAndProviderSubjectHash(SocialProvider.GOOGLE, "a".repeat(64)))
                .isPresent();
        assertThat(roles).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    }
}
