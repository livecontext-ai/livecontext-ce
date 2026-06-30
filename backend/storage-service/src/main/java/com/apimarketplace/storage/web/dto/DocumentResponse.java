package com.apimarketplace.storage.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Reponse de document
 */
@Data
@Builder
public class DocumentResponse {
    private String id;
    private String collection;
    private Map<String, Object> document;
    private Map<String, Object> metadata;
    private long createdAt;
    private long updatedAt;
    private String error;
}
