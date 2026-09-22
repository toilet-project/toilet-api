package com.example.toiletapi.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class CacheInvalidationClient {
    static final String PATH = "/_internal/cache/revalidate";
    private final URI endpoint;
    private final String secret;
    private final HttpClient client;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    public CacheInvalidationClient(String origin, String secret, HttpClient client, Clock clock) {
        URI uri = URI.create(origin);
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if (uri.getHost()==null || !("https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme())))
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))
                || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("Invalid web cache origin or signing configuration");
        }
        this.endpoint = uri.resolve(PATH); this.secret=secret; this.client=client; this.clock=clock;
    }
    public void send(List<Long> ids) throws Exception {
        if(ids.isEmpty() || ids.size()>100 || ids.stream().anyMatch(id->id==null || id<1 || id>9_007_199_254_740_991L)) throw new IllegalArgumentException("Invalid toilet IDs");
        String body = json.writeValueAsString(Map.of("toiletIds",ids));
        JsonNode ack=exchange(body);
        Set<Long> accepted=new HashSet<>();
        if(ack==null || !ack.path("ok").asBoolean(false) || !ack.path("acceptedIds").isArray()) throw new DeliveryException("INVALID_ACK");
        for(JsonNode id:ack.path("acceptedIds")) {
            if(!id.isIntegralNumber() || !id.canConvertToLong()) throw new DeliveryException("INVALID_ACK");
            accepted.add(id.longValue());
        }
        if(!accepted.equals(new HashSet<>(ids))) throw new DeliveryException("INVALID_ACK");
    }
    public void sendEvents(List<CacheInvalidationEvent> events) throws Exception {
        if(events.isEmpty() || events.size()>100) throw new IllegalArgumentException("Invalid cache events");
        var expected=new HashSet<String>();
        for(var event:events) {
            if(event==null) throw new IllegalArgumentException("Invalid cache events");
            expected.add(event.toiletId()+":"+event.revision());
        }
        sendVersionedEvents(2,events,expected);
    }
    public void sendEventsV3(List<CacheInvalidationEventV3> events) throws Exception {
        if(events.isEmpty() || events.size()>100) throw new IllegalArgumentException("Invalid cache events");
        var expected=new HashSet<String>();
        for(var event:events) {
            if(event==null) throw new IllegalArgumentException("Invalid cache events");
            expected.add(event.toiletId()+":"+event.revision());
        }
        sendVersionedEvents(3,events,expected);
    }
    private void sendVersionedEvents(int contractVersion, List<?> events, Set<String> expected) throws Exception {
        JsonNode ack=exchange(json.writeValueAsString(Map.of("contractVersion",contractVersion,"events",events)));
        if(ack==null || !ack.path("ok").asBoolean(false) || !ack.path("acceptedEvents").isArray()) throw new DeliveryException("INVALID_ACK");
        var accepted=new HashSet<String>();
        for(JsonNode event:ack.path("acceptedEvents")) {
            JsonNode id=event.path("toiletId"), revision=event.path("revision");
            if(!id.isIntegralNumber() || !id.canConvertToLong() || !revision.isIntegralNumber() || !revision.canConvertToLong())
                throw new DeliveryException("INVALID_ACK");
            accepted.add(id.longValue()+":"+revision.longValue());
        }
        if(!accepted.equals(expected)) throw new DeliveryException("INVALID_ACK");
    }
    private JsonNode exchange(String body) throws Exception {
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Content-Type","application/json").header("x-cache-timestamp",timestamp)
                .header("x-cache-signature",signature(secret,timestamp,body))
                .POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200) throw new DeliveryException("HTTP_"+response.statusCode());
        if(response.body().length()>8192) throw new DeliveryException("INVALID_ACK");
        return json.readTree(response.body());
    }
    static String signature(String secret,String timestamp,String body) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(("v1\nPOST\n"+PATH+"\n"+timestamp+"\n"+body).getBytes(StandardCharsets.UTF_8)));
    }
    public static class DeliveryException extends Exception {
        public DeliveryException(String code) { super(code); }
    }
}
