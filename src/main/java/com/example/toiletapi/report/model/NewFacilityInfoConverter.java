package com.example.toiletapi.report.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter
public class NewFacilityInfoConverter implements AttributeConverter<NewFacilityInfo, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Override public String convertToDatabaseColumn(NewFacilityInfo value) {
        if (value == null) return null;
        try { return MAPPER.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("신규 제보 정보를 저장하지 못했습니다.", e); }
    }
    @Override public NewFacilityInfo convertToEntityAttribute(String value) {
        if (value == null) return null;
        try { return MAPPER.readValue(value, NewFacilityInfo.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("신규 제보 정보를 읽지 못했습니다.", e); }
    }
}
