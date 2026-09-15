package com.example.toiletapi.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsAggregationService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final AnalyticsRepository repository;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public AnalyticsAggregationService(AnalyticsRepository repository,
                                       @Value("${service-analytics.enabled:false}") boolean enabled) {
        this(repository, Clock.systemUTC(), enabled);
    }

    AnalyticsAggregationService(AnalyticsRepository repository, Clock clock, boolean enabled) {
        this.repository = repository;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${service-analytics.daily-cron:0 30 2 * * *}", zone = "Asia/Seoul")
    public void aggregateRecentCompletedDays() {
        if (!enabled) return;
        if (!running.compareAndSet(false, true)) return;
        Instant now = clock.instant();
        LocalDate end = now.atZone(SEOUL).toLocalDate().minusDays(1);
        LocalDate start = end.minusDays(13);
        long runId = 0;
        try {
            runId = repository.startRun(now, start, end);
            int processed = 0;
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                repository.aggregate(date, now, true);
                processed++;
            }
            repository.deleteExpiredEvents(end.minusDays(33));
            repository.completeRun(runId, clock.instant(), processed);
        } catch (RuntimeException exception) {
            if (runId > 0) repository.failRun(runId, clock.instant(), exception.getClass().getSimpleName());
            throw exception;
        } finally {
            running.set(false);
        }
    }

    @Scheduled(fixedDelayString = "${service-analytics.today-refresh-ms:300000}", initialDelayString = "${service-analytics.today-initial-delay-ms:60000}")
    public void refreshToday() {
        if (!enabled || !running.compareAndSet(false, true)) return;
        try {
            Instant now = clock.instant();
            repository.aggregate(now.atZone(SEOUL).toLocalDate(), now, false);
        } finally {
            running.set(false);
        }
    }
}
