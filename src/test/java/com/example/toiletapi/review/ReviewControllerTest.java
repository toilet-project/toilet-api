package com.example.toiletapi.review;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.global.config.CorsConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value=ReviewController.class,properties={
    "spring.security.oauth2.client.registration.google.client-id=test-google-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({SecurityConfig.class,CorsConfig.class,ReviewBoundaryFilter.class,ReviewExceptionHandler.class})
class ReviewControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ReviewService service;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean OAuthLoginSuccessHandler loginSuccess;
    static final String ORIGIN="https://preview.geupddong.com";
    static final String BODY="""
            {"toiletId":1,"satisfaction":4,"cleanliness":5,"paper":true,"waitMinutes":20,"comment":"가상 본문",
            "position":{"latitude":36.3,"longitude":127.3,"accuracyMeters":10,"measuredAt":"2026-09-11T00:00:00Z"}}
            """;
    @BeforeEach void token() {
        when(jwtDecoder.decode("synthetic")).thenReturn(Jwt.withTokenValue("synthetic").header("alg","HS256").subject("1")
                .claim("auth_version",7).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
    }
    @Test void anonymousPublicReadButAuthenticationRequiredForAllOwnerPaths() throws Exception {
        when(service.publicPage(1,null,10)).thenReturn(new ReviewModels.Page(List.of(),null,false));
        mvc.perform(get("/api/v1/toilets/1/reviews")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get("/api/v1/reviews/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reviews/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/reviews/creation-status?toiletId=1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/reviews").header("Origin",ORIGIN).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
    }
    @Test void trustedMutationCarriesOnlyJwtOwnerAndAuthVersion() throws Exception {
        mvc.perform(post("/api/v1/reviews").header("Authorization","Bearer synthetic").header("Origin",ORIGIN)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(BODY))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","private, no-store"));
        verify(service).create(eq(new ReviewService.Actor(1,7)),any(),anyString());
    }
    @Test void missingOrSiblingOriginCannotMutateWithValidCookie() throws Exception {
        for(String origin:new String[]{"https://evil.example","https://admin.geupddong.com","null",""}) {
            mvc.perform(post("/api/v1/reviews/1/detach-author").header("Authorization","Bearer synthetic").header("Origin",origin)
                    .contentType("application/json").content("{\"version\":0,\"acknowledgeContentRetention\":true}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("REVIEW_ORIGIN_DENIED"));
        }
        verifyNoInteractions(service);
    }
    @Test void malformedPayloadNeverEchoesCoordinatesOrFreeText() throws Exception {
        mvc.perform(post("/api/v1/reviews").header("Authorization","Bearer synthetic").header("Origin",ORIGIN)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(BODY.replace("36.3","\"sensitive-invalid-location\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("REVIEW_INVALID_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive"))));
        verifyNoInteractions(service);
    }
    @Test void semanticFailuresKeepMachineReadableCode() throws Exception {
        when(service.detach(any(),eq(1L),any())).thenThrow(new ReviewFailure(409,"REVIEW_CHANGED","다시 불러와 주세요."));
        mvc.perform(post("/api/v1/reviews/1/detach-author").header("Authorization","Bearer synthetic").header("Origin",ORIGIN)
                .contentType("application/json").content("{\"version\":0,\"acknowledgeContentRetention\":true}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REVIEW_CHANGED"));
    }
    @Test void creationStatusAndConflictReturnOnlyTheAuthenticatedOwnersNavigationTarget() throws Exception {
        var state=new ReviewModels.CreationStatus(false,"42",java.time.OffsetDateTime.parse("2026-09-12T09:00:00+09:00"));
        when(service.creationStatus(new ReviewService.Actor(1,7),1)).thenReturn(state);
        mvc.perform(get("/api/v1/reviews/creation-status?toiletId=1").header("Authorization","Bearer synthetic"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.canCreate").value(false)).andExpect(jsonPath("$.existingReviewId").value("42"));
        when(service.create(any(),any(),any())).thenThrow(new ReviewFailure(409,"REVIEW_ALREADY_EXISTS","작성한 리뷰 내역이 있습니다.",state));
        mvc.perform(post("/api/v1/reviews").header("Authorization","Bearer synthetic").header("Origin",ORIGIN)
                .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(BODY))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("REVIEW_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.error.existingReviewId").value("42"))
                .andExpect(jsonPath("$.error.nextAllowedAt").exists());
    }
    @Test void preflightAllowsIdempotencyHeaderAndPatchFromPreview() throws Exception {
        mvc.perform(options("/api/v1/reviews/1").header("Origin",ORIGIN).header("Access-Control-Request-Method","PATCH")
                .header("Access-Control-Request-Headers","Content-Type,Idempotency-Key"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin",ORIGIN));
    }
    @Test void fractionalStarsMinutesIdsAndStringBooleansAreNotSilentlyCoerced() throws Exception {
        for(String invalid:new String[]{BODY.replace("\"satisfaction\":4","\"satisfaction\":4.5"),
                BODY.replace("\"waitMinutes\":20","\"waitMinutes\":20.5"),BODY.replace("\"toiletId\":1","\"toiletId\":1.5"),
                BODY.replace("\"paper\":true","\"paper\":\"true\""),BODY.replace("\"comment\":\"가상 본문\"","\"comment\":123")}) {
            mvc.perform(post("/api/v1/reviews").header("Authorization","Bearer synthetic").header("Origin",ORIGIN)
                    .header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("REVIEW_INVALID_REQUEST"));
        }
        verifyNoInteractions(service);
    }
}
