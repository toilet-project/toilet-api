package com.example.toiletapi.auth.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.toiletapi.auth.controller.OAuthLoginRedirectController;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.service.AuthTokenService;
import com.example.toiletapi.auth.service.OAuthLoginService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class PreviewOAuthTest {
    private final String home = "https://geupddong.com";

    @Test void previewStartAndHomeClearStaleTarget() throws Exception {
        var controller=new OAuthLoginRedirectController();
        var request=new MockHttpServletRequest();
        var response=new MockHttpServletResponse();
        controller.login("google","preview",request,response);
        assertEquals("/oauth2/authorization/google",response.getRedirectedUrl());
        assertEquals(OAuthReturnTargets.PREVIEW,request.getSession().getAttribute(OAuthReturnTargets.SESSION_ATTRIBUTE));
        controller.login("kakao","home",request,new MockHttpServletResponse());
        assertNull(request.getSession().getAttribute(OAuthReturnTargets.SESSION_ATTRIBUTE));
    }

    @Test void externalTargetsAndUnknownProvidersRejected() throws Exception {
        var controller=new OAuthLoginRedirectController();
        for(String target:List.of("https://evil.example","//evil.example","preview.evil","PREVIEW")) {
            var response=new MockHttpServletResponse();
            controller.login("google",target,new MockHttpServletRequest(),response);
            assertEquals(400,response.getStatus());
            assertNull(response.getRedirectedUrl());
        }
        var response=new MockHttpServletResponse();
        controller.login("evil","preview",new MockHttpServletRequest(),response);
        assertEquals(404,response.getStatus());
    }

    @Test void existingPreviewUserReturnsToPreview() throws Exception {
        assertSuccess(OAuthReturnTargets.PREVIEW,false,OAuthReturnTargets.PREVIEW+"/?login=success");
    }
    @Test void previewSignupConsentStaysOnPreview() throws Exception {
        assertSuccess(OAuthReturnTargets.PREVIEW,true,OAuthReturnTargets.PREVIEW+"/?login=success&consent=required");
    }
    @Test void existingHomeAndAdminBehaviorRetained() throws Exception {
        assertSuccess(null,false,home+"/?login=success");
        assertSuccess(OAuthReturnTargets.ADMIN,false,OAuthReturnTargets.ADMIN);
        assertSuccess(OAuthReturnTargets.ADMIN,true,home+"/?login=success&consent=required&returnTo=admin");
    }
    @Test void tamperedSessionCannotRedirectExternally() throws Exception {
        assertSuccess("https://evil.example",false,home+"/?login=success");
    }
    @Test void failureReturnsSafelyAndConsumesTarget() throws Exception {
        for(String target:List.of(OAuthReturnTargets.PREVIEW,OAuthReturnTargets.ADMIN,"https://evil.example")) {
            var request=new MockHttpServletRequest();
            request.getSession().setAttribute(OAuthReturnTargets.SESSION_ATTRIBUTE,target);
            var response=new MockHttpServletResponse();
            new OAuthLoginFailureHandler(home).onAuthenticationFailure(request,response,new BadCredentialsException("never expose this"));
            assertEquals((target.contains("evil")?home:target)+"/?login=failed",response.getRedirectedUrl());
            assertNull(request.getSession().getAttribute(OAuthReturnTargets.SESSION_ATTRIBUTE));
        }
    }

    private void assertSuccess(String target,boolean consent,String expected) throws Exception {
        var login=mock(OAuthLoginService.class);
        var tokens=mock(AuthTokenService.class);
        var principal=new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),Map.of("sub","test"),"sub");
        when(login.login("google",principal)).thenReturn(new OAuthLoginService.LoginUser(1L,List.of(Role.USER),consent));
        when(tokens.issue(1L,List.of(Role.USER))).thenReturn(new AuthTokenService.IssuedTokens("test-access","test-refresh",Instant.now().plusSeconds(60),Duration.ofDays(1)));
        var request=new MockHttpServletRequest();
        if(target!=null) request.getSession().setAttribute(OAuthReturnTargets.SESSION_ATTRIBUTE,target);
        var response=new MockHttpServletResponse();
        new OAuthLoginSuccessHandler(login,tokens,home).onAuthenticationSuccess(request,response,new OAuth2AuthenticationToken(principal,principal.getAuthorities(),"google"));
        assertEquals(expected,response.getRedirectedUrl());
        assertTrue(request.getSession(false)==null || request.getSession().getAttribute(OAuthReturnTargets.SESSION_ATTRIBUTE)==null);
        assertEquals(2,response.getHeaders("Set-Cookie").size());
        for(String cookie:response.getHeaders("Set-Cookie")) {
            assertTrue(cookie.contains("HttpOnly") && cookie.contains("Secure") && cookie.contains("SameSite=Lax"));
            assertFalse(cookie.contains("Domain="));
        }
    }
}
