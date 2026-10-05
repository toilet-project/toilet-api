package com.example.toiletapi.growth;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.global.config.CorsConfig;
import java.time.Instant;
import java.time.LocalDateTime;
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

@WebMvcTest(value=GrowthController.class,properties={
    "spring.security.oauth2.client.registration.google.client-id=test-google-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({SecurityConfig.class,CorsConfig.class,GrowthBoundaryFilter.class,GrowthExceptionHandler.class})
class GrowthHistoryControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean GrowthService growth;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OAuthLoginSuccessHandler loginSuccess;
    private final GrowthService.Actor actor=new GrowthService.Actor(1,7);
    private final GrowthService.HistoryItem item=new GrowthService.HistoryItem(91,2,"EARN","DAILY_CHECKIN",LocalDateTime.of(2026,10,5,12,0));

    @BeforeEach void token() {
        when(decoder.decode("MEMBER")).thenReturn(Jwt.withTokenValue("MEMBER").header("alg","HS256")
                .subject("1").claim("roles",List.of("USER")).claim("auth_version",7)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build());
    }

    @Test void legacyRequestRetainsItemsOnlyAndNeverSharedCaches() throws Exception {
        when(growth.history(actor)).thenReturn(new GrowthService.History(List.of(item)));
        mvc.perform(get("/api/v1/growth/history").header("Authorization","Bearer MEMBER"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.items[0].id").value(91)).andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.page").doesNotExist()).andExpect(jsonPath("$.size").doesNotExist());
        verify(growth).history(actor);verifyNoMoreInteractions(growth);
    }

    @Test void pagedRequestPassesOnlyAuthenticatedActorAndReturnsCounts() throws Exception {
        when(growth.historyPage(actor,"earned",2,10)).thenReturn(new GrowthService.HistoryPage(List.of(item),31,2,10));
        mvc.perform(get("/api/v1/growth/history").header("Authorization","Bearer MEMBER")
                .param("direction","earned").param("page","2").param("size","10").param("userId","999"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.total").value(31)).andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10)).andExpect(jsonPath("$.items[0].reason").value("DAILY_CHECKIN"));
        verify(growth).historyPage(actor,"earned",2,10);verifyNoMoreInteractions(growth);
    }

    @Test void optionalPagingParametersHaveExplicitDefaults() throws Exception {
        when(growth.historyPage(actor,"deducted",0,10)).thenReturn(new GrowthService.HistoryPage(List.of(),0,0,10));
        when(growth.historyPage(actor,null,1,10)).thenReturn(new GrowthService.HistoryPage(List.of(),0,1,10));
        mvc.perform(get("/api/v1/growth/history").header("Authorization","Bearer MEMBER").param("direction","deducted"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(10));
        mvc.perform(get("/api/v1/growth/history").header("Authorization","Bearer MEMBER").param("page","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(1));
        verify(growth).historyPage(actor,"deducted",0,10);verify(growth).historyPage(actor,null,1,10);
    }

    @Test void historyRequiresAuthenticationAndInvalidNumericQueriesNeverReachTheService() throws Exception {
        mvc.perform(get("/api/v1/growth/history").param("direction","earned"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get("/api/v1/growth/history").header("Authorization","Bearer MEMBER").param("page","invalid"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"));
        verifyNoInteractions(growth);
    }

    @Test void pageValidationErrorsKeepThePrivateResponseBoundary() throws Exception {
        when(growth.historyPage(actor,"earned",0,51)).thenThrow(new GrowthFailure(400,"INVALID_GROWTH_HISTORY_PAGE","조회 개수를 확인해 주세요."));
        mvc.perform(get("/api/v1/growth/history").header("Authorization","Bearer MEMBER").param("direction","earned").param("size","51"))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(jsonPath("$.error.code").value("INVALID_GROWTH_HISTORY_PAGE"));
    }
}
