package com.apimarketplace.storage.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Requete d'indexation RAG
 */
@Data
@Builder
public class RagUpsertRequest {
    private String id;
    private String text;
    private Map<String, Object> meta;
    private String collection;
}
