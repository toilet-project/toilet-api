package com.example.toiletapi.report;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.global.config.CorsConfig;
import com.example.toiletapi.report.controller.ToiletReportController;
import com.example.toiletapi.report.dto.MyToiletReportPageResponse;
import com.example.toiletapi.report.model.ReportStatus;
import com.example.toiletapi.report.service.ToiletReportService;
import com.example.toiletapi.policy.service.PolicyConsentService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value=ToiletReportController.class, properties={
    "spring.security.oauth2.client.registration.google.client-id=test-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-client",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-secret"
})
@Import({SecurityConfig.class, CorsConfig.class})
class MyReportHistoryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ToiletReportService service;
    @MockitoBean PolicyConsentService eligibility;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean OAuthLoginSuccessHandler loginSuccess;

    @Test void anonymousOwnerPathsAreDenied() throws Exception {
        for (String path : List.of("/api/v1/reports/me", "/api/v1/reports/me/search", "/api/v1/reports/me/12"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, eligibility);
    }
    @Test void ownerComesOnlyFromJwtAndLegacyArrayRemainsCompatible() throws Exception {
        when(jwtDecoder.decode("synthetic")).thenReturn(Jwt.withTokenValue("synthetic").header("alg", "HS256")
                .subject("3").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
        when(service.minePage(3L, ReportStatus.PENDING, LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4), 0, 10))
                .thenReturn(new MyToiletReportPageResponse(List.of(), 0, 10, 0, false, Map.of("ALL", 0L)));
        mvc.perform(get("/api/v1/reports/me/search?status=PENDING&from=2026-10-04&to=2026-10-04&userId=4")
                .header("Authorization", "Bearer synthetic")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        verify(service).minePage(3L, ReportStatus.PENDING, LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4), 0, 10);
        mvc.perform(get("/api/v1/reports/me/12?userId=4").header("Authorization", "Bearer synthetic")).andExpect(status().isOk());
        verify(service).mineDetail(3L, 12L);
        when(service.mine(3L)).thenReturn(List.of());
        mvc.perform(get("/api/v1/reports/me").header("Authorization", "Bearer synthetic")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        verify(eligibility, times(3)).requireEligibleUser(3L);
    }
}
