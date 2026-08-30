package com.example.toiletapi.auth.config;

import com.example.toiletapi.auth.controller.AuthController;
import com.example.toiletapi.auth.controller.OAuthLoginRedirectController;
import com.example.toiletapi.auth.service.AuthTokenService;
import com.example.toiletapi.auth.service.OAuthLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

@Component
public class OAuthLoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {
    private final OAuthLoginService loginService;
    private final AuthTokenService tokenService;
    private final String frontendBaseUrl;
    public OAuthLoginSuccessHandler(OAuthLoginService loginService, AuthTokenService tokenService,
                                    @Value("${auth.frontend-base-url}") String frontendBaseUrl) {
        this.loginService = loginService; this.tokenService = tokenService; this.frontendBaseUrl = frontendBaseUrl;
    }
    @Override public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                   Authentication authentication) throws IOException {
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OAuthLoginService.LoginUser user = loginService.login(oauth.getAuthorizedClientRegistrationId(), oauth.getPrincipal());
        AuthController.writeCookies(response, tokenService.issue(user.userId(), user.roles()));
        Object returnUrl = request.getSession(false) == null ? null
                : request.getSession(false).getAttribute(OAuthLoginRedirectController.RETURN_URL_SESSION_ATTRIBUTE);
        if (request.getSession(false) != null) request.getSession(false).removeAttribute(OAuthLoginRedirectController.RETURN_URL_SESSION_ATTRIBUTE);
        String targetUrl = returnUrl instanceof String value ? value : frontendBaseUrl + "/?login=success";
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
