package com.example.toiletapi.auth.controller;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.AuthTokenService;
import com.example.toiletapi.auth.service.RefreshTokenStore;
import com.example.toiletapi.auth.service.UserRolePolicyService;
import com.example.toiletapi.auth.service.AccountService;
import com.example.toiletapi.policy.service.PolicyConsentService;
import com.example.toiletapi.policy.dto.PolicyConsentStatusResponse;
import com.example.toiletapi.global.config.CorsConfig;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = {AuthController.class, OAuthLoginRedirectController.class}, properties = {
        "account.retention.enabled=true",
        "account.erasure.enabled=true",
        "account.lifecycle.maintenance=false",
        "spring.security.oauth2.client.registration.google.client-id=test-google-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
        "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({CorsConfig.class, SecurityConfig.class, com.example.toiletapi.auth.service.AccountLifecycleGate.class})
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RefreshTokenStore refreshTokenStore;
    @MockitoBean
    private AuthTokenService tokenService;
    @MockitoBean
    private AppUserRepository userRepository;
    @MockitoBean
    private UserRolePolicyService rolePolicyService;
    @MockitoBean
    private PolicyConsentService policyConsentService;
    @MockitoBean
    private AccountService accountService;
    @MockitoBean
    private com.example.toiletapi.photo.PhotoService photoService;
    @MockitoBean
    private com.example.toiletapi.auth.service.AccountErasureService erasureService;
    @MockitoBean
    private OAuthLoginSuccessHandler oauthLoginSuccessHandler;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void currentProfileIncludesPhotoStateForFirstPaint() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("profile-photo-test")
                .header("alg", "HS256").subject("7").claim("roles", java.util.List.of("USER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        var user = AppUser.create("사진 사용자", "photo@example.test", true);
        user.activateAfterConsent();
        when(jwtDecoder.decode("profile-photo-test")).thenReturn(jwt);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(policyConsentService.status(7L)).thenReturn(new com.example.toiletapi.policy.dto.PolicyConsentStatusResponse(
                false, java.util.List.of(), java.util.List.of()));
        when(photoService.state(7L)).thenReturn(new com.example.toiletapi.photo.PhotoService.State(
                true, true, "12345678-1234-1234-1234-123456789abc"));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer profile-photo-test"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.profilePhoto.available").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.profilePhoto.publicPhoto").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.profilePhoto.imageVersion")
                        .value("12345678-1234-1234-1234-123456789abc"));
        verify(photoService).state(7L);
    }

    @Test
    void pendingConsentProfileDoesNotReadPhotoState() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("pending-consent-test")
                .header("alg", "HS256").subject("8").claim("roles", java.util.List.of("USER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        var user = AppUser.create("동의 대기 사용자", "pending@example.test", true);
        when(jwtDecoder.decode("pending-consent-test")).thenReturn(jwt);
        when(userRepository.findById(8L)).thenReturn(Optional.of(user));
        when(policyConsentService.status(8L)).thenReturn(new com.example.toiletapi.policy.dto.PolicyConsentStatusResponse(
                true, java.util.List.of(), java.util.List.of()));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer pending-consent-test"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.profilePhoto").doesNotExist());
        verify(photoService, never()).state(8L);
    }

    @Test void withdrawalUsesAuthenticatedIdAndReturnsConfirmedDeadline() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("withdrawal-test")
                .header("alg", "HS256").subject("7").claim("roles", java.util.List.of("USER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(jwtDecoder.decode("withdrawal-test")).thenReturn(jwt);
        when(accountService.withdraw(7L, true, "recovery-2026-09-v1"))
                .thenReturn(new AccountService.WithdrawalReceipt(java.time.OffsetDateTime.parse("2026-12-06T18:00:00+09:00")));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/auth/me")
                .header("Authorization", "Bearer withdrawal-test").header("Origin", "https://geupddong.com")
                .contentType("application/json").content("{\"userId\":999,\"retainForRecovery\":true,\"consentVersion\":\"recovery-2026-09-v1\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.purgeAfter").value("2026-12-06T18:00:00+09:00"))
                .andExpect(cookie().maxAge("geupddong_access", 0));
        verify(accountService).withdraw(7L, true, "recovery-2026-09-v1");
        verify(erasureService, never()).eraseIfDue(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRotateRefreshTokenWithoutAccessToken() throws Exception {
        AppUser user = org.mockito.Mockito.mock(AppUser.class);
        when(refreshTokenStore.findUserId("old-refresh-token")).thenReturn(Optional.of(7L));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(rolePolicyService.rolesOf(7L)).thenReturn(Set.of(Role.USER));
        when(tokenService.issue(eq(7L), anyList())).thenReturn(tokens());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("geupddong_refresh", "old-refresh-token")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value("geupddong_access", "new-access-token"))
                .andExpect(cookie().value("geupddong_refresh", "new-refresh-token"));

        verify(tokenService).revoke("old-refresh-token");
        verify(tokenService).issue(eq(7L), anyList());
    }

    @Test
    void profileIncludesTheCurrentAccessTokenExpiration() throws Exception {
        Instant expiresAt = Instant.parse("2026-09-13T13:30:00Z");
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("profile-expiration-test")
                .header("alg", "HS256").subject("7").claim("roles", java.util.List.of("ADMIN"))
                .issuedAt(Instant.parse("2026-09-13T13:00:00Z")).expiresAt(expiresAt).build();
        when(jwtDecoder.decode("profile-expiration-test")).thenReturn(jwt);
        AppUser user = org.mockito.Mockito.mock(AppUser.class);
        when(user.getDisplayName()).thenReturn("운영자");
        when(user.getEmail()).thenReturn("admin@geupddong.com");
        when(user.getStatus()).thenReturn(com.example.toiletapi.auth.model.UserStatus.ACTIVE);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(policyConsentService.status(7L)).thenReturn(new PolicyConsentStatusResponse(false, java.util.List.of(), java.util.List.of()));

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer profile-expiration-test"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.accessTokenExpiresAt")
                        .value("2026-09-13T13:30:00Z"));
    }

    @Test
    void shouldRejectMissingOrExpiredRefreshToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("geupddong_refresh", "expired-refresh-token")))
                .andExpect(status().isUnauthorized());

        verify(tokenService, never()).issue(org.mockito.ArgumentMatchers.anyLong(), anyList());
    }

    @Test
    void shouldRevokeRefreshTokenAndExpireCookiesOnLogout() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("geupddong_refresh", "active-refresh-token")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("geupddong_access=")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        verify(tokenService).revoke("active-refresh-token");
    }

    @Test
    void shouldStartOAuthLoginWithAdminReturnTarget() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login/google").param("returnTo", "admin"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
    }

    @Test void immediateWithdrawalWithoutConfirmedErasureStaysPending() throws Exception {
        when(erasureService.eraseIfDue(eq(7L), org.mockito.ArgumentMatchers.any())).thenReturn(false);
        immediateWithdrawal().andExpect(status().isAccepted());
        verify(erasureService).recordFailure(7L);
    }

    @Test void immediateWithdrawalWithConfirmedErasureCompletes() throws Exception {
        when(erasureService.eraseIfDue(eq(7L), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        immediateWithdrawal().andExpect(status().isNoContent());
        verify(erasureService, never()).recordFailure(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test void immediateWithdrawalExceptionStaysPending() throws Exception {
        when(erasureService.eraseIfDue(eq(7L), org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException());
        immediateWithdrawal().andExpect(status().isAccepted());
        verify(erasureService).recordFailure(7L);
    }

    private org.springframework.test.web.servlet.ResultActions immediateWithdrawal() throws Exception {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("immediate-test")
                .header("alg", "HS256").subject("7").claim("roles", java.util.List.of("USER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(jwtDecoder.decode("immediate-test")).thenReturn(jwt);
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/auth/me")
                .header("Authorization", "Bearer immediate-test").header("Origin", "https://geupddong.com")
                .contentType("application/json").content("{\"retainForRecovery\":false}"))
                .andExpect(cookie().maxAge("geupddong_access", 0))
                .andExpect(cookie().maxAge("geupddong_refresh", 0));
    }

    private AuthTokenService.IssuedTokens tokens() {
        return new AuthTokenService.IssuedTokens(
                "new-access-token", "new-refresh-token", Instant.now().plus(Duration.ofMinutes(15)), Duration.ofDays(14));
    }

    @Test
    void nicknameRequiresLoginAndUsesJwtSubjectNotBodyUserId() throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/auth/me/profile");
        mockMvc.perform(request.contentType("application/json").content("{\"displayName\":\"새 이름\"}"))
                .andExpect(status().isUnauthorized());
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("profile-test-token")
                .header("alg", "HS256").subject("7").claim("roles", java.util.List.of("USER"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        when(jwtDecoder.decode("profile-test-token")).thenReturn(jwt);
        when(accountService.updateNickname(7L, "새 이름")).thenReturn("새 이름");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/auth/me/profile")
                        .header("Authorization", "Bearer profile-test-token").contentType("application/json")
                        .content("{\"displayName\":\"새 이름\",\"userId\":999}"))
                .andExpect(status().isOk());
        verify(accountService).updateNickname(7L, "새 이름");
        verify(accountService, never()).updateNickname(eq(999L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void previewCorsIsExactAndCredentialsRemainSupported() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/auth/me")
                        .header("Origin", "https://preview.geupddong.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://preview.geupddong.com"))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        for (String origin : java.util.List.of("https://preview.geupddong.com.evil.example", "http://preview.geupddong.com", "https://evil.workers.dev")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/auth/me")
                            .header("Origin", origin).header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }
}
