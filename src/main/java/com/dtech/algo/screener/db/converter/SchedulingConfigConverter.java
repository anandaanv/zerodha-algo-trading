package com.dtech.algo.screener.db.converter;

import com.dtech.algo.screener.model.SchedulingConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = false)
public class SchedulingConfigConverter implements AttributeConverter<SchedulingConfig, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(SchedulingConfig attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize SchedulingConfig to JSON", e);
        }
    }

    @Override
    public SchedulingConfig convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, SchedulingConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize SchedulingConfig from JSON", e);
        }
    }
}
