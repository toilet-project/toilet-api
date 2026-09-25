package com.example.toiletapi.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Single-use, PKCE-bound handoff; access and refresh tokens never enter a URL. */
@Service
public class MobileLoginCodeStore {
    public static final Duration TTL = Duration.ofSeconds(90);
    private static final DefaultRedisScript<String> CONSUME = new DefaultRedisScript<>(
            "local v=redis.call('GET',KEYS[1]); if not v or string.sub(v,1,43)~=ARGV[1] then return nil end; "
                    + "redis.call('DEL',KEYS[1]); return v", String.class);
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    public MobileLoginCodeStore(StringRedisTemplate redis) { this.redis = redis; }

    public String issue(long userId, String challenge) {
        if (userId <= 0 || challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}"))
            throw new IllegalArgumentException("Invalid mobile login grant");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(code), challenge + ":" + userId, TTL);
        return code;
    }

    public long consume(String code, String verifier) {
        if (code == null || !code.matches("[A-Za-z0-9_-]{43}") || verifier == null
                || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) throw RecoveryChallengeStore.expired();
        String value = redis.execute(CONSUME, List.of(key(code)), challenge(verifier));
        if (value == null) throw RecoveryChallengeStore.expired();
        return Long.parseLong(value.substring(44));
    }

    public static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String key(String code) { return "auth:mobile-code:" + challenge(code); }
}
