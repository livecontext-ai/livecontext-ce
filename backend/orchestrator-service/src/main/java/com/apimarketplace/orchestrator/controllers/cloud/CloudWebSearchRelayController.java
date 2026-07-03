package com.apimarketplace.orchestrator.controllers.cloud;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule;
import com.apimarketplace.orchestrator.tools.websearch.CeBrowseControlRequest;
import com.apimarketplace.orchestrator.tools.websearch.CeBrowseRelayRequest;
import com.apimarketplace.orchestrator.tools.websearch.CeWebSearchRelayRequest;
import com.apimarketplace.orchestrator.tools.websearch.WebSearchModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
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

    private static final Set<String> CONTROL_ACTIONS =
            Set.of("status", "intervene", "abort", "screenshot");

    private final AuthClient authClient;
    private final WebSearchModule searchModule;

    /**
     * Cloud browser-agent runner - same {@code websearch.enabled=true} gate as this
     * controller, so it is present in production. Nullable so the bean-gating test
     * (and any misconfiguration) degrades to an actionable 503 on the browse
     * endpoints rather than failing context startup.
     */
    @Nullable
    private final BrowserAgentModule browserAgentModule;

    public CloudWebSearchRelayController(AuthClient authClient, WebSearchModule searchModule,
                                         @Nullable BrowserAgentModule browserAgentModule) {
        this.authClient = authClient;
        this.searchModule = searchModule;
        this.browserAgentModule = browserAgentModule;
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

    /**
     * Relay a new {@code agent_browse} session for a linked CE install. Authorises
     * exactly like {@link #search} (owner of an active link to the given install id),
     * then runs the cloud-local {@link BrowserAgentModule} which owns the whole browser
     * stack (Chromium + per-step LLM), bills the linked cloud account through its
     * observability path (BROWSER_AGENT_EXECUTION, server-generated source id - the CE
     * chat ids are informational and never a billing dedup key), and mints the
     * CLOUD-hosted CDP live-view URL/token in its response. That response - including
     * {@code cdp_ws_url}/{@code cdp_token}/{@code session_id} - is returned verbatim so
     * the CE frontend connects DIRECTLY to {@code wss://<cloud>/cdp/{sid}?token=...}.
     */
    @PostMapping("/agent_browse")
    public ResponseEntity<Map<String, Object>> agentBrowse(
            @RequestHeader("X-User-ID") Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @RequestBody CeBrowseRelayRequest request) {
        ResponseEntity<Map<String, Object>> authFailure = authorize(cloudUserId, installId);
        if (authFailure != null) {
            return authFailure;
        }
        if (browserAgentModule == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "BROWSER_AGENT_UNAVAILABLE"));
        }
        if (request == null || request.task() == null || request.task().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_RELAY_REQUEST"));
        }

        String tenantId = String.valueOf(cloudUserId);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("task", request.task());
        if (request.startUrl() != null && !request.startUrl().isBlank()) {
            parameters.put("start_url", request.startUrl());
        }
        if (request.llm() != null) {
            parameters.put("llm", request.llm());
        }
        if (request.maxSteps() != null) {
            parameters.put("max_steps", request.maxSteps());
        }
        if (request.options() != null) {
            parameters.putAll(request.options());
        }

        // Thread the CE chat/run ids into the browser-agent context so BrowserAgentModule
        // mints a CDP token whose rid/nid claims match the CE side. Unlike the search
        // relay this is replay-safe: browser-agent billing goes through observability
        // (server-generated source), NOT a client-controlled dedup key.
        Map<String, Object> browseCreds = new HashMap<>();
        if (request.streamId() != null && !request.streamId().isBlank()) {
            browseCreds.put("__streamId__", request.streamId());
        }
        if (request.toolCallId() != null && !request.toolCallId().isBlank()) {
            browseCreds.put("__toolCallId__", request.toolCallId());
        }
        ToolExecutionContext context = new ToolExecutionContext(
                tenantId, browseCreds, Map.of(), Set.of(), null, null, null, null);

        ToolExecutionResult result = browserAgentModule.execute("agent_browse", parameters, tenantId, context)
                .orElse(ToolExecutionResult.failure(
                        com.apimarketplace.agent.tools.ToolErrorCode.EXTERNAL_SERVICE_ERROR,
                        "Browser session failed"));
        if (!result.success()) {
            log.warn("CE browser agent relay failed for cloudUser={} installId={}: {}",
                    cloudUserId, installId, result.error());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", result.error() != null ? result.error() : "Browser session failed"));
        }
        return ResponseEntity.ok(asResponseBody(result.data()));
    }

    /**
     * Relay a session-control call ({@code status}/{@code intervene}/{@code abort}/{@code screenshot})
     * to the cloud-local {@link BrowserAgentModule}. Not billed - it targets an already
     * running session by id. Authorises exactly like {@link #agentBrowse}.
     */
    @PostMapping("/agent/sessions/{sessionId}/{action}")
    public ResponseEntity<Map<String, Object>> browseControl(
            @RequestHeader("X-User-ID") Long cloudUserId,
            @RequestHeader(INSTALL_HEADER) String installId,
            @PathVariable String sessionId,
            @PathVariable String action,
            @RequestBody(required = false) CeBrowseControlRequest request) {
        ResponseEntity<Map<String, Object>> authFailure = authorize(cloudUserId, installId);
        if (authFailure != null) {
            return authFailure;
        }
        if (browserAgentModule == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "BROWSER_AGENT_UNAVAILABLE"));
        }
        if (sessionId == null || sessionId.isBlank() || action == null
                || !CONTROL_ACTIONS.contains(action)) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_RELAY_REQUEST"));
        }

        String tenantId = String.valueOf(cloudUserId);
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("session_id", sessionId);
        if ("intervene".equals(action) && request != null && request.hint() != null) {
            parameters.put("hint", request.hint());
        }
        ToolExecutionContext context = new ToolExecutionContext(
                tenantId, Map.of(), Map.of(), Set.of(), null, null, null, null);

        ToolExecutionResult result = browserAgentModule.execute("browse_" + action, parameters, tenantId, context)
                .orElse(ToolExecutionResult.failure(
                        com.apimarketplace.agent.tools.ToolErrorCode.EXTERNAL_SERVICE_ERROR,
                        "Browser session " + action + " failed"));
        if (!result.success()) {
            log.warn("CE browser agent {} relay failed for cloudUser={} installId={}: {}",
                    action, cloudUserId, installId, result.error());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", result.error() != null ? result.error() : "Browser session " + action + " failed"));
        }
        return ResponseEntity.ok(asResponseBody(result.data()));
    }

    /**
     * Shared link-ownership check for the relay endpoints. Returns a populated
     * error {@link ResponseEntity} to short-circuit, or {@code null} when the caller
     * owns an active link to the install and the request may proceed.
     */
    @Nullable
    private ResponseEntity<Map<String, Object>> authorize(Long cloudUserId, String installId) {
        if (cloudUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "AUTHENTICATION_REQUIRED"));
        }
        if (!authClient.userOwnsActiveCeLink(String.valueOf(cloudUserId), installId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "CE_LINK_NOT_ACTIVE"));
        }
        return null;
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
