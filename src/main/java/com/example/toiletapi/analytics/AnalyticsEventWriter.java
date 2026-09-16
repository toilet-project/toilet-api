package com.example.toiletapi.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsEventWriter {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsEventWriter.class);
    private final AnalyticsRepository repository;

    public AnalyticsEventWriter(AnalyticsRepository repository) {
        this.repository = repository;
    }

    @Async("analyticsExecutor")
    public void write(AnalyticsRepository.EventRow row) {
        try {
            repository.insert(row);
        } catch (RuntimeException exception) {
            log.debug("Service analytics event was dropped: {}", exception.getClass().getSimpleName());
        }
    }
}
