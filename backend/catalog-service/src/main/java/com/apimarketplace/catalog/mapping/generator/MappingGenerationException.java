package com.apimarketplace.catalog.mapping.generator;

/**
 * Exception thrown when mapping generation fails.
 */
public class MappingGenerationException extends Exception {
    
    public MappingGenerationException(String message) {
        super(message);
    }
    
    public MappingGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}


