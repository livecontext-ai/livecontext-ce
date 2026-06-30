package com.apimarketplace.agent.webhook;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for handling incoming agent webhook calls.
 * This is an internal endpoint called by the Gateway after route rewriting.
 *
 * Gateway route: /webhook/agent/{token} -> /api/internal/webhook/agent/{token}
 *
 * Supports:
 * - Multiple HTTP methods: GET, POST, PUT, PATCH, DELETE
 * - Authentication: none, basic, header, jwt
 */
@RestController
@RequestMapping("/api/internal/webhook/agent")
public class AgentWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(AgentWebhookController.class);

    private final AgentWebhookDispatchService dispatchService;
    private final AgentWebhookAuthService authService;

    public AgentWebhookController(AgentWebhookDispatchService dispatchService,
                                  AgentWebhookAuthService authService) {
        this.dispatchService = dispatchService;
        this.authService = authService;
    }

    /**
     * Handle incoming agent webhook call with any HTTP method.
     *
     * @param token       The webhook token from the URL
     * @param payload     The request body (optional, for POST/PUT/PATCH)
     * @param queryParams Query parameters (used as payload for GET requests)
     * @param headers     HTTP headers for authentication
     * @param request     The HTTP servlet request for method detection
     * @param sync        Whether to wait for agent response (default: false)
     * @return AgentWebhookResponse with execution status
     */
    @RequestMapping(
            value = "/{token}",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE}
    )
    public ResponseEntity<AgentWebhookResponse> handleWebhook(
            @PathVariable("token") String token,
            @RequestBody(required = false) Map<String, Object> payload,
            @RequestParam Map<String, String> queryParams,
            @RequestHeader HttpHeaders headers,
            HttpServletRequest request,
            @RequestParam(value = "sync", defaultValue = "false") boolean sync) {

        String httpMethod = request.getMethod();
        String tokenPreview = token != null ? token.substring(0, Math.min(8, token.length())) + "..." : "null";

        logger.info("Received agent webhook call: method={}, token={}, sync={}", httpMethod, tokenPreview, sync);

        // 1. Get webhook configuration
        AgentWebhookConfig config = dispatchService.getWebhookConfigByToken(token);
        if (config == null) {
            logger.warn("Agent webhook configuration not found for token: {}", tokenPreview);
            return ResponseEntity.notFound().build();
        }

        // 2. Validate HTTP method
        if (!config.matchesMethod(httpMethod)) {
            String expectedMethod = config.httpMethod() != null ? config.httpMethod() : "POST";
            logger.info("HTTP method mismatch: expected={}, received={}", expectedMethod, httpMethod);
            return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                    .body(AgentWebhookResponse.methodNotAllowed(expectedMethod));
        }

        // 3. Validate authentication
        if (config.requiresAuth()) {
            AgentWebhookAuthService.AuthResult authResult = authService.validateAuth(config, headers);
            if (!authResult.valid()) {
                logger.info("Agent webhook auth failed for token {}: {}", tokenPreview, authResult.message());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(AgentWebhookResponse.unauthorized(authResult.message()));
            }
            logger.debug("Agent webhook auth validated successfully");
        }

        // 4. Build final payload
        Map<String, Object> finalPayload = buildPayload(httpMethod, payload, queryParams);

        // Add request metadata
        finalPayload.put("_webhookMethod", httpMethod);
        finalPayload.put("_webhookTimestamp", java.time.Instant.now().toString());

        // 5. Dispatch to agent
        AgentWebhookResponse response = dispatchService.dispatch(token, finalPayload, sync);

        return mapResponseToHttpStatus(response);
    }

    /**
     * Build the final payload from body and query params based on HTTP method.
     */
    private Map<String, Object> buildPayload(String httpMethod, Map<String, Object> body, Map<String, String> queryParams) {
        Map<String, Object> result = new HashMap<>();

        if ("GET".equalsIgnoreCase(httpMethod)) {
            // GET requests use query params as payload
            if (queryParams != null) {
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
     * Map AgentWebhookResponse status to appropriate HTTP status.
     */
    private ResponseEntity<AgentWebhookResponse> mapResponseToHttpStatus(AgentWebhookResponse response) {
        return switch (response.status()) {
            case "accepted" -> ResponseEntity.accepted().body(response);
            case "success" -> ResponseEntity.ok(response);
            case "not_found" -> ResponseEntity.notFound().build();
            case "inactive" -> ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            case "method_not_allowed" -> ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(response);
            case "unauthorized" -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
            case "error" -> ResponseEntity.badRequest().body(response);
            default -> ResponseEntity.internalServerError().body(response);
        };
    }

    /**
     * Health check endpoint for agent webhook service.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok", "service", "agent-webhook"));
    }
}
