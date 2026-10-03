package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.domain.BridgeProviders;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import com.apimarketplace.common.web.BridgeDispatchSigning;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
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

    /** Shared list - see {@code BridgeProviders} in agent-common (the single source). */
    private static final Set<String> BRIDGE_PROVIDERS = BridgeProviders.NAMES;

    private final String bridgeUrl;
    private final RestTemplate restTemplate;
    private final String gatewaySecretKey;
    private final boolean hostToolsEnabled;

    /**
     * Read timeout of the blocking {@code /execute} POST = the total wall-clock budget
     * this client grants a bridge run. Must sit ABOVE the bridge's own hard cap
     * ({@code BRIDGE_MAX_TIMEOUT_MS}, default 125 min) so the bridge's typed timeout
     * response wins over a client-side socket abort, and must therefore also cover the
     * executionTimeout/inactivityTimeout contract maximum (7200s): under the previous
     * 65-min value a valid 2h budget could never elapse on the bridge path.
     */
    static final Duration EXECUTION_READ_TIMEOUT = Duration.ofMinutes(130);

    /**
     * @param bridgeUrl        base URL of the bridge server
     * @param gatewaySecretKey shared gateway HMAC secret ({@code GATEWAY_SECRET_KEY}). The bridge
     *                         rejects an unsigned dispatch 401 when it enforces authentication
     *                         (its default), so this must be the SAME value the bridge holds.
     *                         Blank leaves the request unsigned (dev / CE, where the bridge runs
     *                         with {@code BRIDGE_REQUIRE_GATEWAY_AUTH=false}).
     * @param hostToolsEnabled {@code conversation.bridge.host-tools-enabled}: whether this deployment
     *                         lets a PLATFORM ADMIN's run use the native host toolset at all. Even
     *                         when true, every non-admin run (and every run with no roles in scope)
     *                         is signed restricted, see {@link BridgeDispatchSigning#providerIdFor}.
     */
    public SubAgentBridgeClient(String bridgeUrl, String gatewaySecretKey, boolean hostToolsEnabled) {
        this.bridgeUrl = bridgeUrl;
        this.gatewaySecretKey = gatewaySecretKey;
        this.hostToolsEnabled = hostToolsEnabled;
        this.restTemplate = new RestTemplateBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(EXECUTION_READ_TIMEOUT)
            .build();
    }

    /**
     * Execute a sub-agent via the bridge server.
     *
     * @return execution response, or null on failure
     */
    public AgentExecutionResponseDto execute(AgentExecutionRequestDto request, String userRoles) {
        URI url = URI.create(bridgeUrl + "/api/bridge/execute");
        try {
            HttpEntity<byte[]> entity = buildEntity(request, userRoles, url);
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

    /**
     * Headers + the exact JSON body bytes, signed (v1 gateway HMAC + body-bound bridge signature).
     *
     * @param userRoles the requesting user's {@code X-User-Roles} (null when no user is in scope);
     *                  decides, with the deployment flag, whether the run may be unrestricted.
     */
    HttpEntity<byte[]> buildEntity(AgentExecutionRequestDto request, String userRoles, URI uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String tenantId = request.tenantId();
        if (tenantId != null) {
            headers.set("X-User-ID", tenantId);
        }
        // PR16 - forward X-Organization-ID / X-Organization-Role from the
        // inbound request. Covers the request-bound dispatch path. For purely
        // async sub-agent dispatch (no request context), the org identity
        // must be threaded through AgentExecutionRequestDto.organizationId
        // (consumed by the receiving agent-service controller, PR20 scope).
        OrgContextHeaderForwarder.forward(headers);
        // LC-001: sign LAST so both signatures cover the identity headers actually sent.
        String providerId = BridgeDispatchSigning.providerIdFor(
            hostToolsEnabled, userRoles, request.claimsRestrictedToolset());
        return BridgeDispatchSigning.signedJsonEntity(request, headers, gatewaySecretKey, providerId, "POST", uri);
    }

}
