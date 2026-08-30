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
        "spring.security.oauth2.client.registration.google.client-id=test-google-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
        "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({CorsConfig.class, SecurityConfig.class})
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
    private OAuthLoginSuccessHandler oauthLoginSuccessHandler;
    @MockitoBean
    private JwtDecoder jwtDecoder;

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

    private AuthTokenService.IssuedTokens tokens() {
        return new AuthTokenService.IssuedTokens(
                "new-access-token", "new-refresh-token", Instant.now().plus(Duration.ofMinutes(15)), Duration.ofDays(14));
    }
}
