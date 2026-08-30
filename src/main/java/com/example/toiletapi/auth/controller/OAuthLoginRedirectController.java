package com.example.toiletapi.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** OAuth 로그인 시작 전, 허용된 화면으로의 복귀 목적지만 세션에 보관한다. */
@Controller
@RequestMapping("/api/v1/auth/login")
public class OAuthLoginRedirectController {
    public static final String RETURN_URL_SESSION_ATTRIBUTE = "oauth.login.return-url";
    private static final String ADMIN_URL = "https://admin.geupddong.com";
    private static final Set<String> PROVIDERS = Set.of("google", "kakao");

    @GetMapping("/{provider}")
    public void login(@PathVariable String provider, @RequestParam(defaultValue = "home") String returnTo,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!PROVIDERS.contains(provider)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if ("admin".equals(returnTo)) request.getSession(true).setAttribute(RETURN_URL_SESSION_ATTRIBUTE, ADMIN_URL);
        response.sendRedirect("/oauth2/authorization/" + provider);
    }
}
