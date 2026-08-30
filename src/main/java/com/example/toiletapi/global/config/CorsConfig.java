package com.example.toiletapi.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 브라우저 기반 웹 클라이언트가 공개 API를 호출할 수 있도록 허용 출처를 제한한다.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                        "https://geupddong.com",
                        "https://www.geupddong.com",
                        "https://admin.geupddong.com",
                        "http://localhost:5173"
                )
                .allowCredentials(true)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
