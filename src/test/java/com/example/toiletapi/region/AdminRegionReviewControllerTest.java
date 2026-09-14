package com.example.toiletapi.region;

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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value=AdminRegionReviewController.class, properties={
    "spring.security.oauth2.client.registration.google.client-id=test-google",
    "spring.security.oauth2.client.registration.google.client-secret=test-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-kakao",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-secret"})
@Import({SecurityConfig.class,CorsConfig.class})
class AdminRegionReviewControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RegionReviewService service;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OAuthLoginSuccessHandler handler;
    private void token(String role) {
        when(decoder.decode("test-token")).thenReturn(Jwt.withTokenValue("test-token").header("alg","HS256")
                .subject("9").claim("roles",List.of(role)).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
    }
    @Test void unauthenticatedCannotReadAnyRegionEndpoint() throws Exception {
        for (String path:List.of("", "/1", "/1/history", "/options")) mvc.perform(get("/api/admin/v1/regions"+path)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/v1/regions/1/coordinates").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/v1/regions/1/district").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void userCannotReadOrWrite() throws Exception {
        token("USER");
        mvc.perform(get("/api/admin/v1/regions").header("Authorization","Bearer test-token")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/v1/regions/1/coordinates").header("Authorization","Bearer test-token").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/v1/regions/1/district").header("Authorization","Bearer test-token").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void adminGetsSeparatePaginatedList() throws Exception {
        token("ADMIN");
        when(service.search(RegionReviewModels.Filter.REVIEW,"",0,20)).thenReturn(new RegionReviewModels.Page<>(List.of(),0,20,0,0));
        mvc.perform(get("/api/admin/v1/regions").header("Authorization","Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }
    @Test void invalidFilterAndMissingSnapshotCannotWrite() throws Exception {
        token("ADMIN");
        mvc.perform(get("/api/admin/v1/regions?status=FORCE_APPROVE").header("Authorization","Bearer test-token")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/v1/regions/1/coordinates").header("Authorization","Bearer test-token")
                .contentType("application/json").content("{\"latitude\":37,\"longitude\":127,\"note\":\"확인\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void adminCanSearchOptionsAndConfirmDistrict() throws Exception {
        token("ADMIN");
        var region = new RegionReviewModels.RegionValue("경기도","41","하남시","41450",null,null);
        when(service.options("하남",20)).thenReturn(List.of(new RegionReviewModels.RegionOption(region)));
        when(service.confirmDistrict(eq(9L),eq(1L),any())).thenReturn(
                new RegionReviewModels.Confirmation(region,"지도 위치 확인",java.time.OffsetDateTime.now()));
        mvc.perform(get("/api/admin/v1/regions/options?keyword=하남&limit=20")
                .header("Authorization","Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].region.sigunguCode").value("41450"));
        mvc.perform(post("/api/admin/v1/regions/1/district").header("Authorization","Bearer test-token")
                .contentType("application/json")
                .content("{\"sigunguCode\":\"41450\",\"note\":\"지도 위치 확인\",\"expectedLocation\":{\"latitude\":37.5,\"longitude\":127.2,\"roadAddress\":\"경기도 하남시\",\"jibunAddress\":null}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.region.sigunguName").value("하남시"));
    }
}
