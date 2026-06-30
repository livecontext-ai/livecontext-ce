package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.TriggerBatchResult;
import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds trigger payloads for error scenarios.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerPayloadBuilder {

    private final WorkflowExecutionConfig config;

    /**
     * Keys that a non-event datasource payload may write (batch-scan via
     * {@code TriggerBatchResult.toLegacyPayload}, post-writes in
     * {@code DataSourceTriggerResolver.resolveRealDatasource}, cap annotations, and error
     * payloads). A flattened event column carrying any of these names would collide - the
     * legacy value wins (via the {@code payload.containsKey} check in
     * {@link #promoteEventFields}) and the user column is silently shadowed. We log a WARN
     * when this happens so the collision is observable without crashing the run.
     *
     * <p><strong>Scope:</strong> this is the batch-scan / error payload key set, NOT the
     * full payload key set. The promoted event keys themselves ({@code event_type},
     * {@code row}, {@code previous_row}, {@code row_id}, {@code datasource_id},
     * {@code triggered_at}) are deliberately NOT included - they are the keys we WANT to
     * land at the top level. Do not add them here "for safety" or you will break the
     * feature.</p>
     */
    private static final Set<String> LEGACY_RESERVED_KEYS = Set.of(
            "triggerId", "tenantId", "data", "count", "totalCount", "realTotalCount",
            "offset", "limit", "hasMore", "nextOffset", "status", "source", "strategy",
            "maxItemsCap", "maxItemsReached", "_inputs",
            // Reserved by buildErrorPayload. Today unreachable (error path never calls
            // promoteEventFields), listed defensively so a future refactor that routes
            // errors through promotion still shadows user columns named 'error'/'message'.
            "error", "message");

    /**
     * Build an error payload for a trigger.
     */
    public Map<String, Object> buildErrorPayload(Trigger trigger, String tenantId, String errorCode, String message) {
        Map<String, Object> error = new HashMap<>();
        error.put("triggerId", trigger != null ? trigger.id() : null);
        error.put("tenantId", tenantId);
        error.put("status", "error");
        error.put("error", errorCode);
        if (message != null && !message.isBlank()) {
            error.put("message", message);
        }
        error.put("data", List.of());
        error.put("count", 0);
        error.put("source", "datasource");
        return error;
    }

    /**
     * Promote event-driven datasource trigger fields (delivered via resolvedInputs on
     * row_created/row_updated/row_deleted fires) to the top level of the payload so the
     * V97-documented references like {{trigger:&lt;label&gt;.output.event_type}} resolve.
     *
     * Legacy batch-scan keys (written by {@code TriggerBatchResult.toLegacyPayload}) take
     * precedence via {@code putIfAbsent} - a user column named {@code status}, {@code count},
     * {@code data}, etc. would be shadowed. A WARN is emitted when a reserved-key collision
     * is detected so the silent shadowing is observable.
     *
     * Null event values are skipped (row_created has {@code previous_row == null}; storing
     * null would then break {@link com.apimarketplace.orchestrator.domain.WorkflowExecutionContext}
     * whose backing ConcurrentHashMap rejects null values).
     *
     * No-op when {@code resolvedInputs} does not carry an {@code event_type} marker.
     */
    public void promoteEventFields(Map<String, Object> payload, Map<String, Object> resolvedInputs) {
        if (resolvedInputs == null || !resolvedInputs.containsKey("event_type")) {
            return;
        }
        for (Map.Entry<String, Object> e : resolvedInputs.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (value == null) {
                continue;
            }
            if (payload.containsKey(key)) {
                if (LEGACY_RESERVED_KEYS.contains(key)) {
                    log.warn("[TriggerPayloadBuilder] Flattened event column '{}' collides with legacy "
                                    + "batch-scan key - legacy value wins. The user column is reachable via "
                                    + "{{{{trigger:<label>.output._inputs.{}}}}} or {{{{trigger:<label>.output.row.{}}}}}. "
                                    + "Consider renaming the column to avoid this collision.",
                            key, key, key);
                }
                continue;
            }
            payload.put(key, value);
        }
    }

    /**
     * Annotate payload with cap metadata from batch result.
     */
    public void annotateCapMetadata(Map<String, Object> payload, TriggerBatchResult batch) {
        int maxItemsCap = config.getMaxDatasourceItems();
        if (maxItemsCap > 0) {
            payload.put("maxItemsCap", maxItemsCap);
            if (batch.realTotalCount() > batch.totalCount()) {
                payload.put("maxItemsReached", true);
            }
        }
        payload.put("realTotalCount", batch.realTotalCount());
    }
}
