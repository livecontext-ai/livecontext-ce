package com.apimarketplace.catalog.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Convertisseur JPA pour gerer la conversion entre String et ResponseFormat
 * Gere les cas minuscules et majuscules pour la compatibilite
 */
@Converter
public class ResponseFormatConverter implements AttributeConverter<ResponseFormat, String> {

    @Override
    public String convertToDatabaseColumn(ResponseFormat responseFormat) {
        if (responseFormat == null) {
            return null;
        }
        return responseFormat.getDatabaseValue();
    }

    @Override
    public ResponseFormat convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        // Utilise la methode robuste qui gere tous les cas
        return ResponseFormat.fromString(dbData);
    }
}
