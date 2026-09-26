package com.example.toiletapi.places;

import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.global.config.CorsConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = PlaceSearchController.class, properties = {
    "spring.security.oauth2.client.registration.google.client-id=test-google-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({SecurityConfig.class, CorsConfig.class})
class PlaceSearchControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PlaceSearchService service;
    @MockitoBean OAuthLoginSuccessHandler oauthLoginSuccessHandler;
    @MockitoBean JwtDecoder jwtDecoder;
    @Test void publicReadOnlyPostHasNoStoreAndDoesNotRequireLogin() throws Exception {
        when(service.search("대전역")).thenReturn(new PlaceSearchService.Results("kakao", List.of()));
        mvc.perform(post("/api/v1/places/search").contentType("application/json").content("{\"query\":\"대전역\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.provider").value("kakao")).andExpect(jsonPath("$.items").isEmpty());
        verify(service).search("대전역");
    }
    @Test void missingOrOversizedBodyIsRejected() throws Exception {
        for (String body : List.of("{}", "{\"query\":\"\"}", "{\"query\":\"" + "가".repeat(161) + "\"}"))
            mvc.perform(post("/api/v1/places/search").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void grantIsLimitedToExactReadOnlyPost() throws Exception {
        mvc.perform(get("/api/v1/places/search")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/places/admin").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
