package com.example.toiletapi.photo;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class PhotoProcessor {
    private final PhotoSettings settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public PhotoProcessor(PhotoSettings settings) { this.settings = settings; }
    public byte[] process(URI uri) throws Exception {
        for (var address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress()) throw new IllegalArgumentException("Invalid source address");
        }
        var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("Accept","image/jpeg,image/png,image/webp").GET().build(),info -> new LimitedBody());
        byte[] original = response.body();
        try {
            if (response.statusCode() != 200) throw new IllegalStateException("Photo source unavailable");
            return convert(original);
        } finally { java.util.Arrays.fill(original,(byte)0); }
    }
    byte[] convert(byte[] original) throws Exception {
        Process process = new ProcessBuilder(settings.python(),"-I",settings.converter())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> writer = tasks.submit(() -> { try (var stdin = process.getOutputStream()) { stdin.write(original); } catch (Exception e) { throw new IllegalStateException("Photo conversion failed"); } });
            Future<byte[]> reader = tasks.submit(() -> { try (var stdout = process.getInputStream()) { return stdout.readNBytes(100_001); } });
            try {
                if (!process.waitFor(8,TimeUnit.SECONDS)) throw new IllegalStateException("Photo conversion timed out");
                writer.get(1,TimeUnit.SECONDS);
                byte[] result = reader.get(1,TimeUnit.SECONDS);
                if (process.exitValue() != 0 || result.length < 12 || result.length > 100_000
                        || !new String(result,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                        || !new String(result,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
                    throw new IllegalStateException("Invalid converted photo");
                return result;
            } finally { process.destroyForcibly(); }
        } finally { process.destroyForcibly(); }
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription s) { subscription=s;s.request(1); }
        public void onNext(List<ByteBuffer> items) {
            for(var item:items) {
                if ((long)bytes.size()+item.remaining()>2*1024*1024) { subscription.cancel();body.completeExceptionally(new IllegalStateException("Photo too large"));return; }
                byte[] chunk=new byte[item.remaining()];item.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(new IllegalStateException("Photo download failed")); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
