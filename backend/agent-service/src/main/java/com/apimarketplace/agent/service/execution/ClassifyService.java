package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;
import com.apimarketplace.agent.domain.KeyRoute;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.TokenUsageConventions;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.agent.loop.GuardResult;
import com.apimarketplace.agent.loop.IterationContext;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.provider.TypeSafeDecisionProvider;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classification execution - delegates to {@link AgentLoopService} in single-shot mode
 * (no tools, 1 iteration) so that budget guards, token tracking, and observability
 * are centralized with the general agent pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassifyService {

    static final String SYSTEM_PROMPT = """
        You are a classification assistant. Your ONLY task is to categorize content \
        into one of the provided categories.

        OUTPUT FORMAT - MANDATORY:
        You MUST respond with EXACTLY one raw JSON object. Nothing else.
        No preamble, no explanation, no markdown fences, no trailing text.
        Any output that is not a single valid JSON object is a fatal error.

        JSON schema (strict):
        {
          "selected_category": "category_label",
          "confidence": 0.95,
          "reasoning": "Brief explanation of why this category was chosen"
        }

        Constraints:
        1. selected_category MUST be exactly one of the provided category labels (case-sensitive)
        2. confidence MUST be a number between 0.0 and 1.0
        3. reasoning MUST be a concise single sentence
        4. Output MUST start with { and end with } - no other characters allowed
        """;

    private final AgentLoopService agentLoopService;
    private final GuardChainFactory guardChainFactory;
    private final ObjectMapper objectMapper;
    private final BridgeLoopDispatcher bridgeDispatcher;
    private final com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;
    private final ExecutionLinkRouter executionLinkRouter;

    /**
     * Swaps a disabled model for its replacement (V515). Field-injected and optional so the
     * unit tests that construct this service positionally keep compiling (null = no swap).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.service.ModelReplacementResolver modelReplacementResolver;
    private final TypeSafeSystemOneClient typeSafeClient;

    /**
     * Pins whose API key the classification runs on (see {@link KeyRouteResolver}).
     * Field-injected and optional so the positional constructor stays test-friendly;
     * absent, the context is unpinned (user-first by tenant, the pre-pin behaviour).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KeyRouteResolver keyRouteResolver;

    /**
     * Optional Prometheus metrics sink, field-injected so existing positional-constructor
     * tests are unaffected. Only consulted by the execution-link bridge-failure fallback
     * below, to keep a silent-by-design recovery visible to operators.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.metrics.AgentPrometheusMetrics prometheusMetrics;

    /**
     * Activity source reported for link resolution. Classify requests are produced by
     * one caller only, the workflow classify node, so a {@code WORKFLOW}-scoped link
     * targets them exactly; an {@code ALL} link applies as it does everywhere else.
     */
    static final String ACTIVITY_SOURCE = "WORKFLOW";

    /**
     * Back-compat overload - async paths (queue worker) call without an inbound
     * role context. Falls through to {@link #execute(ClassifyRequestDto, String)}
     * with null roles; downstream the bridge guard treats null as USER.
     */
    public ClassifyResponseDto execute(ClassifyRequestDto request) {
        return execute(request, null);
    }

    public ClassifyResponseDto execute(ClassifyRequestDto request, String userRoles) {
        long startTime = System.currentTimeMillis();
        // A model an admin disabled runs on its replacement, before the provider is
        // normalised and the execution link resolved: the replacement is what gets billed
        // and what a link is looked up for.
        if (modelReplacementResolver != null) {
            var sub = modelReplacementResolver.substituteIfDisabled(request.provider(), request.model()).orElse(null);
            if (sub != null) {
                request = request.withModel(sub.provider(), sub.model());
            }
        }
        // Normalise provider against the catalog: a bridge (CLI) model
        // stored as provider="anthropic" (frontend heuristic / LLM-authored
        // plan) must resolve to its bridge slug so it dispatches via the bridge
        // AND passes through BridgeAccessGuard - identical to the chat path.
        String providerName = modelCatalogService.resolveProvider(request.provider(), request.model());

        String userPrompt = buildPrompt(request);

        try {
            // The decision engine may serve a plan that named only a model, in which case
            // the catalogue could not normalise a provider and left it null. Settle the
            // BILLED provider before the guard is built: its cost calculator is resolved
            // from the pair it is given, so a null there would price the run against a
            // model with no provider while the guard is then asked about the real one.
            boolean decision = typeSafeClient.serves(providerName, request.model());
            String billedProvider = decision ? TypeSafeDecisionProvider.PROVIDER_NAME : providerName;

            // The budget guard prices the BILLED pair: a link changes where the run
            // executes, never what the user is charged.
            PreIterationGuard guard = guardChainFactory.forAgent(
                request.tenantId(), request.agentEntityId(),
                billedProvider, request.model());

            // Decision engine. Branches BEFORE the execution link on purpose: a link moves
            // a run between things that speak the same protocol, and a decision model
            // speaks none of it. Linking one to a chat provider, or the reverse, could only
            // produce a call the target cannot answer, so there is nothing here to route.
            if (decision) {
                return classifyWithDecisionModel(request, billedProvider, guard, startTime);
            }

            // Model execution link: the billed pair may have to run on another target
            // (a CLI bridge, or another API provider). Without this the node would call
            // the billed provider's own API key, i.e. exactly the key an admin linked
            // away from - the failure shape being an upstream billing error on a key
            // the platform deliberately stopped using.
            var route = executionLinkRouter.runnableRoute(providerName, request.model(), ACTIVITY_SOURCE);
            String execProvider = route != null ? route.executionProvider() : providerName;
            String execModel = route != null ? route.executionModel() : request.model();

            boolean useBridge = bridgeDispatcher.shouldDispatch(execProvider);

            AgentLoopContext context = buildContext(execProvider, execModel, userPrompt, request,
                userRoles, guard, useBridge);
            // The route the turn is billed under: the pin of this context, or of the fallback below.
            KeyRoute executedRoute = context.keyRoute();

            log.info("Executing classify via {}: billed={}/{}, exec={}/{}, linked={}, categories={}",
                useBridge ? "bridge" : "agent loop",
                providerName, request.model(), execProvider, execModel, route != null,
                request.categories() != null ? request.categories().size() : 0);

            AgentLoopResult result = useBridge
                // route != null: the link sent us here, so the caller never chose this CLI.
                ? bridgeDispatcher.execute(context, route != null)
                : agentLoopService.execute(context, null);
            // Which provider PRODUCED the counts - not which one is billed. The two differ
            // under a link, and the bridge-failure fallback below can move it again.
            String usageReportedBy = execProvider;

            // This node is single-shot (maxIterations=1) and never streams: a classify verdict
            // is only consumed by the workflow after the FULL call returns, so nothing has ever
            // been shown to anyone when the bridge attempt fails. Unlike the streaming agent
            // path, no "was anything already visible" check is needed - ANY bridge failure on a
            // linked run is safe to retry invisibly on the billed pair's direct API.
            // No cancellation check here, and that is load-bearing rather than an oversight:
            // BridgeLoopDispatcher.buildRequest sends this dispatch with a null streamChannelId,
            // so the bridge's cancel poller has no key to read and STOPPED_BY_USER cannot reach
            // this branch. Wire a stream or run id into that request - an obvious future
            // improvement - and this retry starts re-running, and re-billing, turns a user
            // cancelled: add the same !wasCancelledByUser() guard the two agent paths carry.
            if (useBridge && route != null && !result.success()) {
                if (prometheusMetrics != null) {
                    prometheusMetrics.recordExecutionLinkFallback(providerName, request.model(), execProvider);
                }
                log.warn("[EXECUTION_LINK_FALLBACK] classify bridge dispatch failed (exec={}/{}: {}); "
                        + "retrying on billed pair {}/{}",
                    execProvider, execModel, result.error(), providerName, request.model());
                AgentLoopContext directContext = buildContext(providerName, request.model(), userPrompt,
                    request, userRoles, guard, false);
                result = agentLoopService.execute(directContext, null);
                executedRoute = directContext.keyRoute();
                usageReportedBy = providerName;
            }

            // Re-stamp the BILLED model ONLY when a link moved the run, mirroring the
            // agent path (which relabels solely on a link). Do not read this as cosmetic:
            // the node output shows it, and on the ASYNC completion path the orchestrator
            // takes the ledger's provider/model from the RESULT first, falling back to the
            // node config - so without the re-stamp a linked run would be charged as the
            // execution target. An UNLINKED bridge run keeps reporting the model id the CLI
            // returned, exactly as before.
            // Re-expressed in the BILLED provider's convention, and the cache counters
            // travel with it. A Claude Code bridge folds the cache into its prompt total and
            // the Anthropic API counts it beside, so a linked run reported verbatim charged
            // the whole context at full input rate: 6.1x its cost, measured. Converting
            // alone would have been worse - with nowhere to put the cache it would have left
            // the bill entirely - which is why the response DTO now carries cacheUsage and
            // AgentNode bills from it.
            UsageInfo billedUsage = TokenUsageConventions.toBilledConvention(
                result.usage(), usageReportedBy, providerName);
            return parseResponse(result, billedUsage, System.currentTimeMillis() - startTime, providerName,
                route != null ? request.model() : null,
                SYSTEM_PROMPT, userPrompt, result.conversationHistory())
                .withKeyRoute(executedRoute != null ? executedRoute.name() : null);

        } catch (BridgeAccessDeniedException e) {
            // Propagate so GlobalExceptionHandler maps reason → 403/429. Must come
            // before the Exception catch, which would otherwise squash the denial
            // into a generic 200/FAILED response body.
            log.warn("Classify denied by bridge guard: provider={} reason={}",
                e.getProviderName(), e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("Classification failed: {}", e.getMessage(), e);
            return new ClassifyResponseDto(false, null, 0, null,
                "Classification error: " + e.getMessage(),
                System.currentTimeMillis() - startTime, providerName, null, 0, 0, 0,
                null, null, userPrompt);
        }
    }

    /**
     * Builds the single-shot classify context for either the primary attempt (on
     * {@code provider}/{@code model} = the execution pair) or the execution-link
     * bridge-failure fallback retry (on {@code provider}/{@code model} = the billed pair,
     * {@code useBridge=false} so no restricted-toolset marker travels into a direct-API call).
     */
    private AgentLoopContext buildContext(String provider, String model, String userPrompt,
                                          ClassifyRequestDto request, String userRoles,
                                          PreIterationGuard guard, boolean useBridge) {
        return AgentLoopContext.builder()
            .provider(provider)
            .model(model)
            .systemPrompt(SYSTEM_PROMPT)
            .userPrompt(userPrompt)
            .tools(null)
            .autoDiscoverTools(false)
            .maxIterations(1)
            // Observability only: context use is reported as a share of THIS model's
            // window. Resolved on the execution pair, which is the one that can overflow.
            .contextWindow(modelCatalogService.resolveContextWindow(provider, model))
            .temperature(request.temperature() != null ? request.temperature() : 0.1)
            .maxTokens(request.maxTokens() != null ? request.maxTokens() : 500)
            .tenantId(request.tenantId())
            // A bridge holds no API key: pinned PLATFORM. Otherwise resolved once for the
            // execution provider, like every other dequeued execution kind.
            .keyRoute(useBridge ? KeyRoute.PLATFORM
                : keyRouteResolver != null ? keyRouteResolver.resolve(request.tenantId(), provider) : null)
            .userRoles(userRoles)
            .agentId(request.agentEntityId())
            .preIterationGuard(guard)
            // EVERY bridge run of this node enters restricted "API mode", linked or
            // not: an empty cwd and none of the CLI's native tools. A single-shot
            // judge that must answer with one JSON object has no use for a source
            // checkout, and without the marker the CLI keeps the repo cwd plus the
            // repo/shell MCP tools, which run arbitrary commands in that checkout.
            // On the direct-API path this node has no tools at all, so restricting is
            // what makes the two transports agree.
            .credentials(useBridge
                ? Map.of(ExecutionLinkRouter.RESTRICTED_TOOLSET_KEY, (Object) Boolean.TRUE)
                : null)
            .purpose(CallPurpose.CLASSIFY)
            .build();
    }

    /**
     * Classify on a decision model, with the same budget gate the loop applies.
     *
     * <p>The guard is checked once, by hand, because there is no loop to check it in: one
     * call, one answer. Skipping it would have been easy to justify (a classification on
     * this engine costs a fraction of a credit) and wrong for the reason budget gates
     * exist at all, which is that a workflow can run a node thousands of times. A tenant
     * out of credits must be refused here exactly as it is refused on the LLM path.
     */
    private ClassifyResponseDto classifyWithDecisionModel(ClassifyRequestDto request,
                                                           String providerName,
                                                           PreIterationGuard guard,
                                                           long startTime) {
        if (!typeSafeClient.isConfigured()) {
            return new ClassifyResponseDto(false, null, 0, null,
                "No API key is configured for the '" + providerName + "' decision provider; "
                + "an administrator must set one, or this node can run on a chat model instead",
                System.currentTimeMillis() - startTime, providerName, request.model(),
                0, 0, 0, null, null, null);
        }

        GuardResult verdict = guard.check(new IterationContext(
            request.tenantId(), request.agentEntityId(), providerName, request.model(),
            1, 0, 0L, 0L, 0L));
        if (verdict != null && !verdict.proceed()) {
            log.warn("Classify denied before dispatch: provider={}, model={}, reason={}",
                providerName, request.model(), verdict.denialReason());
            return new ClassifyResponseDto(false, null, 0, null,
                verdict.denialReason() != null ? verdict.denialReason() : "Budget guard denied the call",
                System.currentTimeMillis() - startTime, providerName, request.model(),
                0, 0, 0, null, null, null);
        }

        log.info("Executing classify via decision model: provider={}, model={}, categories={}",
            providerName, request.model(),
            request.categories() != null ? request.categories().size() : 0);
        return typeSafeClient.classify(request, providerName, startTime);
    }

    private String buildPrompt(ClassifyRequestDto request) {
        StringBuilder sb = new StringBuilder();
        // The prompt is the instruction (it may already carry the content through its own
        // templates) and the content is what is judged. Both are sent when they differ: this
        // used to send the prompt ALONE whenever one was set, so a node configured with a
        // separate content was classified without ever seeing it. When they are the same text
        // (no content configured, so the orchestrator sent the prompt in both fields) it is
        // sent once, under the heading it always had.
        String content = request.content() != null && !request.content().isBlank() ? request.content() : null;
        String instruction = request.prompt() != null && !request.prompt().isBlank() ? request.prompt() : null;
        if (instruction != null) {
            sb.append("## Classification Instruction\n").append(instruction).append("\n\n");
        }
        if (content != null && (instruction == null || request.distinctPrompt() != null)) {
            sb.append("## Content to Classify\n").append(content).append("\n\n");
        }
        sb.append("## Available Categories\n");
        if (request.categories() != null) {
            for (ClassifyRequestDto.CategoryDto category : request.categories()) {
                sb.append("- **").append(category.label()).append("**: ");
                sb.append(category.description() != null ? category.description() : "No description");
                sb.append("\n");
            }
        }
        sb.append("\nClassify the content into ONE of the above categories.");
        return sb.toString();
    }

    /**
     * @param provider    the BILLED provider (never the execution target of a link)
     * @param billedModel the BILLED model, set ONLY when a model execution link moved the
     *                    run elsewhere: the run then reports and is charged as this model
     *                    rather than the execution target. {@code null} on an unlinked run,
     *                    which keeps the identity the loop reported.
     */
    private ClassifyResponseDto parseResponse(AgentLoopResult result, UsageInfo usage, long duration,
                                                String provider,
                                                String billedModel,
                                                String systemPrompt, String userPrompt,
                                                List<Message> conversationHistory) {
        String content = result.content();
        int tokensUsed = usage != null ? usage.getTotal() : 0;
        int promptTokens = usage != null && usage.promptTokens() != null ? usage.promptTokens() : 0;
        int completionTokens = usage != null && usage.completionTokens() != null ? usage.completionTokens() : 0;
        String model = billedModel != null ? billedModel : result.model();
        List<ConversationMessageDto> messages = toConversationMessages(conversationHistory);

        if (!result.success()) {
            return new ClassifyResponseDto(false, null, 0, null,
                result.error(), duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt, usage);
        }

        if (content == null || content.isBlank()) {
            return new ClassifyResponseDto(false, null, 0, null,
                "Empty response from LLM", duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt, usage);
        }
        try {
            String jsonContent = LlmJsonExtractor.extractJson(content);
            Map<String, Object> parsed = objectMapper.readValue(jsonContent, new TypeReference<>() {});
            String selectedCategory = (String) parsed.get("selected_category");
            Number confidenceNum = (Number) parsed.get("confidence");
            String reasoning = (String) parsed.get("reasoning");
            if (selectedCategory == null || selectedCategory.isBlank()) {
                return new ClassifyResponseDto(false, null, 0, null,
                    "No category selected in response", duration, provider, model,
                    tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt, usage);
            }
            double confidence = confidenceNum != null ? confidenceNum.doubleValue() : 0.5;
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            return new ClassifyResponseDto(true, selectedCategory, confidence, reasoning,
                null, duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt, usage);
        } catch (Exception e) {
            log.warn("Failed to parse classify response as JSON, trying plain text: {}", e.getMessage());
            return parseFromPlainText(content, duration, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, userPrompt, messages, usage);
        }
    }

    private ClassifyResponseDto parseFromPlainText(String content, long duration,
                                                     String provider, String model,
                                                     int tokensUsed, int promptTokens,
                                                     int completionTokens,
                                                     String systemPrompt, String userPrompt,
                                                     List<ConversationMessageDto> messages,
                                                     UsageInfo usage) {
        Pattern pattern = Pattern.compile(
            "(?:category|selected|classification)[:\\s]+[\"']?([\\w\\s-]+)[\"']?",
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String category = matcher.group(1).trim();
            return new ClassifyResponseDto(true, category, 0.5,
                "Extracted from plain text response", null, duration, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt, usage);
        }
        return new ClassifyResponseDto(false, null, 0, null,
            "Could not parse classification response", duration, provider, model,
            tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt, usage);
    }

    /**
     * Convert agent loop conversation history to lightweight DTOs for transport.
     */
    static List<ConversationMessageDto> toConversationMessages(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
            .map(m -> new ConversationMessageDto(
                m.role() != null ? m.role().name() : "USER",
                m.content(),
                m.toolCallId(),
                m.toolName()))
            .toList();
    }
}
