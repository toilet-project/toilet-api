package com.example.toiletapi.engagement;

import java.time.Clock;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EngagementConfiguration {
    public record Settings(boolean enabled, String secret, Set<String> origins) {
        public void requireEnabled() {
            if (!enabled || secret == null || secret.length()<32)
                throw new EngagementFailure(503,"ENGAGEMENT_DISABLED","조회수·좋아요를 준비 중입니다.");
        }
        public void requireOrigin(String origin) {
            if (origin==null || !origins.contains(origin)) throw new EngagementFailure(403,"ORIGIN_DENIED","허용되지 않은 요청 출처입니다.");
        }
    }
    @Bean Settings engagementSettings(@Value("${engagement.enabled:false}") boolean enabled,
            @Value("${engagement.secret:${service-analytics.visitor-secret:}}") String secret,
            @Value("${engagement.allowed-origins:https://geupddong.com,https://www.geupddong.com}") String origins) {
        return new Settings(enabled,secret,Arrays.stream(origins.split(",")).map(String::strip).collect(Collectors.toUnmodifiableSet()));
    }
    @Bean Clock engagementClock() { return Clock.systemUTC(); }
}
