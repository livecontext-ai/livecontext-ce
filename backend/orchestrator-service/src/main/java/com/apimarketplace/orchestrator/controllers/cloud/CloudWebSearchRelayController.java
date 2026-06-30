package com.apimarketplace.orchestrator.controllers.cloud;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.orchestrator.tools.websearch.CeWebSearchRelayRequest;
import com.apimarketplace.orchestrator.tools.websearch.WebSearchModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Cloud-side web-search relay for linked CE installs - mirrors the CE→cloud LLM relay
 * ({@code /api/ce-llm/*} in agent-service): validates that the calling cloud user owns
 * an ACTIVE link to the given install id, then executes the search locally through
 * {@link WebSearchModule}, which posts the flat {@code WEB_SEARCH} debit on the cloud
 * account (auth-service owns the credit price). The billing sourceId is SERVER-generated
 * (WebSearchModule's per-call UUID fallback): CE-supplied identifiers are never used as
 * the ledger dedup key, so a linked install cannot replay a key to dodge debits.
 *
 * <p>Only mounted where the local websearch engine runs ({@code websearch.enabled=true},
 * i.e. cloud) - a CE deployment can never be relayed-to.
 */
@Slf4j
@RestController
@RequestMapping("/api/ce-websearch")
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class CloudWebSearchRelayController {

    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";
    private static final int MAX_RESULTS_CAP = 50;

    private final AuthClient authClient;
    private final WebSearchModule searchModule;

    public CloudWebSearchRelayController(AuthClient authClient, WebSearchModule searchModule) {
        this.authClient = authClient;
        this.searchModule = searchModule;
    }

    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestHeader("X-User-ID") Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @RequestBody CeWebSearchRelayRequest request) {
        if (cloudUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "AUTHENTICATION_REQUIRED"));
        }
        if (!authClient.userOwnsActiveCeLink(String.valueOf(cloudUserId), installId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "CE_LINK_NOT_ACTIVE"));
        }
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "INVALID_RELAY_REQUEST"));
        }

        String tenantId = String.valueOf(cloudUserId);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("query", request.query());
        if (request.maxResults() != null) {
            parameters.put("max_results", clampMaxResults(request.maxResults()));
        }
        if (request.timeRange() != null && !request.timeRange().isBlank()) {
            parameters.put("time_range", request.timeRange());
        }

        // Billing sourceId is SERVER-generated: CE-supplied streamId/toolCallId are
        // deliberately NOT threaded into the billing credentials - the ledger dedups
        // on a globally-unique source_id, so a client-controlled key would let a
        // linked install replay one (streamId, toolCallId) pair for unlimited
        // searches billed once. With empty credentials WebSearchModule falls back to
        // its own UUID sourceId per call (same posture as the CE LLM relay, which
        // bills "ce-llm-" + UUID). The CE relay client never retries, so per-call
        // UUID billing cannot double-charge a retry.
        ToolExecutionContext context = new ToolExecutionContext(
                tenantId, Map.of(), Map.of(), Set.of(), null, null, null, null);

        ToolExecutionResult result = searchModule.execute("search", parameters, tenantId, context)
                .orElse(ToolExecutionResult.failure(
                        com.apimarketplace.agent.tools.ToolErrorCode.EXTERNAL_SERVICE_ERROR,
                        "Search failed"));
        if (!result.success()) {
            log.warn("CE web search relay failed for cloudUser={} installId={}: {}",
                    cloudUserId, installId, result.error());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", result.error() != null ? result.error() : "Search failed"));
        }
        return ResponseEntity.ok(asResponseBody(result.data()));
    }

    private static int clampMaxResults(int requested) {
        return Math.max(1, Math.min(MAX_RESULTS_CAP, requested));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asResponseBody(Object data) {
        if (data instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of("data", data == null ? Map.of() : data);
    }
}
