package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.tools.websearch.CdpTokenIssuer;
import com.apimarketplace.orchestrator.tools.websearch.CdpUrls;
import com.apimarketplace.common.scope.ScopeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a {@link SignalType#BROWSER_USER_TAKEOVER} signal so the workflow
 * can advance past a paused browser-agent node.
 *
 * <p>The frontend's {@code BrowserLiveCdpPanel} hits this when the user
 * clicks "Resume agent" after taking control of the live Chromium tab. The
 * controller mirrors {@link com.apimarketplace.orchestrator.controllers.interfaces.InterfaceActionController}
 * for the {@code __continue} path: find the active blocking signal for
 * (runId, nodeId), resolve it with {@code SignalResolution.CONTINUE}, and
 * the orchestrator's {@code SignalResumeService} fires the downstream
 * edges.</p>
 *
 * <p>Auth: shares the orchestrator's standard X-User-ID header. The
 * frontend posts via the proxy
 * ({@code /api/proxy/orchestrator/api/internal/browser-agent/runs/{rid}/nodes/{nid}/takeover-resume})
 * so the gateway already injects X-User-ID from the JWT.</p>
 */
@RestController
@RequestMapping("/api/internal/browser-agent/runs/{runId}/nodes/{nodeId}")
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class BrowserAgentTakeoverController {

    private static final Logger logger = LoggerFactory.getLogger(BrowserAgentTakeoverController.class);

    private final UnifiedSignalService signalService;
    private final RestTemplate restTemplate;
    private final WebSearchConfig webSearchConfig;
    private final CdpTokenIssuer cdpTokenIssuer;
    private final WorkflowRunRepository workflowRunRepository;
    /** Reads the {@code agent:browse:meta:{runId}:{nodeId}} hash to verify
     *  ownership of a CHAT (ad-hoc) agent_browse run, which has no
     *  {@code workflow_runs} row. Null in the test-only constructors. */
    private final StringRedisTemplate redisTemplate;

    @Autowired
    public BrowserAgentTakeoverController(
            UnifiedSignalService signalService,
            @Qualifier("webSearchRestTemplate") RestTemplate restTemplate,
            WebSearchConfig webSearchConfig,
            CdpTokenIssuer cdpTokenIssuer,
            WorkflowRunRepository workflowRunRepository,
            StringRedisTemplate redisTemplate) {
        this.signalService = signalService;
        this.restTemplate = restTemplate;
        this.webSearchConfig = webSearchConfig;
        this.cdpTokenIssuer = cdpTokenIssuer;
        this.workflowRunRepository = workflowRunRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Constructor for tests that exercise the run-ownership repo but not the
     * chat-ownership Redis path ({@code redisTemplate == null}).
     */
    BrowserAgentTakeoverController(
            UnifiedSignalService signalService,
            RestTemplate restTemplate,
            WebSearchConfig webSearchConfig,
            CdpTokenIssuer cdpTokenIssuer,
            WorkflowRunRepository workflowRunRepository) {
        this(signalService, restTemplate, webSearchConfig, cdpTokenIssuer, workflowRunRepository, null);
    }

    /**
     * Back-compat constructor for tests that don't exercise run-ownership
     * enforcement ({@code workflowRunRepository == null} disables the check).
     */
    BrowserAgentTakeoverController(
            UnifiedSignalService signalService,
            RestTemplate restTemplate,
            WebSearchConfig webSearchConfig,
            CdpTokenIssuer cdpTokenIssuer) {
        this(signalService, restTemplate, webSearchConfig, cdpTokenIssuer, null);
    }

    /**
     * Convenience constructor for tests that don't exercise the runner-level
     * resume push, token refresh, or run-ownership check.
     */
    BrowserAgentTakeoverController(UnifiedSignalService signalService) {
        this(signalService, null, null, null, null);
    }

    /**
     * Run-ownership gate. The signal/CDP endpoints are keyed only by (runId,
     * nodeId), so without this an authenticated caller could resume - or mint a
     * live CDP token for - a browser-agent run belonging to ANOTHER tenant/org
     * whose runId they learned. Mirrors {@code WorkflowRunController}'s
     * strict-isolation run guard: the caller's gateway-validated (userId, active
     * org) must scope-match the run's (tenantId, organizationId).
     *
     * <p>Returns false (caller treated as not-owner → 404, no existence leak)
     * for an unknown run or a cross-scope caller. When {@code workflowRunRepository}
     * is null (test-only constructors) the check is skipped - production always
     * wires the repository.</p>
     */
    private boolean callerOwnsRun(String runId, String nodeId, String userId, String orgId) {
        if (workflowRunRepository == null) {
            return true; // test-convenience path; production always injects the repo
        }
        Optional<WorkflowRunEntity> wf = workflowRunRepository.findByRunIdPublic(runId);
        if (wf.isPresent()) {
            // Workflow run: strict (userId, active org) scope-match.
            return ScopeGuard.isInStrictScope(
                    userId, orgId, wf.get().getTenantId(), wf.get().getOrganizationId());
        }
        // No workflow_runs row -> this is a CHAT (ad-hoc) agent_browse, whose
        // runId is a random stream UUID with no DB row. Its owner is the
        // submitter recorded in the agent:browse:meta:{runId}:{nodeId} hash that
        // BrowserAgentModule wrote at submit (server-side, under the
        // gateway-validated userId). The caller owns the run iff that userId
        // matches - so chat live-view (resume / token-refresh / final-screenshot)
        // works without weakening cross-tenant isolation: the hash is not
        // client-forgeable and the runId is unguessable.
        return callerOwnsChatRun(runId, nodeId, userId);
    }

    /**
     * Ownership check for a CHAT agent_browse run (no {@code workflow_runs}
     * row). Returns true iff the {@code agent:browse:meta:{runId}:{nodeId}}
     * hash records the same {@code userId} as the caller. Fail-closed: a null
     * Redis template (test-only), a missing/expired hash, or a userId mismatch
     * all return false.
     */
    private boolean callerOwnsChatRun(String runId, String nodeId, String userId) {
        if (redisTemplate == null || userId == null || userId.isBlank()) {
            return false;
        }
        try {
            String key = "agent:browse:meta:" + runId + ":" + nodeId;
            Object storedUser = redisTemplate.opsForHash().get(key, "userId");
            return storedUser instanceof String s && userId.equals(s);
        } catch (Exception e) {
            logger.warn("[BrowserTakeover] chat-ownership lookup failed (runId={}, nodeId={}): {}",
                    runId, nodeId, e.getMessage());
            return false;
        }
    }

    /**
     * Resolve the BROWSER_USER_TAKEOVER signal for (runId, nodeId).
     *
     * <p>Body is optional; if a {@code memory_injection} field is present we
     * stash it on the resolution data so the runner can use it on its next
     * step (mirrors the {@code browse_intervene} hint mechanism).</p>
     *
     * @return 200 with {@code {status: "resumed"}} on success;
     *         404 if no active takeover signal for (runId, nodeId);
     *         200 with {@code {status: "already_resolved"}} if the signal
     *         was already terminal (idempotent - frontend can retry).
     */
    @PostMapping("/takeover-resume")
    public ResponseEntity<Map<String, Object>> resume(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        logger.info("[BrowserTakeover] resume request: runId={}, nodeId={}, userId={}",
                runId, nodeId, userId);

        if (!callerOwnsRun(runId, nodeId, userId, orgId)) {
            logger.warn("[BrowserTakeover] resume DENIED: caller {} (org {}) is out of scope for run {} - 404",
                    userId, orgId, runId);
            return ResponseEntity.notFound().build();
        }

        List<SignalWaitEntity> active = signalService.getActiveSignals(runId);
        SignalWaitEntity target = active.stream()
                .filter(s -> nodeId.equals(s.getNodeId()))
                .filter(s -> s.getSignalType() == SignalType.BROWSER_USER_TAKEOVER)
                .max(Comparator.comparingInt(SignalWaitEntity::getEpoch))
                .orElse(null);

        if (target == null) {
            logger.info("[BrowserTakeover] no active takeover signal: runId={}, nodeId={}", runId, nodeId);
            // Even without a workflow-level signal, the runner may still be
            // paused at the step boundary OR holding Chromium open after the
            // task completed (cdp.py LPUSHed PAUSE on the first user input).
            // Push RESUME on the runner queue so a session that paused for
            // live-view but never raised the workflow signal still unsticks.
            //
            // A chat agent_browse has no signal config to read the sessionId
            // from, so fall back to the client-supplied session_id (the live
            // panel holds it). callerOwnsRun already gated the run and resume
            // is non-destructive (it only lifts a PAUSE / ends a post-completion
            // hold), so trusting the body's session_id here is safe.
            String bodySid = (body != null && body.get("session_id") instanceof String s && !s.isBlank())
                    ? s : null;
            pushRunnerResume(runId, nodeId, bodySid);
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> resolveData = new LinkedHashMap<>();
        resolveData.put("resolved_by", userId == null ? "system" : userId);
        if (body != null && body.get("memory_injection") instanceof String s && !s.isBlank()) {
            resolveData.put("memory_injection", s);
        }

        boolean resolved = signalService.resolveSignal(
                target.getId(),
                SignalResolution.CONTINUE,
                resolveData,
                userId == null ? "system" : userId);

        // Runner-level RESUME: even when the workflow signal resolved
        // cleanly, the runner's `on_step_start` is currently blocked
        // inside `while session.paused:` waiting for a control entry.
        // Without an LPUSH onto the same per-session control queue, the
        // step loop never wakes and the agent doesn't actually resume -
        // the workflow advances past `awaitingSignal` but Chromium is
        // still frozen. We extract session_id from the signal config
        // (BrowserAgentNode put it there via SignalConfig.browserTakeover)
        // and call the websearch /agent/sessions/{sid}/resume endpoint.
        String sessionId = extractSessionId(target);
        pushRunnerResume(runId, nodeId, sessionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", resolved ? "resumed" : "already_resolved");
        result.put("nodeId", nodeId);
        result.put("runId", runId);
        return ResponseEntity.ok(result);
    }

    /**
     * Mint a fresh CDP-WS token for an in-flight live-view session.
     *
     * <p>The token issued at job-submit time is short-lived (5 min by
     * default - see {@code websearch.cdp.jwt-ttl-seconds}). When the user
     * is mid-takeover and the WS drops because the JWT expired, the
     * frontend reconnect logic hits this endpoint to re-mint a token
     * bound to the same (sessionId, runId, nodeId) and reopen the WS
     * without losing the takeover.</p>
     *
     * <p>The frontend POSTs {@code {"session_id": "<sid>"}} (it already
     * holds the session id from the original tool response). We verify
     * an active BROWSER_USER_TAKEOVER signal still exists for (runId,
     * nodeId) and that its config matches the session id, then mint
     * via {@link CdpTokenIssuer}.</p>
     *
     * @return 200 {@code {cdp_token, cdp_ws_url, session_id}} on success;
     *         404 if no active takeover signal (workflow already moved
     *         past this node - frontend stops retrying);
     *         400 on session-id mismatch or empty body;
     *         503 if the issuer is unconfigured (secret blank).
     */
    @PostMapping("/cdp-token-refresh")
    public ResponseEntity<Map<String, Object>> refreshToken(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        if (!callerOwnsRun(runId, nodeId, userId, orgId)) {
            logger.warn("[BrowserTakeover] refresh DENIED: caller {} (org {}) is out of scope for run {} - 404",
                    userId, orgId, runId);
            return ResponseEntity.notFound().build();
        }

        String requestedSid = body == null ? null
                : (body.get("session_id") instanceof String s && !s.isBlank() ? s : null);
        if (requestedSid == null) {
            logger.info("[BrowserTakeover] refresh missing session_id (runId={}, nodeId={})", runId, nodeId);
            return ResponseEntity.badRequest().body(Map.of("error", "session_id required"));
        }
        if (cdpTokenIssuer == null || !cdpTokenIssuer.isConfigured()) {
            logger.warn("[BrowserTakeover] refresh requested but issuer unconfigured");
            return ResponseEntity.status(503).body(Map.of("error", "cdp issuer unconfigured"));
        }

        // Verify an active takeover signal still backs this session - if
        // the workflow has already advanced past this node, refreshing
        // the token is pointless and the frontend should stop trying.
        List<SignalWaitEntity> active = signalService.getActiveSignals(runId);
        SignalWaitEntity target = active.stream()
                .filter(s -> nodeId.equals(s.getNodeId()))
                .filter(s -> s.getSignalType() == SignalType.BROWSER_USER_TAKEOVER)
                .max(Comparator.comparingInt(SignalWaitEntity::getEpoch))
                .orElse(null);
        if (target == null) {
            logger.info("[BrowserTakeover] refresh: no active takeover signal "
                    + "(runId={}, nodeId={}) - workflow likely already advanced", runId, nodeId);
            return ResponseEntity.notFound().build();
        }
        // Fail closed: signals MUST carry a sessionId in their config -
        // SignalConfig.browserTakeover always sets it. A null storedSid
        // means the signal was malformed at registration time, which we
        // refuse to refresh against rather than allowing any requested
        // session_id through. A mismatch is the actual security check
        // (a user with X-User-ID for run A trying to mint a token bound
        // to a session from run B).
        String storedSid = extractSessionId(target);
        if (!requestedSid.equals(storedSid)) {
            logger.warn("[BrowserTakeover] refresh: session_id mismatch "
                    + "(requested={}, stored={})", requestedSid, storedSid);
            return ResponseEntity.badRequest().body(Map.of("error", "session_id mismatch"));
        }

        String token = cdpTokenIssuer.issue(requestedSid, userId, runId, nodeId);
        if (token == null) {
            logger.warn("[BrowserTakeover] refresh: issuer returned null token");
            return ResponseEntity.status(503).body(Map.of("error", "issue failed"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cdp_token", token);
        result.put("cdp_ws_url", buildCdpWsUrl(requestedSid));
        result.put("session_id", requestedSid);
        logger.info("[BrowserTakeover] refresh OK: sessionId={}, runId={}, nodeId={}",
                requestedSid, runId, nodeId);
        return ResponseEntity.ok(result);
    }

    /**
     * Serve the final-page screenshot captured when the browser-agent
     * session ended, so the frontend live panel can show the last page
     * even when the live CDP screencast WS never connected (no public
     * {@code /cdp} route, internal-only {@code cdp_ws_url}, Cloudflare WS
     * blocked).
     *
     * <p>WS-independent fallback: the runner stores a base64 JPEG in Redis
     * keyed by (runId, nodeId) on teardown; websearch-service serves it;
     * this endpoint proxies it behind the SAME run-ownership gate as
     * {@code takeover-resume} / {@code cdp-token-refresh}. Keyed by
     * (runId, nodeId) with NO client-supplied session id, so an owner of
     * run A cannot read run B's capture.</p>
     *
     * @return 200 {@code {mime, data_base64, runId, nodeId}} when a capture
     *         exists; 404 when none was captured yet (frontend keeps
     *         polling) or the caller is out of scope for the run.
     */
    @GetMapping("/final-screenshot")
    public ResponseEntity<Map<String, Object>> finalScreenshot(
            @PathVariable String runId,
            @PathVariable String nodeId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        if (!callerOwnsRun(runId, nodeId, userId, orgId)) {
            logger.warn("[BrowserTakeover] final-screenshot DENIED: caller {} (org {}) out of scope for run {} - 404",
                    userId, orgId, runId);
            return ResponseEntity.notFound().build();
        }
        if (restTemplate == null || webSearchConfig == null) {
            return ResponseEntity.notFound().build();
        }
        String base = webSearchConfig.getServiceUrl();
        if (base == null || base.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        String url = (base.endsWith("/") ? base : base + "/")
                + "agent/runs/" + runId + "/nodes/" + nodeId + "/final-screenshot";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> shot = restTemplate.getForObject(url, Map.class);
            if (shot == null || shot.get("data_base64") == null) {
                return ResponseEntity.notFound().build();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("runId", runId);
            result.put("nodeId", nodeId);
            result.put("mime", shot.getOrDefault("mime", "image/jpeg"));
            result.put("data_base64", shot.get("data_base64"));
            return ResponseEntity.ok(result);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // No capture yet (websearch 404) - frontend keeps polling.
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.warn("[BrowserTakeover] final-screenshot proxy failed (runId={}, nodeId={}): {}",
                    runId, nodeId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Build the {@code wss://.../cdp/{sessionId}} URL the frontend uses
     * to reopen the bridge. Delegates to {@link CdpUrls} so both the
     * initial-submit response (in {@code BrowserAgentModule}) and the
     * refresh response stay on the same URL scheme.
     */
    private String buildCdpWsUrl(String sessionId) {
        // Use getPublicWsBase() so prod emits the user-reachable host
        // (Caddy proxies /cdp/* on the public site to the VLAN-only
        // websearch). Dev/local falls back to serviceUrl automatically.
        return CdpUrls.buildWsUrl(
                webSearchConfig == null ? null : webSearchConfig.getPublicWsBase(),
                sessionId);
    }

    /**
     * Pull the {@code sessionId} the runner registered when it raised
     * the BROWSER_USER_TAKEOVER signal. Stored under signal config - see
     * {@code SignalConfig#browserTakeover}, key {@code "sessionId"} (camelCase
     * - must match {@link SignalConfig#getSessionId(Map)}). Returns null
     * if the config is absent or shaped differently than expected.
     */
    @SuppressWarnings("unchecked")
    private static String extractSessionId(SignalWaitEntity signal) {
        Object cfg = signal.getSignalConfig();
        if (cfg instanceof Map<?, ?> m) {
            String sid = SignalConfig.getSessionId((Map<String, Object>) m);
            if (sid != null && !sid.isBlank()) return sid;
        }
        return null;
    }

    /**
     * POST {@code /agent/sessions/{sessionId}/resume} on websearch-service
     * so the runner's control-queue BLPOP wakes up and the step loop
     * continues. Best-effort - never throws on the controller's caller
     * path (a websearch outage must not strand the workflow's
     * already-resolved signal). Returns silently on session-not-found
     * because the session may have already terminated by the time the
     * user clicks Resume; the runner-side state is then irrelevant.
     */
    private void pushRunnerResume(String runId, String nodeId, String sessionId) {
        if (restTemplate == null || webSearchConfig == null) {
            // Test path or websearch disabled - nothing to do.
            return;
        }
        if (sessionId == null || sessionId.isBlank()) {
            logger.warn("[BrowserTakeover] no session_id available, "
                    + "cannot push runner-level RESUME (runId={}, nodeId={})", runId, nodeId);
            return;
        }
        String base = webSearchConfig.getServiceUrl();
        if (base == null || base.isBlank()) return;
        String url = base.endsWith("/") ? base + "agent/sessions/" + sessionId + "/resume"
                                        : base + "/agent/sessions/" + sessionId + "/resume";
        try {
            restTemplate.postForObject(url, Map.of(), Map.class);
            logger.info("[BrowserTakeover] runner-level RESUME pushed: sessionId={}", sessionId);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // Session ended before resume arrived - runner-side resume
            // is moot. Log debug, no-op.
            logger.debug("[BrowserTakeover] websearch /resume returned 404 (session ended): sessionId={}",
                    sessionId);
        } catch (Exception e) {
            logger.warn("[BrowserTakeover] runner-level RESUME failed (sessionId={}): {}",
                    sessionId, e.getMessage());
        }
    }
}
