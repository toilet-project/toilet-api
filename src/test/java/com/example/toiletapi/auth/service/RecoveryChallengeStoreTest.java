package com.example.toiletapi.auth.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RecoveryChallengeStoreTest {
    @Test void usesSeparateOpaqueRandomProofWithTenMinuteTtl() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(mock(org.springframework.data.redis.core.SetOperations.class));
        var store = new RecoveryChallengeStore(redis);
        String token = store.issue(3L, "generation");
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        verify(values).set("auth:recovery:" + token, "3:generation", java.time.Duration.ofMinutes(10));
        when(values.get("auth:recovery:" + token)).thenReturn("3:generation");
        assertThat(store.read(token)).isEqualTo(new RecoveryChallengeStore.Proof(3L, "generation"));
        when(values.get("auth:recovery:" + token)).thenReturn(null);
        assertThatThrownBy(() -> store.read(token)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> store.read("../bad")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
}
