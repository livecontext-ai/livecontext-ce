package com.apimarketplace.orchestrator.domain.workflow;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Resultat d'une récupération paginée pour un trigger datasource.
 */
public record TriggerBatchResult(
    Trigger trigger,
    String tenantId,
    List<Map<String, Object>> items,
    int offset,
    int limit,
    int totalCount,
    boolean hasMore,
    int nextOffset,
    int realTotalCount
) {

    public TriggerBatchResult {
        items = items != null ? List.copyOf(items) : List.of();
        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (totalCount < 0) {
            throw new IllegalArgumentException("totalCount cannot be negative");
        }
        if (nextOffset < 0) {
            throw new IllegalArgumentException("nextOffset cannot be negative");
        }
        if (realTotalCount < 0) {
            throw new IllegalArgumentException("realTotalCount cannot be negative");
        }
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Map<String, Object> toLegacyPayload() {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("triggerId", trigger != null ? trigger.id() : null);
        payload.put("tenantId", tenantId);
        payload.put("data", items);
        payload.put("count", items.size());
        payload.put("totalCount", totalCount);
        payload.put("realTotalCount", realTotalCount);
        payload.put("offset", offset);
        payload.put("limit", limit);
        payload.put("hasMore", hasMore);
        payload.put("nextOffset", hasMore ? nextOffset : null);
        payload.put("status", "success");
        payload.put("source", "datasource");
        return payload;
    }

    public TriggerBatchResult withEmptyItems() {
        return new TriggerBatchResult(trigger, tenantId, Collections.emptyList(), offset, limit, totalCount, false, offset, realTotalCount);
    }
}
