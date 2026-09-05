package com.example.toiletapi.cache;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="web-cache.enabled",havingValue="true")
public class CacheInvalidationConfiguration {
    @Bean CacheInvalidationClient cacheInvalidationClient(@Value("${web-cache.origin}") String origin,
            @Value("${web-cache.secret}") String secret) {
        return new CacheInvalidationClient(origin,secret,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(),Clock.systemUTC());
    }
}
