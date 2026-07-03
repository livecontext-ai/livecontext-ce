package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP client for the agent-bridge Node.js server.
 * Same contract as AgentClient.executeAgent() but targets the bridge service.
 *
 * The bridge runs Claude Agent SDK with MCP tools and publishes streaming
 * events to Redis (same format as ConversationRedisStreamingCallback).
 */
@Slf4j
public class BridgeClient {

    private final String bridgeUrl;
    private final RestTemplate restTemplate;

    /**
     * Read timeout of the blocking {@code /execute} POST = the total wall-clock budget
     * this client grants a bridge run. Must sit ABOVE the bridge's own hard cap
     * ({@code BRIDGE_MAX_TIMEOUT_MS}, default 125 min) so the bridge's typed timeout
     * response wins over a client-side socket abort, and must therefore also cover the
     * executionTimeout/inactivityTimeout contract maximum (7200s): under the previous
     * 65-min value a valid 2h budget could never elapse on the bridge path.
     */
    static final Duration EXECUTION_READ_TIMEOUT = Duration.ofMinutes(130);

    public BridgeClient(String bridgeUrl) {
        this.bridgeUrl = bridgeUrl;
        this.restTemplate = new RestTemplateBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(EXECUTION_READ_TIMEOUT)
            .build();
    }

    /**
     * Execute agent via the bridge server.
     * Same pattern as AgentClient.executeAgent() (line 639).
     *
     * @return execution response, or null on failure
     */
    public AgentExecutionResponseDto executeViaBridge(AgentExecutionRequestDto request) {
        String url = bridgeUrl + "/api/bridge/execute";
        HttpEntity<AgentExecutionRequestDto> entity = new HttpEntity<>(request, buildHeaders(request.tenantId()));
        try {
            log.info("Dispatching to bridge: url={}, conv={}, stream={}",
                url, request.conversationId(), request.streamChannelId());
            ResponseEntity<AgentExecutionResponseDto> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, AgentExecutionResponseDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to execute via bridge: {}", e.getMessage());
            return null;
        }
    }

    private HttpHeaders buildHeaders(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        // Phase 3 of MIGRATION_ORG_ID_NOT_NULL.md - forward the workspace scope
        // to the bridge so the subprocess MCP gets ORGANIZATION_ID env (closes
        // Pattern I trace 2026-05-19). OrgContextHeaderForwarder reads
        // X-Organization-ID + X-Organization-Role from the current request via
        // RequestContextHolder; no-op on @Async threads where no inbound
        // request is bound (Phase 4 backfill catches the residue, Phase 6
        // NOT NULL eventually surfaces any leak as a DB-level violation).
        com.apimarketplace.common.web.OrgContextHeaderForwarder.forward(headers);
        return headers;
    }
}
