package com.example.toiletapi.cache;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CacheInvalidationClientTest {
    static final String SECRET="test-only-signing-secret-at-least-32-bytes";
    @Test void matchesTheNodeSigningContractVector() throws Exception {
        assertEquals("6d1dad9672475e2a451aa566d4c55b6a2595a653e0bbe08689a151f33485b4d3",
                CacheInvalidationClient.signature(SECRET,"1788600000","{\"toiletIds\":[1,2]}"));
    }
    @Test void sendsSignedBodyAndRequiresAnExplicitMatchingAck() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> ack=new AtomicReference<>("{\"ok\":true,\"acceptedIds\":[1,2]}");
        AtomicReference<String> body=new AtomicReference<>(),signature=new AtomicReference<>(),timestamp=new AtomicReference<>();
        server.createContext(CacheInvalidationClient.PATH,exchange->{
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            signature.set(exchange.getRequestHeaders().getFirst("x-cache-signature"));
            timestamp.set(exchange.getRequestHeaders().getFirst("x-cache-timestamp"));
            byte[] bytes=ack.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client=new CacheInvalidationClient("http://127.0.0.1:"+server.getAddress().getPort(),SECRET,HttpClient.newHttpClient(),Clock.systemUTC());
            client.send(List.of(1L,2L));
            assertEquals("{\"toiletIds\":[1,2]}",body.get());
            assertEquals(CacheInvalidationClient.signature(SECRET,timestamp.get(),body.get()),signature.get());
            ack.set("{\"ok\":true,\"acceptedIds\":[1]}"); assertThrows(CacheInvalidationClient.DeliveryException.class,()->client.send(List.of(1L,2L)));
            ack.set("{\"ok\":false,\"acceptedIds\":[1,2]}"); assertThrows(CacheInvalidationClient.DeliveryException.class,()->client.send(List.of(1L,2L)));
        } finally {server.stop(0);}
    }
    @Test void rejectsUnsafeDestinationsAndWeakKeys() {
        for(String origin:List.of("http://example.com","https://user:password@example.com","https://example.com/path","https://example.com?secret=1"))
            assertThrows(IllegalArgumentException.class,()->new CacheInvalidationClient(origin,SECRET,HttpClient.newHttpClient(),Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class,()->new CacheInvalidationClient("https://example.com","weak",HttpClient.newHttpClient(),Clock.systemUTC()));
    }
}
