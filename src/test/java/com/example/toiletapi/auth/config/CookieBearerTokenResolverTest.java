package com.example.toiletapi.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class CookieBearerTokenResolverTest {
    private final CookieBearerTokenResolver resolver = new CookieBearerTokenResolver();

    @ParameterizedTest
    @CsvSource({
        "GET,/api/v1/auth/login/google", "GET,/api/v1/auth/login/kakao",
        "GET,/oauth2/authorization/google", "GET,/oauth2/authorization/kakao",
        "GET,/login/oauth2/code/google", "GET,/login/oauth2/code/kakao",
        "GET,/api/v1/auth/recovery", "POST,/api/v1/auth/recovery", "DELETE,/api/v1/auth/recovery",
        "POST,/api/v1/auth/refresh", "POST,/api/v1/auth/logout"
    })
    void independentCredentialEndpointsDoNotResolveAccessToken(String method, String path) {
        var request = request(method, path);
        request.addHeader("Authorization", "Bearer fixture-header");
        assertThat(resolver.resolve(request)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "GET,/api/v1/auth/me", "DELETE,/api/v1/auth/me", "PATCH,/api/v1/auth/me/profile",
        "GET,/api/v1/auth/withdrawal-options", "POST,/api/v1/auth/consent",
        "GET,/api/v1/reports/mine", "GET,/api/admin/reports/7",
        "POST,/api/v1/auth/login/google", "GET,/api/v1/auth/login/google/extra",
        "GET,/api/v1/auth/login/unknown", "GET,/api/v1/auth/recovery/extra",
        "PATCH,/api/v1/auth/recovery", "GET,/api/v1/auth/refresh", "GET,/api/v1/auth/logout",
        "GET,/api/v1/auth/refresh-extra", "GET,/oauth2/authorization/google/extra"
    })
    void noBroadPathOrMethodExemption(String method, String path) {
        assertThat(resolver.resolve(request(method, path))).isEqualTo("fixture-cookie");
    }

    @Test void contextPathDoesNotChangeEndpointClassification() {
        var request = request("GET", "/context/api/v1/auth/login/google");
        request.setContextPath("/context");
        assertThat(resolver.resolve(request)).isNull();
    }

    @Test void protectedRoutesKeepHeaderPrecedence() {
        var request = request("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer fixture-header");
        assertThat(resolver.resolve(request)).isEqualTo("fixture-header");
    }

    @Test void anonymousRequestHasNoToken() {
        assertThat(resolver.resolve(new MockHttpServletRequest("GET", "/api/v1/auth/me"))).isNull();
    }

    private MockHttpServletRequest request(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setCookies(new Cookie("geupddong_access", "fixture-cookie"));
        return request;
    }
}
