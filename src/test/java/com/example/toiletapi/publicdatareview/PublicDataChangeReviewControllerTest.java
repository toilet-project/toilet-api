package com.example.toiletapi.publicdatareview;

import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.global.config.CorsConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static com.example.toiletapi.publicdatareview.PublicDataChangeReviewModels.Page;
import static com.example.toiletapi.publicdatareview.PublicDataChangeReviewModels.Summary;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = PublicDataChangeReviewController.class, properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-google",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "spring.security.oauth2.client.registration.kakao.client-id=test-kakao",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-secret"})
@Import({SecurityConfig.class, CorsConfig.class})
class PublicDataChangeReviewControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PublicDataChangeReviewService service;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OAuthLoginSuccessHandler handler;

    @Test
    void anonymousAndOrdinaryUserCannotReadOrDecide() throws Exception {
        mvc.perform(get("/api/admin/v1/public-data-change-reviews")).andExpect(status().isUnauthorized());
        token("USER");
        mvc.perform(get("/api/admin/v1/public-data-change-reviews").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/v1/public-data-change-reviews/1/decisions")
                        .header("Authorization", "Bearer token").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminCanReadAndDecisionRequiresConcurrencyFields() throws Exception {
        token("ADMIN");
        when(service.search(any(), eq(""), any(), eq(0), eq(15), eq("lastReceivedAt,desc")))
                .thenReturn(new Page(List.of(), 0, 15, 0, 0, new Summary(0, 0, 0, 0)));
        mvc.perform(get("/api/admin/v1/public-data-change-reviews").header("Authorization", "Bearer token"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/v1/public-data-change-reviews/1/decisions")
                        .header("Authorization", "Bearer token").contentType("application/json")
                        .content("{\"action\":\"APPLY\",\"note\":\"확인\"}"))
                .andExpect(status().isBadRequest());
    }

    private void token(String role) {
        when(decoder.decode("token")).thenReturn(Jwt.withTokenValue("token").header("alg", "HS256")
                .subject("9").claim("roles", List.of(role)).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build());
    }
}
