package com.example.toiletapi.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Opaque, short-lived proof of a fresh OAuth login; never a normal access/refresh token. */
@Service
public class RecoveryChallengeStore {
    public static final Duration TTL = Duration.ofMinutes(10);
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    public RecoveryChallengeStore(StringRedisTemplate redis) { this.redis = redis; }
    public String issue(Long userId, String withdrawalKey) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), userId + ":" + withdrawalKey, TTL);
        redis.opsForSet().add(userKey(userId), token);
        redis.expire(userKey(userId), TTL);
        return token;
    }
    public Proof read(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw expired();
        String value = redis.opsForValue().get(key(token));
        if (value == null) throw expired();
        String[] parts = value.split(":", 2);
        if (parts.length != 2) throw expired();
        return new Proof(Long.valueOf(parts[0]), parts[1]);
    }
    public void delete(String token) { if (token != null) redis.delete(key(token)); }
    public void deleteAllForUser(Long userId) {
        var tokens = redis.opsForSet().members(userKey(userId));
        if (tokens != null && !tokens.isEmpty()) redis.delete(tokens.stream().map(this::key).toList());
        redis.delete(userKey(userId));
    }
    private String userKey(Long userId) { return "auth:recovery-user:" + userId; }
    private String key(String token) { return "auth:recovery:" + token; }
    public static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "확인 시간이 지났습니다. 소셜 로그인을 다시 진행해 주세요.");
    }
    public record Proof(Long userId, String withdrawalKey) { }
}
