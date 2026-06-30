package com.apimarketplace.agent.controller;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.dto.ChatAgentObservabilityRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal endpoint for recording agent observability data from other services
 * (e.g., conversation-service recording chat agent executions).
 */
@RestController
@RequestMapping("/api/internal/agent-observability")
public class AgentObservabilityInternalController {

    private static final Logger logger = LoggerFactory.getLogger(AgentObservabilityInternalController.class);

    private final AgentObservabilityService observabilityService;
    private final TenantResolver tenantResolver;

    public AgentObservabilityInternalController(
            AgentObservabilityService observabilityService,
            TenantResolver tenantResolver) {
        this.observabilityService = observabilityService;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Record a chat agent execution for observability.
     * Called by conversation-service after agent streaming completes.
     */
    @PostMapping("/record")
    public ResponseEntity<Map<String, String>> recordChatExecution(
            HttpServletRequest httpRequest,
            @RequestBody ChatAgentObservabilityRequest request) {

        String tenantId = tenantResolver.resolve(httpRequest);
        // PR20 - workspace identity from the inbound header (PR16 forwarder set
        // this on the conversation-service → agent-service call). NULL = personal.
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        try {
            observabilityService.recordFromChat(tenantId, organizationId, request);
            logger.info("Recorded chat agent observability: agentId={}, provider={}, model={}",
                request.agentEntityId(), request.provider(), request.model());
            return ResponseEntity.ok(Map.of("status", "recorded"));
        } catch (Exception e) {
            logger.error("Failed to record chat agent observability: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to record: " + e.getMessage()));
        }
    }
}
