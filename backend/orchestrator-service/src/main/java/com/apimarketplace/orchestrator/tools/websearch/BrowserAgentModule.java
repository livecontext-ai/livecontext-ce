package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.credit.PricingSnapshotClient;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Module for autonomous browser-agent sessions.
 *
 * Handles five actions:
 * <ul>
 *   <li>{@code agent_browse} - submit a new browser-agent session and await its
 *       final result on Redis (job-style; uses the {@code submitAndAwait}
 *       pipeline inherited from {@link WebJobModule}).</li>
 *   <li>{@code browse_status} / {@code browse_intervene} / {@code browse_abort}
 *       / {@code browse_screenshot} - direct sync calls to the websearch-service
 *       FastAPI endpoints {@code POST /agent/sessions/{id}/{action}}. These are
 *       NOT job-style: they target an already-running session by id and return
 *       immediately, so they bypass the submit/await pipeline.</li>
 * </ul>
 *
 * Concurrency is bounded to 1 at the host level: a browser session pins a full
 * Chromium process plus an LLM context, so even small parallelism risks OOM and
 * inter-session cookie/storage bleed. The {@code agent:session:concurrency}
 * Redis semaphore enforces the same limit on the runner side; this in-process
 * gate just rejects fast so the LLM gets an actionable error.
 *
 * Job-completion signalling uses a per-job Redis LIST under
 * {@code agent:result:{jobId}} (mirrors the {@code fetch:result:} pattern, just
 * renamed). Live trace events ({@code step}, {@code thinking}, screenshots) are
 * published to the orchestrator via the {@code callback_url} we pass on submit
 * - the runner XADDs them to {@code agent:run:{r}:node:{n}:steps} for the
 * frontend SSE consumer; the orchestrator does NOT poll that stream from this
 * module.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class BrowserAgentModule extends WebJobModule {

    /**
     * Actions that flow through the submit/await pipeline (job-style).
     * {@code browse_status}/{@code browse_intervene}/{@code browse_abort}/{@code browse_screenshot}
     * are also handled by this module but bypass the pipeline.
     */
    private static final String ACTION_AGENT_BROWSE = "agent_browse";
    private static final String ACTION_BROWSE_STATUS = "browse_status";
    private static final String ACTION_BROWSE_INTERVENE = "browse_intervene";
    private static final String ACTION_BROWSE_ABORT = "browse_abort";
    private static final String ACTION_BROWSE_SCREENSHOT = "browse_screenshot";

    private static final Set<String> HANDLED_ACTIONS = Set.of(
        ACTION_AGENT_BROWSE,
        ACTION_BROWSE_STATUS,
        ACTION_BROWSE_INTERVENE,
        ACTION_BROWSE_ABORT,
        ACTION_BROWSE_SCREENSHOT
    );

    /** Backpressure: at most one active browser session per host. See class javadoc. */
    private static final int CONCURRENCY_LIMIT = 1;

    /** Per-job final-result LIST key prefix. Runner LPUSHes a single entry on completion. */
    // Distinct from `agent:result:` (used by AgentQueueWorkerService for the
    // async-agent queue's STRING values) - that's a different keyspace
    // pattern and a collision would surface as WRONGTYPE on either side.
    private static final String RESULT_KEY_PREFIX = "agent:browser:result:";

    /** Path the runner posts live step/screenshot events to. */
    private static final String STEP_CALLBACK_PATH = "/api/internal/websearch/callback/step";

    /**
     * Per-user budget gate (concurrent sessions + daily steps). Optional so
     * tests that don't care about the gate don't have to wire it; production
     * always has it (same {@code @ConditionalOnProperty} as this module).
     *
     * Bridges - never short-circuit billing (CLAUDE.md): the guard does NOT
     * differentiate bridge vs direct LLM models. A bridge session is still
     * a Chromium-pinning, LLM-stepping session and must be capped by the
     * same per-user resource limits as a direct-API session.
     */
    private final BrowserAgentBudgetGuard budgetGuard;

    /**
     * Issues short-lived JWTs the frontend uses to upgrade
     * {@code wss://websearch-host/cdp/{sid}?token=...}. Optional - if the
     * issuer is unconfigured (no shared secret), result metadata simply
     * omits the {@code cdp_token} field and the frontend renders a
     * "live view unavailable" fallback.
     */
    private final CdpTokenIssuer cdpTokenIssuer;

    /**
     * Resolves the LLM provider's API key with the same user-first → platform
     * fallback chain that {@code AbstractLLMProvider} uses for in-process LLM
     * calls (workflow-agent / classify / guardrail nodes). Single source of
     * truth in {@code shared-agent-lib} - the runner (Python websearch-service)
     * has no credential vocabulary, so we resolve here and inject the result
     * into the {@code llm} block as {@code api_key}.
     *
     * <p>Optional so unit tests that don't care about credential resolution
     * can pass {@code null}; production always has it (Spring autowires the
     * {@link com.apimarketplace.agent.credential.CachedLlmCredentialResolver}
     * bean from shared-agent-lib).
     *
     * <p>Bridge models ({@code provider_kind="bridge"}) skip this lookup -
     * the bridge handles upstream auth + internal credit accounting; we
     * MUST NOT inject an upstream provider key (see CLAUDE.md "Bridges -
     * never short-circuit billing" / memory feedback_bridges_no_billing_change).
     */
    private final LlmCredentialResolver credentialResolver;

    /**
     * Final fallback when {@link #credentialResolver} returns empty. Reads
     * {@code ai.agent.providers.<provider>.api-key} from the same Spring
     * config the agent providers (GeminiProvider, OpenAIProvider, …) consume.
     * The YAML defaults each entry to {@code ${<PROVIDER>_API_KEY:}} so any
     * env var the operator set when launching orchestrator-service is picked
     * up here too. This is the same env-tail that
     * {@code AbstractLLMProvider.resolveApiKey()} appends after the resolver.
     * Optional so unit tests can omit it.
     */
    private final Environment environment;

    /**
     * Resolves per-model rates ({@code (provider, model)} → input/output USD per
     * 1M tokens) from {@code auth.model_pricing} via auth-service's
     * {@code /api/internal/auth/pricing/snapshot} endpoint, with a 5-minute
     * cache. Used by {@link #postProcess} to overwrite the runner's
     * placeholder {@code cost.cost_usd=0} with a real billing figure derived
     * from {@code cost.by_model} before the result reaches the agent / workflow.
     *
     * <p>Bridge models (zero rates in the seed) keep {@code cost_usd=0} -
     * billing for bridges goes through the internal credit ledger
     * ({@code CreditService.consumeForChat}), not the cost block. This is the
     * "Bridges - never short-circuit billing" invariant: surfacing a non-zero
     * upstream USD figure for a bridge would imply double-billing to the
     * operator.
     */
    private final PricingSnapshotClient pricingSnapshotClient;

    /**
     * Posts {@link AgentObservabilityRequest} to agent-service so each
     * browser-agent run lands in {@code agent.agent_executions} +
     * {@code auth.credit_history} (source type
     * {@code BROWSER_AGENT_EXECUTION}). Without this the chat-tool path
     * (agent calling {@code web_search action=agent_browse}) is invisible to
     * Usage History AND Agent Performance - token consumption disappears
     * silently. The workflow path ({@code BrowserAgentNode.recordObservability})
     * has its own call site with richer context (workflowId, run, epoch);
     * this module covers the gap for ad-hoc chat invocations.
     *
     * <p>Optional so unit tests that don't care about observability can pass
     * {@code null}; production always has it (Spring autowires the same bean
     * used by {@code BrowserAgentNode}).
     */
    private final AgentClient agentClient;

    @org.springframework.beans.factory.annotation.Autowired
    public BrowserAgentModule(
            @Qualifier("webSearchRestTemplate") RestTemplate restTemplate,
            WebSearchConfig config,
            @Qualifier("webSearchRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            BrowserAgentBudgetGuard budgetGuard,
            CdpTokenIssuer cdpTokenIssuer,
            LlmCredentialResolver credentialResolver,
            Environment environment,
            PricingSnapshotClient pricingSnapshotClient,
            AgentClient agentClient) {
        super(restTemplate, config, redisTemplate, objectMapper, CONCURRENCY_LIMIT);
        this.budgetGuard = budgetGuard;
        this.cdpTokenIssuer = cdpTokenIssuer;
        this.credentialResolver = credentialResolver;
        this.environment = environment;
        this.pricingSnapshotClient = pricingSnapshotClient;
        this.agentClient = agentClient;
    }

    /**
     * Convenience constructor for tests that don't exercise the budget gate,
     * CDP token issuance, or credential resolution. Production wiring goes
     * through the full constructor with all collaborators injected by Spring.
     */
    BrowserAgentModule(
            RestTemplate restTemplate,
            WebSearchConfig config,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this(restTemplate, config, redisTemplate, objectMapper, null, null, null, null, null, null);
    }

    /**
     * Convenience constructor for tests that exercise the budget gate but
     * not the CDP token issuance or credential resolution. Kept for
     * backward compatibility with tests that pre-date PR #6's CDP wiring.
     */
    BrowserAgentModule(
            RestTemplate restTemplate,
            WebSearchConfig config,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            BrowserAgentBudgetGuard budgetGuard) {
        this(restTemplate, config, redisTemplate, objectMapper, budgetGuard, null, null, null, null, null);
    }

    /**
     * Convenience constructor for tests that exercise the budget gate +
     * CDP token issuance but not credential resolution. Kept for backward
     * compatibility with tests that pre-date credential-based LLM key
     * resolution.
     */
    BrowserAgentModule(
            RestTemplate restTemplate,
            WebSearchConfig config,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            BrowserAgentBudgetGuard budgetGuard,
            CdpTokenIssuer cdpTokenIssuer) {
        this(restTemplate, config, redisTemplate, objectMapper, budgetGuard, cdpTokenIssuer, null, null, null, null);
    }

    /**
     * Convenience constructor for tests that exercise credential resolution
     * via {@link LlmCredentialResolver} only (no Spring {@link Environment}
     * fallback, no pricing snapshot). Used by the existing
     * credential-resolution tests.
     */
    BrowserAgentModule(
            RestTemplate restTemplate,
            WebSearchConfig config,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            BrowserAgentBudgetGuard budgetGuard,
            CdpTokenIssuer cdpTokenIssuer,
            LlmCredentialResolver credentialResolver) {
        this(restTemplate, config, redisTemplate, objectMapper, budgetGuard, cdpTokenIssuer, credentialResolver, null, null, null);
    }

    /**
     * Convenience constructor for tests that exercise credential resolution
     * + Spring {@link Environment} fallback (no pricing snapshot).
     */
    BrowserAgentModule(
            RestTemplate restTemplate,
            WebSearchConfig config,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            BrowserAgentBudgetGuard budgetGuard,
            CdpTokenIssuer cdpTokenIssuer,
            LlmCredentialResolver credentialResolver,
            Environment environment) {
        this(restTemplate, config, redisTemplate, objectMapper, budgetGuard, cdpTokenIssuer, credentialResolver, environment, null, null);
    }

    /**
     * Convenience constructor for tests that exercise pricing but not
     * observability - the previous baseline before {@link AgentClient} was
     * threaded through.
     */
    BrowserAgentModule(
            RestTemplate restTemplate,
            WebSearchConfig config,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            BrowserAgentBudgetGuard budgetGuard,
            CdpTokenIssuer cdpTokenIssuer,
            LlmCredentialResolver credentialResolver,
            Environment environment,
            PricingSnapshotClient pricingSnapshotClient) {
        this(restTemplate, config, redisTemplate, objectMapper, budgetGuard, cdpTokenIssuer, credentialResolver, environment, pricingSnapshotClient, null);
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of(); // Definitions managed by WebSearchToolsProvider
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();
        // agent_browse is job-style (submit + await final on Redis LIST).
        if (ACTION_AGENT_BROWSE.equals(action)) {
            // Per-user budget gate - checked BEFORE submit so we save the
            // FastAPI round-trip when the caps are already saturated. The
            // runner re-checks authoritatively (LPUSH-and-check), so this
            // is a fast-path, not a security boundary.
            String userId = resolveUserId(tenantId, context);
            if (budgetGuard != null && userId != null && !userId.isBlank()) {
                Optional<ToolExecutionResult> rejection = budgetGuard.checkBudget(userId);
                if (rejection.isPresent()) {
                    log.info("BrowserAgentModule: budget guard rejected userId={} action={}",
                        userId, action);
                    return rejection;
                }
            }
            // Default-fallback for the llm block: if it's missing, or
            // provider/model is missing/blank, or the requested pair isn't in
            // the platform's configured catalog, silently substitute with the
            // platform default (model #1 - same source as the UI app-header
            // model picker, see ModelCatalogService.getModelsWithOverrides
            // which filters !configured && !hasDbKey). Mutates `parameters` in
            // place so buildJobParameters and validateLlmModelOrNull see the
            // resolved block. The substitution descriptor (when a swap
            // happened) is forwarded to the result as `model_substituted` so
            // the LLM sees exactly which model was used.
            Optional<ModelSubstitution> substitution = applyDefaultLlmIfNeeded(parameters);
            // CE deployment guardrail: when AgentClient is unwired (CE without
            // billing) AND the agent omitted the llm block, applyDefaultLlmIfNeeded
            // is a no-op - leaving an empty llm parameter that crashes the
            // Python runner with a generic LlmConfigError. Fail loudly here
            // with an actionable message instead.
            if (agentClient == null && parameters.get("llm") == null) {
                return Optional.of(ToolExecutionResult.failure(
                    ToolErrorCode.MISSING_PARAMETER,
                    "agent_browse needs an 'llm' block on this deployment "
                    + "(model catalog client is not wired). Pass {provider, model, max_steps} "
                    + "explicitly. On a fully-wired deployment the block is optional."));
            }
            // Pre-flight LLM model validation - mirror the agent path's
            // checkChatBudget fail-closed contract (CreditConsumptionClient
            // rejects unknown provider/model upfront). With the default-fallback
            // above, this guard now only fires when the platform itself has no
            // configured model (catalog empty / agent-service unreachable).
            Optional<ToolExecutionResult> modelRejection = validateLlmModelOrNull(parameters);
            if (modelRejection.isPresent()) {
                return modelRejection;
            }
            // Persist (runId, nodeId) → {conversationId, userId} so the
            // BrowserSessionLifecycleSubscriber can look up the chat context when
            // the runner emits the first cdp_ready step event. Without
            // this hash, the consumer doesn't know which chat channel to
            // fan out the live-view bootstrap to. Best-effort: a Redis
            // hiccup must NOT block the agent_browse submit.
            persistChatContextForLiveView(parameters, context, userId);
            ToolExecutionResult runResult = submitAndAwait(action, parameters, tenantId, context);
            // Plumb the substitution notice into the result so the LLM sees
            // which model was actually used. Only present when a swap happened.
            if (substitution.isPresent() && runResult.success()) {
                return Optional.of(withModelSubstituted(runResult, substitution.get()));
            }
            return Optional.of(runResult);
        }
        // browse_status/intervene/abort/screenshot are sync-only: hit FastAPI directly.
        return Optional.of(executeSessionControl(action, parameters));
    }

    /**
     * Resolve the user-id to gate against. Source order:
     * <ol>
     *   <li>{@code context.tenantId()} - the orchestrator's authenticated user
     *       (this system uses tenantId == userId).</li>
     *   <li>The {@code tenantId} arg passed by the caller (legacy paths).</li>
     * </ol>
     * Returns {@code null} when neither is set so callers know to skip the
     * gate (test fixtures + diagnostic flows).
     */
    private static String resolveUserId(String tenantId, ToolExecutionContext context) {
        if (context != null && context.tenantId() != null && !context.tenantId().isBlank()) {
            return context.tenantId();
        }
        return tenantId;
    }

    @Override
    protected Map<String, Object> buildJobParameters(Map<String, Object> parameters,
                                                     ToolExecutionContext context) {
        Map<String, Object> jobParams = new LinkedHashMap<>();

        // Required: task description for the agent.
        if (parameters.get("task") != null) {
            jobParams.put("task", parameters.get("task"));
        }
        // Optional: starting URL.
        if (parameters.get("start_url") != null) {
            jobParams.put("start_url", parameters.get("start_url"));
        }

        // LLM block ({provider, model}, plus optional {api_key, max_steps}).
        // The runner has no credential vocabulary - it only reads `api_key` from
        // the block - so we resolve here against auth-service's platform_credentials
        // (integration name = "llm_" + provider; same convention as
        // agent-service.LlmCredentialRepository). Direct callers MAY still pass
        // `provider_kind="bridge"` + `bridge_url` explicitly (the resolver then
        // takes the bridge code path and injectLlmApiKey skips API-key resolution
        // - see CLAUDE.md "Bridges - never short-circuit billing"). The
        // automatic substitution path (applyDefaultLlmIfNeeded) does NOT inject
        // these - it picks the highest-ranked direct-API model because bridges
        // don't expose per-step chat completions.
        // Defensive parsing: pre-fix persisted plans shipped object-valued
        // params as JSON-strings via paramExpressions. parseObjectParam()
        // hydrates them back to Maps when they arrive as a string starting
        // with '{'. Applied symmetrically to llm, session and
        // expected_output_schema since all three are object-shaped per
        // BrowserAgentNodeSpec and would otherwise crash the runner with
        // an `isinstance(dict)` rejection.
        Object llmObj = parseObjectParam(parameters.get("llm"));
        if (llmObj instanceof Map<?, ?> llmRaw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llmCopy = new LinkedHashMap<>((Map<String, Object>) llmRaw);
            // Pull userId from the ToolExecutionContext so credential resolution
            // works on workflow threads (no servlet request bound) as well as
            // chat-tool threads. ToolExecutionContext.tenantId() is populated
            // by both call paths: BrowserAgentNode passes context.tenantId() →
            // ToolExecutionContext, and the chat-tool dispatcher forwards the
            // gateway-injected X-User-ID header into the same field.
            String userId = (context != null) ? context.tenantId() : null;
            injectLlmApiKey(llmCopy, userId);
            jobParams.put("llm", llmCopy);
        } else if (llmObj != null) {
            // Non-map and non-JSON value - forward verbatim so the runner
            // returns a precise LlmConfigError rather than us swallowing it.
            jobParams.put("llm", llmObj);
        }

        // Optional: top-level max_steps override. The schema documents this
        // as "Override for llm.max_steps" but pre-fix the runtime never
        // forwarded it - the runner just used its 50-step default. The agent
        // would dutifully send max_steps=80 and see no effect, then misread
        // the bogus "reached max_steps=50" classifier (since-fixed runner-
        // side) and conclude the bump didn't help. Forward it explicitly so
        // the runner's `parameters.get("max_steps")` lookup actually finds it.
        forwardIfPresent(parameters, jobParams, "max_steps");

        // Optional: interaction_mode (one of: autonomous, supervised, manual).
        forwardIfPresent(parameters, jobParams, "interaction_mode");

        // Optional: domain allow/deny lists (defense-in-depth - runner enforces them).
        forwardIfPresent(parameters, jobParams, "domain_allowlist");
        forwardIfPresent(parameters, jobParams, "domain_denylist");

        // Optional: expected_output_schema (JSON Schema; runner validates extracted_data
        // against it before terminating COMPLETED). Defensive parse for
        // pre-fix persisted plans (see parseObjectParam Javadoc).
        Object schemaObj = parseObjectParam(parameters.get("expected_output_schema"));
        if (schemaObj != null) {
            jobParams.put("expected_output_schema", schemaObj);
        }

        // Optional: screenshot_policy (e.g. "every_step", "on_change", "final_only", "off").
        forwardIfPresent(parameters, jobParams, "screenshot_policy");

        // Optional: session block (headless, viewport {width,height}, timeout_seconds).
        // Defensive parse for pre-fix persisted plans.
        Object sessionObj = parseObjectParam(parameters.get("session"));
        if (sessionObj != null) {
            jobParams.put("session", sessionObj);
        }

        // Live-trace callback. Runner POSTs step/screenshot/thinking events to this URL
        // as the session progresses. Final-result delivery is via the per-job Redis LIST,
        // not the callback.
        String callbackUrl = buildCallbackUrl(context, STEP_CALLBACK_PATH);
        if (callbackUrl != null) {
            jobParams.put("callback_url", callbackUrl);
        }

        // Forward identity + run/node ids to the runner via the credentials
        // block. The runner reads __userId__ to key the per-user budget
        // counters (mirrors BrowserAgentBudgetGuard keying), and uses
        // __streamId__/__toolCallId__ to write the correct per-session
        // Redis Stream + emit a cdp_ready PUBLISH that matches the
        // agent:browse:meta:{run_id}:{node_id} hash this module wrote
        // pre-submit. Without runId/nodeId, the runner falls back to
        // "run_unknown"/"node_unknown" and the entire live-view path
        // silently no-ops.
        if (context != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> existingCreds = (Map<String, Object>) jobParams.get("credentials");
            Map<String, Object> creds = existingCreds != null
                ? new LinkedHashMap<>(existingCreds)
                : new LinkedHashMap<>();
            if (context.tenantId() != null && !context.tenantId().isBlank()) {
                creds.put("__userId__", context.tenantId());
            }
            String runId = stringFromCreds(context, "__streamId__");
            String nodeId = stringFromCreds(context, "__toolCallId__");
            if (runId != null && !runId.isBlank()) {
                creds.put("__streamId__", runId);
            }
            if (nodeId != null && !nodeId.isBlank()) {
                creds.put("__toolCallId__", nodeId);
            }
            String conversationId = stringFromCreds(context, "conversationId");
            if (conversationId != null && !conversationId.isBlank()) {
                creds.put("conversationId", conversationId);
            }
            if (!creds.isEmpty()) {
                jobParams.put("credentials", creds);
            }
        }

        return jobParams;
    }

    /**
     * Override the LIST key so we don't collide with fetch's {@code fetch:result:}
     * keyspace. Same single-entry BLPOP semantics - the runner LPUSHes exactly once
     * when the session terminates (COMPLETED, MAX_STEPS, USER_TAKEOVER, ERROR, …).
     *
     * <p>Failure mapping: the runner ALWAYS emits a result blob (even on
     * LLM_FAILED / DOMAIN_BLOCKED / TIMEOUT / SCHEMA_MISMATCH) without a
     * top-level {@code error} key - only {@code stop_reason}. The base
     * pipeline ({@link WebJobModule#submitAndAwait}) therefore treats every
     * non-null payload as a success and the workflow node ends up COMPLETED
     * even when the session crashed before the first browser step. We
     * inject {@code error} into the payload here when {@code stop_reason}
     * indicates a non-completion exit so the base pipeline routes through
     * {@link WebJobModule#failedError} → {@code ToolExecutionResult.failure}
     * → workflow node FAILED. {@code final_result} is preserved verbatim
     * as the error message so the inspector still surfaces the runner's
     * own diagnostic ("llm config must be a dict, got str", "DOMAIN_BLOCKED:
     * example.com", …).
     */
    @Override
    @SuppressWarnings("unchecked")
    protected Map<String, Object> awaitJobResult(String jobId, ToolExecutionContext context) throws Exception {
        String resultKey = RESULT_KEY_PREFIX + jobId;
        // Use the subclass-overridable timeout (browserAgentBlpopTimeout, ~110 s)
        // instead of the generic blpopTimeout (150 s) so the orchestrator times
        // out BEFORE the agent client (120 s). See getBlpopTimeoutSeconds()
        // javadoc for the full ordering rationale.
        String resultJson = redisTemplate.opsForList().leftPop(
            resultKey, Duration.ofSeconds(getBlpopTimeoutSeconds()));
        if (resultJson == null) return null;
        Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);
        if (!result.containsKey("error")) {
            Object stopReasonObj = result.get("stop_reason");
            String stopReasonStr = stopReasonObj instanceof String s ? s.trim() : null;
            boolean isCompleted = stopReasonStr != null
                && !stopReasonStr.isEmpty()
                && "COMPLETED".equalsIgnoreCase(stopReasonStr);
            if (!isCompleted) {
                // Fail-closed: any non-COMPLETED stop_reason - including null,
                // missing, empty, or whitespace - routes to failure. Without
                // this guard, a runner that LPUSHes a payload before writing
                // stop_reason (or any future extension that emits a
                // structured exit signal we don't recognise) would land as
                // success in agent_executions even though the session never
                // ran a step. final_result is preserved verbatim as the
                // surfaced error message; for non-String final_result
                // (structured extraction errors) we use String.valueOf so
                // the agent sees the diagnostic instead of a generic stub.
                Object finalResult = result.get("final_result");
                String errorMessage;
                if (finalResult instanceof String s && !s.isBlank()) {
                    errorMessage = s;
                } else if (finalResult != null && !(finalResult instanceof String)) {
                    // Structured non-String final_result (Map/List from a
                    // schema-validation failure, etc.) - preserve the
                    // diagnostic via String.valueOf rather than dropping it
                    // into the generic fallback.
                    errorMessage = String.valueOf(finalResult);
                } else {
                    errorMessage = "Browser agent stopped"
                        + (stopReasonStr != null && !stopReasonStr.isEmpty()
                            ? " with reason " + stopReasonStr
                            : " without a stop_reason");
                }
                result.put("error", errorMessage);
            }
        }
        return result;
    }

    /**
     * Resolve the {@code llm} block to the platform default ((provider, model)
     * #1 in the configured catalog) when the agent omits it, omits half of it,
     * or passes an unknown pair. Source = {@link AgentClient#getModelsInfo()}
     * - same as the UI app-header model picker, with providers without a
     * configured API key already filtered out.
     *
     * <p>Mutates {@code parameters.llm} in place so {@code buildJobParameters}
     * forwards the resolved block to the runner. Bridge-kind blocks are NOT
     * substituted (their (provider, model) lives in a different catalog and
     * is intentionally outside this list). When agent-service is unreachable
     * or returns an empty catalog, the block is left as-is and downstream
     * validation surfaces a precise error.
     *
     * <p>Returns an {@link Optional} populated with the {@code requested}/{@code actual}
     * substitution info when a swap actually happened - the caller wires it
     * into the {@code agent_browse} result as {@code model_substituted} so the
     * LLM sees what was used (mirrors the agent create/update flow).
     */
    @SuppressWarnings("unchecked")
    Optional<ModelSubstitution> applyDefaultLlmIfNeeded(Map<String, Object> parameters) {
        if (parameters == null || agentClient == null) return Optional.empty();

        Object llmObj = parseObjectParam(parameters.get("llm"));
        Map<String, Object> llm;
        if (llmObj instanceof Map<?, ?> existing) {
            llm = new LinkedHashMap<>((Map<String, Object>) existing);
            // Bridge models live outside the agent-service catalog and have
            // their own auth/billing - never substitute them.
            Object kindObj = llm.get("provider_kind");
            if (kindObj != null && "bridge".equalsIgnoreCase(String.valueOf(kindObj).trim())) {
                // Re-write the parsed Map so downstream code reads a Map even
                // if the legacy plan shipped a JSON-string - consistent with
                // every other branch.
                parameters.put("llm", llm);
                return Optional.empty();
            }
        } else if (llmObj == null) {
            llm = new LinkedHashMap<>();
        } else {
            // Non-map non-JSON value (legacy stringified plan) - leave as-is so
            // buildJobParameters / runner can surface the precise error.
            return Optional.empty();
        }

        String provider = nonBlankString(llm.get("provider"));
        String model = nonBlankString(llm.get("model"));

        // Fast path: caller passed a complete (provider, model) → check it's in
        // the configured catalog; if so, leave it alone. Catalog miss falls
        // through to the substitution path below.
        boolean needsResolve = (provider == null || model == null);
        if (!needsResolve) {
            try {
                if (modelInConfiguredCatalog(provider, model)) {
                    parameters.put("llm", llm);
                    return Optional.empty();
                }
                needsResolve = true;
            } catch (Exception e) {
                // Catalog lookup failed - don't substitute on infra hiccup,
                // let validateLlmModelOrNull (with its own fail-OPEN) decide.
                log.warn("BrowserAgentModule: catalog lookup threw, skipping llm default-fallback (provider={}, model={}): {}",
                    provider, model, e.getMessage());
                parameters.put("llm", llm);
                return Optional.empty();
            }
        }

        if (!needsResolve) return Optional.empty();

        Map<String, Object> catalog;
        try {
            // V156: per-category catalog so the substituted default reflects
            // the admin's browser_agent ranking, not the chat ranking. The
            // sidecar may also disable a model for browser_agent only - in
            // that case it disappears from this catalog and the next-ranked
            // model becomes the default.
            catalog = agentClient.getModelsInfo("browser_agent");
        } catch (Exception e) {
            log.warn("BrowserAgentModule: getModelsInfo failed, leaving llm block as-is: {}", e.getMessage());
            parameters.put("llm", llm);
            return Optional.empty();
        }

        // Bridges (Claude Code / codex / gemini-cli / mistral-vibe) cannot
        // serve as the LLM for agent_browse: they're full-CLI agent sessions
        // (`POST /api/bridge/execute` runs an entire agent loop per call),
        // not per-step chat-completion APIs. browser-use needs the latter -
        // one atomic LLM call per browser step. Use defaultDirectProvider /
        // defaultDirectModel from the catalog (which excludes bridges) for
        // the substitution. The bridge can still be the platform default for
        // chat / agent.create (full-session use cases that work with the
        // bridge's session-per-call contract).
        String defaultProvider = nonBlankString(catalog.get("defaultDirectProvider"));
        String defaultModel = nonBlankString(catalog.get("defaultDirectModel"));
        // Fallback to defaultProvider/defaultModel only when no direct-API
        // model is configured at all (CE deployments with bridges only). The
        // runner will then surface a precise error rather than silently
        // routing to a bridge that can't honor a per-step call.
        if (defaultProvider == null || defaultModel == null) {
            defaultProvider = nonBlankString(catalog.get("defaultProvider"));
            defaultModel = nonBlankString(catalog.get("defaultModel"));
        }
        if (defaultProvider == null || defaultModel == null) {
            log.warn("BrowserAgentModule: catalog has no defaultDirectProvider/defaultDirectModel and no defaultProvider/defaultModel - leaving llm block as-is");
            parameters.put("llm", llm);
            return Optional.empty();
        }

        String requestedProvider = provider == null ? "(none)" : provider;
        String requestedModel = model == null ? "(none)" : model;
        llm.put("provider", defaultProvider);
        llm.put("model", defaultModel);

        log.info("BrowserAgentModule: substituted llm {}/{} → {}/{} (platform direct-API default - bridges skipped because they don't expose per-step chat completions)",
            requestedProvider, requestedModel, defaultProvider, defaultModel);
        parameters.put("llm", llm);
        return Optional.of(new ModelSubstitution(requestedProvider, requestedModel, defaultProvider, defaultModel));
    }

    /**
     * Substitution descriptor surfaced to the LLM in the {@code agent_browse}
     * result as {@code model_substituted}. Mirrors {@code AgentCrudModule.ModelSubstitution}
     * - same field names so the agent only learns one shape.
     */
    public record ModelSubstitution(String requestedProvider, String requestedModel,
                                    String actualProvider, String actualModel) {
        public Map<String, String> toResponseMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("requested", requestedProvider + "/" + requestedModel);
            m.put("actual", actualProvider + "/" + actualModel);
            m.put("reason", "Requested model is not available for agent_browse - fell back to the platform's "
                + "highest-ranked direct-API model (bridge providers like Claude Code / codex / gemini-cli "
                + "are skipped here because they're full-CLI agent sessions, not per-step chat-completion APIs).");
            return m;
        }
    }

    /**
     * Returns a copy of {@code result} with {@code model_substituted} attached
     * to the data Map so the LLM caller can read which model was actually used.
     * Idempotent - a second call wins (won't happen in the current execute flow
     * but keeps the helper safe for future reuse). Falls back to a wrapper Map
     * if the runner's data shape isn't a Map (defensive - runner has always
     * returned a Map for agent_browse, but the contract is duck-typed).
     */
    @SuppressWarnings("unchecked")
    private static ToolExecutionResult withModelSubstituted(ToolExecutionResult result,
                                                             ModelSubstitution substitution) {
        Object data = result.data();
        Map<String, Object> enriched;
        if (data instanceof Map<?, ?> existing) {
            enriched = new LinkedHashMap<>((Map<String, Object>) existing);
        } else {
            enriched = new LinkedHashMap<>();
            enriched.put("result", data);
        }
        enriched.put("model_substituted", substitution.toResponseMap());
        return ToolExecutionResult.success(enriched, result.metadata());
    }

    /**
     * True iff {@code (provider, model)} appears under any provider in the
     * agent-service configured catalog for the {@code browser_agent} category
     * (V156). A model disabled for browser_agent only is filtered out here so
     * the substitution loop in {@link #applyDefaultLlmIfNeeded} kicks in and
     * routes to the per-category default.
     */
    @SuppressWarnings("unchecked")
    private boolean modelInConfiguredCatalog(String provider, String model) {
        Map<String, Object> catalog = agentClient.getModelsInfo("browser_agent");
        List<Map<String, Object>> providers = (List<Map<String, Object>>) catalog.getOrDefault("providers", List.of());
        for (Map<String, Object> p : providers) {
            if (!provider.equals(p.get("name"))) continue;
            List<Map<String, Object>> models = (List<Map<String, Object>>) p.getOrDefault("models", List.of());
            for (Map<String, Object> m : models) {
                if (model.equals(m.get("id"))) return true;
            }
        }
        return false;
    }

    /**
     * Pre-flight check: the {@code (provider, model)} pair MUST be in the
     * {@link PricingSnapshotClient} catalog (i.e. {@code auth.model_pricing})
     * before we submit, so we don't waste a Chromium spawn + ~6 upstream
     * 404 retries on a typo'd or decommissioned model name. Mirrors the
     * agent path's pre-flight (see {@code CreditConsumptionClient.checkChatBudget}
     * - it fail-closes on unknown provider/model for the exact same reason).
     *
     * <p>Returns empty (allow) when:
     * <ul>
     *   <li>{@link PricingSnapshotClient} is unwired (test path / CE without
     *       billing).</li>
     *   <li>{@code llm} is not a Map - legacy stringified plans surface a
     *       precise runtime error inside {@link #buildJobParameters}'s
     *       {@code parseObjectParam}, so don't pre-empt it here.</li>
     *   <li>{@code provider_kind == "bridge"} - bridges run their own
     *       catalog/auth/billing, and the upstream-404 risk this guard
     *       protects against doesn't apply (the bridge translates to its
     *       own provider).</li>
     *   <li>{@code provider} or {@code model} is missing/blank - let the
     *       runner emit its own LlmConfigError with the precise field name.</li>
     *   <li>The snapshot is unhealthy AND the lookup misses - auth-service
     *       outage must NOT reject otherwise-valid models. We fail-OPEN on
     *       infra problems, fail-CLOSED on confirmed-missing entries.</li>
     * </ul>
     *
     * <p>Returns a populated failure with {@link ToolErrorCode#INVALID_PARAMETER_VALUE}
     * when the snapshot is healthy and the pair is confirmed missing.
     */
    @SuppressWarnings("unchecked")
    private Optional<ToolExecutionResult> validateLlmModelOrNull(Map<String, Object> parameters) {
        if (pricingSnapshotClient == null || parameters == null) {
            return Optional.empty();
        }
        Object llmObj = parseObjectParam(parameters.get("llm"));
        if (!(llmObj instanceof Map<?, ?> llmRaw)) {
            return Optional.empty();
        }
        Map<String, Object> llm = (Map<String, Object>) llmRaw;

        Object kindObj = llm.get("provider_kind");
        if (kindObj != null && "bridge".equalsIgnoreCase(String.valueOf(kindObj).trim())) {
            return Optional.empty();
        }

        String provider = nonBlankString(llm.get("provider"));
        String model = nonBlankString(llm.get("model"));
        if (provider == null || model == null) {
            return Optional.empty();
        }

        // Lookup first - getRates() triggers refreshIfStale() and may flip
        // isHealthy() from false→true on a fresh process, so we must NOT
        // short-circuit on isHealthy() ahead of the call. Wrapped because
        // a pricing-snapshot failure must NEVER reject an otherwise-valid
        // run - same defense-in-depth posture as applyPricingToResponse.
        boolean knownModel;
        boolean snapshotHealthy;
        try {
            knownModel = pricingSnapshotClient.getRates(provider, model).isPresent();
            snapshotHealthy = pricingSnapshotClient.isHealthy();
        } catch (Exception e) {
            log.warn("BrowserAgentModule: pricing snapshot lookup threw during pre-flight "
                + "validation - allowing through (provider={} model={}): {}",
                provider, model, e.getMessage());
            return Optional.empty();
        }
        if (knownModel) {
            return Optional.empty();
        }
        if (!snapshotHealthy) {
            // Snapshot couldn't load - treat as infrastructure outage, not as
            // a confirmed-missing entry. Allowing the runner to try keeps the
            // browser_agent path usable when auth-service is down.
            log.warn("BrowserAgentModule: pricing snapshot unhealthy - skipping pre-flight "
                + "model validation for provider={} model={}", provider, model);
            return Optional.empty();
        }
        log.warn("BrowserAgentModule: rejecting agent_browse - unknown LLM model "
            + "({}, {}) is not in the platform pricing catalog", provider, model);
        return Optional.of(ToolExecutionResult.failure(
            ToolErrorCode.INVALID_PARAMETER_VALUE,
            "Unknown LLM model '" + model + "' for provider '" + provider + "'. "
                + "The platform has no pricing entry for this (provider, model) pair, "
                + "so the upstream API would reject the call. Switch to a registered "
                + "model - the platform admin manages the available list. Do NOT retry "
                + "with the same value."));
    }

    private static String nonBlankString(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Defensive parse for object-shaped params that arrive as JSON strings.
     * Pre-fix workflow plans (saved via the canvas before
     * {@code agentProcessor.ts} learnt to re-hydrate object fields from
     * {@code data.params}) shipped {@code llm} / {@code session} /
     * {@code expected_output_schema} as JSON-stringified strings via
     * {@code paramExpressions}. Each of those reaches the runner which does
     * an {@code isinstance(dict)} and rejects non-dicts. Parsing them on
     * the way through restores a working dict for old plans without
     * forcing a manual re-save.
     */
    @SuppressWarnings("unchecked")
    private Object parseObjectParam(Object raw) {
        if (raw instanceof String str && !str.isBlank()) {
            String trimmed = str.trim();
            if (trimmed.startsWith("{")) {
                try {
                    return objectMapper.readValue(trimmed, Map.class);
                } catch (Exception e) {
                    log.warn("BrowserAgentModule: looked like JSON but failed to parse, "
                        + "forwarding verbatim - runner will surface a precise error: {}",
                        e.getMessage());
                }
            }
        }
        return raw;
    }

    @Override
    protected Map<String, Object> postProcess(Map<String, Object> response,
                                              Map<String, Object> parameters,
                                              ToolExecutionContext context) {
        // Same screenshot cleanup as fetch - drop inline base64 arrays, keep keys.
        // The runner uploads to MinIO and returns keys; the base64 was never meant to
        // round-trip through the agent's context window.
        cleanScreenshots(response);

        // Overwrite the runner's placeholder cost.cost_usd=0 with the real
        // billing figure derived from cost.by_model + auth.model_pricing rates.
        // The Python runner (websearch-service/.../runner.py) explicitly
        // delegates pricing to us via the comment "priced by the
        // orchestrator-side AgentObservability". Without this, every
        // browser_agent call surfaces $0 to the chat agent / workflow even
        // when 200k tokens were consumed - silent billing loss.
        //
        // Wrapped: a snapshot lookup must NEVER fail the whole result delivery.
        // Worst case is the runner's $0 default surviving - the same behavior
        // as before this fix - which is strictly better than swallowing the
        // user's task output to surface a billing error.
        try {
            applyPricingToResponse(response, parameters);
        } catch (Exception e) {
            log.warn("BrowserAgentModule: pricing post-process threw, falling back to runner cost_usd=0: {}",
                e.getMessage());
        }

        // Record observability so chat-tool browser_agent runs land in
        // agent.agent_executions + auth.credit_history (source type
        // BROWSER_AGENT_EXECUTION). Workflow runs go through
        // BrowserAgentNode.recordObservability separately - the two paths
        // never both fire for the same call. Fire-and-forget; never block
        // the result on observability failures.
        try {
            recordObservabilityFromResult(response, parameters, context);
        } catch (Exception e) {
            log.warn("BrowserAgentModule: observability post-process threw: {}", e.getMessage());
        }

        // Mint a CDP token + URL for the live view. The frontend reads
        // these from the per-step SSE event metadata (forwarded as part of
        // the BrowserAgentNode output) and upgrades the WS exactly once.
        // If the issuer isn't configured we leave both fields out and the
        // panel renders a static fallback.
        if (cdpTokenIssuer != null && cdpTokenIssuer.isConfigured()) {
            String sessionId = stringField(response, "session_id");
            String runId = context != null ? stringFromCreds(context, "__streamId__") : null;
            String nodeId = context != null ? stringFromCreds(context, "__toolCallId__") : null;
            String userId = context != null ? context.tenantId() : null;
            if (sessionId != null && !sessionId.isBlank()
                && runId != null && !runId.isBlank()
                && nodeId != null && !nodeId.isBlank()) {
                String token = cdpTokenIssuer.issue(sessionId, userId, runId, nodeId);
                if (token != null) {
                    response.put("cdp_token", token);
                    response.put("cdp_ws_url", buildCdpWsUrl(sessionId));
                    // Surface run_id + node_id alongside the token so the
                    // chat-side AgentBrowsePanelContent can route token
                    // refresh and takeover-resume to the right (runId,
                    // nodeId) pair. Without these the panel still opens
                    // the initial WS but loses the auto-reconnect path
                    // (BrowserLiveCdpPanel gates scheduleReconnect on
                    // both runId and sessionId being present).
                    response.put("run_id", runId);
                    response.put("node_id", nodeId);
                }
            }
        }

        // Cap oversized strings deep in the response. agent_browse can return
        // {@code extracted_data} (free-form scraped JSON), {@code final_result}
        // (LLM summary), and {@code steps[*].observation} fields that an
        // unbounded scrape can fill with HTML / b64-thumbnails - all routed
        // straight into the agent's tool result without the regular
        // dehydrator since this module doesn't go through catalog. Shared
        // walker preserves FileRefs + small strings + structure, only caps
        // strings >32 KB.
        return com.apimarketplace.agent.tools.common.ToolResultSizeCap.capLargeStrings(response);
    }

    /**
     * Write a small Redis hash so {@code BrowserSessionLifecycleSubscriber} can
     * look up the run context when the runner emits its first {@code cdp_ready}
     * step event. Key: {@code agent:browse:meta:{runId}:{nodeId}}, TTL 1 hour
     * (matches the per-session steps stream's own TTL - both keys expire
     * together once the session ends).
     *
     * <p>For chat runs the hash carries {@code conversationId} so the
     * lifecycle service fans out on {@code ws:conversation:{cid}}. For
     * workflow runs (no conversationId) it carries {@code runType=workflow}
     * so the lifecycle service publishes on {@code ws:workflow:run:{runId}}
     * via {@code WorkflowRedisPublisher} - giving the workflow builder UI
     * the same mid-execution live-view the chat side panel already gets.</p>
     */
    private void persistChatContextForLiveView(Map<String, Object> parameters,
                                                ToolExecutionContext context,
                                                String userId) {
        if (redisTemplate == null || context == null) return;
        String runId = stringFromCreds(context, "__streamId__");
        String nodeId = stringFromCreds(context, "__toolCallId__");
        String conversationId = stringFromCreds(context, "conversationId");
        if (runId == null || runId.isBlank() || nodeId == null || nodeId.isBlank()) {
            return;
        }
        try {
            String key = "agent:browse:meta:" + runId + ":" + nodeId;
            Map<String, String> fields = new LinkedHashMap<>();
            if (conversationId != null && !conversationId.isBlank()) {
                fields.put("conversationId", conversationId);
            } else {
                // Workflow path - flag it so the lifecycle subscriber picks
                // the workflow fanout branch (publish on ws:workflow:run:{runId})
                // instead of the chat branch.
                fields.put("runType", "workflow");
            }
            if (userId != null && !userId.isBlank()) {
                fields.put("userId", userId);
            }
            redisTemplate.opsForHash().putAll(key, fields);
            redisTemplate.expire(key, java.time.Duration.ofHours(1));
        } catch (Exception e) {
            log.warn("BrowserAgentModule: failed to persist run context for live-view "
                    + "(runId={}, nodeId={}): {}", runId, nodeId, e.getMessage());
        }
    }

    private String buildCdpWsUrl(String sessionId) {
        // Single source of truth in CdpUrls - keep both the initial
        // submit response and the /cdp-token-refresh response on the
        // same URL scheme. getPublicWsBase() resolves to the user-
        // reachable host (proxied by Caddy on app-host to VLAN websearch
        // in prod) and falls back to serviceUrl for dev/local.
        return CdpUrls.buildWsUrl(config.getPublicWsBase(), sessionId);
    }

    private static String stringField(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static String stringFromCreds(ToolExecutionContext context, String key) {
        if (context == null || context.credentials() == null) return null;
        Object v = context.credentials().get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * Direct sync call to one of the per-session control endpoints
     * ({@code browse_status} / {@code browse_intervene} / {@code browse_abort}
     * / {@code browse_screenshot}).
     *
     * <p>These are not job-style: they operate on a session that is already running
     * (i.e. an {@code agent_browse} call earlier in the workflow holds the concurrency
     * permit and is doing the actual work). Calling these does NOT take a permit - they
     * are read-only or signal-only with respect to the session.</p>
     *
     * <p>Endpoint: {@code POST {serviceUrl}/agent/sessions/{session_id}/{verb}}</p>
     * <ul>
     *   <li>{@code browse_status} → {@code GET}-equivalent payload (status + last step).</li>
     *   <li>{@code browse_intervene} → posts {@code {hint}} for the agent to consume.</li>
     *   <li>{@code browse_abort} → no body; runner cancels the session.</li>
     *   <li>{@code browse_screenshot} → forces an immediate screenshot, returns its key.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeSessionControl(String action, Map<String, Object> parameters) {
        String sessionId = (String) parameters.get("session_id");
        if (sessionId == null || sessionId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "session_id is required for action '" + action + "'");
        }

        String verb = action.substring("browse_".length()); // status / intervene / abort / screenshot
        String url = config.getServiceUrl() + "/agent/sessions/" + sessionId + "/" + verb;

        // Minimal body: only browse_intervene actually needs payload (hint).
        Map<String, Object> body = new LinkedHashMap<>();
        if (ACTION_BROWSE_INTERVENE.equals(action) && parameters.get("hint") != null) {
            body.put("hint", parameters.get("hint"));
        }

        try {
            log.debug("Browser session control: action={} sessionId={}", action, sessionId);
            Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
            if (response == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "No response from websearch-service for " + action);
            }
            return ToolExecutionResult.success(response);
        } catch (Exception e) {
            log.error("Browser session control failed: action={} sessionId={} error={}",
                action, sessionId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Browser session " + verb + " failed: " + e.getMessage());
        }
    }

    private static void forwardIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.get(key) != null) {
            dst.put(key, src.get(key));
        }
    }

    /**
     * Resolve {@code api_key} from auth-service for the LLM provider named in
     * the block, mutating {@code llm} in place. No-op when:
     * <ul>
     *   <li>{@code api_key} is already set (caller supplied an explicit override
     *       - keep it, don't second-guess).</li>
     *   <li>{@code provider_kind == "bridge"} - bridge auth lives in the bridge,
     *       a direct upstream key here would short-circuit billing.</li>
     *   <li>{@code provider} is missing - the runner will raise a precise
     *       LlmConfigError; injecting nothing is the right behaviour.</li>
     *   <li>{@link LlmCredentialResolver} is unconfigured (null) - test path;
     *       runner will surface the upstream {@code OPENAI_API_KEY env var
     *       must be set} error and tests can assert on it.</li>
     *   <li>Neither a user nor a platform credential is set - log at warn
     *       level, leave the block as-is. The runner returns the upstream
     *       provider's authentication error, which is more actionable for
     *       the LLM agent than a swallowed Optional.empty() here.</li>
     * </ul>
     */
    private void injectLlmApiKey(Map<String, Object> llm, String userId) {
        // Bridge check FIRST: a bridge session must never trigger a direct
        // upstream credential lookup, regardless of what api_key (if any) is
        // already in the block. Whatever the caller put under api_key for a
        // bridge session is treated as a bridge passthrough token and left
        // untouched. We do NOT inject an `llm_<provider>` upstream key over
        // it because that would burn the upstream account without recording
        // it against the user's internal credit (CLAUDE.md "Bridges -
        // never short-circuit billing").
        Object kindObj = llm.get("provider_kind");
        if (kindObj != null && "bridge".equals(String.valueOf(kindObj).trim().toLowerCase())) {
            return;
        }
        // Caller-supplied api_key wins for direct-provider sessions: explicit
        // override beats resolver lookup. Same shape the runner consumes.
        if (llm.get("api_key") instanceof String existing && !existing.isBlank()) {
            return;
        }
        Object providerObj = llm.get("provider");
        if (providerObj == null) {
            return;
        }
        String provider = String.valueOf(providerObj).trim().toLowerCase();
        if (provider.isEmpty()) {
            return;
        }
        // 1) Shared resolver: user's default credential → platform credential.
        //    Same chain AbstractLLMProvider uses for in-process LLM calls
        //    (workflow-agent / classify / guardrail), so a user with a
        //    personal OpenAI key sees the same resolution everywhere. We pass
        //    userId explicitly because workflow threads have no servlet
        //    request bound (RequestContextHolder is empty there) - relying on
        //    the resolver's static TenantResolver.currentRequestUserId() would
        //    silently skip the user lookup on the primary BrowserAgent caller.
        String resolved = lookupResolverCredential(provider, userId);
        // 2) Spring config fallback (`ai.agent.providers.<provider>.api-key`).
        //    Same env-tail AbstractLLMProvider.resolveApiKey() appends after
        //    the resolver. Required when the operator runs on env vars only
        //    (no DB seed) - common dev/CE setup.
        if (resolved == null || resolved.isBlank()) {
            resolved = lookupSpringConfigCredential(provider);
        }
        if (resolved != null && !resolved.isBlank()) {
            llm.put("api_key", resolved);
        } else {
            log.warn("BrowserAgentModule: no api_key resolvable for provider={} (tried "
                + "user/platform credential 'llm_{}' and Spring config "
                + "'ai.agent.providers.{}.api-key'); runner will return LLM_FAILED "
                + "with the upstream provider's auth error.",
                provider, provider, provider);
        }
    }

    private String lookupResolverCredential(String provider, String userId) {
        if (credentialResolver == null) {
            return null;
        }
        try {
            return credentialResolver.resolveApiKey(userId, provider)
                .filter(s -> !s.isBlank())
                .orElse(null);
        } catch (Exception e) {
            log.warn("BrowserAgentModule: failed to resolve api_key for provider={}: {}",
                provider, e.getMessage());
            return null;
        }
    }


    private String lookupSpringConfigCredential(String provider) {
        if (environment == null) {
            return null;
        }
        // Property name follows application-agents.yml shape:
        //   ai.agent.providers.<provider>.api-key
        // Default expression (`${<PROVIDER>_API_KEY:}`) is resolved by Spring
        // before we read the property, so we just see the final string.
        String value = environment.getProperty("ai.agent.providers." + provider + ".api-key");
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * Compute {@code cost.cost_usd} from the runner's per-model token counts +
     * cached pricing rates, then mutate the response in place.
     *
     * <p>Formula matches {@code ModelCostCalculator}: rate is USD per 1M tokens
     * (provider list-price convention from V80), so the per-call cost is
     * {@code (prompt × inputRate + completion × outputRate) / 1_000_000}.
     *
     * <p>No-op (cost_usd left as runner default) when:
     * <ul>
     *   <li>{@link PricingSnapshotClient} is unconfigured (test path).</li>
     *   <li>{@code cost} or {@code cost.by_model} is missing/empty (e.g. the
     *       run failed before any LLM call - billing should stay at 0).</li>
     *   <li>{@code parameters.llm.provider} is missing (provider is the
     *       authoritative key for the snapshot lookup; without it the rates
     *       can't be resolved unambiguously across providers that share a
     *       model name).</li>
     *   <li>The snapshot has no rate for {@code (provider, model)} -
     *       a structural miss is logged at WARN and skipped (cost_usd stays
     *       0). For bridge models the snapshot rows have rate=0 by design,
     *       so the formula produces 0 and we keep the existing value.</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void applyPricingToResponse(Map<String, Object> response, Map<String, Object> parameters) {
        if (pricingSnapshotClient == null || response == null) {
            return;
        }
        Object costObj = response.get("cost");
        if (!(costObj instanceof Map<?, ?> costRaw)) {
            return;
        }
        Map<String, Object> cost = (Map<String, Object>) costRaw;
        Object byModelObj = cost.get("by_model");
        if (!(byModelObj instanceof Map<?, ?> byModelRaw) || byModelRaw.isEmpty()) {
            return;
        }
        Map<String, Object> byModel = (Map<String, Object>) byModelRaw;

        // Provider comes from the user-supplied `llm.provider`. The runner does
        // not echo it back per-model in `by_model` (which is keyed by model name
        // only), and a single browser session never mixes providers.
        String provider = providerFromParameters(parameters);
        if (provider == null) {
            log.debug("BrowserAgentModule: skipping cost calculation - no provider in llm block");
            return;
        }

        BigDecimal totalUsd = BigDecimal.ZERO;
        boolean anyMatched = false;
        for (Map.Entry<String, Object> entry : byModel.entrySet()) {
            String model = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> stats)) {
                continue;
            }
            long promptTokens = longField((Map<String, Object>) stats, "prompt_tokens");
            long completionTokens = longField((Map<String, Object>) stats, "completion_tokens");
            if (promptTokens <= 0 && completionTokens <= 0) {
                continue;
            }
            var ratesOpt = pricingSnapshotClient.getRates(provider, model);
            if (ratesOpt.isEmpty()) {
                log.warn("BrowserAgentModule: no pricing rate for ({}, {}) - cost_usd contribution skipped",
                    provider, model);
                continue;
            }
            var rates = ratesOpt.get();
            // (prompt × inputRate + completion × outputRate) / 1_000_000 + fixedCost
            // Matches ModelCostCalculator.computeCost - fixedCost is per-call,
            // not per-token, so it adds outside the /1M scaling. All current
            // V112/V128 rows have fixedCost=0, but treating it as 0 silently
            // would silently underbill any future row that uses it.
            BigDecimal callCost = rates.inputRate()
                .multiply(BigDecimal.valueOf(promptTokens))
                .add(rates.outputRate().multiply(BigDecimal.valueOf(completionTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP)
                .add(rates.fixedCost() != null ? rates.fixedCost() : BigDecimal.ZERO);
            totalUsd = totalUsd.add(callCost);
            anyMatched = true;
        }
        if (anyMatched) {
            cost.put("cost_usd", totalUsd.doubleValue());
        }
    }

    private static String providerFromParameters(Map<String, Object> parameters) {
        if (parameters == null) return null;
        Object llm = parameters.get("llm");
        if (!(llm instanceof Map<?, ?> llmMap)) return null;
        Object p = llmMap.get("provider");
        if (p == null) return null;
        String s = String.valueOf(p).trim().toLowerCase();
        return s.isEmpty() ? null : s;
    }

    private static long longField(Map<String, Object> m, String key) {
        if (m == null) return 0L;
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /**
     * Build and post an {@link AgentObservabilityRequest} for a chat-tool
     * browser_agent invocation. Mirrors the field-by-field mapping that
     * {@code BrowserAgentNode.recordObservability} does for the workflow
     * path; this is the gap-closer for ad-hoc agent calls.
     *
     * <p>No-op (early return) when:
     * <ul>
     *   <li>{@link AgentClient} is unwired (test path).</li>
     *   <li>{@code context.credentials()['__skipObservability__']} is set -
     *       the workflow path ({@code BrowserAgentNode}) records its own
     *       observability with richer context (workflowId, runId, epoch,
     *       spawn). Letting both fire would double-bill every workflow
     *       browser session.</li>
     *   <li>{@link ToolExecutionContext#tenantId()} is null/blank - the auth
     *       gateway always populates it; if absent the call is unauthenticated
     *       and observability would land under no tenant. Surface the gap
     *       loudly via debug log rather than billing it to "anonymous".</li>
     *   <li>{@code response} is null (job-level failure handled upstream).</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private void recordObservabilityFromResult(Map<String, Object> response,
                                                Map<String, Object> parameters,
                                                ToolExecutionContext context) {
        if (agentClient == null || response == null) {
            return;
        }
        if (context == null || context.tenantId() == null || context.tenantId().isBlank()) {
            log.debug("BrowserAgentModule: skipping observability - no tenantId in context");
            return;
        }
        // Workflow path opt-out: BrowserAgentNode sets this credential before
        // calling the module so its own recordObservability() call is the
        // sole writer of the agent_executions row for this run. See
        // BrowserAgentNode.buildCallbackCredentials().
        if (context.credentials() != null
            && "true".equals(String.valueOf(context.credentials().get("__skipObservability__")))) {
            log.debug("BrowserAgentModule: skipping observability - __skipObservability__=true (workflow path)");
            return;
        }

        AgentObservabilityRequest req = new AgentObservabilityRequest();
        req.setTenantId(context.tenantId());
        // PR20 - propagate workspace identity onto the browser-agent chat-tool row.
        // ToolExecutionContext exposes orgId() (not organizationId()) - the
        // record has carried this since PR15 wired the chat-tool path.
        req.setOrganizationId(context.orgId());
        req.setAgentType("browser_agent");
        req.setSource("chat_tool");

        String rawStop = stringFieldFromObj(response.get("stop_reason"));
        boolean success = "COMPLETED".equalsIgnoreCase(rawStop);
        req.setStopReason(BrowserAgentStopReasonMapper.map(rawStop, success));
        req.setStatus(success ? "COMPLETED" : "FAILED");

        Object costObj = response.get("cost");
        if (costObj instanceof Map<?, ?> costRaw) {
            Map<String, Object> cost = (Map<String, Object>) costRaw;
            long tokensIn = longField(cost, "tokens_in");
            long tokensOut = longField(cost, "tokens_out");
            long cacheRead = longField(cost, "cache_read_tokens");
            long cacheCreation = longField(cost, "cache_creation_tokens");
            if (tokensIn > 0 || tokensOut > 0) {
                req.setPromptTokens(tokensIn);
                req.setCompletionTokens(tokensOut);
                req.setTotalTokens(tokensIn + tokensOut);
            }
            if (cacheRead > 0) req.setCacheReadTokens(cacheRead);
            if (cacheCreation > 0) req.setCacheCreationTokens(cacheCreation);
            // browser_seconds is the wall-clock cost (Chromium + LLM).
            // Convert to ms so the dashboard's "duration" column matches
            // chat / classify / guardrail rows.
            Object browserSecs = cost.get("browser_seconds");
            if (browserSecs instanceof Number n && n.doubleValue() > 0) {
                req.setDurationMs((long) (n.doubleValue() * 1000.0));
            }
            req.setTotalToolCalls((int) longField(cost, "llm_calls"));
        }

        Object stepsObj = response.get("steps");
        if (stepsObj instanceof java.util.List<?> stepsList) {
            req.setIterationCount(stepsList.size());
        }

        Object llm = parameters == null ? null : parameters.get("llm");
        if (llm instanceof Map<?, ?> llmMap) {
            Object p = llmMap.get("provider");
            if (p != null) req.setProvider(String.valueOf(p));
            Object m = llmMap.get("model");
            if (m != null) req.setModel(String.valueOf(m));
        }

        // Build the conversation timeline so the Agent Performance side panel
        // can replay this run like classify/guardrail. Pattern mirrors
        // AgentNode.executeClassify (lines 1022-1063): SYSTEM (omitted - the
        // browser-use system prompt is internal to the runner and not
        // exposed), USER (the task description), then one ASSISTANT message
        // per extracted_data step + a final ASSISTANT for final_result.
        // Without this, the side panel shows an empty conversation tab.
        java.util.List<AgentObservabilityRequest.MessageData> messages = buildConversationMessages(
            parameters, response);
        if (!messages.isEmpty()) {
            req.setMessages(messages);
        }

        agentClient.recordObservability(req);
    }

    /**
     * Materialise the browser-agent run as a flat USER/ASSISTANT timeline.
     * The runner does not surface the LLM's internal prompt/response pairs
     * (browser-use abstracts them behind step decisions), so we synthesise
     * a coarse view: the task is the user goal, extracted_data + final_result
     * the assistant's narrated progress. Good enough for triage and budget
     * accounting, not a faithful transcript.
     */
    // Public so BrowserAgentNode can reuse the same construction for the
    // workflow observability path - keeps the chat-tool and workflow paths
    // emitting identical conversation timelines.
    @SuppressWarnings("unchecked")
    public static java.util.List<AgentObservabilityRequest.MessageData> buildConversationMessages(
            Map<String, Object> parameters, Map<String, Object> response) {
        java.util.List<AgentObservabilityRequest.MessageData> messages = new java.util.ArrayList<>();
        int seq = 0;
        // USER - the task description (what the agent told browser-use to do).
        if (parameters != null) {
            Object task = parameters.get("task");
            if (task != null && !String.valueOf(task).isBlank()) {
                AgentObservabilityRequest.MessageData usr = new AgentObservabilityRequest.MessageData();
                usr.setSequenceNumber(seq++);
                usr.setRole("USER");
                usr.setContent(String.valueOf(task));
                usr.setIterationNumber(1);
                messages.add(usr);
            }
        }
        // ASSISTANT intermediate - one per extracted_data entry. Each is a
        // human-readable trace of what happened on a step (navigation,
        // element interaction, partial extraction). Skip blanks.
        if (response != null && response.get("extracted_data") instanceof java.util.List<?> extracted) {
            int iter = 1;
            for (Object item : extracted) {
                if (item == null) continue;
                String content = String.valueOf(item);
                if (content.isBlank()) continue;
                AgentObservabilityRequest.MessageData asst = new AgentObservabilityRequest.MessageData();
                asst.setSequenceNumber(seq++);
                asst.setRole("ASSISTANT");
                asst.setContent(content);
                asst.setIterationNumber(iter++);
                messages.add(asst);
            }
        }
        // ASSISTANT final - the agent's terminal answer to the task. Skip if
        // identical to the last extracted_data entry (browser-use sometimes
        // duplicates the final step into final_result), to avoid
        // double-rendering.
        if (response != null) {
            Object finalResult = response.get("final_result");
            if (finalResult != null && !String.valueOf(finalResult).isBlank()) {
                String finalStr = String.valueOf(finalResult);
                boolean duplicate = !messages.isEmpty()
                    && finalStr.equals(messages.get(messages.size() - 1).getContent());
                if (!duplicate) {
                    AgentObservabilityRequest.MessageData asst = new AgentObservabilityRequest.MessageData();
                    asst.setSequenceNumber(seq++);
                    asst.setRole("ASSISTANT");
                    asst.setContent(finalStr);
                    asst.setIterationNumber(1);
                    messages.add(asst);
                }
            }
        }
        return messages;
    }

    private static String stringFieldFromObj(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    // ── Override error wording so the agent gets browser-specific guidance. ──

    @Override
    protected String concurrencyError() {
        return "Browser agent capacity full. Wait and retry - the host runs at most 1 "
            + "concurrent browser session.";
    }

    @Override
    protected String failedError(String action, String detail) {
        if (ACTION_AGENT_BROWSE.equals(action)) {
            return "Browser session failed: " + detail + ". Do NOT retry immediately.";
        }
        return super.failedError(action, detail);
    }

    /**
     * Tighter BLPOP ceiling than the default (~150 s). The agent-side
     * per-tool timeout for {@code web_search} is 120 s - if the orchestrator
     * waits 150 s, the agent gives up FIRST while the runner keeps spinning,
     * holding the per-user concurrent slot for the runner's own ~600 s
     * internal timeout. That locks the user out of starting another browse
     * session in the meantime. Capping at ~110 s ensures the orchestrator
     * times out first, runs cleanup ({@link #onSubmitTimeout}), and the
     * agent receives a structured failure within its own deadline.
     */
    @Override
    protected int getBlpopTimeoutSeconds() {
        return config.getBrowserAgentBlpopTimeout();
    }

    /**
     * Same Redis namespace as {@code BrowserAgentBudgetGuard} (the Java-side
     * fast-path). Format MUST match {@code budget_gate.py} on the runner side
     * - both must agree or the LREM here mismatches the LPUSH'd value.
     */
    private static String concurrentSlotKey(String userId) {
        return "agent:browser:user:" + userId + ":concurrent";
    }

    /**
     * Mapping key the runner publishes ({@code runner.py} ~line 213) right
     * after spawning the session - value is the {@code session_id} string,
     * 10-min TTL. Lets the orchestrator (or any external observer) resolve
     * job_id → session_id without reaching into the runner's in-memory
     * registry.
     */
    private static String jobToSessionKey(String jobId) {
        return "agent:browser:job:" + jobId;
    }

    /**
     * Cleanup hook for orphaned {@code agent_browse} sessions when the
     * orchestrator BLPOP times out before the runner publishes a result.
     *
     * <p><b>Why this matters:</b> the runner LPUSHes the session_id onto the
     * per-user {@code agent:browser:user:{uid}:concurrent} LIST in
     * {@code acquire_concurrent_slot} and only LREMs it in {@code finally}
     * when its own loop exits. If the orchestrator gives up (BLPOP timeout)
     * but doesn't tell the runner, the slot stays held until the runner's
     * internal session timeout (~600 s) - locking the user out of starting
     * another browse session for several minutes.
     *
     * <p><b>Sequence (best-effort, never throws):</b>
     * <ol>
     *   <li><b>Race recovery</b> - re-poll the result LIST with a 1 s LPOP.
     *       The runner may have LPUSHed milliseconds AFTER our BLPOP
     *       returned null. If we find a result, drain it and return SUCCESS
     *       (not failure) - the user shouldn't be punished for a microsecond
     *       race.</li>
     *   <li><b>Resolve session_id</b> - short retry loop on {@code GET
     *       agent:browser:job:{jobId}} (3 × 100 ms). The runner publishes
     *       this mapping right after registering the session, so a missing
     *       value usually means the session crashed during bootstrap (LLM
     *       config error, browser launch failure, …) and there's nothing to
     *       abort.</li>
     *   <li><b>Abort the session</b> - POST to {@code /agent/sessions/{sid}/abort}
     *       so the runner exits its loop and runs its own
     *       {@code finally: release_concurrent_slot} cleanup. Idempotent on
     *       the runner side: aborting a COMPLETED session is a no-op.</li>
     *   <li><b>Authoritative slot release</b> - {@code LREM agent:browser:user:{uid}:concurrent
     *       1 {sessionId}} ourselves. Doesn't trust the runner's
     *       finally-block (it may itself crash) - guarantees the slot is
     *       freed before the agent retries.</li>
     *   <li><b>Drop the mapping</b> - {@code DEL agent:browser:job:{jobId}}
     *       to prevent a stale lookup if the next agent_browse re-uses the
     *       same job_id (extremely unlikely with UUIDs but cheap to ensure).</li>
     * </ol>
     *
     * <p>Returns a structured failure carrying the session_id (when known)
     * so the agent can manually fire {@code browse_status}/{@code browse_abort}
     * if the auto-cleanup somehow leaked the slot. The failure message is
     * worded with explicit "Do NOT retry" guidance to short-circuit the
     * agent's retry-on-error loop.
     */
    @Override
    protected ToolExecutionResult onSubmitTimeout(String action,
                                                   Map<String, Object> parameters,
                                                   String tenantId,
                                                   ToolExecutionContext context,
                                                   String jobId) {
        // Only the long-running submit path needs cleanup. browse_status/abort/
        // intervene/screenshot are sync HTTP calls that don't go through
        // submitAndAwait, so this hook is reached only via agent_browse -
        // but guard explicitly so a future job-style action doesn't
        // accidentally inherit the cleanup.
        if (!ACTION_AGENT_BROWSE.equals(action)) {
            return null;
        }

        // 1. Race recovery (round 1): drain a late-arriving result. 1 s LPOP
        //    gives the runner enough time to LPUSH if it was about to. Pass
        //    through the same fail-closed stop_reason check the main
        //    awaitJobResult uses so a non-COMPLETED late result routes to
        //    failure (not silent success).
        Map<String, Object> lateResult = drainLateResult(jobId, Duration.ofSeconds(1));
        if (lateResult != null) {
            return finalizeRaceRecovered(action, parameters, context, jobId, lateResult);
        }

        // 2. Resolve session_id with a brief retry - covers the case where
        //    the runner is mid-bootstrap and hasn't published the mapping
        //    yet. 3 × 100 ms is generous: if it's not there after 300 ms,
        //    the session is in cold-start (cookies/network/Chromium launch)
        //    and there's likely nothing to abort.
        String sessionId = resolveSessionIdWithRetry(jobId);

        // 3. Abort the runner session (best-effort).
        if (sessionId != null) {
            tryAbortSession(sessionId);
        } else {
            log.info("BrowserAgentModule: no session_id mapping for jobId={} after retry - "
                + "session likely crashed during bootstrap (LlmConfigError, browser launch, ...). "
                + "Skipping abort POST.", jobId);
        }

        // 4. Authoritative slot release: LREM ourselves. Even if the runner
        //    abort path crashes, the slot is freed.
        String userId = resolveUserId(tenantId, context);
        boolean slotReleasedByUs = false;
        if (sessionId != null && userId != null) {
            slotReleasedByUs = tryReleaseSlot(userId, sessionId);
        } else if (sessionId != null) {
            // Production paths always have userId. Surface this clearly so a
            // future caller missing the tenant context doesn't silently
            // re-introduce the original lock-out bug.
            log.warn("BrowserAgentModule: sessionId={} known but tenantId/context.tenantId() both null - "
                + "skipping authoritative LREM, relying on runner-side finally for slot release "
                + "(orphan TTL ~1h).", sessionId);
        }

        // 5. Cleanup the job → session mapping so a stale lookup can't
        //    surface later. Runs regardless of success/failure of earlier
        //    steps so the mapping doesn't outlive the cleanup decision.
        tryDeleteJobMapping(jobId);

        // 6. Race recovery (round 2) + recap capture: 10 s LPOP gives the
        //    runner enough time to react to the ABORT command (it polls the
        //    control queue at step boundaries - could take seconds while
        //    blocked on an LLM call or page load), exit its loop via
        //    `_AbortRequested`, run finally, and `push_result` the
        //    CANCELLED blob with full {steps[], pages_visited[],
        //    extracted_data, final_url, cost} from `_build_result`.
        //
        //    Three outcomes:
        //    - COMPLETED late result → true success race recovery (rare -
        //      runner finished naturally during the window). Surface as success.
        //    - CANCELLED / TIMEOUT / other late result → use it for a RECAP
        //      failure that surfaces the partial trace to the agent. The
        //      agent then sees what was attempted ("aborted on step 12 at
        //      /booking after filling form") and can decide its fallback
        //      strategy without re-running from scratch.
        //    - Still null → runner didn't react in time; emit basic failure.
        Map<String, Object> postCleanupResult = drainLateResult(jobId, Duration.ofSeconds(10));
        if (postCleanupResult != null) {
            String stopReason = stringFieldFromObj(postCleanupResult.get("stop_reason"));
            if ("COMPLETED".equalsIgnoreCase(stopReason)) {
                log.info("BrowserAgentModule: race-recovered COMPLETED result AFTER cleanup for "
                    + "jobId={} - returning success despite our abort POST.", jobId);
                return finalizeRaceRecovered(action, parameters, context, jobId, postCleanupResult);
            }
            log.info("BrowserAgentModule: captured runner recap AFTER cleanup for jobId={} "
                + "stop_reason={} - surfacing partial trace to agent.", jobId, stopReason);
            // The timeout path returns its result directly from this hook (see
            // WebJobModule.submitAndAwait line ~116), bypassing postProcess.
            // Without explicitly running pricing + observability here, every
            // timeout-recap call burns LLM tokens for free - the runner already
            // populated cost.by_model in the recap blob, so the credit ledger
            // line is just a billing pipeline call away. Mirrors postProcess()
            // failure-path contract (WebJobModule.java:218-231): on every non-null
            // payload run pricing then observability - both wrapped so a billing
            // failure never masks the agent-facing recap result.
            try {
                applyPricingToResponse(postCleanupResult, parameters);
            } catch (Exception e) {
                log.warn("BrowserAgentModule: timeout-recap pricing failed for jobId={}: {}",
                    jobId, e.getMessage());
            }
            try {
                recordObservabilityFromResult(postCleanupResult, parameters, context);
            } catch (Exception e) {
                log.warn("BrowserAgentModule: timeout-recap observability failed for jobId={}: {}",
                    jobId, e.getMessage());
            }
            return buildRecapFailure(sessionId, slotReleasedByUs, postCleanupResult);
        }

        // 7. No recap available (runner didn't react in time, or abort POST
        //    failed and runner is still spinning). Build the basic failure
        //    payload - agent at least knows "timed out, slot released, do
        //    not retry" + session_id for manual recovery.
        return buildBasicTimeoutFailure(sessionId, slotReleasedByUs);
    }

    /**
     * Build the basic timeout failure payload when no recap is available
     * (runner didn't react to the abort command in time, or abort POST
     * itself failed). The agent sees a "do not retry" verdict + session_id.
     */
    private ToolExecutionResult buildBasicTimeoutFailure(String sessionId, boolean slotReleasedByUs) {
        int timeoutSecs = getBlpopTimeoutSeconds();
        StringBuilder msg = new StringBuilder("Browser agent_browse timed out after ")
            .append(timeoutSecs).append("s. Slot released. Do NOT retry this agent_browse.");
        if (sessionId != null) {
            msg.append(" session_id=").append(sessionId);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timeout_seconds", timeoutSecs);
        metadata.put("auto_aborted", sessionId != null);
        metadata.put("slot_released", slotReleasedByUs);
        metadata.put("recap_available", false);
        if (sessionId != null) {
            metadata.put("session_id", sessionId);
        }
        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, msg.toString(), metadata);
    }

    /**
     * Build a RICH failure payload when the runner published its recap
     * blob during the cleanup window (typically a {@code stop_reason=CANCELLED}
     * result emitted by the runner's {@code finally} after our
     * {@code POST /abort}).
     *
     * <p>The agent gets a structured view of what was attempted:
     * <ul>
     *   <li>Number of steps the runner managed to execute</li>
     *   <li>Pages visited (so the agent knows where the run stalled)</li>
     *   <li>Last URL the browser was on at abort time</li>
     *   <li>Partial {@code extracted_data} when the runner had populated any</li>
     *   <li>The runner's own {@code final_result} string (e.g. "Session aborted by user")</li>
     * </ul>
     *
     * <p>Including this in the failure metadata lets the agent decide its
     * fallback: re-run with a tighter task, fall back to {@code fetch} on a
     * specific URL it never reached, or surface to the user "I got as far as
     * X; here's what I have so far" instead of a flat "timed out, do not retry."
     */
    private ToolExecutionResult buildRecapFailure(String sessionId,
                                                   boolean slotReleasedByUs,
                                                   Map<String, Object> recap) {
        int timeoutSecs = getBlpopTimeoutSeconds();
        String stopReason = stringFieldFromObj(recap.get("stop_reason"));
        String finalResult = stringFieldFromObj(recap.get("final_result"));
        String finalUrl = stringFieldFromObj(recap.get("final_url"));
        int stepsCompleted = countList(recap.get("steps"));
        int pagesVisited = countList(recap.get("pages_visited"));
        Object extractedData = recap.get("extracted_data");
        boolean hasExtracted = extractedData != null
            && !(extractedData instanceof java.util.Collection<?> c && c.isEmpty())
            && !(extractedData instanceof Map<?, ?> m && m.isEmpty());

        StringBuilder msg = new StringBuilder("Browser agent_browse timed out after ")
            .append(timeoutSecs).append("s. Slot released. ")
            .append(stepsCompleted).append(" step(s) completed");
        if (pagesVisited > 0) {
            msg.append(", ").append(pagesVisited).append(" page(s) visited");
        }
        if (finalUrl != null && !finalUrl.isBlank()) {
            msg.append(" (last URL: ").append(finalUrl).append(")");
        }
        if (finalResult != null && !finalResult.isBlank()
                && !"Session aborted by user".equalsIgnoreCase(finalResult)) {
            // Surface the runner's own diagnostic when it's more informative
            // than the generic "Session aborted by user" we get from CANCELLED.
            msg.append(". Runner status: ").append(finalResult);
        }
        if (hasExtracted) {
            msg.append(". Partial extracted_data available in metadata.");
        }
        msg.append(" Do NOT retry this agent_browse.");
        if (sessionId != null) {
            msg.append(" session_id=").append(sessionId);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timeout_seconds", timeoutSecs);
        metadata.put("auto_aborted", true);
        metadata.put("slot_released", slotReleasedByUs);
        metadata.put("recap_available", true);
        metadata.put("recap_stop_reason", stopReason != null ? stopReason : "");
        metadata.put("steps_completed", stepsCompleted);
        metadata.put("pages_visited_count", pagesVisited);
        if (recap.get("pages_visited") instanceof List<?> pagesList && !pagesList.isEmpty()) {
            metadata.put("pages_visited", pagesList);
        }
        if (finalUrl != null && !finalUrl.isBlank()) {
            metadata.put("final_url", finalUrl);
        }
        if (hasExtracted) {
            metadata.put("partial_extracted_data", extractedData);
        }
        if (sessionId != null) {
            metadata.put("session_id", sessionId);
        }
        return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, msg.toString(), metadata);
    }

    private static int countList(Object listObj) {
        return listObj instanceof java.util.Collection<?> c ? c.size() : 0;
    }

    /**
     * Finalize a race-recovered late result through the standard success
     * pipeline (pricing / observability / CDP token / size cap). Mirrors the
     * fail-closed {@code stop_reason != COMPLETED} guard from
     * {@link #awaitJobResult} so a TIMEOUT/MAX_STEPS/ERROR payload that
     * arrives late is reported as failure (not silent success).
     */
    private ToolExecutionResult finalizeRaceRecovered(String action,
                                                       Map<String, Object> parameters,
                                                       ToolExecutionContext context,
                                                       String jobId,
                                                       Map<String, Object> lateResult) {
        log.info("BrowserAgentModule: race-recovered late result for jobId={} - running through "
            + "post-process and returning to caller.", jobId);
        try {
            // Apply the same fail-closed stop_reason check the main
            // awaitJobResult does - without this the race-recovery path
            // would surface a COMPLETED-success for a runner that
            // actually crashed.
            if (!lateResult.containsKey("error")) {
                Object stopReasonObj = lateResult.get("stop_reason");
                String stopReason = stopReasonObj instanceof String s ? s.trim() : null;
                boolean isCompleted = stopReason != null && !stopReason.isEmpty()
                    && "COMPLETED".equalsIgnoreCase(stopReason);
                if (!isCompleted) {
                    Object finalResult = lateResult.get("final_result");
                    String errMsg;
                    if (finalResult instanceof String s && !s.isBlank()) {
                        errMsg = s;
                    } else if (finalResult != null) {
                        errMsg = String.valueOf(finalResult);
                    } else {
                        errMsg = "Browser agent stopped"
                            + (stopReason != null && !stopReason.isEmpty()
                                ? " with reason " + stopReason
                                : " without a stop_reason");
                    }
                    lateResult.put("error", errMsg);
                }
            }
            Map<String, Object> processed = postProcess(lateResult, parameters, context);
            if (processed.containsKey("error")) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    failedError(action, String.valueOf(processed.get("error"))));
            }
            return ToolExecutionResult.success(processed);
        } catch (Exception e) {
            log.warn("BrowserAgentModule: postProcess failed on race-recovered result jobId={}: {}",
                jobId, e.getMessage());
            return ToolExecutionResult.success(lateResult);
        }
    }

    /**
     * Bounded LPOP on the result LIST to catch a result LPUSHed by the runner
     * during the cleanup window. Used in two distinct windows:
     * <ul>
     *   <li><b>Round 1</b> ({@code Duration.ofSeconds(1)}): catches the
     *       microsecond-scale race where the runner LPUSHed between our
     *       main BLPOP returning null and the cleanup hook starting.</li>
     *   <li><b>Round 2</b> ({@code Duration.ofSeconds(10)}): catches the
     *       runner's reaction to our {@code POST /abort} - it picks up the
     *       ABORT command at the next step boundary (could be seconds while
     *       blocked on an LLM call), exits the loop via {@code _AbortRequested},
     *       runs {@code finally}, and {@code push_result}s the CANCELLED
     *       blob with the full step trace.</li>
     * </ul>
     * Returns the parsed payload or {@code null} if the LIST is still empty.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> drainLateResult(String jobId, Duration timeout) {
        try {
            String resultJson = redisTemplate.opsForList().leftPop(
                RESULT_KEY_PREFIX + jobId, timeout);
            if (resultJson == null) return null;
            return objectMapper.readValue(resultJson, Map.class);
        } catch (Exception e) {
            log.warn("BrowserAgentModule: drainLateResult failed for jobId={}: {}",
                jobId, e.getMessage());
            return null;
        }
    }

    /**
     * Read the runner-published {@code agent:browser:job:{jobId}} mapping
     * with a short retry loop (3 × 100 ms). The runner sets this key right
     * after registering the session, so a transient miss usually clears in
     * &lt; 100 ms; a persistent miss means the session crashed during
     * bootstrap and there's no live runner to abort.
     */
    private String resolveSessionIdWithRetry(String jobId) {
        Exception lastEx = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String sid = redisTemplate.opsForValue().get(jobToSessionKey(jobId));
                if (sid != null && !sid.isBlank()) {
                    return sid;
                }
                // Null is the runner-not-yet-published case - keep retrying.
            } catch (Exception e) {
                // Don't bail on the first transient blip (lettuce reconnect,
                // momentary CLUSTERDOWN). Retry the remaining attempts;
                // log+return null only after all 3 fail.
                lastEx = e;
                log.debug("BrowserAgentModule: GET {} attempt {} failed (will retry): {}",
                    jobToSessionKey(jobId), attempt + 1, e.getMessage());
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        if (lastEx != null) {
            log.warn("BrowserAgentModule: GET {} failed across all 3 retries: {}",
                jobToSessionKey(jobId), lastEx.getMessage());
        }
        return null;
    }

    /**
     * POST {@code /agent/sessions/{sid}/abort} so the runner exits its loop
     * and runs its own {@code finally: release_concurrent_slot} cleanup.
     * Best-effort: a 4xx/5xx (e.g. session already gone) is logged but never
     * thrown - the LREM that follows is the authoritative slot release.
     */
    @SuppressWarnings("unchecked")
    private void tryAbortSession(String sessionId) {
        String url = config.getServiceUrl() + "/agent/sessions/" + sessionId + "/abort";
        try {
            restTemplate.postForObject(url, new LinkedHashMap<>(), Map.class);
            log.info("BrowserAgentModule: aborted orphaned session sessionId={}", sessionId);
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e404) {
            // Common case: session ended naturally between our session_id
            // resolution and the abort POST. Not actionable, debug-level.
            log.debug("BrowserAgentModule: abort POST 404 for sessionId={} (session already gone)",
                sessionId);
        } catch (org.springframework.web.client.RestClientResponseException e) {
            // 5xx (runner crash) or unexpected 4xx (path drift, auth issue) -
            // these signal a real bug; surface them so they can't go silent.
            log.warn("BrowserAgentModule: abort POST returned {} for sessionId={}: {}",
                e.getRawStatusCode(), sessionId, e.getMessage());
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // Network blip / connection refused / read timeout. Worth seeing
            // in logs even though we don't fail the cleanup over it - slot
            // release ran below regardless.
            log.warn("BrowserAgentModule: abort POST network failure for sessionId={}: {}",
                sessionId, e.getMessage());
        } catch (Exception e) {
            log.warn("BrowserAgentModule: abort POST unexpected exception for sessionId={}: {}",
                sessionId, e.getMessage());
        }
    }

    /**
     * Authoritative {@code LREM agent:browser:user:{uid}:concurrent 1 {sid}}
     * so the per-user slot is freed even if the runner's finally-block
     * crashes. Idempotent: a no-op LREM (count=0) is safe.
     */
    private boolean tryReleaseSlot(String userId, String sessionId) {
        try {
            Long removed = redisTemplate.opsForList().remove(
                concurrentSlotKey(userId), 1, sessionId);
            log.info("BrowserAgentModule: LREM concurrent slot userId={} sessionId={} removed={}",
                userId, sessionId, removed);
            // Only report `slot_released=true` to the agent when the LREM
            // actually removed an entry - count==0 means the runner's own
            // finally-block beat us (or the value never existed), which is
            // *also* fine but not authoritatively released BY US.
            return removed != null && removed > 0;
        } catch (Exception e) {
            log.warn("BrowserAgentModule: LREM concurrent slot failed userId={} sessionId={}: {}",
                userId, sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * Drop the {@code agent:browser:job:{jobId}} mapping so a stale lookup
     * can't surface later. The runner also DELs this in its finally-block
     * (10 min TTL otherwise) - the duplicate DEL is harmless.
     */
    private void tryDeleteJobMapping(String jobId) {
        try {
            redisTemplate.delete(jobToSessionKey(jobId));
        } catch (Exception e) {
            log.debug("BrowserAgentModule: DEL {} failed: {}",
                jobToSessionKey(jobId), e.getMessage());
        }
    }
}
