package com.example.toiletapi.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="web-cache.enabled",havingValue="true")
public class CacheInvalidationDispatcher {
    private static final Logger log=LoggerFactory.getLogger(CacheInvalidationDispatcher.class);
    private final CacheInvalidationRepository repository;
    private final CacheInvalidationClient client;
    private final int contractVersion;
    private final Counter deliveries, failures;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    public CacheInvalidationDispatcher(CacheInvalidationRepository repository,CacheInvalidationClient client,MeterRegistry metrics,
            @org.springframework.beans.factory.annotation.Value("${web-cache.contract-version:1}") int contractVersion) {
        this.repository=repository; this.client=client;
        if(contractVersion!=1 && contractVersion!=2 && contractVersion!=3) throw new IllegalArgumentException("Unsupported cache contract version");
        this.contractVersion=contractVersion;
        deliveries=metrics.counter("web.cache.invalidation.deliveries");
        failures=metrics.counter("web.cache.invalidation.failures");
        metrics.gauge("web.cache.invalidation.pending",pending);
        metrics.gauge("web.cache.invalidation.oldest.seconds",oldestPendingSeconds);
    }
    // Never holds the toilet mutation transaction open while making HTTP calls.
    @Scheduled(fixedDelayString="${web-cache.poll-ms:5000}")
    public void dispatch() {
        try {
            pending.set(repository.pendingCount());
            oldestPendingSeconds.set(repository.oldestPendingSeconds());
            var items=contractVersion==3 ? repository.dueScoped() : repository.due();
            if(items.isEmpty()) return;
            try {
                if(contractVersion==1) client.send(items.stream().map(CacheInvalidationRepository.Pending::toiletId).toList());
                else if(contractVersion==2) client.sendEvents(items.stream().map(CacheInvalidationRepository.Pending::event).toList());
                else client.sendEventsV3(items.stream().map(CacheInvalidationRepository.Pending::eventV3).toList());
            } catch (Exception error) {
                failures.increment();
                String code=error instanceof CacheInvalidationClient.DeliveryException ? error.getMessage() : "TRANSPORT_OR_ACK_ERROR";
                for(var item:items) repository.retry(item,code);
                // Never log URL, headers, signing key, source response or personal information.
                log.warn("Web cache delivery postponed: {} ({} items)",code,items.size());
                if(error instanceof InterruptedException) Thread.currentThread().interrupt();
                return;
            }
            for(var item:items) {
                if(contractVersion==3) repository.acknowledgeScoped(item);
                else repository.acknowledge(item);
            }
            deliveries.increment(items.size());
        } catch (Exception error) {
            failures.increment();
            pending.set(-1);
            oldestPendingSeconds.set(-1);
            log.warn("Web cache queue unavailable; pending items retained");
        }
    }
}
