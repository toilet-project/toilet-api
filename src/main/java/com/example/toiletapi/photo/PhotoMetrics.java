package com.example.toiletapi.photo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PhotoMetrics {
    private final PhotoSettings settings;
    private final JdbcTemplate jdbc;
    private final AtomicLong objects = new AtomicLong();
    private final AtomicLong deletionPending = new AtomicLong();
    private final AtomicLong deletionOldestSeconds = new AtomicLong();
    private final Counter conversionSuccess;
    private final Counter conversionFailure;
    private final Counter putSuccess;
    private final Counter putFailure;
    private final Counter getSuccess;
    private final Counter getFailure;
    private final Counter deleteSuccess;
    private final Counter deleteFailure;
    private final Counter refreshFailure;

    public PhotoMetrics(PhotoSettings settings, JdbcTemplate jdbc, MeterRegistry registry) {
        this.settings = settings;
        this.jdbc = jdbc;
        registry.gauge("profile.photo.storage.objects", objects);
        registry.gauge("profile.photo.deletion.pending", deletionPending);
        registry.gauge("profile.photo.deletion.oldest.seconds", deletionOldestSeconds);
        conversionSuccess = counter(registry, "profile.photo.conversions", "result", "success");
        conversionFailure = counter(registry, "profile.photo.conversions", "result", "failure");
        putSuccess = storageCounter(registry, "put", "success");
        putFailure = storageCounter(registry, "put", "failure");
        getSuccess = storageCounter(registry, "get", "success");
        getFailure = storageCounter(registry, "get", "failure");
        deleteSuccess = storageCounter(registry, "delete", "success");
        deleteFailure = storageCounter(registry, "delete", "failure");
        refreshFailure = registry.counter("profile.photo.metrics.refresh.failures");
    }

    private static Counter counter(MeterRegistry registry, String name, String key, String value) {
        return Counter.builder(name).tag(key, value).register(registry);
    }

    private static Counter storageCounter(MeterRegistry registry, String operation, String result) {
        return Counter.builder("profile.photo.storage.operations")
                .tag("operation", operation).tag("result", result).register(registry);
    }

    void conversion(boolean success) { (success ? conversionSuccess : conversionFailure).increment(); }
    void put(boolean success) { (success ? putSuccess : putFailure).increment(); }
    void get(boolean success) { (success ? getSuccess : getFailure).increment(); }
    void delete(boolean success) { (success ? deleteSuccess : deleteFailure).increment(); }

    record Backlog(long count, LocalDateTime oldest) { }

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void refresh() {
        if (!settings.enabled()) {
            objects.set(0); deletionPending.set(0); deletionOldestSeconds.set(0);
            return;
        }
        try {
            objects.set(jdbc.queryForObject("SELECT COUNT(*) FROM profile_photo_object", Long.class));
            Backlog backlog = jdbc.query("""
                    SELECT COUNT(*),MIN(o.created_at) FROM profile_photo_object o
                    WHERE o.created_at<? AND NOT EXISTS
                    (SELECT 1 FROM profile_photo p WHERE p.object_key=o.object_key)
                    """, (rs, n) -> new Backlog(rs.getLong(1), rs.getObject(2, LocalDateTime.class)),
                    com.example.toiletapi.global.time.KoreanTime.now().minusMinutes(5)).getFirst();
            deletionPending.set(backlog.count());
            deletionOldestSeconds.set(backlog.oldest() == null ? 0 : Math.max(0,
                    Duration.between(backlog.oldest(), com.example.toiletapi.global.time.KoreanTime.now()).toSeconds()));
        } catch (RuntimeException unavailable) {
            objects.set(-1); deletionPending.set(-1); deletionOldestSeconds.set(-1);
            refreshFailure.increment();
        }
    }
}
