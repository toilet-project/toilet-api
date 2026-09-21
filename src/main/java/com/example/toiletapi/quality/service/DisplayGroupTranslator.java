package com.example.toiletapi.quality.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class DisplayGroupTranslator {
    private final RestClient client;
    private final ObjectMapper mapper;
    private final boolean enabled;

    @Autowired
    public DisplayGroupTranslator(@Value("${display-group-translation.enabled:false}") boolean enabled,
                                  @Value("${display-group-translation.api-key:}") String apiKey,
                                  ObjectMapper mapper) {
        this(createClient(apiKey), mapper, enabled && StringUtils.hasText(apiKey));
    }

    DisplayGroupTranslator(RestClient client, ObjectMapper mapper, boolean enabled) {
        this.client = client;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    private static RestClient createClient(String apiKey) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder()
                .baseUrl("https://translation.googleapis.com")
                .defaultHeader("X-Goog-Api-Key", apiKey == null ? "" : apiKey.trim())
                .requestFactory(factory)
                .build();
    }

    public String translate(String sourceName) {
        return translate(List.of(sourceName)).getFirst();
    }

    public List<String> translate(List<String> sourceNames) {
        if (!enabled || sourceNames == null || sourceNames.isEmpty() || sourceNames.size() > 100
                || sourceNames.stream().anyMatch(name -> !StringUtils.hasText(name) || name.length() > 100)) {
            throw new DisplayGroupTranslationException();
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("q", sourceNames);
            body.put("source", "ko");
            body.put("target", "en");
            body.put("format", "text");
            String response = client.post().uri("/language/translate/v2")
                    .body(body).retrieve().body(String.class);
            JsonNode translations = mapper.readTree(response).path("data").path("translations");
            if (!translations.isArray() || translations.size() != sourceNames.size()) {
                throw new DisplayGroupTranslationException();
            }
            List<String> values = new java.util.ArrayList<>(translations.size());
            for (JsonNode item : translations) {
                String value = org.springframework.web.util.HtmlUtils.htmlUnescape(
                        item.path("translatedText").asText("")).trim();
                if (value.isEmpty() || value.length() > 255) throw new DisplayGroupTranslationException();
                values.add(value);
            }
            return List.copyOf(values);
        } catch (DisplayGroupTranslationException exception) {
            throw exception;
        } catch (Exception exception) {
            // Provider bodies and credentials must never be exposed to administrators or logs.
            throw new DisplayGroupTranslationException();
        }
    }
}
