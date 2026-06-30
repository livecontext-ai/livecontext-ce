package com.apimarketplace.storage.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Requete de creation de document
 */
@Data
@Builder
public class DocumentRequest {
    private String collection;
    private Map<String, Object> document;
    private Map<String, Object> metadata;
}
