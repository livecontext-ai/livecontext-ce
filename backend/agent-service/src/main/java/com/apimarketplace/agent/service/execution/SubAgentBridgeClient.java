package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Set;

/**
 * HTTP client for dispatching sub-agent execution to the bridge server
 * when the sub-agent uses a CLI-based provider (claude-code, codex, gemini-cli, mistral-vibe).
 *
 * Same contract as conversation-service's BridgeClient - POST to /api/bridge/execute
 * with AgentExecutionRequestDto, returns AgentExecutionResponseDto.
 *
 * The bridge runs the CLI agent (e.g. Claude Agent SDK) with MCP tools and publishes
 * streaming events to Redis in the same format as ConversationRedisStreamingCallback.
 */
@Slf4j
public class SubAgentBridgeClient {

    private static final Set<String> BRIDGE_PROVIDERS = Set.of(
        "claude-code", "codex", "gemini-cli", "mistral-vibe"
    );

    private final String bridgeUrl;
    private final RestTemplate restTemplate;

    public SubAgentBridgeClient(String bridgeUrl) {
        this.bridgeUrl = bridgeUrl;
        this.restTemplate = new RestTemplateBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofMinutes(65))
            .build();
    }

    /**
     * Execute a sub-agent via the bridge server.
     *
     * @return execution response, or null on failure
     */
    public AgentExecutionResponseDto execute(AgentExecutionRequestDto request) {
        String url = bridgeUrl + "/api/bridge/execute";
        HttpEntity<AgentExecutionRequestDto> entity = new HttpEntity<>(request, buildHeaders(request.tenantId()));
        try {
            log.info("[SUB_AGENT_BRIDGE] Dispatching to bridge: url={}, conv={}, stream={}, provider={}, model={}",
                url, request.conversationId(), request.streamChannelId(),
                request.provider(), request.model());
            ResponseEntity<AgentExecutionResponseDto> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, AgentExecutionResponseDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("[SUB_AGENT_BRIDGE] Failed to execute via bridge: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if a provider should be routed to the bridge.
     * Same logic as ConversationAgentService.isBridgeProvider().
     */
    public static boolean isBridgeProvider(String provider) {
        if (provider == null || provider.isBlank()) return false;
        return BRIDGE_PROVIDERS.contains(provider.toLowerCase());
    }

    private HttpHeaders buildHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request. Covers the request-bound dispatch path. For purely
        // async sub-agent dispatch (no request context), the org identity
        // must be threaded through AgentExecutionRequestDto.organizationId
        // (consumed by the receiving agent-service controller, PR20 scope).
        OrgContextHeaderForwarder.forward(headers);
        return headers;
    }

}
