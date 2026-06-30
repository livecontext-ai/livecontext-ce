package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves webhook triggers.
 * Webhook triggers are fired when an external HTTP call is received.
 * Output: { payload: object, triggeredAt: ISO timestamp }
 *
 * The webhook payload is passed in resolvedInputs.payload when the webhook call is received.
 */
@Slf4j
@Component
public class WebhookTriggerResolver implements TriggerTypeHandler {

    /**
     * F7: hard cap on the webhook payload size in bytes (UTF-8 JSON). Above
     * this, the payload is replaced with a `{"_orchestrator_oversized": true}`
     * stub before landing in {@code workflow_runs.trigger_payload}. 5 MB
     * accommodates legitimate large webhooks (GitHub squashed-merge pushes,
     * Stripe invoice events with line items, Slack event_callback with
     * embedded files) while staying bounded against the OOM 2026-05-07
     * incident vector (RunCloneService replay loops + Hibernate L1 fetches
     * over workflow_runs).
     *
     * Configurable via the {@code orchestrator.webhook.max-payload-bytes}
     * property so operators can tighten or relax per environment without
     * a code change.
     */
    static final int DEFAULT_MAX_WEBHOOK_PAYLOAD_BYTES = 5 * 1_048_576; // 5 MB

    @org.springframework.beans.factory.annotation.Value(
            "${orchestrator.webhook.max-payload-bytes:" + DEFAULT_MAX_WEBHOOK_PAYLOAD_BYTES + "}")
    int maxWebhookPayloadBytes;

    final ObjectMapper sizingMapper = new ObjectMapper();

    @Autowired
    private TriggerUserResolver triggerUserResolver;

    /**
     * Sentinel raised by {@link SizeLimitOutputStream} when the cap is hit.
     * A dedicated subclass lets {@link #payloadExceedsCap} distinguish "real
     * oversized" from "genuine Jackson failure" - without this, a non-
     * serializable object in the payload would surface as "oversized" too,
     * obscuring a real bug.
     */
    static final class PayloadOversizedException extends IOException {
        PayloadOversizedException(int limit) { super("payload exceeded " + limit + " bytes"); }
    }

    /**
     * Bounded-memory size check via a counting OutputStream that aborts as
     * soon as Jackson writes more than {@code limit} bytes. Avoids the
     * naive {@code writeValueAsBytes} approach which would allocate the
     * full serialized array (and OOM on a 50 MB pathological payload).
     */
    private static final class SizeLimitOutputStream extends OutputStream {
        private final int limit;
        long written = 0;
        SizeLimitOutputStream(int limit) { this.limit = limit; }
        @Override public void write(int b) throws IOException {
            if (++written > limit) throw new PayloadOversizedException(limit);
        }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            written += len;
            if (written > limit) throw new PayloadOversizedException(limit);
        }
    }

    /**
     * Returns {@code true} if serializing {@code payload} to JSON would
     * exceed {@link #maxWebhookPayloadBytes}. Streams via SizeLimit-
     * OutputStream so peak memory stays at Jackson's internal buffer
     * (~8 KB) regardless of input size.
     *
     * <p>Distinguishes the size-limit case (return true) from any other
     * serialization error (logged and returned false - fail-open, the
     * caller will then attempt to persist and if Jackson really cannot
     * write the payload the underlying storage layer will surface it).
     */
    boolean payloadExceedsCap(Object payload) {
        try {
            sizingMapper.writeValue(new SizeLimitOutputStream(maxWebhookPayloadBytes), payload);
            return false;
        } catch (PayloadOversizedException oversized) {
            return true;
        } catch (IOException e) {
            // Genuine Jackson failure (non-serializable object, cyclic ref).
            // Do NOT mis-classify as oversized - let the payload flow through;
            // the persistence layer will surface the real error.
            log.warn("Webhook payload size pre-check failed (non-oversized error), letting payload flow through: [{}] {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean canHandle(String triggerType) {
        return "webhook".equalsIgnoreCase(triggerType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.info("Resolving webhook trigger: {} for tenant: {}", trigger.id(), tenantId);

        Map<String, Object> result = new HashMap<>();
        result.put("triggerId", trigger.id());
        result.put("type", "webhook");
        result.put("source", "webhook");

        String triggeredAt = java.time.Instant.now().toString();
        String triggeredBy = triggerUserResolver.resolveDisplayName(tenantId);
        result.put("triggered_at", triggeredAt);
        result.put("triggered_by", triggeredBy);

        // Get webhook payload from resolved inputs
        Map<String, Object> webhookPayload = new HashMap<>();
        if (resolvedInputs != null && resolvedInputs.containsKey("payload")) {
            Object payloadObj = resolvedInputs.get("payload");
            if (payloadObj instanceof Map) {
                webhookPayload.putAll((Map<String, Object>) payloadObj);
            }
        }

        // F7: enforce the configurable cap before the payload reaches
        // workflow_runs.trigger_payload. Above the cap, replace with a stub
        // so RunCloneService cannot re-inflate the OOM vector across replays.
        // Stub keys are namespaced under `_orchestrator_*` so they cannot
        // collide with legitimate webhook payload keys.
        if (payloadExceedsCap(webhookPayload)) {
            log.error("Webhook payload for trigger {} (tenant {}) exceeded {} bytes - replacing with stub",
                    trigger.id(), tenantId, maxWebhookPayloadBytes);
            Map<String, Object> stub = new HashMap<>();
            stub.put("_orchestrator_oversized", true);
            stub.put("_orchestrator_cap_bytes", maxWebhookPayloadBytes);
            stub.put("_orchestrator_message", "Webhook payload exceeded " + maxWebhookPayloadBytes
                    + " bytes - replaced with summary to protect orchestrator memory. "
                    + "Configure the webhook source to POST smaller payloads or use a file pointer.");
            webhookPayload = stub;
        }

        result.put("status", "success");
        result.put("payload", webhookPayload);

        // Also include in data array for compatibility
        Map<String, Object> dataItem = new HashMap<>();
        dataItem.put("triggered_at", triggeredAt);
        dataItem.put("triggered_by", triggeredBy);
        dataItem.putAll(webhookPayload);
        result.put("data", List.of(dataItem));
        result.put("count", 1);

        // Include custom fields from trigger.params if any
        if (trigger.params() != null && !trigger.params().isEmpty()) {
            Map<String, Object> cleanParams = new HashMap<>(trigger.params());
            cleanParams.remove("webhook"); // Remove webhook config object
            if (!cleanParams.isEmpty()) {
                result.putAll(cleanParams);
                dataItem.putAll(cleanParams);
            }
        }

        log.info("Webhook trigger {} resolved with payload keys: {}", trigger.id(), webhookPayload.keySet());
        return result;
    }
}
