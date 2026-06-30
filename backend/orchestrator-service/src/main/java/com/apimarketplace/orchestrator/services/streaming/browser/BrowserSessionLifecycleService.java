package com.apimarketplace.orchestrator.services.streaming.browser;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import com.apimarketplace.orchestrator.tools.websearch.CdpTokenIssuer;
import com.apimarketplace.orchestrator.tools.websearch.CdpUrls;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Live-view lifecycle: turns a {@code "cdp_ready"} step event from the
 * websearch runner into the live-view bootstrap event consumed by either
 * the chat right-side panel or the workflow-builder side panel.
 *
 * <p>Wired downstream of {@link BrowserSessionLifecycleSubscriber}, which
 * listens on the {@code agent:browse:cdp_ready} pub/sub channel the
 * runner publishes to in parallel with its XADD on the per-session
 * Redis Stream
 * {@code agent:run:{rid}:node:{nid}:steps}. On the {@code "cdp_ready"}
 * event (emitted on the FIRST step once the runner has captured the
 * upstream Chromium DevTools URL), this service:</p>
 *
 * <ol>
 *   <li>Looks up the run context for the (runId, nodeId) pair from the
 *       Redis hash that {@code BrowserAgentModule} wrote pre-submit
 *       ({@code agent:browse:meta:{rid}:{nid}}). The hash carries either
 *       {@code conversationId} (chat-driven run) or {@code runType=workflow}
 *       (workflow-builder run) - never both.</li>
 *   <li>Mints a CDP JWT via {@link CdpTokenIssuer} bound to the
 *       (sessionId, runId, nodeId, userId) tuple.</li>
 *   <li>Caches the live-view envelope under
 *       {@code live_view:session:{toolId}} with a 5-minute TTL so a
 *       reconnecting frontend can recover the bootstrap event before
 *       the JWT expires.</li>
 *   <li>Fans out the bootstrap on the appropriate channel:
 *       <ul>
 *         <li>Chat path → dual-publish on {@code stream:events:{streamId}}
 *             (SSE) and {@code ws:conversation:{conversationId}} (gateway
 *             WS bridge). Mirrors the fetch-screenshot fanout pattern.</li>
 *         <li>Workflow path → publish via {@link WorkflowRedisPublisher}
 *             on {@code ws:workflow:run:{runId}} as event type
 *             {@code agentBrowseStep}. The frontend
 *             {@code WorkflowRunManager.handleEvent} patches the
 *             matching node's {@code lastBrowser*} fields without
 *             touching tracking sets, so the eye-button can open
 *             {@code BrowserLiveCdpPanel} mid-execution.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Idempotent on the (toolId, sessionId) pair: a stream replay or a
 * consumer-group redelivery skips the work if the cache key is already
 * set. The first event wins; subsequent {@code cdp_ready} events for
 * the same session are no-ops.</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class BrowserSessionLifecycleService {

    /** Redis hash written by BrowserAgentModule before submit, mapping
     *  (runId, nodeId) → {conversationId|runType, userId}. Required to
     *  pick the right fanout channel (chat vs workflow). */
    public static final String CONTEXT_HASH_PREFIX = "agent:browse:meta:";

    /** Cache TTL for the bootstrap envelope. Matches the JWT TTL so a
     *  reconnect within the same window can recover the live-view
     *  coordinates without re-minting. */
    private static final Duration LIVE_VIEW_CACHE_TTL = Duration.ofMinutes(5);

    /** Cache key prefix for the bootstrap envelope (toolId-keyed). */
    private static final String LIVE_VIEW_CACHE_PREFIX = "live_view:session:";

    private static final String STREAM_CHANNEL_PREFIX = "stream:events:";
    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";

    private final StringRedisTemplate redisTemplate;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final CdpTokenIssuer cdpTokenIssuer;
    private final WebSearchConfig webSearchConfig;
    private final WorkflowRedisPublisher workflowRedisPublisher;

    /**
     * Handle a single {@code "cdp_ready"} step event from the runner.
     *
     * @param runId     the workflow/conversation run id (also the chat streamId)
     * @param nodeId    the workflow node id (also the chat toolCallId)
     * @param sessionId the browser-agent session id captured by the runner
     * @param cdpWsUrl  the upstream Chromium DevTools WS URL the runner exposed
     *                  (used only for logging - the URL we hand to the frontend
     *                  is built via {@link CdpUrls} and points at our own
     *                  cdp.py proxy, not the raw Chromium endpoint)
     * @param currentUrl the page URL the agent has open (best-effort for the
     *                   address bar; may be empty on the very first event)
     * @param stepIndex  the runner's step counter at the time of capture (0)
     * @return {@code true} if the bootstrap event was published; {@code false}
     *         when skipped (already-bootstrapped, missing context, missing
     *         issuer config, etc. - all logged for diagnosis).
     */
    public boolean onCdpReady(String runId, String nodeId, String sessionId,
                              String cdpWsUrl, String currentUrl, int stepIndex) {
        if (runId == null || nodeId == null || sessionId == null
                || runId.isBlank() || nodeId.isBlank() || sessionId.isBlank()) {
            log.warn("[BrowserLifecycle] cdp_ready missing required ids "
                    + "(runId={}, nodeId={}, sessionId={})", runId, nodeId, sessionId);
            return false;
        }
        if (cdpTokenIssuer == null || !cdpTokenIssuer.isConfigured()) {
            log.warn("[BrowserLifecycle] CdpTokenIssuer unconfigured - skipping bootstrap "
                    + "(runId={}, nodeId={})", runId, nodeId);
            return false;
        }

        // Look up the run context BrowserAgentModule wrote pre-submit.
        // Either {conversationId, userId} (chat path) or {runType=workflow,
        // userId} (workflow path) - both share the same idempotency cache
        // and JWT minting; the difference is the fanout channel.
        String hashKey = CONTEXT_HASH_PREFIX + runId + ":" + nodeId;
        Map<Object, Object> ctx = redisTemplate.<Object, Object>opsForHash().entries(hashKey);
        String conversationId = ctx.get("conversationId") instanceof String s ? s : null;
        String userId = ctx.get("userId") instanceof String s ? s : null;
        String runType = ctx.get("runType") instanceof String s ? s : null;
        boolean isWorkflowRun = (conversationId == null || conversationId.isBlank())
                && "workflow".equals(runType);

        if ((conversationId == null || conversationId.isBlank()) && !isWorkflowRun) {
            // Neither chat context nor workflow-flag was written - the
            // BrowserAgentModule pre-submit step skipped, or the hash
            // expired before cdp_ready arrived. Nothing to fan out to.
            log.debug("[BrowserLifecycle] no run context for (runId={}, nodeId={}) - "
                    + "skipping fanout", runId, nodeId);
            return false;
        }

        // Idempotency guard: skip if we've already published the bootstrap
        // for this toolId+session pair (consumer-group replay safe).
        String cacheKey = LIVE_VIEW_CACHE_PREFIX + nodeId;
        String existing = redisTemplate.opsForValue().get(cacheKey);
        if (existing != null && existing.contains(sessionId)) {
            log.debug("[BrowserLifecycle] cdp_ready already bootstrapped for "
                    + "(nodeId={}, sessionId={}) - skipping duplicate", nodeId, sessionId);
            return false;
        }

        // Mint the CDP JWT - short-lived (5 min default).
        String cdpToken = cdpTokenIssuer.issue(sessionId, userId, runId, nodeId);
        if (cdpToken == null) {
            log.warn("[BrowserLifecycle] CdpTokenIssuer returned null token "
                    + "(runId={}, nodeId={})", runId, nodeId);
            return false;
        }

        // Build the wss:// URL via the shared CdpUrls util (single source of
        // truth - same one BrowserAgentModule + BrowserAgentTakeoverController
        // use). NOT the raw Chromium URL: the frontend connects through our
        // cdp.py proxy which validates the JWT and bridges to Chromium.
        // Use getPublicWsBase() so prod emits the user-reachable host
        // (Caddy proxies /cdp/* on the public site to the VLAN-only
        // websearch). Dev/local falls back to serviceUrl automatically.
        String publicWsUrl = CdpUrls.buildWsUrl(
                webSearchConfig != null ? webSearchConfig.getPublicWsBase() : null,
                sessionId);

        // Build the StreamEvent payload. Field names must match the
        // record component names in StreamEvent.AgentBrowseStep so
        // StreamPubSubService.deserializeEvent can round-trip it.
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("streamId", runId);
        envelope.put("toolId", nodeId);
        envelope.put("sessionId", sessionId);
        envelope.put("cdpToken", cdpToken);
        envelope.put("cdpWsUrl", publicWsUrl);
        envelope.put("currentUrl", currentUrl == null ? "" : currentUrl);
        envelope.put("stepIndex", stepIndex);
        envelope.put("runId", runId);
        envelope.put("nodeId", nodeId);
        envelope.put("timestamp", Instant.now().toString());

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.warn("[BrowserLifecycle] failed to serialize AgentBrowseStep: {}", e.getMessage());
            return false;
        }

        // Cache the envelope so a reconnecting frontend can recover it
        // (same pattern as StreamPubSubService.publishAndStoreToolEvent
        // for chat tool events). 5-minute TTL aligned with JWT expiry -
        // a stale cached token would be useless anyway.
        try {
            redisTemplate.opsForValue().set(cacheKey, json, LIVE_VIEW_CACHE_TTL);
        } catch (Exception e) {
            log.warn("[BrowserLifecycle] cache SET failed (non-fatal): {}", e.getMessage());
        }

        if (isWorkflowRun) {
            // Workflow path: publish on the workflow run channel instead of
            // the chat channels. The frontend WorkflowRunManager.handleEvent
            // routes the "agentBrowseStep" event to a per-node patch that
            // populates lastBrowserCdpToken/CdpWsUrl/RunId/SessionId on the
            // BrowserAgentNode mid-execution - exactly what the eye button
            // needs to bootstrap BrowserLiveCdpPanel before the tool call
            // returns. Field names use snake_case to match the wire format
            // statusUpdater.updateNodeFromStep already parses, so no
            // additional translation layer is needed.
            Map<String, Object> wfPayload = new LinkedHashMap<>();
            wfPayload.put("nodeId", nodeId);
            wfPayload.put("runId", runId);
            wfPayload.put("session_id", sessionId);
            wfPayload.put("cdp_token", cdpToken);
            wfPayload.put("cdp_ws_url", publicWsUrl);
            wfPayload.put("current_url", currentUrl == null ? "" : currentUrl);
            wfPayload.put("step_index", stepIndex);
            try {
                workflowRedisPublisher.publishEvent(runId, "agentBrowseStep", wfPayload);
            } catch (Exception e) {
                log.warn("[BrowserLifecycle] workflow publish failed (runId={}, nodeId={}): {}",
                        runId, nodeId, e.getMessage());
                return false;
            }
            log.info("[BrowserLifecycle] cdp_ready workflow-bootstrap published: "
                    + "runId={}, nodeId={}, sessionId={}", runId, nodeId, sessionId);
            return true;
        }

        // Chat path: dual-publish on stream:events:{streamId} for SSE
        // consumers and ws:conversation:{conversationId} for the gateway
        // WS bridge. Identical to the fetch-screenshot fanout pattern.
        try {
            eventBus.publish(STREAM_CHANNEL_PREFIX + runId, json);
            eventBus.publish(WS_CHANNEL_PREFIX + conversationId, json);
        } catch (Exception e) {
            log.warn("[BrowserLifecycle] eventBus.publish failed (runId={}, nodeId={}): {}",
                    runId, nodeId, e.getMessage());
            return false;
        }

        log.info("[BrowserLifecycle] cdp_ready bootstrap published: runId={}, nodeId={}, "
                + "sessionId={}, conversationId={}", runId, nodeId, sessionId, conversationId);
        return true;
    }
}
