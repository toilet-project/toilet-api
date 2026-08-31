package com.example.toiletapi.auth.service;

import java.time.Duration;
import java.util.Optional;

/**
 * 리프레시 토큰의 수명과 폐기를 관리하는 저장소 추상화다.
 *
 * <p>원문 토큰은 저장하지 않고 SHA-256 해시만 Redis 키로 사용한다.</p>
 */
public interface RefreshTokenStore {

    void save(Long userId, String rawToken, Duration ttl);

    Optional<Long> findUserId(String rawToken);

    void delete(String rawToken);

    void deleteAllForUser(Long userId);
}
