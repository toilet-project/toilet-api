package com.example.toiletapi.photo;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

class PhotoControllerTest {
    PhotoService photos=mock(PhotoService.class);PhotoProcessor processor=mock(PhotoProcessor.class);
    MockMvc mvc=MockMvcBuilders.standaloneSetup(new PhotoController(photos,processor,true))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).addFilters(new PhotoBoundaryFilter()).build();
    @BeforeEach void login() {
        var jwt=Jwt.withTokenValue("test").header("alg","none").subject("1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
    @AfterEach void logout() {SecurityContextHolder.clearContext();}
    @Test void settingRequiresTrustedOriginAndExplicitFields() throws Exception {
        for(String origin:new String[]{"https://evil.example","https://api.geupddong.com","null"})
            mvc.perform(patch("/api/v1/auth/me/photo").header("Origin",origin).contentType(MediaType.APPLICATION_JSON).content("{\"publicPhoto\":true}"))
                    .andExpect(status().isForbidden()).andExpect(header().string("Cloudflare-CDN-Cache-Control","no-store"));
        mvc.perform(patch("/api/v1/auth/me/photo").header("Origin","https://geupddong.com").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());verifyNoInteractions(photos);
        when(photos.visibility(1,false)).thenReturn(new PhotoService.State(true,false,"12345678-1234-1234-1234-123456789abc"));
        mvc.perform(patch("/api/v1/auth/me/photo").header("Origin","https://geupddong.com").contentType(MediaType.APPLICATION_JSON).content("{\"publicPhoto\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicPhoto").value(false));
    }
    @Test void boundedUploadAndDeleteUseExplicitOwnerActions() throws Exception {
        byte[] jpeg=new byte[]{1,2,3};byte[] webp="RIFFtestWEBP".getBytes();
        var ticket=new PhotoService.Ticket(1,0,1);
        when(photos.uploadTicket(1)).thenReturn(ticket);when(processor.convert(jpeg)).thenReturn(webp);
        when(photos.saveWithReceipt(eq(ticket),eq(webp),eq("DIRECT_UPLOAD"),eq(PhotoService.NOTICE_VERSION),any())).thenReturn(true);
        when(photos.state(1)).thenReturn(new PhotoService.State(true,false,"12345678-1234-1234-1234-123456789abc"));
        mvc.perform(put("/api/v1/auth/me/photo").header("Origin","https://geupddong.com").contentType(MediaType.IMAGE_JPEG).content(jpeg))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageVersion").isNotEmpty());
        when(photos.delete(1)).thenReturn(new PhotoService.State(true,false,null));
        mvc.perform(delete("/api/v1/auth/me/photo").header("Origin","https://geupddong.com"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageVersion").doesNotExist());
        mvc.perform(put("/api/v1/auth/me/photo").header("Origin","https://geupddong.com").contentType(MediaType.APPLICATION_OCTET_STREAM).content(jpeg))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(put("/api/v1/auth/me/photo").header("Origin","https://geupddong.com").contentType(MediaType.IMAGE_PNG).content(new byte[2*1024*1024+1]))
                .andExpect(status().isPayloadTooLarge());
    }
    @Test void publicDeniedResponsesAndImagesAreNeverCached() throws Exception {
        when(photos.reviewImage(2,3)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(get("/api/v1/toilets/2/reviews/3/photo")).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Robots-Tag","noindex, noimageindex"));
        when(photos.reviewImage(2,4)).thenReturn("RIFFtestWEBP".getBytes());
        mvc.perform(get("/api/v1/toilets/2/reviews/4/photo")).andExpect(status().isOk())
                .andExpect(content().contentType("image/webp")).andExpect(header().string("CDN-Cache-Control","no-store"));
    }
}
