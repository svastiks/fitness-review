package com.fitanalysis.server.models;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;

@Converter
public class VectorConverter implements AttributeConverter<float[], String> {
    
    @Override
    public String convertToDatabaseColumn(float[] attribute) {
        if (attribute == null) {
            return null;
        }
        return "[" + Arrays.toString(attribute).replaceAll("[\\[\\]]", "") + "]";
    }
    
    @Override
    public float[] convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null;
        }
        // Remove brackets and split by comma
        String[] values = dbData.replaceAll("[\\[\\]]", "").split(",");
        float[] result = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = Float.parseFloat(values[i].trim());
        }
        return result;
    }
} 