package com.example.toiletapi.auth.service;

import com.example.toiletapi.auth.config.AuthTokenProperties;
import com.example.toiletapi.auth.model.Role;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {
    private final JwtEncoder jwtEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthTokenProperties properties;
    private final com.example.toiletapi.auth.repository.AppUserRepository users;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthTokenService(JwtEncoder jwtEncoder, RefreshTokenStore refreshTokenStore, AuthTokenProperties properties,
                            com.example.toiletapi.auth.repository.AppUserRepository users) {
        this.jwtEncoder = jwtEncoder;
        this.refreshTokenStore = refreshTokenStore;
        this.properties = properties;
        this.users = users;
    }

    @org.springframework.transaction.annotation.Transactional
    public IssuedTokens issue(Long userId, List<Role> roles) {
        var user = users.lockById(userId).orElseThrow(RecoveryChallengeStore::expired);
        if (user.getStatus() == com.example.toiletapi.auth.model.UserStatus.WITHDRAWN
                || user.getStatus() == com.example.toiletapi.auth.model.UserStatus.SUSPENDED) throw RecoveryChallengeStore.expired();
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl());
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                JwtClaimsSet.builder().subject(userId.toString()).issuedAt(issuedAt).expiresAt(expiresAt)
                        .claim("auth_version", user.getAuthVersion())
                        .claim("roles", roles.stream().map(Role::name).toList()).build()
        )).getTokenValue();
        String refreshToken = randomToken();
        refreshTokenStore.save(userId, refreshToken, properties.refreshTokenTtl());
        return new IssuedTokens(accessToken, refreshToken, expiresAt, properties.refreshTokenTtl());
    }

    public void revoke(String refreshToken) { refreshTokenStore.delete(refreshToken); }

    private String randomToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record IssuedTokens(String accessToken, String refreshToken, Instant accessTokenExpiresAt,
                               java.time.Duration refreshTokenTtl) { }
}
