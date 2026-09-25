package com.example.toiletapi.auth.controller;

import com.example.toiletapi.auth.config.MobileOAuthSession;
import com.example.toiletapi.auth.model.UserStatus;
import com.example.toiletapi.auth.repository.AppUserRepository;
import com.example.toiletapi.auth.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth/mobile")
public class MobileAuthController {
    private final boolean enabled;
    private final MobileLoginCodeStore codes;
    private final RefreshTokenStore refreshTokens;
    private final AuthTokenService tokens;
    private final AppUserRepository users;
    private final UserRolePolicyService roles;

    public MobileAuthController(@Value("${auth.mobile.enabled:false}") boolean enabled, MobileLoginCodeStore codes,
            RefreshTokenStore refreshTokens, AuthTokenService tokens, AppUserRepository users, UserRolePolicyService roles) {
        this.enabled = enabled; this.codes = codes; this.refreshTokens = refreshTokens;
        this.tokens = tokens; this.users = users; this.roles = roles;
    }

    @GetMapping("/config")
    public ResponseEntity<?> config() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("enabled", enabled));
    }

    @GetMapping("/login/{provider}")
    public void login(@PathVariable String provider, @RequestParam String state,
            @RequestParam("code_challenge") String challenge, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        requireEnabled();
        if (!Set.of("google", "kakao").contains(provider)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        MobileOAuthSession.begin(request, state, challenge);
        response.sendRedirect("/oauth2/authorization/" + provider);
    }

    @PostMapping("/exchange")
    public ResponseEntity<SessionResponse> exchange(@RequestBody ExchangeRequest body) {
        requireEnabled();
        long userId = codes.consume(body.code(), body.codeVerifier());
        return session(issue(userId));
    }

    @PostMapping("/refresh")
    public ResponseEntity<SessionResponse> refresh(@RequestBody RefreshRequest body) {
        requireEnabled();
        validateRefresh(body.refreshToken());
        Long userId = refreshTokens.consume(body.refreshToken()).orElseThrow(RecoveryChallengeStore::expired);
        return session(issue(userId));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody RefreshRequest body) {
        requireEnabled();
        validateRefresh(body.refreshToken());
        tokens.revoke(body.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private AuthTokenService.IssuedTokens issue(long userId) {
        var user = users.findById(userId).orElseThrow(RecoveryChallengeStore::expired);
        if (user.getStatus() == UserStatus.WITHDRAWN || user.getStatus() == UserStatus.SUSPENDED)
            throw RecoveryChallengeStore.expired();
        return tokens.issue(userId, List.copyOf(roles.rolesOf(userId)));
    }
    private ResponseEntity<SessionResponse> session(AuthTokenService.IssuedTokens value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionResponse(
                value.accessToken(), value.refreshToken(), value.accessTokenExpiresAt()));
    }
    private void requireEnabled() { if (!enabled) throw new ResponseStatusException(HttpStatus.NOT_FOUND); }
    private void validateRefresh(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{64}")) throw RecoveryChallengeStore.expired();
    }
    public record ExchangeRequest(String code, String codeVerifier) { }
    public record RefreshRequest(String refreshToken) { }
    public record SessionResponse(String accessToken, String refreshToken, Instant accessTokenExpiresAt) { }
}
