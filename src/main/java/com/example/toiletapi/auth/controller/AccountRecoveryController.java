package com.example.toiletapi.auth.controller;

import com.example.toiletapi.auth.service.*;
import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.global.time.KoreanTime;
import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/v1/auth/recovery")
public class AccountRecoveryController {
    private static final String COOKIE = "geupddong_recovery";
    private final RecoveryChallengeStore challenges;
    private final AccountRecoveryService recovery;
    private final AccountErasureService erasure;
    private final AuthTokenService tokens;
    private final AccountLifecycleGate lifecycle;
    public AccountRecoveryController(RecoveryChallengeStore challenges, AccountRecoveryService recovery,
            AccountErasureService erasure, AuthTokenService tokens, AccountLifecycleGate lifecycle) {
        this.challenges = challenges; this.recovery = recovery; this.erasure = erasure; this.tokens = tokens;
        this.lifecycle = lifecycle;
    }
    @GetMapping
    public ResponseEntity<AccountRecoveryService.RecoveryStatus> status(HttpServletRequest request) {
        lifecycle.requireRecovery();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(recovery.status(challenges.read(cookie(request))));
    }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> decide(@RequestBody Decision decision, HttpServletRequest request, HttpServletResponse response) {
        requireTrustedOrigin(request);
        lifecycle.requireRecovery();
        if ("ERASE".equals(decision.action())) lifecycle.requireErasure();
        String token = cookie(request);
        var proof = challenges.read(token);
        if ("RESTORE".equals(decision.action())) {
            Long id = recovery.confirm(proof);
            // If token issuance fails after commit, a new OAuth login works; the old challenge is no longer valid.
            AuthController.writeCookies(response, tokens.issue(id, List.of(Role.USER)));
        } else if ("ERASE".equals(decision.action())) {
            Long id = recovery.requestImmediateErasure(proof);
            AuthController.clearCookies(response);
            try { erasure.eraseIfDue(id, KoreanTime.now()); }
            catch (Exception failure) {
                erasure.recordFailure(id);
                challenges.delete(token); writeRecoveryCookie(response, "", Duration.ZERO);
                return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
            }
        } else throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "처리 방법을 선택해 주세요.");
        challenges.delete(token);
        writeRecoveryCookie(response, "", Duration.ZERO);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @DeleteMapping
    public ResponseEntity<Void> cancel(HttpServletRequest request, HttpServletResponse response) {
        requireTrustedOrigin(request);
        challenges.delete(cookie(request)); writeRecoveryCookie(response, "", Duration.ZERO);
        return ResponseEntity.noContent().build();
    }
    public static void requireTrustedOrigin(HttpServletRequest request) {
        // Do not trust same-site alone: a compromised sibling origin must not restore/erase accounts.
        if (!Set.of("https://geupddong.com", "https://www.geupddong.com", "https://preview.geupddong.com",
                "https://admin.geupddong.com").contains(String.valueOf(request.getHeader("Origin")))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "허용되지 않은 요청 출처입니다.");
        }
    }
    public static void writeRecoveryCookie(HttpServletResponse response, String token, Duration ttl) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, token).httpOnly(true).secure(true)
                .sameSite("Lax").path("/api/v1/auth/recovery").maxAge(ttl).build().toString());
    }
    private String cookie(HttpServletRequest request) {
        if (request.getCookies() != null) for (Cookie cookie : request.getCookies()) {
            if (COOKIE.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }
    public record Decision(String action) { }
}
