package com.example.toiletapi.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

public class OAuthLoginFailureHandler implements AuthenticationFailureHandler {
    private final String home;
    public OAuthLoginFailureHandler(String home) { this.home = home; }

    @Override public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                                 AuthenticationException exception) throws IOException {
        // 원인/인가 코드/토큰을 URL에 노출하지 않는다.
        var mobile = MobileOAuthSession.consume(request);
        if (mobile != null) {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            if (request.getSession(false) != null) request.getSession(false).invalidate();
            response.setHeader("Cache-Control", "no-store");
            response.sendRedirect(mobile.errorUrl("login_failed"));
            return;
        }
        response.sendRedirect(OAuthReturnTargets.consume(request, home) + "/?login=failed");
    }
}
