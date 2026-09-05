package com.example.toiletapi.geocoding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class CoordinateAddressResolver {
    private final RestClient client;
    private final ObjectMapper mapper;

    @Autowired
    public CoordinateAddressResolver(@Value("${kakao.api.key}") String apiKey, ObjectMapper mapper) {
        this(createClient(apiKey), mapper);
    }

    CoordinateAddressResolver(RestClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    private static RestClient createClient(String apiKey) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + apiKey)
                .requestFactory(factory).build();
    }

    public CoordinateAddress resolve(BigDecimal latitude, BigDecimal longitude) {
        validateCoordinates(latitude, longitude);
        // Lookup the same DECIMAL(10,7) coordinates that will be stored, not a higher precision candidate.
        BigDecimal lat = latitude.setScale(7, RoundingMode.HALF_UP);
        BigDecimal lng = longitude.setScale(7, RoundingMode.HALF_UP);
        try {
            String body = client.get().uri(builder -> builder.path("/v2/local/geo/coord2address.json")
                    .queryParam("x", lng.toPlainString()).queryParam("y", lat.toPlainString())
                    .queryParam("input_coord", "WGS84").build()).retrieve().body(String.class);
            JsonNode documents = mapper.readTree(body).path("documents");
            if (!documents.isArray() || documents.size() != 1) throw new AddressLookupException();
            JsonNode document = documents.get(0);
            String road = address(document.path("road_address").path("address_name"));
            String jibun = address(document.path("address").path("address_name"));
            if (road == null && jibun == null) throw new AddressLookupException();
            return new CoordinateAddress(lat, lng, road, jibun);
        } catch (Exception exception) {
            // Do not expose provider bodies, authorization headers or credentials in application logs/errors.
            throw new AddressLookupException();
        }
    }

    public static void validateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null
                || latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("위도와 경도를 올바르게 입력해 주세요.");
        }
    }

    private String address(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new AddressLookupException();
        String address = value.asText().trim();
        if (address.length() > 255) throw new AddressLookupException();
        return address.isEmpty() ? null : address;
    }
}
