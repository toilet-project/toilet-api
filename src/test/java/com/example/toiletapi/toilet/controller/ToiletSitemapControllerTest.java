package com.example.toiletapi.toilet.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.toiletapi.auth.config.SecurityConfig;
import com.example.toiletapi.auth.config.OAuthLoginSuccessHandler;
import com.example.toiletapi.global.config.CorsConfig;
import com.example.toiletapi.toilet.service.ToiletSitemapService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value=ToiletSitemapController.class,properties={
    "spring.security.oauth2.client.registration.google.client-id=test-google-client",
    "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
    "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
    "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret"
})
@Import({CorsConfig.class,SecurityConfig.class})
class ToiletSitemapControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ToiletSitemapService service;
    @MockitoBean OAuthLoginSuccessHandler oauthLoginSuccessHandler;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test void anonymousIdOnlyProjectionIsPublicAndNoindex() throws Exception {
        when(service.shards()).thenReturn(List.of(0L,90L));
        when(service.ids(90)).thenReturn(List.of(900001L));
        mvc.perform(get("/api/v1/toilets/sitemap/shards")).andExpect(status().isOk())
            .andExpect(content().json("[0,90]")).andExpect(header().string("X-Robots-Tag","noindex"));
        mvc.perform(get("/api/v1/toilets/sitemap/ids").param("shard","90"))
            .andExpect(status().isOk()).andExpect(content().json("[900001]"));
    }
    @Test void localizedEntriesExposeOnlyPublicSearchProjection() throws Exception {
        when(service.localizedShards("en")).thenReturn(List.of(0L));
        when(service.entries(0,"en")).thenReturn(List.of(
                new ToiletSitemapService.SitemapEntry(1,"English restroom",null,null)));
        mvc.perform(get("/api/v1/toilets/sitemap/shards").param("locale","en"))
                .andExpect(status().isOk()).andExpect(content().json("[0]"));
        mvc.perform(get("/api/v1/toilets/sitemap/entries").param("shard","0").param("locale","en"))
                .andExpect(status().isOk()).andExpect(content().json("[{\"id\":1,\"name\":\"English restroom\"}]"))
                .andExpect(header().string("X-Robots-Tag","noindex"));
    }
    @Test void invalidParametersFailInsteadOfDumpingAllIds() throws Exception {
        when(service.ids(-1)).thenThrow(new IllegalArgumentException("Invalid sitemap shard"));
        mvc.perform(get("/api/v1/toilets/sitemap/ids")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/toilets/sitemap/ids").param("shard","nope")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/toilets/sitemap/ids").param("shard","-1")).andExpect(status().isBadRequest());
        verify(service,never()).shards();
    }
}
