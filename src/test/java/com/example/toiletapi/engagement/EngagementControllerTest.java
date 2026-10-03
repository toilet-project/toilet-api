package com.example.toiletapi.engagement;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.toiletapi.auth.config.*;
import com.example.toiletapi.global.config.CorsConfig;
import java.time.Instant;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value=EngagementController.class,properties={"engagement.enabled=true","engagement.secret=synthetic-test-secret-at-least-32-characters",
 "spring.security.oauth2.client.registration.google.client-id=test","spring.security.oauth2.client.registration.google.client-secret=test",
 "spring.security.oauth2.client.registration.kakao.client-id=test","spring.security.oauth2.client.registration.kakao.client-secret=test"})
@Import({SecurityConfig.class,CorsConfig.class,EngagementConfiguration.class,EngagementBoundaryFilter.class,EngagementExceptionHandler.class})
class EngagementControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EngagementService service;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean OAuthLoginSuccessHandler login;
    static final String ORIGIN="https://geupddong.com", OWN="/api/v1/engagement/toilets/1/like";
    @BeforeEach void setup(){
        when(decoder.decode("fixture")).thenReturn(Jwt.withTokenValue("fixture").header("alg","HS256").subject("2").claim("auth_version",3).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
        when(service.hash(anyString())).thenReturn("safe-session-hash");
    }
    @Test void publicCountsButEveryPersonalPathRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/toilets/1/engagement")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get(OWN)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/engagement/likes")).andExpect(status().isUnauthorized());
        mvc.perform(put(OWN).header("Origin",ORIGIN)).andExpect(status().isUnauthorized());
        mvc.perform(delete(OWN).header("Origin",ORIGIN)).andExpect(status().isUnauthorized());
        verify(service,never()).setLike(anyLong(),any(),anyBoolean());
    }
    @Test void likedListUsesAuthenticatedActorAndIsNeverPubliclyCacheable() throws Exception {
        mvc.perform(get("/api/v1/engagement/likes").header("Authorization","Bearer fixture")
                .param("sort","distance").param("latitude","37.5").param("longitude","127"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
        verify(service).likedToilets(new EngagementService.Actor(2,3),"distance",0,30,37.5,127.0);
    }
    @Test void ownerComesFromVerifiedJwtAndCookieWritesRequireTrustedOrigin() throws Exception {
        mvc.perform(put(OWN).header("Origin",ORIGIN).header("Authorization","Bearer fixture")).andExpect(status().isOk());
        verify(service).setLike(1,new EngagementService.Actor(2,3),true);
        mvc.perform(delete(OWN).header("Authorization","Bearer fixture")).andExpect(status().isOk());
        verify(service).setLike(1,new EngagementService.Actor(2,3),false);
        mvc.perform(put(OWN).cookie(new jakarta.servlet.http.Cookie("geupddong_access","fixture"))).andExpect(status().isForbidden());
        mvc.perform(put(OWN).header("Origin","https://preview.geupddong.com").header("Authorization","Bearer fixture")).andExpect(status().isForbidden());
    }
    @Test void viewEndpointAcceptsAnonymousOriginAndDetectsBotsButRejectsPreviewToProduction() throws Exception {
        String body="{\"sessionId\":\"00000000-0000-4000-8000-000000000001\",\"eventId\":\"00000000-0000-4000-8000-000000000002\"}";
        mvc.perform(post("/api/v1/toilets/1/views").header("Origin",ORIGIN).header("User-Agent","Mozilla/5.0").contentType("application/json").content(body)).andExpect(status().isOk());
        verify(service).view(eq(1L),any(),eq(false));
        mvc.perform(post("/api/v1/toilets/1/views").header("Origin",ORIGIN).header("User-Agent","Googlebot").contentType("application/json").content(body)).andExpect(status().isOk());
        verify(service).view(eq(1L),any(),eq(true));
        mvc.perform(post("/api/v1/toilets/1/views").header("Origin","https://preview.geupddong.com").contentType("application/json").content(body)).andExpect(status().isForbidden());
    }
}
