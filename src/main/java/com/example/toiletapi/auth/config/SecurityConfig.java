package com.example.toiletapi.auth.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * 공개 지도 조회 API와 앞으로 추가될 인증·관리자 API의 접근 경계를 정의한다.
 *
 * <p>소셜 로그인과 JWT 발급은 OAuth 클라이언트 등록 작업에서 연결한다. 현재는 세션을 만들지 않고,
 * 등록되지 않은 쓰기 요청과 관리자 경로를 기본적으로 차단한다.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, OAuthLoginSuccessHandler oauthLoginSuccessHandler) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/health", "/actuator/health", "/error").permitAll()
                        .requestMatchers("/api/v1/toilets/**").permitAll()
                        .requestMatchers("/api/v1/data-status").permitAll()
                        .requestMatchers("/api/v1/auth/login/**").permitAll()
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()
                        // access token이 만료된 뒤에도 HttpOnly refresh cookie로 재발급할 수 있어야 한다.
                        // 실제 인증은 AuthController가 refresh token 저장소 조회로 수행한다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/reports/**").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2Login(login -> login.successHandler(oauthLoginSuccessHandler))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(new CookieBearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        "AUTHENTICATION_REQUIRED", "로그인이 필요합니다."))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        "ACCESS_DENIED", "접근 권한이 없습니다."))
                )
                .build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}}");
    }
}
