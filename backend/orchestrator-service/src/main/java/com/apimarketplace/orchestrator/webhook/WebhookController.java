package com.apimarketplace.orchestrator.webhook;

import com.apimarketplace.orchestrator.services.approvalchannel.ApprovalCallbackInterceptor;
import com.apimarketplace.trigger.client.webhook.WebhookAuthService;
import com.apimarketplace.trigger.client.webhook.WebhookConfig;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for handling incoming webhook calls.
 * This is an internal endpoint called by the Gateway after route rewriting.
 *
 * Gateway route: /webhook/{token} -> /api/internal/webhook/{token}
 *
 * Supports:
 * - Multiple HTTP methods: GET, POST, PUT, PATCH, DELETE
 * - Authentication: none, basic, header, jwt
 * - Synchronous mode (sync=true): Defers response until RespondToWebhookNode executes
 */
@RestController
@RequestMapping("/api/internal/webhook")
public class WebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WebhookController.class);

    /** Timeout for synchronous webhook responses (60 seconds). */
    private static final long SYNC_TIMEOUT_MS = 60_000;

    private final WebhookDispatchService dispatchService;
    private final WebhookAuthService authService;
    private final WebhookResponseRegistry webhookResponseRegistry;
    private final ApprovalCallbackInterceptor approvalCallbackInterceptor;

    public WebhookController(WebhookDispatchService dispatchService,
                             WebhookAuthService authService,
                             WebhookResponseRegistry webhookResponseRegistry,
                             ApprovalCallbackInterceptor approvalCallbackInterceptor) {
        this.dispatchService = dispatchService;
        this.authService = authService;
        this.webhookResponseRegistry = webhookResponseRegistry;
        this.approvalCallbackInterceptor = approvalCallbackInterceptor;
    }

    /**
     * Handle incoming webhook call with any HTTP method.
     *
     * When sync=true and the dispatch returns "triggered", the response is deferred
     * until a RespondToWebhookNode in the workflow resolves it (or timeout occurs).
     * This allows workflows to control the exact HTTP response returned to the caller.
     *
     * @param token       The webhook token from the URL
     * @param payload     The request body (optional, for POST/PUT/PATCH)
     * @param queryParams Query parameters (used as payload for GET requests)
     * @param headers     HTTP headers for authentication
     * @param request     The HTTP servlet request for method detection
     * @param sync        Whether to wait for workflow response (default: false)
     * @return WebhookResponse or DeferredResult for sync mode
     */
    @RequestMapping(
            value = "/{token}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE}
    )
    public Object handleWebhook(
            @PathVariable("token") String token,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestParam Map<String, String> queryParams,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request,
            @RequestParam(value = "sync", defaultValue = "false") boolean sync) {

        String httpMethod = request.getMethod();
        String tokenPreview = token != null ? token.substring(0, Math.min(8, token.length())) + "..." : "null";

        logger.info("Received webhook call: method={}, token={}, sync={}", httpMethod, tokenPreview, sync);

        // 0. Platform webhook verification (Meta WhatsApp, Facebook, Instagram)
        //    Meta sends GET with hub.mode=subscribe&hub.verify_token=XXX&hub.challenge=YYY
        //    We echo back the challenge as plain text to complete the handshake.
        if ("GET".equalsIgnoreCase(httpMethod) && "subscribe".equals(queryParams.get("hub.mode"))) {
            String challenge = queryParams.get("hub.challenge");
            if (challenge != null) {
                logger.info("Platform webhook verification for token={}, echoing challenge", tokenPreview);
                return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(challenge);
            }
        }

        // 0b. Delegated-approval callback diversion. A Telegram bot has ONE global
        //     webhook URL, usually the workflow trigger's, so approval button clicks
        //     (callback_query with our namespaced lcapr: callback_data) arrive HERE.
        //     They resolve the approval signal and are NOT dispatched to the workflow
        //     (dispatching would open a spurious epoch on the host workflow). Handled
        //     async; 200 immediately (Telegram retries non-2xx aggressively).
        //     Ordinary callback_query payloads (no lcapr: prefix) are unaffected.
        if (approvalCallbackInterceptor.isApprovalCallback(payload)) {
            logger.info("Approval callback diverted from webhook token={}", tokenPreview);
            approvalCallbackInterceptor.handleAsync(payload);
            return ResponseEntity.ok(Map.of("status", "approval_callback_handled"));
        }

        // 1. Get webhook configuration from workflow plan
        WebhookConfig config = dispatchService.getWebhookConfigByToken(token);
        if (config == null) {
            logger.warn("Webhook configuration not found for token: {}", tokenPreview);
            return ResponseEntity.notFound().build();
        }

        // 2. Validate HTTP method
        if (!config.matchesMethod(httpMethod)) {
            String expectedMethod = config.httpMethod() != null ? config.httpMethod() : "POST";
            logger.info("HTTP method mismatch: expected={}, received={}", expectedMethod, httpMethod);
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .body(WebhookResponse.error("Expected " + expectedMethod + " but received " + httpMethod));
        }

        // 3. Validate authentication
        if (config.requiresAuth()) {
            WebhookAuthService.WebhookAuthResult authResult = authService.validateAuth(config, headers);
            if (!authResult.valid()) {
                logger.info("Webhook auth failed for token {}: {}", tokenPreview, authResult.message());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(WebhookResponse.error(authResult.message()));
            }
            logger.debug("Webhook auth validated successfully");
        }

        // 4. Build final payload
        // For GET requests, use query params as payload
        // For other methods, use request body, falling back to query params
        Map<String, Object> finalPayload = buildPayload(httpMethod, payload, queryParams);

        // Add request metadata to payload
        finalPayload.put("_webhookMethod", httpMethod);
        finalPayload.put("_webhookTimestamp", java.time.Instant.now().toString());

        // 5. Dispatch to workflow execution
        WebhookResponse response = dispatchService.dispatch(token, finalPayload, sync);

        // 6. For sync mode with "triggered" status, defer the response
        //    until RespondToWebhookNode resolves it (or timeout)
        if (sync && "triggered".equals(response.status()) && response.executionId() != null) {
            return createDeferredResponse(response);
        }

        return mapResponseToHttpStatus(response);
    }

    /**
     * Create a DeferredResult that waits for RespondToWebhookNode to resolve.
     * On timeout, falls back to the standard 202 Accepted response.
     */
    private DeferredResult<ResponseEntity<?>> createDeferredResponse(WebhookResponse triggerResponse) {
        String runId = triggerResponse.executionId();
        logger.info("Creating deferred webhook response for runId={}, timeout={}ms", runId, SYNC_TIMEOUT_MS);

        DeferredResult<ResponseEntity<?>> deferred = new DeferredResult<>(SYNC_TIMEOUT_MS);

        // On timeout, return the standard 202 Accepted response
        deferred.onTimeout(() -> {
            logger.warn("Deferred webhook response timed out for runId={}, returning 202 Accepted", runId);
            deferred.setResult(ResponseEntity.accepted().body(triggerResponse));
        });

        // Register in the registry so RespondToWebhookNode can resolve it
        webhookResponseRegistry.register(runId, deferred);

        return deferred;
    }

    /**
     * Build the final payload from body and query params based on HTTP method.
     */
    private Map<String, Object> buildPayload(String httpMethod, Map<String, Object> body, Map<String, String> queryParams) {
        Map<String, Object> result = new HashMap<>();

        if ("GET".equalsIgnoreCase(httpMethod)) {
            // GET requests use query params as payload
            if (queryParams != null) {
                // Filter out internal params like 'sync'
                queryParams.forEach((key, value) -> {
                    if (!"sync".equals(key)) {
                        result.put(key, value);
                    }
                });
            }
        } else {
            // Other methods use body, with query params as fallback/supplement
            if (body != null) {
                result.putAll(body);
            }
            // Add query params that aren't in the body
            if (queryParams != null) {
                queryParams.forEach((key, value) -> {
                    if (!"sync".equals(key) && !result.containsKey(key)) {
                        result.put(key, value);
                    }
                });
            }
        }

        return result;
    }

    /**
     * Map WebhookResponse status to appropriate HTTP status.
     */
    private ResponseEntity<WebhookResponse> mapResponseToHttpStatus(WebhookResponse response) {
        return switch (response.status()) {
            case "accepted", "triggered" -> ResponseEntity.accepted().body(response);
            case "completed" -> ResponseEntity.ok(response);
            case "not_found" -> ResponseEntity.notFound().build();
            case "not_active" -> ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            case "insufficient_credits" -> ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(response);
            case "rate_limited" -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(response);
            case "method_not_allowed" -> ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
            case "unauthorized" -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            case "error" -> ResponseEntity.badRequest().body(response);
            default -> ResponseEntity.internalServerError().body(response);
        };
    }

    /**
     * Health check endpoint for webhook service.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "webhook"));
    }
}
