package com.example.toiletapi.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class AuthenticatedMutationBoundaryTest {
    final Jwt principal = Jwt.withTokenValue("verified-token").header("alg", "RS256").subject("7").build();
    @Test void explicitAuthenticatedNativeBearerIsAcceptedWithoutBrowserOrigin() {
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer verified-token");
        assertThatCode(() -> AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(request, principal)).doesNotThrowAnyException();
    }
    @Test void cookieAuthenticationAndUnverifiedOrDifferentHeadersCannotUseNativeException() {
        for (String authorization : new String[]{"", "Bearer different-token", "Basic verified-token"}) {
            var request = new MockHttpServletRequest(); request.addHeader("Authorization", authorization);
            assertThatThrownBy(() -> AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(request, principal)).isInstanceOf(ResponseStatusException.class);
        }
        var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer verified-token");
        assertThatThrownBy(() -> AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(request, null)).isInstanceOf(ResponseStatusException.class);
    }
    @Test void foreignAndNullBrowserOriginsStayRejectedEvenWithMatchingBearer() {
        for (String origin : new String[]{"null", "", "https://evil.example", "https://geupddong.com.evil.example"}) {
            var request = new MockHttpServletRequest(); request.addHeader("Origin", origin); request.addHeader("Authorization", "Bearer verified-token");
            assertThatThrownBy(() -> AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(request, principal)).isInstanceOf(ResponseStatusException.class);
        }
    }
    @Test void trustedBrowserAndRecoveryCookieRulesRemainUnchanged() {
        var browser = new MockHttpServletRequest(); browser.addHeader("Origin", "https://geupddong.com");
        assertThatCode(() -> AuthenticatedMutationBoundary.requireTrustedOriginOrBearer(browser, principal)).doesNotThrowAnyException();
        var nativeRequest = new MockHttpServletRequest(); nativeRequest.addHeader("Authorization", "Bearer verified-token");
        assertThatThrownBy(() -> AccountRecoveryController.requireTrustedOrigin(nativeRequest)).isInstanceOf(ResponseStatusException.class);
    }
}
