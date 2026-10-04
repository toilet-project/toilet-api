package com.example.toiletapi.growth;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.global.config.CorsConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(value=AdminGrowthController.class,properties={
    "spring.security.oauth2.client.registration.google.client-id=test-google-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({SecurityConfig.class,CorsConfig.class})
class AdminGrowthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminGrowthAccess access;
    @MockitoBean AdminGrowthOperations operations;
    @MockitoBean GrowthService growth;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OAuthLoginSuccessHandler loginSuccess;
    @BeforeEach void tokens() {
        for(String role:List.of("ADMIN","USER"))when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role)
                .header("alg","HS256").subject("1").claim("roles",List.of(role)).claim("auth_version",1)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
    }
    @Test void allGrowthAdminRoutesRejectAnonymousAndOrdinaryMembers() throws Exception {
        mvc.perform(get("/api/admin/v1/growth/policy/preview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/v1/growth/policy/preview").header("Authorization","Bearer USER")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/v1/growth/users/2/apply").header("Authorization","Bearer USER")
                .contentType("application/json").content("{\"policyVersion\":\"v1\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(access,operations,growth);
    }
    @Test void previewIsReadOnlyAndNeverSharedCached() throws Exception {
        when(access.candidates(0,50)).thenReturn(new AdminGrowthAccess.CandidatePage(List.of("2"),null,false));
        mvc.perform(get("/api/admin/v1/growth/backfill/candidates").header("Authorization","Bearer ADMIN"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.userIds[0]").value("2"));
        verify(access).requireAdmin(any());verifyNoInteractions(operations,growth);
    }
    @Test void removedAdminRoleIsCheckedAgainstCurrentDatabaseEvenWithValidJwt() throws Exception {
        when(access.requireAdmin(any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        mvc.perform(get("/api/admin/v1/growth/users/2/preview").header("Authorization","Bearer ADMIN"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(operations,growth);
    }
    @Test void foreignOriginCannotApplyEvenWithValidAdminBearer() throws Exception {
        mvc.perform(post("/api/admin/v1/growth/users/2/apply").header("Authorization","Bearer ADMIN")
                .header("Origin","https://untrusted.example").contentType("application/json")
                .content("{\"policyVersion\":\"v1\"}")).andExpect(status().isForbidden());
        verifyNoInteractions(operations,growth);
    }
    @Test void explicitBearerCanApplyAndControllerPassesExpectedVersion() throws Exception {
        mvc.perform(post("/api/admin/v1/growth/users/2/apply").header("Authorization","Bearer ADMIN")
                .contentType("application/json").content("{\"policyVersion\":\"v1\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(operations).apply(any(),eq(2L),eq("v1"));verifyNoInteractions(growth);
    }
}
