package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.TriggerItemContext;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds TriggerItemContext objects for workflow execution.
 */
@Component
@RequiredArgsConstructor
public class TriggerItemContextBuilder {

    private final WorkflowExecutionConfig config;
    private final TriggerPayloadBuilder payloadBuilder;

    /**
     * Build a single TriggerItemContext.
     */
    public TriggerItemContext buildTriggerItemContext(
            String runId,
            String tenantId,
            Trigger trigger,
            Map<String, Object> rawPayload,
            int batchIndex,
            int absoluteIndex,
            int totalCount,
            boolean hasMore) {
        return buildTriggerItemContext(runId, tenantId, trigger, rawPayload, batchIndex, absoluteIndex, totalCount, hasMore, null);
    }

    /**
     * Build a single TriggerItemContext with optional execution context.
     */
    public TriggerItemContext buildTriggerItemContext(
            String runId,
            String tenantId,
            Trigger trigger,
            Map<String, Object> rawPayload,
            int batchIndex,
            int absoluteIndex,
            int totalCount,
            boolean hasMore,
            WorkflowExecution execution) {

        Objects.requireNonNull(runId, "runId cannot be null");
        Objects.requireNonNull(trigger, "trigger cannot be null");

        Map<String, Object> payload = new HashMap<>();
        if (rawPayload != null) {
            payload.putAll(rawPayload);
        }

        return TriggerItemContext.builder()
                .runId(runId)
                .triggerId(trigger.getNormalizedKey())
                .tenantId(tenantId)
                .payload(payload)
                .batchIndex(batchIndex)
                .absoluteIndex(absoluteIndex)
                .totalCount(Math.max(totalCount, 0))
                .hasMore(hasMore)
                .build();
    }

    /**
     * Build a list of TriggerItemContexts from a list of items.
     */
    public List<TriggerItemContext> buildTriggerItemContexts(
            String runId,
            String tenantId,
            Trigger trigger,
            List<Map<String, Object>> items,
            int offset,
            int totalCount,
            boolean hasMore) {

        if (items == null || items.isEmpty()) {
            return List.of();
        }

        List<TriggerItemContext> contexts = new ArrayList<>(items.size());
        int safeTotal = Math.max(totalCount, items.size());

        for (int i = 0; i < items.size(); i++) {
            int absoluteIndex = offset + i;
            boolean itemHasMore = hasMore || absoluteIndex + 1 < safeTotal;
            contexts.add(buildTriggerItemContext(
                    runId,
                    tenantId,
                    trigger,
                    items.get(i),
                    i,
                    absoluteIndex,
                    safeTotal,
                    itemHasMore
            ));
        }
        return contexts;
    }
}
