package com.example.toiletapi.auth.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

/** Authorization 헤더를 우선 사용하고, 웹 로그인 후의 HttpOnly 쿠키도 지원한다. */
public class CookieBearerTokenResolver implements BearerTokenResolver {
    @Override
    public String resolve(HttpServletRequest request) {
        // These endpoints authenticate with OAuth state, recovery proof or refresh cookie,
        // not the previous access token. permitAll alone does not skip JWT authentication:
        // a revoked/expired cookie would otherwise block even a fresh login attempt.
        if (usesIndependentCredentials(request)) return null;
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) return authorization.substring(7);
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie cookie : cookies) {
            if ("geupddong_access".equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private boolean usesIndependentCredentials(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if ("GET".equals(method) && switch (path) {
            case "/api/v1/auth/login/google", "/api/v1/auth/login/kakao",
                 "/oauth2/authorization/google", "/oauth2/authorization/kakao",
                 "/login/oauth2/code/google", "/login/oauth2/code/kakao" -> true;
            default -> false;
        }) return true;
        if ("/api/v1/auth/recovery".equals(path)) {
            return "GET".equals(method) || "POST".equals(method) || "DELETE".equals(method);
        }
        return "POST".equals(method)
                && ("/api/v1/auth/refresh".equals(path) || "/api/v1/auth/logout".equals(path));
    }
}
