package com.example.toiletapi.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AuthTokenPropertiesTest {
    @Test
    void bindsSeparateUserAndAdministratorAccessTokenDurations() {
        var source = new MapConfigurationPropertySource(Map.of(
                "auth.jwt.secret", "test-secret",
                "auth.jwt.access-token-ttl", "15m",
                "auth.jwt.admin-access-token-ttl", "30m",
                "auth.jwt.refresh-token-ttl", "14d"));

        AuthTokenProperties properties = new Binder(source)
                .bind("auth.jwt", Bindable.of(AuthTokenProperties.class)).get();

        assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.adminAccessTokenTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(14));
    }
}
