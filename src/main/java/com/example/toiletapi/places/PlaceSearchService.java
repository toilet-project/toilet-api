package com.example.toiletapi.places;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PlaceSearchService {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Semaphore concurrent = new Semaphore(4);
    private long minute = -1;
    private int requests;
    public record Place(String id, String name, String address, double latitude, double longitude, String kind) {}
    public record Results(String provider, List<Place> items) {}

    @Autowired
    public PlaceSearchService(@Value("${kakao.api.key}") String apiKey, ObjectMapper mapper) {
        this(createClient(apiKey), mapper, Clock.systemUTC());
    }
    PlaceSearchService(RestClient client, ObjectMapper mapper, Clock clock) {
        this.client = client; this.mapper = mapper; this.clock = clock;
    }
    private static RestClient createClient(String key) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + key).requestFactory(factory).build();
    }
    public Results search(String input) {
        String query = normalize(input);
        reserve();
        try {
            List<Place> places = read("keyword", query), addresses = read("address", query);
            var combined = new LinkedHashMap<String, Place>();
            // A house number usually means an address; preserve provider relevance within each group.
            boolean addressFirst = query.codePoints().anyMatch(Character::isDigit);
            for (var group : addressFirst ? List.of(addresses, places) : List.of(places, addresses)) {
                for (var place : group) combined.putIfAbsent(place.id(), place);
            }
            return new Results("kakao", combined.values().stream().limit(10).toList());
        } catch (Exception ignored) {
            // Never log/return the query, upstream URL/body, or REST credential.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "장소를 찾지 못했어요. 잠시 후 다시 시도해 주세요.");
        } finally { concurrent.release(); }
    }
    private synchronized void reserve() {
        long now = clock.instant().getEpochSecond() / 60;
        if (minute != now) { minute = now; requests = 0; }
        // Bound provider usage globally without storing IPs or search histories.
        if (requests >= 120 || !concurrent.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 검색해 주세요.");
        requests++;
    }
    static String normalize(String value) {
        if (value == null || value.length() > 160) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        String query = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
        int length = query.codePointCount(0, query.length());
        if (length < 2 || length > 80 || query.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어를 2~80자로 입력해 주세요.");
        return query;
    }
    private List<Place> read(String kind, String query) throws Exception {
        String body = client.get().uri(builder -> builder.path("/v2/local/search/" + kind + ".json")
                .queryParam("query", "{query}").queryParam("size", 10).build(Map.of("query", query)))
                .retrieve().body(String.class);
        JsonNode documents = mapper.readTree(body).path("documents");
        if (!documents.isArray() || documents.size() > 10) throw new IllegalStateException("Invalid provider results");
        var result = new ArrayList<Place>();
        boolean address = "address".equals(kind);
        for (var item : documents) {
            double lat = item.path("y").asDouble(Double.NaN), lng = item.path("x").asDouble(Double.NaN);
            if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat < 32 || lat > 40 || lng < 124 || lng > 132) continue;
            String name = text(item.path(address ? "address_name" : "place_name"));
            String road = text(item.path("road_address_name"));
            if (address) road = text(item.path("road_address").path("address_name"));
            String displayAddress = !road.isEmpty() ? road : text(item.path("address_name"));
            String id = address ? "address:" + lat + ":" + lng : "place:" + text(item.path("id"));
            if (name.isEmpty() || id.length() > 100 || id.equals("place:")) continue;
            result.add(new Place(id, name, displayAddress, lat, lng, address ? "address" : "place"));
        }
        return result;
    }
    private static String text(JsonNode value) {
        if (!value.isTextual()) return "";
        String text = value.asText().strip();
        return text.length() <= 300 ? text : "";
    }
}
