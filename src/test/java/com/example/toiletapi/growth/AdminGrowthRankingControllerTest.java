package com.example.toiletapi.growth;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.method.support.*;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.server.ResponseStatusException;

class AdminGrowthRankingControllerTest {
    AdminGrowthAccess access;AdminGrowthRankingService service;MockMvc mvc;
    Jwt jwt=Jwt.withTokenValue("verified-test-token").header("alg","HS256").subject("1").claim("auth_version",0).build();
    final String path="/api/admin/v1/growth/ranking/search";
    @BeforeEach void setup() {
        access=mock(AdminGrowthAccess.class);service=mock(AdminGrowthRankingService.class);
        when(service.search(any())).thenReturn(new AdminGrowthRankingModels.Page(List.of(),0,10,0,0,"ACTIVE_AND_SUSPENDED",List.of("PENDING_CONSENT","WITHDRAWN")));
        mvc=MockMvcBuilders.standaloneSetup(new AdminGrowthRankingController(access,service))
                .setControllerAdvice(new AdminGrowthRankingExceptionHandler()).addFilters(new AdminGrowthRankingBoundaryFilter())
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    public boolean supportsParameter(MethodParameter parameter) {return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);}
                    public Object resolveArgument(MethodParameter p,ModelAndViewContainer m,NativeWebRequest r,WebDataBinderFactory f) {return jwt;}
                }).build();
    }
    @Test void trustedAdminSearchIsPrivateAndRechecksCurrentDatabaseAccess() throws Exception {
        mvc.perform(post(path).header("Origin","https://admin.geupddong.com").contentType(MediaType.APPLICATION_JSON)
                .content("{\"keyword\":\"synthetic@example.test\",\"sort\":\"xp_desc\",\"page\":0,\"size\":10}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().string("Vary",org.hamcrest.Matchers.containsString("Authorization")));
        verify(access).requireAdmin(jwt);verify(service).search(any());
    }
    @Test void siblingOriginsMissingOriginAndMismatchedBearerCannotSearch() throws Exception {
        for(String origin:List.of("https://geupddong.com","https://preview.geupddong.com","https://evil.example"))
            mvc.perform(post(path).header("Origin",origin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control",org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization","Bearer forged").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(access,service);
    }
    @Test void onlyVerifiedNativeBearerPassesSecondBoundaryAndRevokedAdminGetsPrivateError() throws Exception {
        mvc.perform(post(path).header("Authorization","Bearer verified-test-token").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"fixed")).when(access).requireAdmin(jwt);
        mvc.perform(get("/api/admin/v1/growth/ranking/distribution")).andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control","private, no-store")).andExpect(jsonPath("$.error.code").value("ACCESS_DENIED"));
        verify(service,never()).distribution();
    }
    @Test void queryStringsAndMalformedPrivateBodiesAreRejectedWithoutEcho() throws Exception {
        mvc.perform(post(path+"?keyword=synthetic%40example.test").header("Origin","https://admin.geupddong.com").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        for(String json:List.of("{\"keyword\":\"synthetic@example.test\",\"page\":1.5}","{\"page\":\"2\"}","{\"page\":2147483648}","{\"keyword\":\"synthetic@example.test\",\"unexpected\":true}","{\"page\":1,\"page\":2}","{\"keyword\": [\"synthetic@example.test\"]}")) {
            var result=mvc.perform(post(path).header("Origin","https://admin.geupddong.com").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest()).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains("synthetic@example.test"));
        }
        verifyNoInteractions(service);
    }
    @Test void actualBodyLimitAlsoRejectsUnknownChunkedContentLength() throws Exception {
        var request=new MockHttpServletRequest("POST",path) {
            @Override public long getContentLengthLong() {return -1;}
            @Override public int getContentLength() {return -1;}
        };
        request.setRequestURI(path);request.addHeader("Origin","https://admin.geupddong.com");request.setContent(new byte[4097]);
        var response=new MockHttpServletResponse();var chain=new MockFilterChain();
        new AdminGrowthRankingBoundaryFilter().doFilter(request,response,chain);
        assertEquals(413,response.getStatus());assertEquals("private, no-store",response.getHeader("Cache-Control"));assertNull(chain.getRequest());
    }
    @Test void jdbcFailuresNeverIncludePrivateArgumentsOrDiagnostics() throws Exception {
        when(service.search(any())).thenThrow(new org.springframework.jdbc.BadSqlGrammarException("private@example.test","private@example.test",new java.sql.SQLException("private@example.test")));
        var result=mvc.perform(post(path).header("Origin","https://admin.geupddong.com").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("XP_RANKING_UNAVAILABLE")).andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("private@example.test"));
    }
}
