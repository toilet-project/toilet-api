package com.example.toiletapi.auth.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.*;
import com.example.toiletapi.global.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = MobileAuthController.class, properties = {
    "auth.mobile.enabled=true",
    "spring.security.oauth2.client.registration.google.client-id=test-google-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({SecurityConfig.class, CorsConfig.class})
class MobileAuthHttpTest {
    @Autowired MockMvc mvc;
    @MockitoBean MobileLoginCodeStore codes;
    @MockitoBean RefreshTokenStore refresh;
    @MockitoBean AuthTokenService tokens;
    @MockitoBean AppUserRepository users;
    @MockitoBean UserRolePolicyService roles;
    @MockitoBean OAuthLoginSuccessHandler oauth;
    @MockitoBean JwtDecoder decoder;

    @Test void configAndLoginAreReachableWithoutCookiesOrBearerToken() throws Exception {
        mvc.perform(get("/api/v1/auth/mobile/config")).andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(true)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get("/api/v1/auth/mobile/login/google").param("state", "s".repeat(43)).param("code_challenge", "c".repeat(43)))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/oauth2/authorization/google"));
    }
    @Test void exchangeCannotIssueTokensForAnInvalidOrConsumedCode() throws Exception {
        when(codes.consume(any(), any())).thenThrow(RecoveryChallengeStore.expired());
        mvc.perform(post("/api/v1/auth/mobile/exchange").contentType("application/json")
            .content("{\"code\":\"bad\",\"codeVerifier\":\"bad\"}"))
            .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("Set-Cookie"));
        verify(codes).consume("bad", "bad"); verifyNoInteractions(tokens);
    }
    @Test void nativeLogoutIsPublicButRequiresARefreshToken() throws Exception {
        mvc.perform(post("/api/v1/auth/mobile/logout").contentType("application/json").content("{\"refreshToken\":\"\"}"))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(tokens);
    }
}
