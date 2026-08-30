package com.example.toiletapi.sync.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.toiletapi.sync.dto.DataSyncStatusResponse;
import com.example.toiletapi.sync.service.DataSyncStatusService;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.global.config.CorsConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = DataSyncStatusController.class, properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-google-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
        "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({CorsConfig.class, SecurityConfig.class})
class DataSyncStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataSyncStatusService dataSyncStatusService;

    @MockitoBean
    private OAuthLoginSuccessHandler oauthLoginSuccessHandler;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void returnsOnlyTheLatestPublicSyncStatus() throws Exception {
        when(dataSyncStatusService.getLatestStatus()).thenReturn(new DataSyncStatusResponse(
                true,
                LocalDateTime.of(2026, 8, 29, 2, 1),
                LocalDateTime.of(2026, 8, 29, 0, 0),
                12_345L
        ));

        mockMvc.perform(get("/api/v1/data-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.totalToiletCount").value(12345))
                .andExpect(jsonPath("$.lastSyncedAt").value("2026-08-29T02:01:00"));

        verify(dataSyncStatusService).getLatestStatus();
    }
}
