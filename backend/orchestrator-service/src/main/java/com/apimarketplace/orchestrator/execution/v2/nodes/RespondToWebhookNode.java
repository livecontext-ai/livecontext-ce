package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.template.ReportedParams;
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
        Map<String, String> configuredHeaders = respondToWebhookConfig != null ? respondToWebhookConfig.headers() : Map.of();

        logger.info("RespondToWebhook node executing: nodeId={}, statusCode={}, contentType={}, itemId={}",
            nodeId, statusCode, contentType, context.itemId());

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        String resolvedBody = null;
        // The configured headers until they are resolved, so a failure before that still reports them.
        Map<String, String> headers = configuredHeaders;
        // A statusCode written as {{...}}: the template until it resolves, then its value.
        String statusTemplate = deferredScalar("respondToWebhook", "statusCode");
        Object reportedStatus = statusTemplate != null ? statusTemplate : statusCode;

        try {
            Core.RespondToWebhookConfig cfg = withDeferredScalars(
                "respondToWebhook", respondToWebhookConfig, Core.RespondToWebhookConfig.class, context);
            if (cfg != null) {
                statusCode = cfg.statusCode();
            }
            reportedStatus = statusTemplate != null ? ReportedParams.valueFrom(statusTemplate, statusCode) : statusCode;

            // Header VALUES accept references (the builder offers free-text inputs for them); the
            // names are literal. They used to be sent exactly as typed, so a
            // {{core:x.output.token}} reached the caller as that text while the body beside it resolved.
            headers = resolveHeaderValues(configuredHeaders, context);

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
            result.put("resolved_params", buildInputDataMap(reportedStatus, contentType, resolvedBody, headers));

            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("RespondToWebhook execution failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("node_type", "RESPOND_TO_WEBHOOK");
            failOutput.put("item_index", context.itemIndex());
            failOutput.put("itemIndex", context.itemIndex());
            failOutput.put("item_id", context.itemId());
            failOutput.put("resolved_params", buildInputDataMap(reportedStatus, contentType, resolvedBody, headers));
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
        // One resolver for every field of every node: typed, JSON for a structure, never the
        // configured template in place of a value (BaseNode#resolveTemplateValue).
        return resolveTemplateString(expression, context);
    }

    private Map<String, String> resolveHeaderValues(Map<String, String> configured, ExecutionContext context) {
        if (configured == null || configured.isEmpty()) {
            return configured;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        configured.forEach((name, value) -> {
            String resolvedValue = value == null || value.isBlank() ? value : resolveTemplateString(value, context);
            resolved.put(name, resolvedValue != null ? resolvedValue : "");
        });
        return resolved;
    }

    private Map<String, Object> buildInputDataMap(Object statusCode, String contentType, String bodyExpression,
                                                  Map<String, String> headers) {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("statusCode", statusCode);
        inputData.put("contentType", contentType);
        if (bodyExpression != null) {
            inputData.put("body", bodyExpression);
        }
        // RespondToWebhookConfig has `headers` and the node was sending them on the
        // wire without ever reporting them, so a reader debugging a response could
        // not see the headers they configured. The label registry already had an
        // entry for the key, waiting on a node that never sent it.
        if (headers != null && !headers.isEmpty()) {
            // Through the gate: an `Authorization` or a `Set-Cookie` an author configured
            // here is a credential under a name the word rules read, and this map is
            // persisted and published as `{{core:x.input.headers}}`.
            // A value pulled from a workspace variable is withheld: its header NAME says nothing
            // ("X-Tier"), so the word rules cannot see it.
            Map<String, String> configured = respondToWebhookConfig != null
                ? respondToWebhookConfig.headers() : Map.of();
            Map<String, Object> reported = new java.util.LinkedHashMap<>();
            headers.forEach((name, value) -> reported.put(name,
                ReportedParams.referencesAnyWorkspaceVariable(configured != null ? configured.get(name) : null)
                    ? ReportedParams.valueFrom(configured.get(name), value)
                    : value));
            inputData.put("headers", ReportedParams.reportValue(reported));
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
