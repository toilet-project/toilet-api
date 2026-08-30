package com.example.toiletapi.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.toiletapi.auth.model.Role;
import com.example.toiletapi.auth.service.AuthTokenService;
import com.example.toiletapi.auth.service.RefreshTokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwsHeader;

class AuthTokenServiceTest {

    private final AuthTokenProperties properties = new AuthTokenProperties(
            Base64.getEncoder().encodeToString(new byte[32]), Duration.ofMinutes(15), Duration.ofDays(14));
    private final JwtConfig jwtConfig = new JwtConfig();

    @Test
    void shouldIssueAccessTokenWithRolesAndRefreshToken() {
        RefreshTokenStore refreshTokenStore = mock(RefreshTokenStore.class);
        JwtEncoder encoder = jwtConfig.jwtEncoder(jwtConfig.jwtSecretKey(properties));
        JwtDecoder decoder = jwtConfig.jwtDecoder(jwtConfig.jwtSecretKey(properties));
        AuthTokenService service = new AuthTokenService(encoder, refreshTokenStore, properties);

        AuthTokenService.IssuedTokens tokens = service.issue(7L, List.of(Role.USER));

        assertThat(decoder.decode(tokens.accessToken()).getSubject()).isEqualTo("7");
        assertThat(decoder.decode(tokens.accessToken()).getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(tokens.accessTokenExpiresAt()).isAfter(Instant.now());
        verify(refreshTokenStore).save(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(tokens.refreshToken()), org.mockito.ArgumentMatchers.eq(Duration.ofDays(14)));
    }

    @Test
    void shouldRejectExpiredAccessToken() {
        var secret = jwtConfig.jwtSecretKey(properties);
        JwtEncoder encoder = jwtConfig.jwtEncoder(secret);
        JwtDecoder decoder = jwtConfig.jwtDecoder(secret);
        String expired = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                JwtClaimsSet.builder().subject("7").issuedAt(Instant.now().minus(Duration.ofHours(1)))
                        .expiresAt(Instant.now().minus(Duration.ofMinutes(1))).claim("roles", List.of("USER")).build()
        )).getTokenValue();

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void shouldDeleteRefreshTokenOnRevoke() {
        RefreshTokenStore refreshTokenStore = mock(RefreshTokenStore.class);
        AuthTokenService service = new AuthTokenService(
                jwtConfig.jwtEncoder(jwtConfig.jwtSecretKey(properties)), refreshTokenStore, properties);

        service.revoke("refresh-token");

        verify(refreshTokenStore).delete("refresh-token");
    }
}
