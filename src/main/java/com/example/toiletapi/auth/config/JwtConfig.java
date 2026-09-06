package com.example.toiletapi.auth.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class JwtConfig {
    @Bean
    SecretKey jwtSecretKey(AuthTokenProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException("JWT_SECRET 환경변수가 필요합니다.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(properties.secret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT_SECRET은 Base64 형식이어야 합니다.", exception);
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET은 최소 32바이트여야 합니다.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(jwtSecretKey));
    }

    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder accountAwareJwtDecoder(SecretKey jwtSecretKey,
            com.example.toiletapi.auth.repository.AppUserRepository users) {
        JwtDecoder signatureDecoder = jwtDecoder(jwtSecretKey);
        return token -> {
            var jwt = signatureDecoder.decode(token);
            try {
                var user = users.findById(Long.valueOf(jwt.getSubject())).orElseThrow();
                var status = user.getStatus();
                Number version = jwt.getClaim("auth_version");
                long tokenVersion = version == null ? 0 : version.longValue();
                if (status == com.example.toiletapi.auth.model.UserStatus.WITHDRAWN
                        || status == com.example.toiletapi.auth.model.UserStatus.SUSPENDED
                        || tokenVersion != user.getAuthVersion()) throw new IllegalStateException();
                return jwt;
            } catch (RuntimeException invalid) {
                throw new org.springframework.security.oauth2.jwt.BadJwtException("이용할 수 없는 로그인 세션입니다.");
            }
        };
    }
}
