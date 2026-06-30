package com.apimarketplace.orchestrator.controllers.agent;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Thin proxy that forwards chat agent observability data to agent-service.
 * Called by conversation-service after agent streaming completes.
 * Disabled in monolith mode (agent-service controller handles it directly).
 */
@RestController
@RequestMapping("/api/internal/agent-observability")
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class AgentObservabilityInternalController {

    private static final Logger logger = LoggerFactory.getLogger(AgentObservabilityInternalController.class);

    private final AgentClient agentClient;
    private final TenantResolver tenantResolver;

    public AgentObservabilityInternalController(
            AgentClient agentClient,
            TenantResolver tenantResolver) {
        this.agentClient = agentClient;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Record a chat agent execution for observability.
     * Forwards the request to agent-service.
     */
    @PostMapping("/record")
    public ResponseEntity<Map<String, String>> recordChatExecution(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);

        try {
            agentClient.recordChatObservability(tenantId, request);
            logger.info("Forwarded chat agent observability to agent-service: agentId={}, provider={}, model={}",
                request.get("agentEntityId"), request.get("provider"), request.get("model"));
            return ResponseEntity.ok(Map.of("status", "recorded"));
        } catch (Exception e) {
            logger.error("Failed to forward chat agent observability: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to record: " + e.getMessage()));
        }
    }
}
