package com.example.toiletapi.report.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;
import java.time.LocalTime;
import java.util.Map;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class NewFacilityInfoConverter implements AttributeConverter<NewFacilityInfo, String> {
    private static final ObjectMapper MAPPER = mapper();
    private static ObjectMapper mapper() {
        var times = new SimpleModule();
        times.addSerializer(LocalTime.class, new JsonSerializer<LocalTime>() {
            @Override public void serialize(LocalTime value, JsonGenerator output, com.fasterxml.jackson.databind.SerializerProvider provider) throws IOException {
                output.writeString(value.toString());
            }
        });
        times.addDeserializer(LocalTime.class, new JsonDeserializer<LocalTime>() {
            @Override public LocalTime deserialize(JsonParser input, com.fasterxml.jackson.databind.DeserializationContext context) throws IOException {
                return LocalTime.parse(input.getValueAsString());
            }
        });
        return new ObjectMapper().registerModule(times).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    @Override public String convertToDatabaseColumn(NewFacilityInfo value) {
        if (value == null) return null;
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("신규 제보 정보를 저장하지 못했습니다.", e); }
    }
    public static Map<String, Object> auditSnapshot(NewFacilityInfo value) {
        return MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }
    @Override public NewFacilityInfo convertToEntityAttribute(String value) {
        if (value == null) return null;
        try { return MAPPER.readValue(value, NewFacilityInfo.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("신규 제보 정보를 읽지 못했습니다.", e); }
    }
}
