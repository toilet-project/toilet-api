package com.example.toiletapi.auth.controller;

import com.example.toiletapi.auth.model.AppUser;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.AuthTokenService;
import com.example.toiletapi.auth.service.RefreshTokenStore;
import com.example.toiletapi.auth.service.UserRolePolicyService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private static final String REFRESH_COOKIE = "geupddong_refresh";
    private final RefreshTokenStore refreshTokenStore;
    private final AuthTokenService tokenService;
    private final AppUserRepository userRepository;
    private final UserRolePolicyService rolePolicyService;

    public AuthController(RefreshTokenStore refreshTokenStore, AuthTokenService tokenService,
                          AppUserRepository userRepository, UserRolePolicyService rolePolicyService) {
        this.refreshTokenStore = refreshTokenStore;
        this.tokenService = tokenService;
        this.userRepository = userRepository;
        this.rolePolicyService = rolePolicyService;
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookie(request.getCookies(), REFRESH_COOKIE);
        Long userId = refreshToken == null ? null : refreshTokenStore.findUserId(refreshToken).orElse(null);
        if (userId == null) return ResponseEntity.status(401).build();
        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(401).build();
        tokenService.revoke(refreshToken);
        writeCookies(response, tokenService.issue(userId, List.copyOf(rolePolicyService.rolesOf(userId))));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public AuthProfileResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new AuthProfileResponse(jwt.getSubject(), jwt.getClaimAsStringList("roles"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = cookie(request.getCookies(), REFRESH_COOKIE);
        if (refreshToken != null) tokenService.revoke(refreshToken);
        expire(response, "geupddong_access", "/");
        expire(response, REFRESH_COOKIE, "/api/v1/auth");
        return ResponseEntity.noContent().build();
    }

    public static void writeCookies(HttpServletResponse response, AuthTokenService.IssuedTokens tokens) {
        add(response, "geupddong_access", tokens.accessToken(), Duration.between(java.time.Instant.now(), tokens.accessTokenExpiresAt()), "/");
        add(response, REFRESH_COOKIE, tokens.refreshToken(), tokens.refreshTokenTtl(), "/api/v1/auth");
    }
    private static void add(HttpServletResponse response, String name, String value, Duration ttl, String path) {
        response.addHeader("Set-Cookie", ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Lax")
                .path(path).maxAge(ttl).build().toString());
    }
    private static void expire(HttpServletResponse response, String name, String path) { add(response, name, "", Duration.ZERO, path); }
    private static String cookie(Cookie[] cookies, String name) {
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    public record AuthProfileResponse(String userId, List<String> roles) { }
}
