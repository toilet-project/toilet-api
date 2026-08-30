package com.example.toiletapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisRefreshTokenStoreTest {

    @Test
    void shouldStoreOnlyHashedTokenKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        RedisRefreshTokenStore store = new RedisRefreshTokenStore(redisTemplate);

        store.save(42L, "raw-refresh-token", Duration.ofDays(14));

        verify(operations).set(
                org.mockito.ArgumentMatchers.argThat(key -> key.startsWith("auth:refresh-token:")
                        && !key.contains("raw-refresh-token")),
                eq("42"), eq(Duration.ofDays(14)));
    }

    @Test
    void shouldReturnEmptyWhenNoTokenExists() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);
        when(operations.get(any())).thenReturn(null);
        RedisRefreshTokenStore store = new RedisRefreshTokenStore(redisTemplate);

        assertThat(store.findUserId("raw-refresh-token")).isEmpty();
    }

    @Test
    void shouldRejectInvalidLifetime() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        RedisRefreshTokenStore store = new RedisRefreshTokenStore(redisTemplate);

        assertThatThrownBy(() -> store.save(42L, "raw-refresh-token", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("리프레시 토큰 만료 시간은 양수여야 합니다.");
    }
}
