package com.example.toiletapi.photo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="profile-photo.cdn.enabled",havingValue="true")
public class PhotoCdnPurgeDispatcher {
    private static final Logger log=LoggerFactory.getLogger(PhotoCdnPurgeDispatcher.class);
    private final PhotoCdnPurgeRepository repository;
    private final PhotoCdnClient client;
    private final Counter success,failure;
    private final AtomicLong pending=new AtomicLong();
    PhotoCdnPurgeDispatcher(PhotoCdnPurgeRepository repository,PhotoCdnClient client,MeterRegistry metrics){
        this.repository=repository;this.client=client;
        success=metrics.counter("profile.photo.cdn.purges","result","success");
        failure=metrics.counter("profile.photo.cdn.purges","result","failure");
        metrics.gauge("profile.photo.cdn.purge.pending",pending);
    }
    @Scheduled(fixedDelayString="${profile-photo.cdn.poll-ms:1000}")
    public void dispatch(){
        try {
            pending.set(repository.pendingCount());var items=repository.due();if(items.isEmpty())return;
            try {client.purge(items.stream().map(PhotoCdnPurgeRepository.Pending::version).toList());}
            catch(Exception error){
                failure.increment();String code=error instanceof PhotoCdnClient.PurgeException?error.getMessage():"TRANSPORT_OR_ACK_ERROR";
                for(var item:items)repository.retry(item,code);
                log.warn("Profile photo CDN purge postponed: {} ({} items)",code,items.size());
                if(error instanceof InterruptedException)Thread.currentThread().interrupt();return;
            }
            for(var item:items)repository.acknowledge(item);success.increment(items.size());pending.set(repository.pendingCount());
        } catch(Exception unavailable){failure.increment();pending.set(-1);log.warn("Profile photo CDN purge queue unavailable");}
    }
}
