package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response node - Sends a message response to chat and continues.
 *
 * Flow:
 * 1. Resolve message template using SpEL
 * 2. Output the message (will be sent via streaming by event service)
 * 3. Continue to successors
 *
 * Usage:
 * - Send intermediate responses during workflow
 * - Provide status updates to users
 * - Return data to chat interface
 */
public class ResponseNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(ResponseNode.class);

    private final String messageTemplate;

    public ResponseNode(String nodeId, String messageTemplate) {
        super(nodeId, NodeType.RESPONSE);
        this.messageTemplate = messageTemplate != null ? messageTemplate : "";
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("💬 Response node executing: nodeId={}, itemId={}",
            nodeId, context.itemId());

        // Captured outside the try so failure paths still surface the resolved message
        // to the inspector "Resolved parameters" panel.
        String resolvedMessage = messageTemplate;

        try {
            if (templateAdapter != null && messageTemplate != null && !messageTemplate.isBlank()) {
                // Resolve the message template using SpEL
                Map<String, Object> toResolve = Map.of("__message__", messageTemplate);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object messageValue = resolved.get("__message__");
                resolvedMessage = messageValue != null ? messageValue.toString() : messageTemplate;
            } else {
                resolvedMessage = messageTemplate;
            }

            // Build resolved_params snapshot for inspector visibility (resolved values only)
            Map<String, Object> resolvedParams = new LinkedHashMap<>();
            resolvedParams.put("message", resolvedMessage);

            Map<String, Object> result = new HashMap<>();
            result.put("resolved_params", resolvedParams);
            result.put("node_type", "RESPONSE");
            result.put("message", resolvedMessage);
            result.put("sent_at", Instant.now().toString());
            result.put("item_index", context.itemIndex());
            result.put("item_id", context.itemId());

            logger.info("✅ Response sent: nodeId={}, messageLength={}",
                nodeId, resolvedMessage != null ? resolvedMessage.length() : 0);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("❌ Response execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failResolvedParams = new LinkedHashMap<>();
            failResolvedParams.put("message", resolvedMessage);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "RESPONSE");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", failResolvedParams);
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    public String getMessageTemplate() {
        return messageTemplate;
    }

    public static class Builder {
        private String nodeId;
        private String messageTemplate;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder messageTemplate(String messageTemplate) {
            this.messageTemplate = messageTemplate;
            return this;
        }

        public ResponseNode build() {
            return new ResponseNode(nodeId, messageTemplate);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
