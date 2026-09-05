package com.example.toiletapi.auth.controller;

import com.example.toiletapi.auth.config.OAuthReturnTargets;

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
    public static final String RETURN_URL_SESSION_ATTRIBUTE = OAuthReturnTargets.SESSION_ATTRIBUTE;
    private static final Set<String> PROVIDERS = Set.of("google", "kakao");

    @GetMapping("/{provider}")
    public void login(@PathVariable String provider, @RequestParam(defaultValue = "home") String returnTo,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!PROVIDERS.contains(provider)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (request.getSession(false) != null) request.getSession(false).removeAttribute(RETURN_URL_SESSION_ATTRIBUTE);
        switch (returnTo) {
            case "admin" -> request.getSession(true).setAttribute(RETURN_URL_SESSION_ATTRIBUTE, OAuthReturnTargets.ADMIN);
            case "preview" -> request.getSession(true).setAttribute(RETURN_URL_SESSION_ATTRIBUTE, OAuthReturnTargets.PREVIEW);
            case "home" -> { }
            default -> { response.sendError(HttpServletResponse.SC_BAD_REQUEST); return; }
        }
        response.sendRedirect("/oauth2/authorization/" + provider);
    }
}
