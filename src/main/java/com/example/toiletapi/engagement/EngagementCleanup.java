package com.example.toiletapi.engagement;

import java.time.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EngagementCleanup {
    private final EngagementRepository repository;
    private final EngagementConfiguration.Settings settings;
    private final Clock clock;
    public EngagementCleanup(EngagementRepository repository,EngagementConfiguration.Settings settings,@Qualifier("engagementClock") Clock clock) {this.repository=repository;this.settings=settings;this.clock=clock;}
    @Scheduled(fixedDelayString="${engagement.cleanup-delay-ms:300000}")
    public void cleanup() { if(settings.enabled())repository.cleanup(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.ofHours(9))); }
}
