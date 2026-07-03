package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import com.apimarketplace.agent.tools.common.ToolResultPersistEnricher;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.AgentBrowseInterfaceRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.orchestrator.controllers.internal.WebSearchCallbackController;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;

/**
 * Unified facade provider for web search, fetching, and browser-agent
 * automation. Exposes ONE tool {@code "web_search"} with an action parameter.
 *
 * <p>Delegates to specialized modules:
 * <ul>
 *   <li>{@link WebSearchModule}: search</li>
 *   <li>{@link WebFetchModule}: fetch (single or batch)</li>
 *   <li>{@link BrowserAgentModule}: agent_browse / browse_*</li>
 * </ul>
 *
 * <p>Persistence policy (2026-05-22):
 * <ul>
 *   <li><b>search / fetch</b> - no Interface row is created. Results render
 *       inline in chat as a {@code FaviconStack} on the tool-call row
 *       (commit f600c8885). The legacy {@code interface_type='web_search'}
 *       bucket is archived; existing prod rows are one-shot purged.</li>
 *   <li><b>agent_browse / browse_*</b> - still persists, but now as
 *       {@code interface_type='agent_browse'} (was misclassified as
 *       {@code 'web_search'} before this change). The
 *       {@code [visualize:agent_browse:&lt;id&gt;]} marker drives the live
 *       browser-agent CDP card in the chat side panel.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
public class WebSearchToolsProvider implements ToolsProvider {

    private final WebSearchModule searchModule;
    private final WebFetchModule fetchModule;
    private final BrowserAgentModule browserAgentModule;
    private final InterfaceClient interfaceClient;
    private final WebSearchConfig config;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    /**
     * Surfaces the platform's {@code (provider, model)} catalog through the
     * {@code help_models} action so the LLM agent can pick a registered pair
     * if it wants to override the default. Sources from agent-service via
     * {@link com.apimarketplace.agent.client.AgentClient#getModelsInfo()} -
     * SAME source as the UI app-header model picker, so providers without a
     * configured API key (e.g. openrouter on a deployment without an
     * OPENROUTER_API_KEY) are filtered out before the agent ever sees them.
     */
    private final com.apimarketplace.agent.client.AgentClient agentClient;

    public WebSearchToolsProvider(
            WebSearchModule searchModule,
            WebFetchModule fetchModule,
            BrowserAgentModule browserAgentModule,
            InterfaceClient interfaceClient,
            WebSearchConfig config,
            @Qualifier("webSearchRedisTemplate") StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.apimarketplace.agent.client.AgentClient agentClient) {
        this.searchModule = searchModule;
        this.fetchModule = fetchModule;
        this.browserAgentModule = browserAgentModule;
        this.interfaceClient = interfaceClient;
        this.config = config;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.agentClient = agentClient;
    }

    private static final List<String> VALID_ACTIONS = List.of(
        "search", "fetch",
        "agent_browse", "browse_status", "browse_intervene", "browse_abort", "browse_screenshot",
        "help", "help_models"
    );

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.WEB_SEARCH;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedWebSearchTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"web_search".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        String action = (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }

        try {
            ToolExecutionResult result;

            if ("help".equals(action)) {
                // Help is read-only and free. Skip persistence (no Interface
                // entity for documentation lookups) by short-circuiting here
                // - falls through to the same caller-visible shape as a
                // successful action minus the visualization marker.
                return ToolExecutionResult.success(buildHelpPayload(parameters));
            } else if ("help_models".equals(action)) {
                return ToolExecutionResult.success(buildHelpModelsPayload());
            } else if (searchModule.canHandle(action)) {
                // search results render inline in chat via FaviconStack -
                // no Interface row created (legacy web_search bucket retired
                // 2026-05-22). Return the raw result directly.
                return searchModule.execute(action, parameters, tenantId(context), context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "Search failed"));
            } else if (fetchModule.canHandle(action)) {
                // fetch results render inline in chat via FaviconStack -
                // no Interface row created (same rationale as search above).
                return fetchModule.execute(action, parameters, tenantId(context), context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "Fetch failed"));
            } else if (browserAgentModule.canHandle(action)) {
                result = browserAgentModule.execute(action, parameters, tenantId(context), context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Browser agent action failed"));
            } else {
                return ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR,
                    "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
            }

            // On success, persist as agent_browse interface and add visualization.
            // Only the browser-agent path reaches here - search/fetch short-circuit
            // above without persistence.
            if (result.success()) {
                return persistAndEnrichResult(result, action, parameters, context);
            }

            return result;

        } catch (Exception e) {
            log.error("Error executing web_search action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    private static String tenantId(ToolExecutionContext context) {
        return context != null ? context.tenantId() : null;
    }

    /**
     * Persist a browser-agent action result as an
     * {@code interface_type='agent_browse'} Interface entity and enrich the
     * tool result with the {@code [visualize:agent_browse:&lt;id&gt;]} marker
     * the chat side panel uses to render the live CDP card.
     *
     * <p>Only browser-agent actions reach this method since 2026-05-22 -
     * search/fetch results render inline via the {@code FaviconStack} and
     * skip persistence entirely (commit f600c8885 + this refactor).
     *
     * <p>The generic flow (credential extraction, marker emission,
     * {@code metadata.visualization} shape) lives in
     * {@link com.apimarketplace.agent.tools.common.ToolResultPersistEnricher}.
     */
    private ToolExecutionResult persistAndEnrichResult(
            ToolExecutionResult originalResult, String action,
            Map<String, Object> parameters, ToolExecutionContext context) {
        String visualizationType = "agent_browse";

        ToolResultPersistEnricher.PersistFn persistFn = (ctx, params, originalData) -> {
            Map<String, Object> credentials = ctx.credentials();
            String conversationId = (String) credentials.get("conversationId");
            String messageId = (String) credentials.get("__messageId__");
            String agentId = credentials != null ? (String) credentials.get("__agentId__") : null;

            // Build per-action result entry for interface storage
            Map<String, Object> resultData = new HashMap<>();
            resultData.put("action", action);
            if (params.get("query") != null) {
                resultData.put("query", params.get("query"));
            }
            if (params.get("url") != null) {
                resultData.put("url", params.get("url"));
            }
            if (originalData instanceof Map<?, ?> resultMap) {
                resultData.putAll(ToolResultPersistEnricher.deepCopyResultData(
                        ToolResultPersistEnricher.asStringKeyMap(resultMap)));
            }

            String name = (String) params.get("query");
            if (name == null) {
                name = (String) params.get("url");
            }

            AgentBrowseInterfaceRequest req = new AgentBrowseInterfaceRequest();
            req.setName(name != null ? name : "Browser Agent");
            req.setConversationId(conversationId);
            req.setMessageId(messageId);
            req.setAgentId(agentId);
            req.setData(resultData);
            // Stamp organization_id so org-teammates can GET /api/interfaces/{id}
            // and the AgentBrowseVisualizeCard can render in chats started by a
            // teammate. Without this, the card shows "noResults" because the
            // interface returns 404 (cross-tenant strict check in interface-service).
            // Prod incident 2026-05-16 - same bug shape as InterfaceCrudModule.
            req.setOrganizationId(ctx.orgId());

            InterfaceDto interfaceEntity = interfaceClient.createOrUpdateAgentBrowseInterface(
                    req, ctx.tenantId());
            if (interfaceEntity == null || interfaceEntity.getId() == null) {
                return null;
            }
            return new ToolResultPersistEnricher.PersistedInterface(
                    interfaceEntity.getId().toString(), interfaceEntity.getName());
        };

        // Store toolCallId → interfaceId in Redis so the async screenshot
        // callback (which lives outside the request thread and has no
        // ToolExecutionContext) can resolve the right Interface. Stored as
        // "tenantId|interfaceId" so the callback can forward X-User-ID to
        // interface-service; old format (interfaceId alone) is parsed
        // defensively for rolling-deploy compat. Kept as defense-in-depth
        // even though the websearch screenshot pipeline was disabled in
        // commit f600c8885 - the agent_browse path may revive it.
        ToolResultPersistEnricher.PostPersistHook redisHook = (ctx, params, persisted) -> {
            String toolCallId = (String) ctx.credentials().get("__toolCallId__");
            String tenantId = ctx.tenantId();
            if (toolCallId == null || tenantId == null || tenantId.isBlank()) return;
            String ifaceKey = WebSearchCallbackController.TOOL_IFACE_PREFIX + toolCallId;
            redisTemplate.opsForValue().set(ifaceKey, tenantId + "|" + persisted.id(), Duration.ofMinutes(10));
        };

        return ToolResultPersistEnricher.enrichAndPersist(
                originalResult, parameters, context, visualizationType, persistFn, redisHook);
    }

    private AgentToolDefinition buildUnifiedWebSearchTool() {
        int maxFetches = config.getMaxParallelFetches();
        var params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("search | fetch | agent_browse | browse_status | browse_intervene | "
                    + "browse_abort | browse_screenshot | help | help_models")
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),

            // search
            stringParam("query", "Search query (search)", false),
            intParam("max_results", "Max results, default 10, cap 50 (search)", false, 10),
            stringParam("time_range", "'day' | 'week' | 'month' | 'year' (search)", false),

            // fetch
            stringParam("url", "Single URL to fetch (fetch)", false),
            arrayParam("urls", "Up to " + maxFetches + " URLs in parallel (fetch)", false),

            // agent_browse
            stringParam("task", "Goal for the browser agent (agent_browse). Be specific.", false),
            stringParam("start_url", "Optional starting URL (agent_browse)", false),
            objectParam("llm", "OPTIONAL {provider, model} (agent_browse). Omit (the whole block or "
                + "either field) to use the platform default model. Pick pairs from "
                + "web_search(action='help_models'); unknown pairs are silently substituted with the "
                + "default and reported as 'model_substituted' in the response.", false),
            intParam("max_steps", "Override the runner's default of 50 steps (agent_browse). "
                + "Hard cap = 50 enforced runner-side: values above 50 are silently clamped. "
                + "If a session ends with stop_reason=MAX_STEPS in less than ~30s the cause is almost "
                + "always upstream (LLM auth, anti-bot, navigation error) - bumping max_steps will NOT help.", false, null),
            objectParam("expected_output_schema", "JSON Schema for extracted_data; mismatch → "
                + "stop_reason=SCHEMA_MISMATCH (agent_browse)", false),
            stringParam("interaction_mode", "'autonomous' (default) | 'supervised' | 'manual' "
                + "(agent_browse)", false),
            arrayParam("domain_allowlist", "Allowed domains; off-list → DOMAIN_BLOCKED (agent_browse)", false),
            arrayParam("domain_denylist", "Blocked domains (agent_browse)", false),
            stringParam("screenshot_policy", "'every_step' | 'on_change' (default) | 'final_only' "
                + "| 'off' (agent_browse)", false),
            objectParam("session", "{headless, viewport:{width,height}, timeout_seconds} (agent_browse)", false),

            // browse_*
            stringParam("session_id", "Session id from an earlier agent_browse "
                + "(browse_status/intervene/abort/screenshot)", false),
            stringParam("hint", "Guidance to inject into a running session (browse_intervene)", false),

            // help
            arrayParam("topics", "Optional filter for help payload "
                + "(['actions','concepts','examples'])", false)
        );

        String description = "Web search and browse.\n"
            + "- search: query the web (~1s). Returns URL/title/snippet.\n"
            + "- fetch: page → markdown (~5s). Up to " + maxFetches + " URLs in parallel.\n"
            + "- agent_browse: LLM-driven browser session - only when fetch can't reach (login, "
            + "JS UI, multi-page nav). ~100x slower, burns LLM tokens.\n"
            + "- browse_status/intervene/abort/screenshot: control a running session.\n"
            + "- help: action reference (no model catalog).\n"
            + "- help_models: live model catalog (top 30 by priority) - call only if you want to "
            + "override the default for agent_browse.";

        return AgentToolDefinition.builder()
            .name("web_search")
            .description(description)
            .category(ToolCategory.WEB_SEARCH)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("Call web_search(action='help') for the action reference. "
                + "Call web_search(action='help_models') only if you need to override the platform "
                + "default LLM for agent_browse.")
            .requiresAuth(false)
            .tags(List.of("websearch", "search", "fetch", "web", "unified"))
            // 640 s ceiling sits 40 s above the orchestrator-side BLPOP
            // for agent_browse (600 s - aligned with the runner's hard
            // wallclock cap in runner.py). The 40 s cleanup window covers
            // drain1 + retry + abort POST + LREM + drain2 (~26 s worst
            // case) with margin. Real browse tasks (multi-page booking,
            // checkout, login + form fills) routinely need 200-400 s, so
            // the prior 240 s ceiling timed them out just before
            // completion. fetch / search / help are sub-second in practice
            // - they sit well inside this ceiling and are unaffected.
            .timeoutMs(640_000L)
            .build();
    }

    // ── help action ──────────────────────────────────────────────────
    // Mirrors agent(action='help') / workflow(action='help'): the tool
    // description stays compact (paid in every prompt the tool is exposed
    // in), and verbose details - action specs, available_models catalog,
    // examples - are pulled on demand by the agent and cached for the
    // session ("Help First" pattern, DefaultSystemPrompts.HELP_FIRST).

    private static final java.util.Set<String> HELP_TOPICS = java.util.Set.of(
        "actions", "concepts", "examples");

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildHelpPayload(Map<String, Object> parameters) {
        java.util.Set<String> requested;
        Object topicsObj = parameters == null ? null : parameters.get("topics");
        if (topicsObj instanceof java.util.List<?> topicList && !topicList.isEmpty()) {
            requested = new java.util.LinkedHashSet<>();
            for (Object t : topicList) {
                if (t == null) continue;
                String s = String.valueOf(t).trim().toLowerCase();
                if (HELP_TOPICS.contains(s)) requested.add(s);
            }
            if (requested.isEmpty()) requested = HELP_TOPICS;
        } else {
            requested = HELP_TOPICS;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
            "WEB_SEARCH TOOL - search, fetch as markdown, or drive an LLM-powered browser. "
            + "Pick the cheapest action that can answer the question: search → fetch → "
            + "agent_browse. agent_browse is ~100x more expensive than fetch and pins a "
            + "Chromium process at concurrency=1, so reach for it ONLY when fetch cannot "
            + "load the target (auth wall, JS-rendered DOM, multi-step interactive flow). "
            + "agent_browse's llm block is OPTIONAL - omit it to use the platform default "
            + "(model #1). Call web_search(action='help_models') only if you want to override.");

        if (requested.contains("actions")) out.put("actions", buildActionsHelp());
        // Slim Map shape (mirrors agent(action='help').available_models): the
        // full catalog moved to web_search(action='help_models'). Map (not
        // String) so the LLM sees the same shape across both tools - an LLM
        // that learns the agent contract reads the web_search contract the
        // same way.
        Map<String, Object> availableModels = new LinkedHashMap<>();
        availableModels.put("see_also", "web_search(action='help_models')");
        availableModels.put("note",
            "Live catalog (top " + HELP_MODELS_MAX_ROWS + " by priority, only providers with a "
            + "configured API key) → web_search(action='help_models'). Override contract → the "
            + "model_catalog concept (topics=['concepts']).");
        out.put("available_models", availableModels);
        if (requested.contains("concepts")) out.put("concepts", buildConceptsHelp());
        if (requested.contains("examples")) out.put("examples", buildExamplesHelp());

        return out;
    }

    /**
     * Live model catalog for agent_browse, top {@value #HELP_MODELS_MAX_ROWS} by
     * priority. Sourced from agent-service via {@link com.apimarketplace.agent.client.AgentClient#getModelsInfo()}
     * - SAME endpoint the UI app-header model picker uses, so providers without
     * a configured API key (e.g. openrouter on a deployment that has no
     * OPENROUTER_API_KEY) are filtered out before the agent ever sees them.
     * Falls back to a textual hint when the client is unwired (CE without
     * billing) so the action is always callable.
     */
    private static final int HELP_MODELS_MAX_ROWS = 30;

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildHelpModelsPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
            "Available LLM models for agent_browse, in priority order. "
            + "Only providers with a configured API key appear here - same configured catalog the platform admin manages (you cannot change it from this tool). "
            + "The llm block on agent_browse is OPTIONAL: omit it (or omit provider/model individually) to use the platform default (the first pair below). "
            + "Unknown pairs are silently substituted with the default and the swap is reported as 'model_substituted' in the response.");

        if (agentClient == null) {
            out.put("note", "Model catalog client is not wired in this deployment. "
                + "agent_browse without an llm block falls back to whatever the runner can resolve.");
            return out;
        }

        Map<String, Object> catalog;
        try {
            // V156: pull the catalog through the per-category sidecar so admins
            // can re-rank or disable models for browser_agent independently of
            // the chat picker. Falls back to the global ranking when no sidecar
            // rows exist for a model.
            catalog = agentClient.getModelsInfo("browser_agent");
        } catch (Exception e) {
            log.warn("help_models: failed to fetch model catalog from agent-service: {}", e.getMessage());
            out.put("note", "Could not reach the model catalog (agent-service). Try again or omit the llm block to use the platform default.");
            return out;
        }

        // Catalog shape (same as ModelCatalogService.getModelsWithOverrides):
        //   { providers: [{name, providerKind, displayOrder, models: [{id, displayOrder, ...}]}, ...],
        //     defaultProvider, defaultModel, defaultDirectProvider, defaultDirectModel }
        // Already filtered (only configured providers) and per-provider sorted
        // by displayOrder. Flatten to a global priority order, then take the
        // top HELP_MODELS_MAX_ROWS.
        //
        // V156: bridges (claude-code / codex / gemini-cli / mistral-vibe) are
        // EXCLUDED from the LLM-facing pairs list because agent_browse
        // substitutes them at runtime anyway (they don't expose per-step
        // /v1/chat/completions). Showing them here makes the LLM pick
        // pairs[0]=claude-code/... and trigger a confusing model_substituted
        // notice. Filter at flatten time so the agent sees only models it can
        // actually use without substitution. We count both before and after
        // for the `note` field so the operator can tell when bridges are
        // hidden.
        java.util.List<Map<String, Object>> providers =
            (java.util.List<Map<String, Object>>) catalog.getOrDefault("providers", java.util.List.of());
        java.util.List<Map<String, Object>> flat = new java.util.ArrayList<>();
        int hiddenBridgeCount = 0;
        for (Map<String, Object> p : providers) {
            String pname = (String) p.get("name");
            if (pname == null) continue;
            boolean isBridge = "bridge".equals(p.get("providerKind"));
            java.util.List<Map<String, Object>> models =
                (java.util.List<Map<String, Object>>) p.getOrDefault("models", java.util.List.of());
            if (isBridge) {
                hiddenBridgeCount += models.size();
                continue;
            }
            for (Map<String, Object> m : models) {
                String mid = (String) m.get("id");
                if (mid == null) continue;
                int order = m.get("displayOrder") instanceof Number n ? n.intValue() : 999;
                flat.add(Map.of("provider", pname, "model", mid, "displayOrder", order));
            }
        }
        flat.sort(java.util.Comparator.comparingInt(m -> (int) m.getOrDefault("displayOrder", 999)));

        int totalEnabled = flat.size();
        boolean truncated = totalEnabled > HELP_MODELS_MAX_ROWS;
        if (truncated) {
            flat = flat.subList(0, HELP_MODELS_MAX_ROWS);
        }

        Map<String, java.util.List<String>> byProvider = new LinkedHashMap<>();
        java.util.List<String> pairs = new java.util.ArrayList<>();
        for (Map<String, Object> row : flat) {
            String prov = (String) row.get("provider");
            String mid = (String) row.get("model");
            int order = (int) row.get("displayOrder");
            byProvider.computeIfAbsent(prov, k -> new java.util.ArrayList<>())
                .add(mid + " (#" + order + ")");
            pairs.add(prov + "/" + mid + " (#" + order + ")");
        }

        out.put("providers", byProvider);
        out.put("pairs", pairs);
        out.put("total_enabled", totalEnabled);
        out.put("returned", flat.size());
        // The `default` field MUST match what BrowserAgentModule.applyDefaultLlmIfNeeded
        // actually substitutes - i.e. the direct-API default. Bridges (codex /
        // claude-code / gemini-cli / mistral-vibe) are skipped on agent_browse
        // because they're full-CLI agent sessions, not per-step chat-completion
        // APIs (the per-step path 404s on `/v1/chat/completions`). Without
        // this alignment, the agent reads `default = codex/...` here, passes
        // it explicitly, then sees a confusing `model_substituted` notice
        // claiming we swapped its model.
        Object directProvider = catalog.get("defaultDirectProvider");
        Object directModel = catalog.get("defaultDirectModel");
        if (directProvider == null || directModel == null) {
            // CE deployment with bridges only - fall back to overall defaults
            // so the agent at least sees something. The runner will then surface
            // the precise bridge-incompatibility error if it actually tries.
            directProvider = catalog.getOrDefault("defaultProvider", "");
            directModel = catalog.getOrDefault("defaultModel", "");
        }
        out.put("default", Map.of("provider", directProvider, "model", directModel));
        StringBuilder note = new StringBuilder();
        note.append("Top ").append(flat.size()).append(" of ").append(totalEnabled)
            .append(" direct-API (provider, model) pairs by priority ")
            .append("(same ordering as the configured catalog the platform admin manages, ")
            .append("scoped to the browser_agent category - V156 sidecar). The 'default' field is what ")
            .append("agent_browse uses when llm is omitted. Pass an explicit (provider, model) only if you need to override.");
        if (hiddenBridgeCount > 0) {
            note.append(" Note: ").append(hiddenBridgeCount).append(" bridge model(s) (claude-code / codex / ")
                .append("gemini-cli / mistral-vibe) are intentionally hidden - they're full-session CLIs, not ")
                .append("per-step chat-completion APIs, and the runner would substitute them away.");
        }
        out.put("note", note.toString());
        return out;
    }

    private Map<String, Object> buildActionsHelp() {
        int maxFetches = config.getMaxParallelFetches();
        Map<String, Object> actions = new LinkedHashMap<>();

        actions.put("search", Map.of(
            "summary", "Multi-engine web search (~1s). First-pass for any factual lookup.",
            "params", Map.of(
                "query", "required - the search query",
                "max_results", "optional, default 10, cap 50",
                "time_range", "optional - 'day' | 'week' | 'month' | 'year'"),
            "returns", "results[]: {url, title, snippet}"
        ));

        actions.put("fetch", Map.of(
            "summary", "Render a static page as markdown (~5s/page). Use after search "
                + "when the snippet isn't enough.",
            "params", Map.of(
                "url", "string - single page (use 'urls' for batch)",
                "urls", "array, max " + maxFetches + " - parallel fetch"),
            "returns", "pages[]: {url, title, content (markdown)}"
        ));

        actions.put("agent_browse", Map.of(
            "summary", "Spawn an LLM-driven browser. ONLY when fetch cannot reach the target. "
                + "concurrency=1 per host - a second call returns RATE_LIMITED.",
            "params", new LinkedHashMap<String, Object>() {{
                put("task", "required - natural-language goal. Be specific.");
                put("start_url", "optional - if omitted, the agent picks one from the task");
                put("llm", "OPTIONAL - {provider, model}. Omit the whole block (or "
                    + "omit provider/model individually) to use the platform default model. "
                    + "Call web_search(action='help_models') first if you want a specific pair. "
                    + "Unknown pairs are silently substituted with the default and the swap is "
                    + "reported as 'model_substituted' in the result.");
                put("max_steps", "OPTIONAL int - overrides the runner's default of 50. Hard cap = 50 "
                    + "(values above are clamped). Bumping max_steps does NOT help when a session "
                    + "ends with stop_reason=MAX_STEPS in <30s - that signals an upstream failure "
                    + "(LLM auth, anti-bot, navigation), not insufficient budget.");
                put("expected_output_schema", "optional JSON Schema; mismatch → SCHEMA_MISMATCH");
                put("interaction_mode", "'autonomous' (default) | 'supervised' | 'manual'");
                put("domain_allowlist", "off-list nav → DOMAIN_BLOCKED");
                put("domain_denylist", "blocked domains");
                put("screenshot_policy", "'every_step' | 'on_change' (default) | 'final_only' | 'off'");
                put("session", "{headless, viewport:{width,height}, timeout_seconds}");
            }},
            "returns", "{final_result, extracted_data, stop_reason, session_id, pages_visited[], "
                + "steps[], cost:{tokens_in, tokens_out, llm_calls, browser_seconds, cost_usd}, "
                + "model_substituted? - present only when llm was omitted/invalid and the platform "
                + "default was substituted}"
        ));

        actions.put("browse_status", Map.of(
            "summary", "Inspect a running session by session_id (returned by agent_browse).",
            "params", Map.of("session_id", "required")));

        actions.put("browse_intervene", Map.of(
            "summary", "Inject guidance into a running session (interaction_mode='supervised' "
                + "or 'manual'). Idempotent - sending the same hint twice is safe.",
            "params", Map.of(
                "session_id", "required",
                "hint", "required - free-form text the agent will read on its next step")));

        actions.put("browse_abort", Map.of(
            "summary", "Cancel a running session. The session terminates with stop_reason=CANCELLED.",
            "params", Map.of("session_id", "required")));

        actions.put("browse_screenshot", Map.of(
            "summary", "Force an immediate screenshot of the running session.",
            "params", Map.of("session_id", "required"),
            "returns", "{screenshot_key} - MinIO key for the captured PNG"));

        actions.put("help", Map.of(
            "summary", "Action reference for this tool. The model catalog lives in help_models.",
            "params", Map.of(
                "topics", "optional array - filter to ['actions','concepts','examples']")
        ));
        actions.put("help_models", Map.of(
            "summary", "Live LLM catalog (top " + HELP_MODELS_MAX_ROWS + " by priority, only providers with a configured API key). "
                + "Call only if you want to override the platform default for agent_browse.",
            "params", Map.of("(none)", "no parameters"),
            "returns", "{providers, pairs, total_enabled, returned, default:{provider,model}, note}"
        ));

        return actions;
    }

    private static Map<String, Object> buildConceptsHelp() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cost_asymmetry",
            "search ≪ fetch ≪ agent_browse. agent_browse multiplies cost by ~100x because "
            + "every step issues an LLM call AND uses Chromium CPU/RAM. A 10-step session "
            + "can match the cost of 100 fetches. Always exhaust search+fetch first.");
        out.put("concurrency",
            "agent_browse is capped at 1 concurrent session per host. A second call while "
            + "another is in flight returns a RATE_LIMITED-style failure - wait for "
            + "browse_status to report a terminal stop_reason before retrying.");
        out.put("stop_reasons",
            "agent_browse always emits one of: COMPLETED (success), MAX_STEPS, USER_TAKEOVER, "
            + "LLM_FAILED, SCHEMA_MISMATCH, DOMAIN_BLOCKED, TIMEOUT, CANCELLED, "
            + "BUDGET_EXHAUSTED. Branch on stop_reason - only COMPLETED guarantees the task "
            + "succeeded; the others may have partial extracted_data but it's NOT authoritative.");
        out.put("model_catalog",
            "llm is OPTIONAL on agent_browse - omit it to use the platform default model "
            + "(top of the catalog). If you pass an unknown (provider, model) pair, the "
            + "platform silently substitutes the default and reports the swap in the result "
            + "as 'model_substituted'. Call web_search(action='help_models') only when you "
            + "specifically want to override the default - the same source the UI model "
            + "picker uses, so providers without a configured API key never appear.");
        out.put("credentials",
            "You don't pass an LLM API key - the platform resolves it from "
            + "platform_credentials (DB) or env vars based on llm.provider. credentials_ref "
            + "in older docs is no longer needed for agent_browse.");
        out.put("interactive_steering",
            "Default 'autonomous' lets the agent run end-to-end. Use 'supervised' when you "
            + "want to inject browse_intervene hints between steps; 'manual' starts paused "
            + "and waits for the first browse_intervene before doing anything.");
        out.put("output_schema",
            "Setting expected_output_schema makes the runner validate extracted_data before "
            + "COMPLETED. Mismatch → stop_reason=SCHEMA_MISMATCH and the run ends; tighten "
            + "the task or loosen the schema before retrying.");
        return out;
    }

    private static Map<String, Object> buildExamplesHelp() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("simple_search",
            "web_search(action='search', query='Python 3.13 release notes')");
        out.put("static_article",
            "web_search(action='fetch', url='https://example.com/blog/post')");
        out.put("parallel_fetch",
            "web_search(action='fetch', urls=['https://a.com', 'https://b.com'])");
        out.put("agent_browse_login_wall",
            "web_search(action='agent_browse', "
            + "task='Log into billing.example.com using {{credentials.example}}, open the "
            + "latest invoice, return amount and due date.', "
            + "start_url='https://billing.example.com/login', "
            + "llm={provider:'google', model:'gemini-2.5-flash', max_steps:25}, "
            + "expected_output_schema={type:'object', "
            + "properties:{amount:{type:'number'}, due_date:{type:'string'}}, "
            + "required:['amount','due_date']})");
        out.put("agent_browse_check_status",
            "web_search(action='browse_status', session_id='ses_…')");
        out.put("agent_browse_intervene",
            "web_search(action='browse_intervene', session_id='ses_…', "
            + "hint='click the \"Accept Cookies\" banner first')");
        return out;
    }
}
