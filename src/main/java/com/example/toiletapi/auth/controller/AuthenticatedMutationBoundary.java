package com.example.toiletapi.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

/** Cookie-based browser writes retain their origin guard; native clients supply an explicit verified bearer. */
public final class AuthenticatedMutationBoundary {
    private AuthenticatedMutationBoundary() { }

    public static void requireTrustedOriginOrBearer(HttpServletRequest request, Jwt authenticatedJwt) {
        String authorization = request.getHeader("Authorization");
        if (request.getHeader("Origin") == null && authenticatedJwt != null
                && authorization != null && authorization.equals("Bearer " + authenticatedJwt.getTokenValue())) return;
        AccountRecoveryController.requireTrustedOrigin(request);
    }
}
