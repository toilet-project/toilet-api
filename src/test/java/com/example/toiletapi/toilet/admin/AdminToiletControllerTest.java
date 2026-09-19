package com.example.toiletapi.toilet.admin;

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

import static com.example.toiletapi.toilet.admin.AdminToiletModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AdminToiletController.class, properties = {
        "spring.security.oauth2.client.registration.google.client-id=test-google",
        "spring.security.oauth2.client.registration.google.client-secret=test-secret",
        "spring.security.oauth2.client.registration.kakao.client-id=test-kakao",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-secret"})
@Import({SecurityConfig.class, CorsConfig.class})
class AdminToiletControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminToiletService service;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OAuthLoginSuccessHandler handler;

    private void token(String role) {
        when(decoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token").header("alg", "HS256")
                .subject("9").claim("roles", List.of(role)).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300)).build());
    }

    @Test void unauthenticatedAndRegularUsersCannotReadOrWrite() throws Exception {
        mvc.perform(get("/api/admin/v1/toilets")).andExpect(status().isUnauthorized());
        token("USER");
        mvc.perform(get("/api/admin/v1/toilets").header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/v1/toilets/1").header("Authorization", "Bearer test-token")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void adminCanSearchSuggestionsRegionsAndDetail() throws Exception {
        token("ADMIN");
        when(service.search("대학", "11", "11230", 0, 15))
                .thenReturn(new Page<>(List.of(), 0, 15, 0, 0));
        when(service.suggestions("대학", 8)).thenReturn(List.of(new Suggestion(1, "충남대학교", "대전")));
        when(service.regions()).thenReturn(List.of(new RegionOption("서울특별시", "11", "동대문구", "11230")));

        mvc.perform(get("/api/admin/v1/toilets?keyword=대학&sidoCode=11&sigunguCode=11230")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/v1/toilets/suggestions?keyword=대학")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("충남대학교"));
        mvc.perform(get("/api/admin/v1/toilets/regions").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].sigunguCode").value("11230"));
        mvc.perform(get("/api/admin/v1/toilets/1").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
        verify(service).detail(1);
    }

    @Test void invalidUpdateIsRejectedBeforeService() throws Exception {
        token("ADMIN");
        mvc.perform(put("/api/admin/v1/toilets/1").header("Authorization", "Bearer test-token")
                        .contentType("application/json")
                        .content("{\"snapshotToken\":\"bad\",\"editable\":{\"name\":\"\",\"latitude\":37}}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void adminIdentityIsPassedToUpdate() throws Exception {
        token("ADMIN");
        String token = "a".repeat(64);
        String body = "{\"snapshotToken\":\"" + token + "\",\"editable\":{\"name\":\"시험 화장실\"}}";
        when(service.update(eq(9L), eq(1L), any())).thenReturn(null);
        mvc.perform(put("/api/admin/v1/toilets/1").header("Authorization", "Bearer test-token")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk());
        verify(service).update(eq(9L), eq(1L), any());
    }
}
