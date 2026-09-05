package com.example.toiletapi.auth.config;

import com.example.toiletapi.auth.controller.AuthController;
import com.example.toiletapi.auth.service.AuthTokenService;
import com.example.toiletapi.auth.service.OAuthLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

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
        String returnUrl = OAuthReturnTargets.consume(request, frontendBaseUrl);
        String targetUrl;
        if (user.consentRequired()) {
            String returnTarget = OAuthReturnTargets.ADMIN.equals(returnUrl) ? "admin" : null;
            String consentBase = OAuthReturnTargets.PREVIEW.equals(returnUrl) ? returnUrl : frontendBaseUrl;
            targetUrl = UriComponentsBuilder.fromUriString(consentBase)
                    .path("/")
                    .queryParam("login", "success")
                    .queryParam("consent", "required")
                    .queryParamIfPresent("returnTo", Optional.ofNullable(returnTarget))
                    .build().encode().toUriString();
        } else {
            targetUrl = OAuthReturnTargets.ADMIN.equals(returnUrl) ? returnUrl : returnUrl + "/?login=success";
        }
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
