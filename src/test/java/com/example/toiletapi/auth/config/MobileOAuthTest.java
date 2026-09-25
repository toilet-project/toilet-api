package com.example.toiletapi.auth.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.service.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class MobileOAuthTest {
    private final String state = "s".repeat(43), challenge = "c".repeat(43);

    @Test void mobileSuccessOnlyReturnsSingleUseCodeAndClearsBrowserSession() throws Exception {
        var login = mock(OAuthLoginService.class); var tokens = mock(AuthTokenService.class);
        var codes = mock(MobileLoginCodeStore.class); var photos = mock(com.example.toiletapi.photo.PhotoSync.class);
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), Map.of("sub", "test"), "sub");
        when(login.login("google", principal)).thenReturn(new OAuthLoginService.LoginUser(7L, List.of(Role.USER), true));
        when(codes.issue(7L, challenge)).thenReturn("k".repeat(43));
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        MobileOAuthSession.begin(request, state, challenge);
        new OAuthLoginSuccessHandler(login, tokens, "https://geupddong.com", mock(RecoveryChallengeStore.class), photos, codes)
            .onAuthenticationSuccess(request, response, new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "google"));
        assertEquals("geupddong://auth/callback?state=" + state + "&code=" + "k".repeat(43), response.getRedirectedUrl());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(request.getSession(false));
        verifyNoInteractions(tokens);
    }

    @Test void mobileFailureReturnsSafeErrorAndConsumesAttempt() throws Exception {
        var request = new MockHttpServletRequest(); var response = new MockHttpServletResponse();
        MobileOAuthSession.begin(request, state, challenge);
        new OAuthLoginFailureHandler("https://geupddong.com").onAuthenticationFailure(request, response, new BadCredentialsException("secret"));
        assertEquals("geupddong://auth/callback?state=" + state + "&error=login_failed", response.getRedirectedUrl());
        assertNull(request.getSession(false));
        assertFalse(response.getRedirectedUrl().contains("secret"));
    }

    @Test void invalidPkceOrStateNeverStartsAttempt() {
        for (String invalid : List.of("", "plain", "https://evil.example", "s".repeat(44))) {
            var request = new MockHttpServletRequest();
            assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> MobileOAuthSession.begin(request, invalid, challenge));
            assertNull(request.getSession(false));
        }
    }

    @Test void webLoginClearsStaleMobileAttempt() throws Exception {
        var request = new MockHttpServletRequest(); MobileOAuthSession.begin(request, state, challenge);
        new com.example.toiletapi.auth.controller.OAuthLoginRedirectController().login("google", "home", request, new MockHttpServletResponse());
        assertNull(MobileOAuthSession.consume(request));
    }

    @Test void pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            MobileLoginCodeStore.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }
}
