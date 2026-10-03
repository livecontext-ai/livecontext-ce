package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.common.web.BridgeDispatchSigning;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
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
    public BridgeClient(String bridgeUrl, String gatewaySecretKey, boolean hostToolsEnabled) {
        this.bridgeUrl = bridgeUrl;
        this.gatewaySecretKey = gatewaySecretKey;
        this.hostToolsEnabled = hostToolsEnabled;
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
    public AgentExecutionResponseDto executeViaBridge(AgentExecutionRequestDto request, String userRoles) {
        URI uri = URI.create(bridgeUrl + "/api/bridge/execute");
        try {
            HttpEntity<byte[]> entity = buildEntity(request, userRoles, uri);
            log.info("Dispatching to bridge: url={}, conv={}, stream={}",
                uri, request.conversationId(), request.streamChannelId());
            ResponseEntity<AgentExecutionResponseDto> response = restTemplate.exchange(
                uri, HttpMethod.POST, entity, AgentExecutionResponseDto.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to execute via bridge: {}", e.getMessage());
            return null;
        }
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
        // Phase 3 of MIGRATION_ORG_ID_NOT_NULL.md - forward the workspace scope
        // to the bridge so the subprocess MCP gets ORGANIZATION_ID env (closes
        // Pattern I trace 2026-05-19). OrgContextHeaderForwarder reads
        // X-Organization-ID + X-Organization-Role from the current request via
        // RequestContextHolder; no-op on @Async threads where no inbound
        // request is bound (Phase 4 backfill catches the residue, Phase 6
        // NOT NULL eventually surfaces any leak as a DB-level violation).
        com.apimarketplace.common.web.OrgContextHeaderForwarder.forward(headers);
        // LC-001: sign LAST so both signatures cover the identity headers actually sent.
        String providerId = BridgeDispatchSigning.providerIdFor(
            hostToolsEnabled, userRoles, request.claimsRestrictedToolset());
        return BridgeDispatchSigning.signedJsonEntity(request, headers, gatewaySecretKey, providerId, "POST", uri);
    }
}
