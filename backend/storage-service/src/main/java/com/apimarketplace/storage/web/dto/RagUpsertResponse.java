package com.apimarketplace.storage.web.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Reponse d'indexation RAG
 */
@Data
@Builder
public class RagUpsertResponse {
    private String id;
    private String status;
    private int vectorCount;
    private long indexedAt;
    private String error;
}
