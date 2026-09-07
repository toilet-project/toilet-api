package com.example.toiletapi.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.toiletapi.auth.controller.OAuthLoginRedirectController;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Real embedded Tomcat cookies; real security chain, no DB/Redis/provider calls. */
@SpringBootTest(classes = OAuthSessionCookieIntegrationTest.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "auth.frontend-base-url=https://geupddong.com"})
class OAuthSessionCookieIntegrationTest {
    @Value("${local.server.port}") int port;
    @Autowired OAuthLoginSuccessHandler successHandler;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    @Test void googleSessionCookieIsSecureEvenOnPlainHttpUpstream() throws Exception {
        var response = get("/oauth2/authorization/google", null, false);
        assertCookie(response);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .startsWith("https://provider.fixture.invalid/authorize?")
                .contains("redirect_uri=https://api.geupddong.com/login/oauth2/code/google");
    }

    @Test void kakaoSessionCookieHasSamePolicyBehindHttpsProxy() throws Exception {
        var response = get("/oauth2/authorization/kakao", null, true);
        assertCookie(response);
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow())
                .contains("redirect_uri=https://api.geupddong.com/login/oauth2/code/kakao");
    }

    @Test void controllerCreatedSessionKeepsAdminTargetThroughCancelledCallback() throws Exception {
        assertCancelledFlow("google", "admin", OAuthReturnTargets.ADMIN);
    }

    @Test void controllerCreatedSessionKeepsPreviewTargetThroughCancelledCallback() throws Exception {
        assertCancelledFlow("kakao", "preview", OAuthReturnTargets.PREVIEW);
    }

    @Test void incorrectStateDoesNotAuthenticate() throws Exception {
        var start = get("/oauth2/authorization/google", null, true);
        var failure = get("/login/oauth2/code/google?error=access_denied&state=invalid-fixture-state",
                cookiePair(start), true);
        assertThat(failure.statusCode()).isEqualTo(302);
        assertThat(failure.headers().firstValue("Location").orElseThrow())
                .isEqualTo("https://geupddong.com/?login=failed");
        verifyNoInteractions(successHandler);
    }

    @Test void missingSessionCannotUseAnotherRequestsState() throws Exception {
        var start = get("/oauth2/authorization/google", null, true);
        var failure = get("/login/oauth2/code/google?error=access_denied&state=" + state(start), null, true);
        assertThat(failure.statusCode()).isEqualTo(302);
        assertThat(failure.headers().firstValue("Location").orElseThrow())
                .isEqualTo("https://geupddong.com/?login=failed");
        verifyNoInteractions(successHandler);
    }

    private void assertCancelledFlow(String provider, String target, String expected) throws Exception {
        var start = get("/api/v1/auth/login/" + provider + "?returnTo=" + target, null, true);
        assertCookie(start);
        String session = cookiePair(start);
        var authorization = get("/oauth2/authorization/" + provider, session, true);
        var cancelled = get("/login/oauth2/code/" + provider + "?error=access_denied&state=" + state(authorization),
                session, true);
        assertThat(cancelled.statusCode()).isEqualTo(302);
        assertThat(cancelled.headers().firstValue("Location").orElseThrow()).isEqualTo(expected + "/?login=failed");
        verifyNoInteractions(successHandler);
    }

    private HttpResponse<Void> get(String path, String cookie, boolean proxy) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET();
        if (proxy) request.header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", "api.geupddong.com").header("X-Forwarded-Port", "443");
        // Explicit transport test only: this does not simulate a browser's SameSite enforcement.
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding());
    }

    private String sessionCookie(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("JSESSIONID=")).findFirst().orElseThrow();
    }

    private String cookiePair(HttpResponse<?> response) { return sessionCookie(response).split(";", 2)[0]; }

    private void assertCookie(HttpResponse<?> response) {
        String cookie = sessionCookie(response);
        // Assertion failures must not print even the synthetic session identifier.
        assertThat(cookie.substring(cookie.indexOf(';'))).contains("; Secure", "; HttpOnly", "; SameSite=Lax", "; Path=/")
                .doesNotContain("Domain=", "Max-Age=");
    }

    private String state(HttpResponse<?> response) {
        var match = Pattern.compile("(?:[?&])state=([^&]+)")
                .matcher(response.headers().firstValue("Location").orElseThrow());
        assertThat(match.find()).isTrue();
        // The provider-facing state is already URL encoded; preserve it for the callback.
        return match.group(1);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})
    @Import({SecurityConfig.class, OAuthLoginRedirectController.class})
    static class Fixture {
        @Bean OAuthLoginSuccessHandler successHandler() { return mock(OAuthLoginSuccessHandler.class); }
        @Bean JwtDecoder jwtDecoder() { return mock(JwtDecoder.class); }
        @Bean ClientRegistrationRepository clientRegistrations() {
            return new InMemoryClientRegistrationRepository(registration("google"), registration("kakao"));
        }
        private ClientRegistration registration(String provider) {
            return ClientRegistration.withRegistrationId(provider)
                    .clientId("fixture-client").clientSecret("fixture-secret")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("https://api.geupddong.com/login/oauth2/code/" + provider)
                    .authorizationUri("https://provider.fixture.invalid/authorize")
                    .tokenUri("https://provider.fixture.invalid/token")
                    .userInfoUri("https://provider.fixture.invalid/userinfo")
                    .userNameAttributeName("id").scope("profile").clientName(provider).build();
        }
    }
}
