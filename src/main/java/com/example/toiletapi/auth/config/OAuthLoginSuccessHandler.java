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
    private final com.example.toiletapi.auth.service.RecoveryChallengeStore recoveryChallenges;
    private final com.example.toiletapi.photo.PhotoSync photos;
    public OAuthLoginSuccessHandler(OAuthLoginService loginService, AuthTokenService tokenService,
                                    @Value("${auth.frontend-base-url}") String frontendBaseUrl,
                                    com.example.toiletapi.auth.service.RecoveryChallengeStore recoveryChallenges,
                                    com.example.toiletapi.photo.PhotoSync photos) {
        this.loginService = loginService; this.tokenService = tokenService; this.frontendBaseUrl = frontendBaseUrl;
        this.recoveryChallenges = recoveryChallenges;
        this.photos = photos;
    }
    @Override public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                                   Authentication authentication) throws IOException {
        OAuth2AuthenticationToken oauth = (OAuth2AuthenticationToken) authentication;
        OAuthLoginService.LoginUser user = loginService.login(oauth.getAuthorizedClientRegistrationId(), oauth.getPrincipal());
        String returnUrl = OAuthReturnTargets.consume(request, frontendBaseUrl);
        // The OAuth session must not act as an authenticated service session for withdrawn users.
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        if (user.recoveryKey() != null) {
            AuthController.clearCookies(response);
            com.example.toiletapi.auth.controller.AccountRecoveryController.writeRecoveryCookie(response,
                    recoveryChallenges.issue(user.userId(), user.recoveryKey()),
                    com.example.toiletapi.auth.service.RecoveryChallengeStore.TTL);
            String home = OAuthReturnTargets.PREVIEW.equals(returnUrl) ? returnUrl : frontendBaseUrl;
            getRedirectStrategy().sendRedirect(request, response, home + "/?recovery=required");
            return;
        }
        AuthController.writeCookies(response, tokenService.issue(user.userId(), user.roles()));
        if (!user.consentRequired()) photos.login(user.userId(), oauth.getAuthorizedClientRegistrationId(), oauth.getPrincipal().getAttributes());
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
