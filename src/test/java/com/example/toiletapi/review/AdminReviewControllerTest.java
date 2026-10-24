package com.example.toiletapi.review;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.global.config.CorsConfig;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value=AdminReviewController.class,properties={
    "spring.security.oauth2.client.registration.google.client-id=test-google-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({SecurityConfig.class,CorsConfig.class,ReviewBoundaryFilter.class,ReviewExceptionHandler.class})
class AdminReviewControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminReviewAccess access;
    @MockitoBean AdminReviewService service;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OAuthLoginSuccessHandler loginSuccess;
    @BeforeEach void tokens() {
        for(String role:List.of("ADMIN","USER"))when(decoder.decode(role)).thenReturn(Jwt.withTokenValue(role)
                .header("alg","HS256").subject("1").claim("roles",List.of(role)).claim("auth_version",1)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
    }
    @Test void anonymousAndOrdinaryMembersCannotReadListOrDetailAndErrorsAreNotCached() throws Exception {
        for(String path:List.of("/api/admin/v1/reviews","/api/admin/v1/reviews/1")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
            mvc.perform(get(path).header("Authorization","Bearer USER")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(access,service);
    }
    @Test void listUsesBoundedDefaultWithoutSendingContentOrAccountIdentifiers() throws Exception {
        var time=OffsetDateTime.parse("2026-10-24T12:00:00.123456+09:00");
        when(service.page(null,20)).thenReturn(new AdminReviewModels.Page(List.of(new AdminReviewModels.Item(
                "1",4,"서울역 화장실","VISIBLE",5,3,true,20,"작성자",false,time,time,true)),null,false));
        mvc.perform(get("/api/admin/v1/reviews").header("Authorization","Bearer ADMIN"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.items[0].toiletName").value("서울역 화장실"))
                .andExpect(jsonPath("$.items[0].comment").doesNotExist()).andExpect(jsonPath("$.items[0].authorId").doesNotExist())
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-10-24T12:00:00.123456+09:00"));
        verify(access).requireAdmin(any());verify(service).page(null,20);
    }
    @Test void currentRoleRevocationStopsQueryAndProvidesStructuredError() throws Exception {
        doThrow(new ReviewFailure(403,"ACCESS_DENIED","관리자 권한이 필요합니다.")).when(access).requireAdmin(any());
        mvc.perform(get("/api/admin/v1/reviews").header("Authorization","Bearer ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verifyNoInteractions(service);
    }
    @Test void unknownDetailAndMalformedIdsHaveStructuredErrors() throws Exception {
        when(service.detail(999)).thenThrow(new ReviewFailure(404,"REVIEW_NOT_FOUND","리뷰를 찾을 수 없습니다."));
        mvc.perform(get("/api/admin/v1/reviews/999").header("Authorization","Bearer ADMIN"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("REVIEW_NOT_FOUND"));
        mvc.perform(get("/api/admin/v1/reviews/not-a-number").header("Authorization","Bearer ADMIN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("REVIEW_INVALID_REQUEST"));
    }
    @Test void routesOfferNoWriteOperation() throws Exception {
        mvc.perform(delete("/api/admin/v1/reviews/1").header("Origin","https://geupddong.com").header("Authorization","Bearer ADMIN"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(access,service);
    }
}
