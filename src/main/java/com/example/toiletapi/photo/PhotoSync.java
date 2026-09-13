package com.example.toiletapi.photo;

import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

/** Signup-only import. URLs stay in bounded volatile memory, never in Redis/DB/logs. */
@Component
public class PhotoSync implements AutoCloseable {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final PhotoSettings settings;
    private final PhotoService photos;
    private final PhotoProcessor processor;
    private final Map<Long, Candidate> candidates = new HashMap<>();
    private record Candidate(PhotoSource source, Instant expires, LocalDateTime collectedAt) { }
    private final java.util.concurrent.atomic.AtomicBoolean cleaning = new java.util.concurrent.atomic.AtomicBoolean();
    private final ThreadPoolExecutor queue = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), r -> { var t = new Thread(r, "profile-photo"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());

    public PhotoSync(PhotoSettings settings, PhotoService photos, PhotoProcessor processor) {
        this.settings = settings; this.photos = photos; this.processor = processor;
    }

    // Called only for an account created by this OAuth callback, never existing/recovered users.
    public synchronized void stageSignup(long user, String provider, Map<String,Object> attributes) {
        if (!settings.enabled() || !"kakao".equals(provider)) return;
        expire();
        try {
            Object account = attributes.get("kakao_account");
            if (!(account instanceof Map<?,?> a) || !Boolean.FALSE.equals(a.get("profile_image_needs_agreement"))) return;
            PhotoSource source = PhotoSource.from(provider, attributes);
            if (source.uri() != null && candidates.size() < 256)
                candidates.putIfAbsent(user, new Candidate(source, Instant.now().plus(TTL), com.example.toiletapi.global.time.KoreanTime.now()));
        } catch (RuntimeException ignored) { /* Bad/missing photo must not fail signup. */ }
    }

    public void completeSignup(long user) {
        if (!settings.enabled()) return;
        Candidate candidate;
        synchronized (this) { expire(); candidate = candidates.remove(user); }
        if (candidate == null) return;
        try {
            var ticket = photos.signupTicket(user);
            if (ticket == null) return;
            queue.execute(() -> {
                try {
                    if (!Instant.now().isBefore(candidate.expires())) return;
                    byte[] image = processor.process(candidate.source().uri());
                    try {
                        photos.saveWithReceipt(ticket, image, "KAKAO_SIGNUP", PhotoService.NOTICE_VERSION, candidate.collectedAt());
                    } finally { Arrays.fill(image, (byte) 0); }
                } catch (Exception ignored) {
                    // No later-login retry. The user may upload a photo from My Page.
                }
            });
        } catch (Exception ignored) { /* A photo failure must not break successful signup. */ }
    }

    private void expire() { candidates.values().removeIf(c -> !Instant.now().isBefore(c.expires())); }
    static String hash(byte[] input) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input)); }
    @Scheduled(fixedDelay=60000, initialDelay=60000)
    public void cleanup() {
        synchronized (this) { expire(); }
        if (!settings.enabled() || !cleaning.compareAndSet(false, true)) return;
        try { queue.execute(() -> { try { photos.cleanup(); } finally { cleaning.set(false); } }); }
        catch (RejectedExecutionException failure) { cleaning.set(false); }
    }
    @jakarta.annotation.PreDestroy public void close() { synchronized (this) { candidates.clear(); } queue.shutdownNow(); }
}
