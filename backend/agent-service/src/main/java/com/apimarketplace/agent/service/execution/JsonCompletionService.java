package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.JsonCompletionRequestDto;
import com.apimarketplace.agent.completion.ProviderLlmJsonInvoker;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import lombok.extern.slf4j.Slf4j;
import com.apimarketplace.agent.domain.KeyRoute;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * One-shot JSON completion behind {@code /api/internal/agent/execute/json-completion}: the
 * COLD-summary compaction of a chat, and any other caller that wants one
 * {@code (system, user) -> JSON} answer without agent-loop scaffolding.
 *
 * <p>The requested pair is the BILLED pair. A model execution link may send it elsewhere,
 * and that "elsewhere" can be a CLI bridge: production links {@code anthropic/claude-haiku-4-5}
 * (the platform's compaction default) to {@code claude-code}. Until this service existed the
 * endpoint refused such a link outright ({@code BRIDGE_EXECUTION_NOT_RELAYABLE}), so a chat
 * whose summariser was linked to a CLI was never compacted: every turn re-sent the whole
 * history, 120k+ prompt tokens a run, until the user's credits ran out.
 *
 * <p>A CLI can serve a bare completion the same way {@link ClassifyService} and
 * {@link GuardrailService} already have theirs served: a single-shot context
 * ({@code maxIterations=1}, no tools) in restricted "API mode" (empty cwd, none of the CLI's
 * native tools), dispatched through {@link BridgeLoopDispatcher}. A bridge reached by a
 * link is a SESSION the caller never chose, so the dispatch is marked routed and the
 * selection policy does not apply; a bridge the caller named itself is gated as everywhere
 * else. An API target, or no link at all, runs on {@link ProviderLlmJsonInvoker} exactly as
 * before.
 *
 * <p>Whichever transport answered, the caller gets the first balanced JSON object out of the
 * reply (the fence-stripped text when there is none), because a CLI prompted with a strict
 * JSON instruction still answers conversationally more often than a raw API does, and the
 * one consumer feeds this string straight into a JSON parser.
 */
@Slf4j
@Service
public class JsonCompletionService {

    /**
     * Wall-clock cap for a bridge run, in seconds, enforced by the bridge process as the CLI
     * spawn timeout (its default without one is 125 minutes). The endpoint's caller
     * (conversation-service's COLD summariser) stops reading after 90 s and its lock expires
     * after 2 min, so a CLI still working past this point answers nobody and only occupies the
     * shared operator session. 60 s leaves the linked fallback below about 30 s of the caller's
     * budget: a bridge TIMEOUT is the one failure where the retry starts late, and a summary the
     * caller no longer reads is still paid for. The direct-API path keeps the provider client's
     * own timeout, as it always has; a bridge SERVER that hangs is bounded only by the client's
     * read timeout, as for every other bridge caller.
     */
    static final int BRIDGE_EXECUTION_TIMEOUT_SECONDS = 60;

    /** Same sampling as the direct path ({@link ProviderLlmJsonInvoker}): a summary, not prose. */
    static final double TEMPERATURE = 0.2;

    private final ProviderLlmJsonInvoker jsonInvoker;
    private final BridgeLoopDispatcher bridgeDispatcher;
    private final ExecutionLinkRouter executionLinkRouter;

    /** Swaps a disabled model for its replacement (V515); null in positional unit tests = no swap. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.service.ModelReplacementResolver modelReplacementResolver;

    /**
     * Pins whose API key the completion runs on (see {@link KeyRouteResolver}). Optional
     * like the other field-injected collaborators; absent, the invoker is called unpinned
     * (user-first by tenant, the pre-pin behaviour). With it, a blank tenant (the
     * conversation-service seam that sends none) pins {@code PLATFORM}, which is also what
     * the thread-less pre-pin resolution ended up with for that caller.
     */
    @Autowired(required = false)
    private KeyRouteResolver keyRouteResolver;

    /**
     * Optional Prometheus sink, field-injected so the positional constructor stays test-friendly.
     * Only the execution-link bridge-failure fallback reports to it, to keep a silent-by-design
     * recovery visible to operators (same counter as the agent and classify paths).
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.metrics.AgentPrometheusMetrics prometheusMetrics;

    /**
     * Where the COLD summary's cost is recorded and charged. Field-injected for the same
     * reason as the counter above: the positional constructor stays test-friendly.
     *
     * <p>This endpoint IS the compaction summariser (see {@code CallPurpose}), and it was
     * the one LLM call the platform made that nobody paid for - the usage was discarded by
     * the invoker, so no observability row and no ledger row could exist, while the
     * {@code COMPACTION_SUMMARY} source type sat allow-listed in billing waiting for one.
     */
    @Autowired(required = false)
    private com.apimarketplace.agent.service.AgentObservabilityService observabilityService;

    public JsonCompletionService(ProviderLlmJsonInvoker jsonInvoker,
                                 BridgeLoopDispatcher bridgeDispatcher,
                                 ExecutionLinkRouter executionLinkRouter) {
        this.jsonInvoker = jsonInvoker;
        this.bridgeDispatcher = bridgeDispatcher;
        this.executionLinkRouter = executionLinkRouter;
    }

    /**
     * @param userRoles the inbound {@code X-User-Roles}, usually absent: compaction runs on an
     *                  async executor with no bound request. Only a bridge the caller NAMED is
     *                  judged on it; a linked one is routed, not chosen.
     * @return the model's answer, reduced to its first balanced JSON object when it has one
     * @throws IllegalStateException when neither transport produced content
     * @throws com.apimarketplace.agent.bridge.BridgeAccessDeniedException when the caller named a
     *         CLI it may not select (mapped to 403/429 by the controller advice)
     */
    public String complete(JsonCompletionRequestDto request, String userRoles) {
        // A model an admin disabled runs on its replacement, which is then billed and is
        // what the execution link below is looked up for (V515).
        // null = the resolver is not wired: whether a swap happened is unknown.
        java.util.Optional<com.apimarketplace.agent.service.ModelReplacementResolver.Substitution> replacement = null;
        if (modelReplacementResolver != null) {
            replacement = modelReplacementResolver.substituteIfDisabled(request.provider(), request.model());
            var sub = replacement.orElse(null);
            if (sub != null) {
                request = request.withModel(sub.provider(), sub.model());
            }
        }
        String billedProvider = request.provider();
        String billedModel = request.model();

        // A blank pair is not linkable and not dispatchable: hand it to the invoker, which
        // rejects it with its own explicit error, as this endpoint always has.
        if (isBlank(billedProvider) || isBlank(billedModel)) {
            // Unbillable by construction: with no (provider, model) there is no price to
            // apply. The invoker rejects it with its own explicit error, as it always has.
            return extract(jsonInvoker.invoke(billedProvider, billedModel,
                request.system(), request.user(), request.tenantId()));
        }

        // No activity source: a bare completion belongs to no surface, so only an ALL-scoped
        // link can apply. The router already turns "feature off" (CE) and "bridge not wired"
        // into null, i.e. the billed pair on its own provider.
        ModelExecutionLinkService.ExecutionRoute route =
            executionLinkRouter.runnableRoute(billedProvider, billedModel, null);
        String execProvider = route != null ? route.executionProvider() : billedProvider;
        String execModel = route != null ? route.executionModel() : billedModel;

        if (!bridgeDispatcher.shouldDispatch(execProvider)) {
            if (route != null) {
                log.info("json-completion link route: billed={}/{} -> exec={}/{}",
                    billedProvider, billedModel, execProvider, execModel);
            }
            Pinned direct = invokePinned(execProvider, execModel, request);
            bill(request, billedProvider, billedModel, execProvider, direct.result().usage(), direct.keyRoute(),
                replacement);
            return extract(direct.result().content());
        }

        boolean routed = route != null;
        log.info("Executing json-completion via bridge: billed={}/{}, exec={}/{}, linked={}, tenant={}",
            billedProvider, billedModel, execProvider, execModel, routed, request.tenantId());

        // routed: the link sent us here, so the caller never chose this CLI and the selection
        // policy must not judge it. Throws BridgeAccessDeniedException on a CHOSEN bridge the
        // caller may not use; that must propagate, never turn into a retry elsewhere.
        AgentLoopResult result = bridgeDispatcher.execute(
            bridgeContext(execProvider, execModel, request, userRoles), routed);

        if (result.success() && !isBlank(result.content())) {
            // A CLI bridge holds no API key: platform route.
            bill(request, billedProvider, billedModel, execProvider, result.usage(), KeyRoute.PLATFORM, replacement);
            return extract(result.content());
        }
        String failure = result.success() ? "empty content" : result.error();

        if (!routed) {
            throw new IllegalStateException("json-completion provider=" + execProvider
                + " model=" + execModel + " bridge run failed: " + failure);
        }

        // Single-shot and never streamed: nothing has been shown to anyone when the bridge
        // fails, so a linked run is safe to retry invisibly on the billed pair's own API. Same
        // recovery as classify/guardrail, and reported on the same counter so it stays visible.
        //
        // No cancellation check either, and that is load-bearing rather than an oversight:
        // BridgeLoopDispatcher.buildRequest sends this dispatch with a null streamChannelId,
        // so the bridge's cancel poller has no key to read and STOPPED_BY_USER cannot reach
        // here. Wire a stream or run id into that request and this retry starts re-running,
        // and re-billing, a turn the user cancelled: add the same !wasCancelledByUser()
        // guard the two agent paths carry.
        if (prometheusMetrics != null) {
            prometheusMetrics.recordExecutionLinkFallback(billedProvider, billedModel, execProvider);
        }
        log.warn("[EXECUTION_LINK_FALLBACK] json-completion bridge dispatch failed (exec={}/{}: {}); "
                + "retrying on billed pair {}/{}",
            execProvider, execModel, failure, billedProvider, billedModel);
        Pinned fallback = invokePinned(billedProvider, billedModel, request);
        // The discarded bridge attempt is not billed: it produced nothing, and the tokens it
        // burned were the bridge's own. Only the run that answered is charged.
        bill(request, billedProvider, billedModel, billedProvider, fallback.result().usage(), fallback.keyRoute(),
            replacement);
        return extract(fallback.result().content());
    }

    /** A direct API call's answer together with the key route it was pinned to (null = unpinned). */
    record Pinned(ProviderLlmJsonInvoker.InvocationResult result, KeyRoute keyRoute) {}

    /**
     * The direct API call, pinned once for the provider whose key serves it (the execution
     * provider, or the billed one on the bridge-failure fallback). Without a resolver the
     * invoker is called unpinned, the pre-pin behaviour. The route is returned with the
     * answer so the debit bills an own-key turn its flat fee.
     */
    private Pinned invokePinned(String provider, String model, JsonCompletionRequestDto request) {
        KeyRoute keyRoute = keyRouteResolver != null
            ? keyRouteResolver.resolve(request.tenantId(), provider) : null;
        ProviderLlmJsonInvoker.InvocationResult result = keyRoute != null
            ? jsonInvoker.invokeWithUsage(provider, model, request.system(), request.user(), request.tenantId(), keyRoute)
            : jsonInvoker.invokeWithUsage(provider, model, request.system(), request.user(), request.tenantId());
        return new Pinned(result, keyRoute);
    }

    /**
     * Record and charge one COLD summary, as {@code COMPACTION_SUMMARY}.
     *
     * <p>Best-effort and post-success, the rule every other billing write in this codebase
     * follows: the summary has already been produced, so whether its cost could be recorded
     * must never decide whether the caller keeps it. A failure here is logged and swallowed.
     *
     * @param reportedBy the provider that PRODUCED {@code usage}. Under a model execution
     *                   link that is the bridge, not the billed pair, and the two count
     *                   their input tokens differently - the Claude Code bridge folds the
     *                   cache into its prompt total and the Anthropic API counts it beside.
     *                   Billing reads the numbers through the stamped provider's convention,
     *                   so they are re-expressed here or the cache is charged twice.
     */
    private void bill(JsonCompletionRequestDto request, String billedProvider, String billedModel,
                      String reportedBy, com.apimarketplace.agent.domain.UsageInfo usage, KeyRoute keyRoute,
                      java.util.Optional<com.apimarketplace.agent.service.ModelReplacementResolver.Substitution> replacement) {
        if (observabilityService == null || usage == null || isBlank(request.tenantId())) {
            return;
        }
        try {
            com.apimarketplace.agent.domain.UsageInfo billed = com.apimarketplace.agent.domain
                .TokenUsageConventions.toBilledConvention(usage, reportedBy, billedProvider);

            var req = new com.apimarketplace.agent.client.dto.AgentObservabilityRequest();
            req.setTenantId(request.tenantId());
            // "compaction_summary" is what AgentObservabilityService.resolveSourceType turns
            // into the COMPACTION_SUMMARY ledger source, which is what keeps this cost
            // segregated from primary agent spend in the ledger UI and in Grafana.
            //
            // Hardcoded because this endpoint has exactly one caller today, the COLD
            // summariser (HttpLlmJsonInvoker). A second caller would be billed as
            // compaction, which would be wrong and silent: give the request a purpose
            // field before adding one, rather than discovering it in the ledger.
            req.setAgentType("compaction_summary");
            // Per CALL, not per tenant. auth.credit_ledger has a GLOBAL unique index on
            // source_id, and AgentObservabilityService falls back to this nodeId as the
            // source when the execution row fails to persist - a constant would then bill a
            // tenant's first compaction and silently dead-letter every later one.
            req.setNodeId("compaction:" + request.tenantId() + ":" + java.util.UUID.randomUUID());
            req.setProvider(billedProvider);
            req.setModel(billedModel);
            // Whose key served the call: an own-key turn is billed a flat fee, not tokens.
            req.setKeyRoute(keyRoute != null ? keyRoute.name() : null);
            com.apimarketplace.agent.service.AgentObservabilityService.stampModelReplacement(req, replacement);
            req.setStatus("COMPLETED");
            req.setIterationCount(1);
            req.setPromptTokens(orZero(billed.promptTokens()));
            req.setCompletionTokens(orZero(billed.completionTokens()));
            req.setTotalTokens(billed.getTotal());
            req.setCacheCreationTokens(orZero(billed.cacheCreationInputTokens()));
            req.setCacheReadTokens(orZero(billed.cacheReadInputTokens()));
            req.setCachedTokens(orZero(billed.cachedTokens()));
            req.setReasoningTokens(orZero(billed.reasoningTokens()));

            observabilityService.recordFromRequest(req);
        } catch (Exception e) {
            log.warn("json-completion billing failed for tenant={} billed={}/{}: {}",
                request.tenantId(), billedProvider, billedModel, e.getMessage());
        }
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }

    /**
     * The single-shot context a CLI serves a bare completion with. Tool-less on purpose
     * ({@code tools=null}, no discovery): the dispatcher turns that into an EMPTY module list,
     * so the session gets none of the platform tools either. And EVERY bridge run of this
     * path enters restricted "API mode", linked or not: a summariser has no use for a source
     * checkout, and without the marker the CLI keeps the repo cwd plus its repo/shell tools.
     */
    private static AgentLoopContext bridgeContext(String provider, String model,
                                                  JsonCompletionRequestDto request, String userRoles) {
        return AgentLoopContext.builder()
            .provider(provider)
            .model(model)
            .systemPrompt(request.system())
            .userPrompt(request.user())
            .tools(null)
            .autoDiscoverTools(false)
            .maxIterations(1)
            .executionTimeout(BRIDGE_EXECUTION_TIMEOUT_SECONDS)
            .temperature(TEMPERATURE)
            .tenantId(request.tenantId())
            // A bridge holds no API key: pinned PLATFORM.
            .keyRoute(KeyRoute.PLATFORM)
            .userRoles(userRoles)
            .credentials(Map.of(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, (Object) Boolean.TRUE))
            .purpose(CallPurpose.JSON_COMPLETION)
            .build();
    }

    /**
     * One rule for both transports: the first balanced object, else the fence-stripped text. A
     * top-level ARRAY is therefore reduced to its first element; every caller of this endpoint
     * parses an envelope OBJECT, which is what the rule is written for.
     */
    private static String extract(String content) {
        return LlmJsonExtractor.extractJson(content);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
