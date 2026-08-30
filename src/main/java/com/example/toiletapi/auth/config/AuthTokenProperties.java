package com.example.toiletapi.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.jwt")
public record AuthTokenProperties(String secret, Duration accessTokenTtl, Duration refreshTokenTtl) {
}
