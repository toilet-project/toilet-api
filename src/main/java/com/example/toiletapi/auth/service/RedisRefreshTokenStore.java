package com.example.toiletapi.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis에 리프레시 토큰 해시와 사용자 식별자만 보관한다. */
@Service
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "auth:refresh-token:";

    private final StringRedisTemplate redisTemplate;

    public RedisRefreshTokenStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void save(Long userId, String rawToken, Duration ttl) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 식별자는 양수여야 합니다.");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("리프레시 토큰 만료 시간은 양수여야 합니다.");
        }

        redisTemplate.opsForValue().set(key(rawToken), userId.toString(), ttl);
    }

    @Override
    public Optional<Long> findUserId(String rawToken) {
        String userId = redisTemplate.opsForValue().get(key(rawToken));
        if (userId == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.parseLong(userId));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String rawToken) {
        redisTemplate.delete(key(rawToken));
    }

    private String key(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("리프레시 토큰이 비어 있습니다.");
        }

        return KEY_PREFIX + sha256(rawToken);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
