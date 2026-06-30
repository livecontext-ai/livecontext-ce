package com.apimarketplace.catalog.mapping;

/**
 * Enumeration of supported source formats for mapping
 */
public enum SourceFormat {
    JSON,
    XML,
    HTML,
    CSV,
    TEXT,
    BINARY;

    /**
     * Convert from existing ResponseFormat to SourceFormat
     */
    public static SourceFormat fromResponseFormat(com.apimarketplace.catalog.domain.ResponseFormat responseFormat) {
        if (responseFormat == null) {
            return BINARY;
        }
        
        return switch (responseFormat) {
            case JSON -> JSON;
            case XML -> XML;
            case HTML -> HTML;
            case CSV -> CSV;
            case TEXT -> TEXT;
            case BINARY -> BINARY;
        };
    }

    /**
     * Convert to existing ResponseFormat
     */
    public com.apimarketplace.catalog.domain.ResponseFormat toResponseFormat() {
        return switch (this) {
            case JSON -> com.apimarketplace.catalog.domain.ResponseFormat.JSON;
            case XML -> com.apimarketplace.catalog.domain.ResponseFormat.XML;
            case HTML -> com.apimarketplace.catalog.domain.ResponseFormat.HTML;
            case CSV -> com.apimarketplace.catalog.domain.ResponseFormat.CSV;
            case TEXT -> com.apimarketplace.catalog.domain.ResponseFormat.TEXT;
            case BINARY -> com.apimarketplace.catalog.domain.ResponseFormat.BINARY;
        };
    }
}
