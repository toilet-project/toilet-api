package com.example.toiletapi.auth.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.toiletapi.auth.controller.*;
import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.*;
import com.example.toiletapi.global.config.CorsConfig;
import com.example.toiletapi.policy.service.PolicyConsentService;
import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Actual security filter chain and controllers; only synthetic credentials and mocked storage. */
@WebMvcTest(value = {AuthController.class, OAuthLoginRedirectController.class, AccountRecoveryController.class}, properties = {
        "account.retention.enabled=true", "account.erasure.enabled=true", "account.lifecycle.maintenance=false",
        "spring.security.oauth2.client.registration.google.client-id=fixture-client",
        "spring.security.oauth2.client.registration.google.client-secret=fixture-secret",
        "spring.security.oauth2.client.registration.kakao.client-id=fixture-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=fixture-secret"
})
@Import({SecurityConfig.class, CorsConfig.class, AccountLifecycleGate.class})
class StaleCookieAuthenticationTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OAuthLoginSuccessHandler success;
    @MockitoBean RefreshTokenStore refresh;
    @MockitoBean AuthTokenService tokens;
    @MockitoBean AppUserRepository users;
    @MockitoBean UserRolePolicyService roles;
    @MockitoBean PolicyConsentService consents;
    @MockitoBean AccountService accounts;
    @MockitoBean AccountErasureService erasure;
    @MockitoBean RecoveryChallengeStore challenges;
    @MockitoBean AccountRecoveryService recovery;
    private Cookie stale() { return new Cookie("geupddong_access", "fixture-revoked"); }

    @BeforeEach void invalidAccess() {
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("Fixture revoked or expired token"));
    }

    @Test void bothLoginStartsIgnoreOldAccessWithoutAuthenticatingUser() throws Exception {
        for (String provider : List.of("google", "kakao")) {
            mvc.perform(get("/api/v1/auth/login/" + provider).cookie(stale())
                    .header("Authorization", "Bearer fixture-revoked"))
                    .andExpect(status().isFound()).andExpect(redirectedUrl("/oauth2/authorization/" + provider));
        }
        verifyNoInteractions(decoder, success, accounts, tokens, recovery);
    }

    @Test void loginReturnTargetValidationStillApplies() throws Exception {
        mvc.perform(get("/api/v1/auth/login/google?returnTo=untrusted").cookie(stale()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(decoder, tokens);
    }

    @Test void recoveryReadsSeparateProofDespiteStaleAccess() throws Exception {
        var proof = new RecoveryChallengeStore.Proof(7L, "fixture-generation");
        when(challenges.read("fixture-proof")).thenReturn(proof);
        when(recovery.status(proof)).thenReturn(new AccountRecoveryService.RecoveryStatus(
                LocalDateTime.of(2026, 12, 1, 0, 0), "Fixture member"));
        mvc.perform(get("/api/v1/auth/recovery").cookie(stale(), new Cookie("geupddong_recovery", "fixture-proof")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("Fixture member"));
        verifyNoInteractions(decoder, tokens, erasure);
    }

    @Test void missingOrExpiredRecoveryProofNeverRestoresOrErases() throws Exception {
        when(challenges.read(null)).thenThrow(RecoveryChallengeStore.expired());
        when(challenges.read("expired-proof")).thenThrow(RecoveryChallengeStore.expired());
        mvc.perform(get("/api/v1/auth/recovery").cookie(stale())).andExpect(status().isUnauthorized());
        for (String action : List.of("RESTORE", "ERASE")) {
            mvc.perform(post("/api/v1/auth/recovery").cookie(stale(), new Cookie("geupddong_recovery", "expired-proof"))
                    .header("Origin", "https://geupddong.com").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"action\":\"" + action + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(decoder, recovery, erasure, tokens);
    }

    @Test void untrustedRecoveryOriginIsStillRejectedBeforeProofLookup() throws Exception {
        mvc.perform(post("/api/v1/auth/recovery").cookie(stale()).header("Origin", "https://untrusted.invalid")
                .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RESTORE\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(decoder, challenges, recovery, erasure, tokens);
    }

    @Test void recoveryCancelWorksWithoutRestoringOrErasing() throws Exception {
        mvc.perform(delete("/api/v1/auth/recovery").cookie(stale(), new Cookie("geupddong_recovery", "fixture-proof"))
                .header("Origin", "https://geupddong.com"))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("geupddong_recovery", 0));
        verify(challenges).delete("fixture-proof");
        verifyNoInteractions(decoder, recovery, erasure, tokens);
    }

    @Test void refreshStillRequiresItsOwnValidCredential() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").cookie(stale())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(stale(), new Cookie("geupddong_refresh", "unknown")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(decoder, tokens);
    }

    @Test void validRefreshCanReplaceExpiredAccess() throws Exception {
        var user = AppUser.create("Fixture member", null, false); user.activateAfterConsent();
        when(refresh.findUserId("fixture-refresh")).thenReturn(Optional.of(7L));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(roles.rolesOf(7L)).thenReturn(Set.of(Role.USER));
        when(tokens.issue(7L, List.of(Role.USER))).thenReturn(new AuthTokenService.IssuedTokens(
                "fixture-new-access", "fixture-new-refresh", Instant.now().plusSeconds(300), Duration.ofDays(14)));
        mvc.perform(post("/api/v1/auth/refresh").cookie(stale(), new Cookie("geupddong_refresh", "fixture-refresh")))
                .andExpect(status().isNoContent()).andExpect(cookie().value("geupddong_access", "fixture-new-access"));
        verify(tokens).revoke("fixture-refresh");
        verifyNoInteractions(decoder);
    }

    @Test void withdrawnAccountCannotRefreshEvenWithRemainingRefreshRecord() throws Exception {
        var user = AppUser.create("Fixture member", null, false); user.withdraw();
        when(refresh.findUserId("fixture-refresh")).thenReturn(Optional.of(7L));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        mvc.perform(post("/api/v1/auth/refresh").cookie(stale(), new Cookie("geupddong_refresh", "fixture-refresh")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(decoder, tokens);
    }

    @Test void logoutCanClearExpiredCookies() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").cookie(stale(), new Cookie("geupddong_refresh", "fixture-refresh")))
                .andExpect(status().isNoContent()).andExpect(cookie().maxAge("geupddong_access", 0))
                .andExpect(cookie().maxAge("geupddong_refresh", 0));
        verify(tokens).revoke("fixture-refresh");
        verifyNoInteractions(decoder);
    }

    @Test void protectedEndpointsStillRejectRevokedAccess() throws Exception {
        for (String path : List.of("/api/v1/auth/me", "/api/v1/reports/mine", "/api/admin/reports/7")) {
            mvc.perform(get(path).cookie(stale())).andExpect(status().isUnauthorized());
        }
        mvc.perform(delete("/api/v1/auth/me").cookie(stale()).header("Origin", "https://geupddong.com"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/v1/auth/me/profile").cookie(stale()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Fixture\"}")).andExpect(status().isUnauthorized());
        verify(decoder, times(5)).decode("fixture-revoked");
        verifyNoInteractions(accounts, recovery, erasure, tokens);
    }

    @Test void realExpiredAndWithdrawnTokensAllowOnlyFreshLogin() throws Exception {
        var config = new JwtConfig();
        var properties = new AuthTokenProperties(Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(15), Duration.ofDays(14));
        var key = config.jwtSecretKey(properties);
        var encoder = config.jwtEncoder(key);
        var actualDecoder = config.accountAwareJwtDecoder(key, users);
        var user = AppUser.create("Fixture member", null, false); user.activateAfterConsent();
        when(users.findById(7L)).thenReturn(Optional.of(user));
        doAnswer(call -> actualDecoder.decode(call.getArgument(0))).when(decoder).decode(anyString());
        var now = Instant.now();
        String expired = encodeFixture(encoder, now.minusSeconds(600), now.minusSeconds(120));
        String revoked = encodeFixture(encoder, now, now.plusSeconds(300));
        user.withdraw();
        for (String token : List.of(expired, revoked, "not-a-jwt")) {
            var cookie = new Cookie("geupddong_access", token);
            mvc.perform(get("/api/v1/auth/login/google").cookie(cookie))
                    .andExpect(status().isFound()).andExpect(redirectedUrl("/oauth2/authorization/google"));
            mvc.perform(get("/api/v1/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/admin/reports/7").cookie(cookie)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(tokens, accounts, erasure, recovery, success);
    }

    private String encodeFixture(org.springframework.security.oauth2.jwt.JwtEncoder encoder, Instant issued, Instant expires) {
        return encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(),
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().subject("7").issuedAt(issued).expiresAt(expires)
                        .claim("roles", List.of("USER")).claim("auth_version", 0).build())).getTokenValue();
    }
}
