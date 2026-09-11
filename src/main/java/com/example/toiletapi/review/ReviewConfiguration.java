package com.example.toiletapi.review;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReviewConfiguration {
    @Bean("reviewClock") Clock reviewClock() { return Clock.systemUTC(); }
    @Bean ReviewSettings reviewSettings(@Value("${reviews.enabled:false}") boolean enabled,
                                       @Value("${reviews.create-cooldown-seconds:60}") int cooldown,
                                       @Value("${reviews.max-per-day:10}") int maxPerDay) {
        return new ReviewSettings(enabled, cooldown, maxPerDay);
    }
    public record ReviewSettings(boolean enabled, int cooldownSeconds, int maxPerDay) {
        public ReviewSettings {
            if (cooldownSeconds < 1 || cooldownSeconds > 86400 || maxPerDay < 1 || maxPerDay > 100)
                throw new IllegalArgumentException("Invalid review write limits");
        }
        void requireEnabled() {
            if (!enabled) throw new ReviewFailure(503, "REVIEWS_DISABLED", "리뷰 저장 기능을 준비하고 있어요.");
        }
    }
}
