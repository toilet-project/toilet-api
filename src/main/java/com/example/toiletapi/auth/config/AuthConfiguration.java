package com.example.toiletapi.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({AdminBootstrapProperties.class, AuthTokenProperties.class})
public class AuthConfiguration {
    /** 감사 로그 상세 정보를 안전하게 JSON으로 저장하기 위한 공통 직렬화기다. */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
