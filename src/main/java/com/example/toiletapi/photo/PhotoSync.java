package com.example.toiletapi.photo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

@Component
public class PhotoSync implements AutoCloseable {
    private final PhotoSettings settings;
    private final PhotoService photos;
    private final PhotoProcessor processor;
    private final Set<Long> pending=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicBoolean cleaning=new java.util.concurrent.atomic.AtomicBoolean();
    private final ThreadPoolExecutor queue=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),
            r->{Thread t=new Thread(r,"profile-photo");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public PhotoSync(PhotoSettings settings,PhotoService photos,PhotoProcessor processor) {this.settings=settings;this.photos=photos;this.processor=processor;}
    public void login(long user,String provider,Map<String,Object> attributes) {
        if(!settings.enabled() || !pending.add(user)) return;
        try {
            PhotoSource source=PhotoSource.from(provider,attributes);
            String sourceHash=source.uri()==null?"absent":hash(source.uri().toString().getBytes(StandardCharsets.UTF_8));
            var ticket=photos.ticket(user,sourceHash);
            if(ticket==null) {pending.remove(user);return;}
            queue.execute(()->{
                try {
                    if(source.uri()==null) {photos.absent(ticket);return;}
                    byte[] image=processor.process(source.uri());String hash=hash(image);
                    if(!photos.unchanged(ticket,hash,sourceHash)) photos.save(ticket,image,hash,sourceHash);
                } catch(Exception failure) {
                    // Login already succeeded. A later login retries; no provider data in error output.
                } finally {pending.remove(user);}
            });
        } catch(Exception failure) {pending.remove(user);}
    }
    static String hash(byte[] input) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));}
    @Scheduled(fixedDelay=60000,initialDelay=60000) public void cleanup() {
        if(!settings.enabled() || !cleaning.compareAndSet(false,true)) return;
        try { queue.execute(()->{try {photos.cleanup();} finally {cleaning.set(false);}}); }
        catch(RejectedExecutionException failure) {cleaning.set(false);}
    }
    @jakarta.annotation.PreDestroy public void close() {queue.shutdownNow();}
}
