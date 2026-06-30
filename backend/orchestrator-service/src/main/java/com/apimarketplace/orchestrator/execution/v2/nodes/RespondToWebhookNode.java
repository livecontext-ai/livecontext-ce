package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.webhook.WebhookResponseRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RespondToWebhook node - Controls the HTTP response returned to a webhook caller.
 *
 * When a webhook is called with sync=true, a DeferredResult is registered in the
 * WebhookResponseRegistry. This node resolves that deferred result with a custom
 * HTTP response (status code, body, content type, headers).
 *
 * If no pending deferred result exists (e.g., workflow was triggered manually or
 * the webhook was called without sync=true), the node logs a warning and succeeds
 * with responded=false. This allows the same workflow to work in both sync webhook
 * and manual trigger scenarios.
 *
 * Usage:
 * - Return custom JSON/HTML/text responses to webhook callers
 * - Set custom HTTP status codes (200, 201, 400, etc.)
 * - Add custom response headers
 * - Use SpEL expressions in the body to include workflow data
 */
public class RespondToWebhookNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(RespondToWebhookNode.class);

    private final Core.RespondToWebhookConfig respondToWebhookConfig;

    // Injected via setter or acceptServices
    private WebhookResponseRegistry webhookResponseRegistry;

    public RespondToWebhookNode(String nodeId, Core.RespondToWebhookConfig respondToWebhookConfig) {
        super(nodeId, NodeType.RESPOND_TO_WEBHOOK);
        this.respondToWebhookConfig = respondToWebhookConfig;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        int statusCode = respondToWebhookConfig != null ? respondToWebhookConfig.statusCode() : 200;
        String contentType = respondToWebhookConfig != null ? respondToWebhookConfig.contentType() : "application/json";
        Map<String, String> headers = respondToWebhookConfig != null ? respondToWebhookConfig.headers() : Map.of();

        logger.info("RespondToWebhook node executing: nodeId={}, statusCode={}, contentType={}, itemId={}",
            nodeId, statusCode, contentType, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        String resolvedBody = null;

        try {
            // Resolve body expression using SpEL template adapter
            String bodyExpression = respondToWebhookConfig != null ? respondToWebhookConfig.body() : null;
            resolvedBody = resolveExpression(bodyExpression, context);

            // Build the ResponseEntity
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set("Content-Type", contentType);
            if (headers != null) {
                headers.forEach(httpHeaders::set);
            }

            ResponseEntity<?> responseEntity = new ResponseEntity<>(
                resolvedBody,
                httpHeaders,
                HttpStatus.valueOf(statusCode)
            );

            // Try to resolve the deferred webhook response
            boolean responded = false;
            if (webhookResponseRegistry != null) {
                responded = webhookResponseRegistry.resolve(context.runId(), responseEntity);
            }

            if (!responded) {
                logger.warn("No pending webhook response for runId={} (workflow may not have been triggered by a sync webhook)",
                    context.runId());
            } else {
                logger.info("Webhook response sent: nodeId={}, statusCode={}, runId={}", nodeId, statusCode, context.runId());
            }

            // Build output
            Map<String, Object> result = new HashMap<>();
            result.put("responded", responded);
            result.put("statusCode", statusCode);
            result.put("contentType", contentType);

            // MANDATORY metadata
            result.put("node_type", "RESPOND_TO_WEBHOOK");
            result.put("item_index", context.itemIndex());
            result.put("itemIndex", context.itemIndex());
            result.put("item_id", context.itemId());
            result.put("resolved_params", buildInputDataMap(statusCode, contentType, resolvedBody));

            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("RespondToWebhook execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "RESPOND_TO_WEBHOOK");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", buildInputDataMap(statusCode, contentType, resolvedBody));
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    /**
     * Resolve a SpEL expression using the template adapter.
     */
    private String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        if (templateAdapter != null) {
            try {
                Map<String, Object> toResolve = Map.of("__expr__", expression);
                Map<String, Object> resolved = templateAdapter.resolveTemplates(toResolve, context);
                Object result = resolved.get("__expr__");
                return result != null ? String.valueOf(result) : expression;
            } catch (Exception e) {
                logger.warn("Failed to resolve body expression '{}': {}", expression, e.getMessage());
                return expression;
            }
        }

        return expression;
    }

    private Map<String, Object> buildInputDataMap(int statusCode, String contentType, String bodyExpression) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("statusCode", statusCode);
        inputData.put("contentType", contentType);
        if (bodyExpression != null) {
            inputData.put("body", bodyExpression);
        }
        return inputData;
    }

    // Getters
    public Core.RespondToWebhookConfig getRespondToWebhookConfig() {
        return respondToWebhookConfig;
    }

    // ========================================================================
    // SERVICE INJECTION
    // ========================================================================

    public void setWebhookResponseRegistry(WebhookResponseRegistry webhookResponseRegistry) {
        this.webhookResponseRegistry = webhookResponseRegistry;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.webhookResponseRegistry = registry.getWebhookResponseRegistry();
    }

    // ========================================================================
    // BUILDER
    // ========================================================================

    public static class Builder {
        private String nodeId;
        private Core.RespondToWebhookConfig respondToWebhookConfig;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder respondToWebhookConfig(Core.RespondToWebhookConfig config) {
            this.respondToWebhookConfig = config;
            return this;
        }

        public RespondToWebhookNode build() {
            return new RespondToWebhookNode(nodeId, respondToWebhookConfig);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
