package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.domain.workflow.ChatMatchConfig;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves chat triggers.
 * Chat triggers are fired when user sends a message.
 * Output (canonical snake_case, runtime-ready):
 *   { message, extracted_message, conversation_id, matched, match_type, match_value,
 *     triggered_at, triggered_by, attachments[], data[], count }
 *
 * All keys are snake_case at resolver level so that SpEL templates like
 * {{trigger:chat.output.extracted_message}} resolve correctly at runtime.
 * Attachments are flattened from raw FileRef shape to proxy-URL shape here,
 * so the runtime stepOutputs and the persisted DB shape are identical.
 *
 * The trigger's chatMatch configuration determines matching behavior:
 * - ANY: Any message matches (default)
 * - STARTS_WITH: Message must start with configured value (prefix is trimmed from extracted_message)
 * - ENDS_WITH: Message must end with configured value (suffix is trimmed from extracted_message)
 * - CONTAINS: Message must contain configured value
 * - EQUALS: Message must exactly equal configured value
 * - REGEX: Message must match configured regex pattern
 */
@Slf4j
@Component
public class ChatTriggerResolver implements TriggerTypeHandler {

    @Autowired
    private TriggerUserResolver triggerUserResolver;

    @Override
    public boolean canHandle(String triggerType) {
        return "chat".equalsIgnoreCase(triggerType);
    }

    @Override
    public Map<String, Object> resolve(Trigger trigger, String tenantId, Map<String, Object> resolvedInputs) {
        log.info("Resolving chat trigger: {} for tenant: {}", trigger.id(), tenantId);

        Map<String, Object> payload = new HashMap<>();
        payload.put("triggerId", trigger.id());
        payload.put("tenantId", tenantId);
        payload.put("type", "chat");
        payload.put("source", "chat");

        // Unified snake_case user context: triggered_by = display name, empty when unknown.
        String triggeredBy = triggerUserResolver.resolveDisplayName(tenantId);

        // Get message from resolved inputs
        String message = "";
        if (resolvedInputs != null && resolvedInputs.containsKey("message")) {
            message = String.valueOf(resolvedInputs.get("message"));
        }

        // Pass through conversation_id (snake_case canonical key) if provided.
        // Also accept legacy camelCase conversationId from older callers.
        if (resolvedInputs != null) {
            Object convId = resolvedInputs.containsKey("conversation_id")
                    ? resolvedInputs.get("conversation_id")
                    : resolvedInputs.get("conversationId");
            if (convId != null) {
                payload.put("conversation_id", convId);
            }
        }

        // Flatten attachments from raw FileRef shape to proxy-URL shape.
        // This must happen at resolver level so runtime SpEL sees the correct shape.
        List<Map<String, Object>> flattenedAttachments = null;
        if (resolvedInputs != null && resolvedInputs.get("attachments") instanceof List<?> list && !list.isEmpty()) {
            flattenedAttachments = flattenAttachments(list);
            payload.put("attachments", flattenedAttachments);
            log.info("Chat trigger {} includes {} attachment(s)", trigger.id(), list.size());
        }

        // Get the chat match configuration (defaults to ANY if not configured)
        ChatMatchConfig matchConfig = trigger.chatMatch();
        if (matchConfig == null) {
            matchConfig = ChatMatchConfig.any();
        }

        // Check if message matches the configured pattern
        boolean matched = matchConfig.matches(message);
        String extractedMessage = matched ? matchConfig.extractMessage(message) : message;
        String triggeredAt = java.time.Instant.now().toString();

        if (matched) {
            buildMatchedPayload(payload, trigger, message, extractedMessage, triggeredAt, triggeredBy, matchConfig, flattenedAttachments);
        } else {
            buildUnmatchedPayload(payload, trigger, message, matchConfig);
        }

        payload.put("triggered_at", triggeredAt);
        payload.put("triggered_by", triggeredBy);
        return payload;
    }

    /**
     * Normalise raw FileRef maps to the canonical FileRef shape.
     * Shape: { _type:'file', path, name, mimeType, size } - matches the runtime
     * {@code FileRef} record so {@code {{trigger:chat.output.attachments[0]}}}
     * resolves to a tokenised URL (logged-in app) or HMAC-signed URL (marketplace
     * + share preview) via the same rewriter chain as the 4 file-producer nodes.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> flattenAttachments(List<?> rawList) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> rawMap) {
                Map<String, Object> file = (Map<String, Object>) rawMap;

                // Strict contract: only normalise maps that carry _type='file'.
                // A plain Map field without _type is left as-is (not treated as a FileRef).
                if (!"file".equals(file.get("_type"))) {
                    log.warn("Chat attachment skipped: missing or non-'file' _type - passing through as-is");
                    result.add(file);
                    continue;
                }

                Map<String, Object> entry = new HashMap<>();
                entry.put("_type", "file");
                Object path = file.get("path");
                if (path != null) entry.put("path", path);
                entry.put("name", file.getOrDefault("name", file.get("fileName")));
                entry.put("mimeType", file.getOrDefault("mimeType", file.get("contentType")));
                entry.put("size", file.getOrDefault("size", file.get("sizeBytes")));
                result.add(entry);
            }
        }
        return result;
    }

    private void buildMatchedPayload(Map<String, Object> payload, Trigger trigger, String message,
                                     String extractedMessage, String triggeredAt, String triggeredBy,
                                     ChatMatchConfig matchConfig, List<Map<String, Object>> attachments) {
        payload.put("status", "success");
        payload.put("message", message);
        payload.put("extracted_message", extractedMessage);
        payload.put("matched", true);
        payload.put("match_type", matchConfig.type().name().toLowerCase(Locale.ROOT));
        if (matchConfig.value() != null) {
            payload.put("match_value", matchConfig.value());
        }

        Map<String, Object> dataItem = new HashMap<>();
        dataItem.put("message", message);
        dataItem.put("extracted_message", extractedMessage);
        dataItem.put("triggered_at", triggeredAt);
        dataItem.put("triggered_by", triggeredBy);
        dataItem.put("matched", true);
        if (attachments != null && !attachments.isEmpty()) {
            dataItem.put("attachments", attachments);
        }
        payload.put("data", List.of(dataItem));
        payload.put("count", 1);

        log.info("Chat trigger {} matched with match_type={}, message='{}', extracted_message='{}'",
                trigger.id(), matchConfig.type(), message, extractedMessage);
    }

    private void buildUnmatchedPayload(Map<String, Object> payload, Trigger trigger, String message,
                                       ChatMatchConfig matchConfig) {
        payload.put("status", "no_match");
        payload.put("message", message);
        payload.put("extracted_message", message);
        payload.put("matched", false);
        payload.put("match_type", matchConfig.type().name().toLowerCase(Locale.ROOT));
        if (matchConfig.value() != null) {
            payload.put("match_value", matchConfig.value());
        }
        payload.put("data", List.of());
        payload.put("count", 0);

        log.info("Chat trigger {} did NOT match - match_type={}, match_value='{}', message='{}'",
                trigger.id(), matchConfig.type(), matchConfig.value(), message);
    }
}
