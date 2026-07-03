package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.orchestrator.config.WebSearchConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Aborts in-flight {@code browser_agent} sessions so a workflow STOP/CANCEL (or
 * the live-view red "stop" button) actually kills Chromium instead of letting
 * the websearch runner browse on to completion.
 *
 * <p><b>Why this exists.</b> A {@code browser_agent} node submits a job to the
 * websearch-service and BLPOP-waits on the result LIST. When the user stops the
 * run, the orchestrator cancels signals + closes epochs, but nothing reaches the
 * runner - the node's thread is still blocked on the BLPOP and the runner keeps
 * driving browser-use until it finishes or hits its own wallclock. The user's
 * "Stop" then looks ignored (the browser visibly keeps going). This aborter is
 * the missing cross-service signal.</p>
 *
 * <p><b>How.</b> Two redundant paths, both best-effort (a stop must never fail on
 * a websearch hiccup):</p>
 * <ol>
 *   <li>POST {@code /agent/sessions/{sid}/abort} - the runner sets
 *       {@code session.aborted} immediately; its step loop raises
 *       {@code _StopAgent("CANCELLED")} at the next step boundary (browser-use
 *       has no hard mid-step interrupt, so abort lands within ~one step).</li>
 *   <li>LPUSH {@code {"cmd":"ABORT","session_id":sid}} onto
 *       {@code agent:run:{runId}:node:{nodeId}:control} - the same control queue
 *       the runner drains, as a fallback when the session id is unknown or the
 *       REST call cannot reach the box.</li>
 * </ol>
 *
 * <p>The runner then LPUSHes a {@code stop_reason=CANCELLED} result, which
 * unblocks the node's BLPOP (traversal is already gated by the run's cancel
 * signal). Chromium is closed by the runner's teardown {@code finally}.</p>
 *
 * <p>The {@code (runId, nodeId, sessionId)} of in-flight browses is discovered
 * from the {@code agent:browse:meta:{runId}:{nodeId}} hashes the module writes
 * at submit (the {@code sessionId} field is HSET by the runner at cdp_ready).
 * For the dedicated {@code browser_agent} node, the browse's {@code __streamId__}
 * IS the workflow runId, so a {@code SCAN agent:browse:meta:{runId}:*} finds
 * every browse of that run.</p>
 *
 * <p>Gated on {@code websearch.enabled} like the rest of the browser-agent
 * stack, so it is simply absent (and its callers no-op) when the web stack is
 * off (e.g. a CE install without the browser-agent profile).</p>
 */
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class BrowserAgentRunAborter {

    private static final Logger logger = LoggerFactory.getLogger(BrowserAgentRunAborter.class);

    /** Same hash prefix BrowserAgentModule / BrowserSessionLifecycleService use. */
    static final String META_PREFIX = "agent:browse:meta:";
    /** Control-queue key format - MUST match websearch redis_io.control_key. */
    static final String CONTROL_KEY_FMT = "agent:run:%s:node:%s:control";

    private final RestTemplate restTemplate;
    private final WebSearchConfig config;
    private final StringRedisTemplate redisTemplate;

    public BrowserAgentRunAborter(
            @Qualifier("webSearchRestTemplate") RestTemplate restTemplate,
            WebSearchConfig config,
            @Qualifier("webSearchRedisTemplate") StringRedisTemplate redisTemplate) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Abort every in-flight browser-agent session recorded for {@code runId}.
     * Best-effort and never throws - a stop/cancel must complete regardless.
     *
     * @return the number of sessions an abort was issued for (0 when the run has
     *         no in-flight browse, i.e. the common case).
     */
    public int abortAllForRun(String runId) {
        if (runId == null || runId.isBlank() || redisTemplate == null) {
            return 0;
        }
        int aborted = 0;
        try {
            String prefix = META_PREFIX + runId + ":";
            Set<String> keys = scanKeys(prefix + "*");
            for (String key : keys) {
                String nodeId = key.substring(prefix.length());
                if (nodeId.isBlank()) {
                    continue;
                }
                String sid = readSessionId(key);
                abortSession(runId, nodeId, sid);
                aborted++;
            }
            if (aborted > 0) {
                logger.info("[BrowserAbort] issued abort for {} in-flight browse session(s) on run {}",
                        aborted, runId);
            }
        } catch (Exception e) {
            logger.warn("[BrowserAbort] abortAllForRun failed (runId={}): {}", runId, e.getMessage());
        }
        return aborted;
    }

    /**
     * Abort ONE browser-agent session, addressed by its control (runId, nodeId)
     * and - when known - its session id. Best-effort; never throws.
     *
     * <p>Used by both {@link #abortAllForRun(String)} and the live-view red
     * "stop" button (via the takeover controller). {@code sessionId} may be
     * null (meta hash gone / cdp_ready not reached yet): the control LPUSH still
     * reaches a running session; only the immediate REST abort is skipped.</p>
     */
    public void abortSession(String runId, String nodeId, String sessionId) {
        if (runId == null || nodeId == null) {
            return;
        }
        // 1. Immediate REST abort (sets session.aborted in-process on the box).
        if (sessionId != null && !sessionId.isBlank()) {
            String base = config != null ? config.getServiceUrl() : null;
            if (restTemplate != null && base != null && !base.isBlank()) {
                String url = (base.endsWith("/") ? base : base + "/")
                        + "agent/sessions/" + sessionId + "/abort";
                try {
                    restTemplate.postForObject(url, Map.of(), Map.class);
                } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
                    // Session already ended - nothing to abort. Not an error.
                    logger.debug("[BrowserAbort] session {} already gone (404)", sessionId);
                } catch (Exception e) {
                    logger.warn("[BrowserAbort] REST abort failed (sessionId={}): {}",
                            sessionId, e.getMessage());
                }
            }
        }
        // 2. Fallback: LPUSH a session-tagged ABORT onto the runner's control
        //    queue. Reaches a running session even if the REST leg failed;
        //    tagging by session_id makes the runner apply it to the right
        //    session on a shared (runId, nodeId) control key.
        if (redisTemplate != null) {
            try {
                String key = String.format(CONTROL_KEY_FMT, runId, nodeId);
                String payload = sessionId != null && !sessionId.isBlank()
                        ? "{\"cmd\":\"ABORT\",\"session_id\":\"" + sessionId + "\"}"
                        : "{\"cmd\":\"ABORT\"}";
                redisTemplate.opsForList().leftPush(key, payload);
                redisTemplate.expire(key, java.time.Duration.ofSeconds(600));
            } catch (Exception e) {
                logger.warn("[BrowserAbort] control LPUSH failed (runId={}, nodeId={}): {}",
                        runId, nodeId, e.getMessage());
            }
        }
    }

    private String readSessionId(String metaKey) {
        try {
            Object sid = redisTemplate.opsForHash().get(metaKey, "sessionId");
            return sid instanceof String s && !s.isBlank() ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Set<String> scanKeys(String pattern) {
        Set<String> out = new LinkedHashSet<>();
        ScanOptions opts = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<String> cursor = redisTemplate.scan(opts)) {
            while (cursor.hasNext()) {
                out.add(cursor.next());
            }
        } catch (Exception e) {
            logger.warn("[BrowserAbort] SCAN failed for {}: {}", pattern, e.getMessage());
        }
        return out;
    }
}
