package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Abstract base for job-style websearch modules.
 *
 * Encapsulates the shared submit-then-await-on-Redis pipeline used by
 * single-shot fetches today and to be reused by long-running browser-agent
 * sessions tomorrow:
 *
 *   1. POST /jobs/submit  →  receive {job_id} (~50ms)
 *   2. Wait for the result on Redis (default: BLPOP `fetch:result:{job_id}`)
 *   3. Run subclass post-processing (e.g. drop base64 screenshots)
 *   4. Return as ToolExecutionResult
 *
 * Concurrency is bounded by a per-instance Semaphore so each subclass can
 * size its own quota independently (cheap fetches: 30; expensive multi-step
 * sessions: small, e.g. 1).
 *
 * Subclass contract:
 *   - {@link #buildJobParameters(Map, ToolExecutionContext)} - required
 *   - {@link #postProcess(Map, Map, ToolExecutionContext)}     - optional
 *   - {@link #awaitJobResult(String, ToolExecutionContext)}    - optional
 *     (default reads a single LIST entry; override for Redis Streams)
 */
@Slf4j
public abstract class WebJobModule implements ToolModule {

    protected final RestTemplate restTemplate;
    protected final WebSearchConfig config;
    protected final StringRedisTemplate redisTemplate;
    protected final ObjectMapper objectMapper;
    private final Semaphore concurrencyGate;

    protected WebJobModule(RestTemplate restTemplate,
                           WebSearchConfig config,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper,
                           int concurrencyLimit) {
        this.restTemplate = restTemplate;
        this.config = config;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.concurrencyGate = new Semaphore(concurrencyLimit);
    }

    /**
     * Run the full submit → await → post-process pipeline.
     * Subclasses call this from their {@code execute(...)} method.
     */
    @SuppressWarnings("unchecked")
    protected final ToolExecutionResult submitAndAwait(String action,
                                                       Map<String, Object> parameters,
                                                       String tenantId,
                                                       ToolExecutionContext context) {
        if (!concurrencyGate.tryAcquire()) {
            return ToolExecutionResult.failure(ToolErrorCode.RATE_LIMITED, concurrencyError());
        }
        try {
            // 1. Build submit body
            Map<String, Object> jobParams = buildJobParameters(parameters, context);
            Map<String, Object> submitBody = new LinkedHashMap<>();
            submitBody.put("action", action);
            submitBody.put("parameters", jobParams);

            // 2. Submit (fast, ~50ms)
            String endpoint = config.getServiceUrl() + "/jobs/submit";
            Map<String, Object> submitResp = restTemplate.postForObject(endpoint, submitBody, Map.class);
            if (submitResp == null || !submitResp.containsKey("job_id")) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "No job_id from websearch-service");
            }
            String jobId = (String) submitResp.get("job_id");
            log.debug("Web job submitted: action={} jobId={}", action, jobId);

            // 3. Await result on Redis (default: BLPOP single entry)
            Map<String, Object> result = awaitJobResult(jobId, context);
            if (result == null) {
                // Subclass hook for timeout cleanup (e.g. BrowserAgentModule
                // auto-aborts the orphaned runner session + LREMs the per-user
                // concurrent slot so the user isn't locked out for the
                // runner's full ~600 s internal timeout). The hook is also
                // responsible for race recovery: if the runner LPUSHed a
                // result between our BLPOP timeout and the cleanup path, the
                // hook may return that success result instead of a failure.
                //
                // Wrapped in try/catch so a buggy subclass that lets a
                // RuntimeException escape doesn't corrupt the failure shape
                // - we still emit the structured timeoutError instead of
                // landing in the outer catch as a generic failedError.
                ToolExecutionResult timeoutOverride = null;
                try {
                    timeoutOverride = onSubmitTimeout(action, parameters, tenantId, context, jobId);
                } catch (Exception hookEx) {
                    log.warn("onSubmitTimeout hook threw for action={} jobId={}: {} - falling back to standard timeoutError",
                        action, jobId, hookEx.getMessage());
                }
                if (timeoutOverride != null) {
                    return timeoutOverride;
                }
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    timeoutError(action, getBlpopTimeoutSeconds()));
            }
            // 4. Subclass post-processing - runs BEFORE the error check so
            // failure paths still benefit from it (observability recording,
            // pricing computation, screenshot cleanup). The check below then
            // routes based on the post-processed payload.
            Map<String, Object> processed = postProcess(result, parameters, context);
            if (processed.containsKey("error")) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    failedError(action, String.valueOf(processed.get("error"))));
            }
            return ToolExecutionResult.success(processed);

        } catch (Exception e) {
            log.error("Web job ({}) failed: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                failedError(action, e.getMessage()));
        } finally {
            concurrencyGate.release();
        }
    }

    /**
     * Build the {@code parameters} map sent to {@code POST /jobs/submit}.
     * Subclasses populate any action-specific fields (urls, screenshots,
     * task, llm config, callback_url, …).
     */
    protected abstract Map<String, Object> buildJobParameters(Map<String, Object> parameters,
                                                              ToolExecutionContext context);

    /**
     * Default: read a single LIST entry from {@code fetch:result:{jobId}}
     * with the configured BLPOP timeout. Subclasses can override for
     * Redis Streams (e.g. a multi-step session that XADDs steps and a
     * final entry).
     */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> awaitJobResult(String jobId, ToolExecutionContext context) throws Exception {
        String resultKey = "fetch:result:" + jobId;
        String resultJson = redisTemplate.opsForList().leftPop(
            resultKey, Duration.ofSeconds(getBlpopTimeoutSeconds()));
        if (resultJson == null) return null;
        return objectMapper.readValue(resultJson, Map.class);
    }

    /**
     * Effective BLPOP timeout (seconds) for this module's job-style actions.
     * Defaults to {@code WebSearchConfig.blpopTimeout}; subclasses override
     * when they need a tighter ceiling - e.g. {@code BrowserAgentModule}
     * caps it well below the agent-side per-tool timeout so the orchestrator
     * can run cleanup BEFORE the agent client gives up.
     *
     * <p>Used by {@link #awaitJobResult} for the BLPOP duration AND by the
     * default {@code timeoutError} message so the wording stays accurate
     * when subclasses customise the ceiling.
     */
    protected int getBlpopTimeoutSeconds() {
        return config.getBlpopTimeout();
    }

    /**
     * Hook invoked when {@link #awaitJobResult} returns {@code null} (BLPOP
     * timed out). Default: returns {@code null} so the caller falls through
     * to the standard {@code timeoutError} failure. Subclasses override to
     * run cleanup (e.g. abort a runner-side long-running session, release a
     * per-user concurrency slot) AND/OR recover late-arriving results that
     * raced with the timeout.
     *
     * <p>Contract:
     * <ul>
     *   <li>Return {@code null} → caller emits the standard timeout failure.</li>
     *   <li>Return non-{@code null} → caller uses the returned result verbatim.
     *       Implementations may return success when they detect a result
     *       LPUSHed between BLPOP timeout and cleanup, or return a
     *       customised failure (e.g. carrying session_id for agent-side
     *       recovery).</li>
     * </ul>
     *
     * <p>Implementations MAY throw - {@code submitAndAwait} catches and logs
     * any exception escaping the hook and falls back to the standard
     * {@code timeoutError}. Prefer to catch internally so the failure shape
     * stays predictable, but the safety net is in place.
     *
     * <p><b>Why both {@code tenantId} AND {@code context}?</b> Some legacy
     * callers pass {@code tenantId} as an explicit arg WITHOUT setting it on
     * {@link ToolExecutionContext#tenantId()} - pre-fix this caused the
     * cleanup path to skip per-user budget release ("LREM by user_id"
     * couldn't key on a null userId). Always prefer the explicit
     * {@code tenantId} arg, fall back to {@code context.tenantId()}.
     */
    protected ToolExecutionResult onSubmitTimeout(String action,
                                                   Map<String, Object> parameters,
                                                   String tenantId,
                                                   ToolExecutionContext context,
                                                   String jobId) {
        return null;
    }

    /**
     * Hook for subclass-specific response post-processing. Default: identity.
     * Implementations may mutate {@code response} in place and return it.
     *
     * <p><b>Contract:</b> {@code postProcess} runs on EVERY non-null payload
     * - including failure payloads (top-level {@code error} key set, or
     * payloads where {@link #awaitJobResult} injected an error from a
     * non-COMPLETED stop reason). The pipeline routes the post-processed
     * payload to {@code success(...)} or {@code failure(...)} AFTER this
     * call. Implementations MUST be null-safe and idempotent on failure
     * shapes - callers rely on this to record observability + apply
     * pricing for crashed runs (otherwise failed sessions are invisible
     * in Agent Performance and Usage History). If you only want to run
     * on success, gate the implementation on
     * {@code !response.containsKey("error")} explicitly.
     */
    protected Map<String, Object> postProcess(Map<String, Object> response,
                                              Map<String, Object> parameters,
                                              ToolExecutionContext context) {
        return response;
    }

    /**
     * Build a callback URL the websearch-service can hit when an async
     * artefact (screenshot, per-step trace, …) is ready. Pulls the streaming
     * identifiers from the execution context credentials.
     *
     * @return the URL or {@code null} if the context lacks the required
     *         streaming identifiers.
     */
    protected String buildCallbackUrl(ToolExecutionContext context, String callbackPath) {
        if (context == null || context.credentials() == null) return null;
        Map<String, Object> creds = context.credentials();
        String streamId = (String) creds.get("__streamId__");
        String toolCallId = (String) creds.get("__toolCallId__");
        String conversationId = (String) creds.get("conversationId");
        if (streamId == null || toolCallId == null) return null;

        StringBuilder sb = new StringBuilder(config.getCallbackBaseUrl())
            .append(callbackPath)
            .append("?streamId=").append(URLEncoder.encode(streamId, StandardCharsets.UTF_8))
            .append("&toolId=").append(URLEncoder.encode(toolCallId, StandardCharsets.UTF_8));
        if (conversationId != null) {
            sb.append("&conversationId=").append(URLEncoder.encode(conversationId, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Drop empty base64 screenshot arrays from a websearch response (single
     * page or batch with {@code pages[]}). The {@code screenshot_key} (MinIO
     * key) is preserved for frontend display.
     *
     * Shared between fetch and any future job that returns screenshots.
     */
    @SuppressWarnings("unchecked")
    protected void cleanScreenshots(Map<String, Object> response) {
        response.remove("screenshots");
        Object pages = response.get("pages");
        if (pages instanceof List<?> pageList) {
            for (Object page : pageList) {
                if (page instanceof Map<?, ?> pageMap) {
                    ((Map<String, Object>) pageMap).remove("screenshots");
                }
            }
        }
    }

    // ── Subclass-overridable error wording (preserved verbatim from
    //    WebFetchModule today; subclasses may localize as needed) ───────

    protected String concurrencyError() {
        return "Too many concurrent web searches. Wait a moment and try again. "
            + "Do NOT retry immediately.";
    }

    protected String timeoutError(String action, int seconds) {
        return "Web " + action + " timed out after " + seconds + "s. "
            + "Do NOT retry this " + action + ".";
    }

    protected String failedError(String action, String detail) {
        return "Web " + action + " failed: " + detail
            + ". Do NOT retry this " + action + ".";
    }
}
