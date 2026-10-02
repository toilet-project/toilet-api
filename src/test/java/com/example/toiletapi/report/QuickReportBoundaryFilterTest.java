package com.example.toiletapi.report;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

class QuickReportBoundaryFilterTest {
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/reports/guest");
        request.addHeader("Origin", "https://preview.geupddong.com"); request.setContentType("application/json");
        request.addHeader("X-Report-Guest", UUID.randomUUID().toString()); request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        return request;
    }
    @Test void disabledByDefaultAndOriginCannotBeSpoofed() throws Exception {
        var response = new MockHttpServletResponse();
        new QuickReportBoundaryFilter(false).doFilter(request(), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(404);
        var foreign = request(); foreign.removeHeader("Origin"); foreign.addHeader("Origin", "https://evil.invalid");
        response = new MockHttpServletResponse(); new QuickReportBoundaryFilter(true).doFilter(foreign, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(403);
    }
    @Test void onlyBoundedJsonIsPassedOnWithNoStore() throws Exception {
        var request = request(); var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new QuickReportBoundaryFilter(true).doFilter(request, response, chain);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        request = request(); request.setContent(new byte[8193]); response = new MockHttpServletResponse();
        new QuickReportBoundaryFilter(true).doFilter(request, response, new MockFilterChain()); assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void repeatedGuestSubmissionsAreLimitedAndCookiesAreIgnoredOnlyOnGuestPost() throws Exception {
        var filter = new QuickReportBoundaryFilter(true); var guest = UUID.randomUUID().toString();
        for (int i = 0; i < 13; i++) {
            var request = request(); request.removeHeader("X-Report-Guest"); request.addHeader("X-Report-Guest", guest);
            var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(i == 12 ? 429 : 200);
        }
        var resolver = new com.example.toiletapi.auth.config.CookieBearerTokenResolver();
        var request = request(); request.setCookies(new jakarta.servlet.http.Cookie("geupddong_access", "expired"));
        assertThat(resolver.resolve(request)).isNull(); request.setRequestURI("/api/v1/reports/me"); request.setMethod("GET");
        assertThat(resolver.resolve(request)).isEqualTo("expired");
    }
}
