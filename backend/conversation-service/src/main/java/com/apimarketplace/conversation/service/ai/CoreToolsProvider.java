package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.prompt.ConversationToolDefinitions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Provider for minimal core agent tools.
 *
 * Uses unified facade pattern for ALL tools (single tool with action param).
 *
 * Core tools (8):
 * - catalog: Unified facade for external API tools (search, execute, response_schema, help)
 * - workflow: Unified facade for workflow builder + management (init, load, save, ..., get, list, delete, runs, help)
 * - table: CRUD tables (unified facade with action param)
 * - interface: CRUD interfaces (unified facade with action param)
 * - agent: CRUD AI agents (unified facade with action param)
 * - skill: CRUD skills (unified facade with action param)
 * - application: Marketplace apps (unified facade with action param)
 * - web_search: Web search and fetch (unified facade with action param)
 *
 * Note: visualize tool removed - use [visualize:type:id] marker in response instead
 */
@Slf4j
@Service
public class CoreToolsProvider {

    /**
     * Minimal set of core tools to include.
     *
     * All tools use the unified facade pattern (single tool with action param):
     * - catalog: search, execute, response_schema, help
     * - workflow: init, load, save, add_node, ..., get, list, delete, runs, help
     * - table: create, get, list, update, delete, query_rows, etc.
     * - interface: create, get, list, update, delete, help
     * - agent: create, get, list, update, delete, help
     * - skill: create, get, list, update, delete, help
     * - application: browse, acquire, publish, help
     * - web_search: search, fetch
     */
    private static final Set<String> BASE_CORE_TOOL_NAMES = Set.of(
        "catalog",          // Unified facade: search, execute, response_schema, help
        "workflow",          // Unified facade: builder + management (init, load, save, ..., get, list, delete, runs, help)
        "table",             // Unified facade: CRUD
        "interface",         // Unified facade: CRUD
        "agent",             // Unified facade: CRUD
        "skill",             // Unified facade: CRUD
        "application",       // Unified facade: marketplace
        "web_search",        // Unified facade: search, fetch
        "image_generation",  // Unified facade: generate, help (gated by orchestrator's image-generation.enabled flag)
        "files",             // Unified facade: browse & open workspace files (list, get, view, help)
        "wait"               // Unified facade: sleep, help (blocking pause primitive)
    );

    @Value("${websearch.enabled:true}")
    private boolean webSearchEnabled = true;

    /**
     * CE→cloud web-search relay gate (present in the CE monolith context;
     * standalone conversation-service does not scan the bean and falls back to
     * the static websearch.enabled flag). When wired, web_search stays an active
     * core tool even with the local engine disabled so cloud-linked tenants get
     * the relayed tool; per-tenant exposure is filtered in AgentContextBuilder.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.agent.cloud.CeWebSearchRelayGate webSearchRelayGate;

    @Value("${image-generation.enabled:true}")
    private boolean imageGenerationEnabled = true;

    @Value("${orchestrator.service.url:http://localhost:8099}")
    private String orchestratorUrl;

    @Value("${agent.service.url:http://localhost:8090}")
    private String agentServiceUrl;

    @Value("${services.datasource-service.url:http://localhost:8088}")
    private String datasourceServiceUrl;

    @Value("${services.interface-service.url:http://localhost:8089}")
    private String interfaceServiceUrl;

    @Value("${services.catalog-service.url:http://localhost:8081}")
    private String catalogServiceUrl;

    private static final long RETRY_INITIAL_DELAY_SEC = 3;
    private static final long RETRY_MAX_DELAY_SEC = 30;
    private static final int MAX_RETRIES = 20;

    private final RestTemplate restTemplate;
    private final Map<String, ToolDefinition> coreToolsCache = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    /** True once agent-service responded successfully (even if some tools are missing). */
    private volatile boolean reachable = false;
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "core-tools-retry");
        t.setDaemon(true);
        return t;
    });

    public CoreToolsProvider(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Get minimal core tools for the agent.
     * Returns only essential tools (8) plus conversation-specific tools.
     * The agent can discover additional tools via tool(action='help') or catalog(action='search').
     *
     * @param isNewConversation if true, includes set_conversation_title tool
     */
    public List<ToolDefinition> getCoreTools(boolean isNewConversation) {
        if (!reachable || !getMissingTools().isEmpty()) {
            refreshCoreTools();
        }
        List<ToolDefinition> tools = new ArrayList<>(coreToolsCache.values());
        // Add conversation-specific tools (conditionally)
        tools.addAll(getConversationTools(isNewConversation));
        return sortByName(tools);
    }

    /**
     * Get filtered core tools for the agent based on enabled module tool names.
     * Returns only tools whose names are in the provided set, plus conversation-specific tools.
     *
     * @param enabledModuleToolNames set of tool names to include (e.g., "catalog", "table", "web_search")
     * @param isNewConversation if true, includes set_conversation_title tool
     */
    public List<ToolDefinition> getCoreTools(Set<String> enabledModuleToolNames, boolean isNewConversation) {
        if (!reachable || !getMissingTools().isEmpty()) {
            refreshCoreTools();
        }
        List<ToolDefinition> tools = coreToolsCache.entrySet().stream()
            .filter(e -> enabledModuleToolNames.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .collect(Collectors.toCollection(ArrayList::new));
        // Add conversation-specific tools (conditionally)
        tools.addAll(getConversationTools(isNewConversation));
        return sortByName(tools);
    }

    /**
     * Stage 1a.1-D - return tools in id-ascending order. {@code coreToolsCache} is a
     * {@link ConcurrentHashMap} whose iteration order is not stable across JVMs, so
     * returning {@code values()} directly shuffles the tools list on every pod
     * restart. Anthropic prompt caching hashes the tools prefix, so an unstable
     * order invalidates the cached prefix and bills the full rate instead of the
     * 0.1× cache-read rate. Providers also sort as a safety net, but sorting at
     * the source keeps logs/tests/metrics deterministic too.
     */
    private static List<ToolDefinition> sortByName(List<ToolDefinition> tools) {
        tools.sort(Comparator.comparing(ToolDefinition::name,
            Comparator.nullsLast(Comparator.naturalOrder())));
        return tools;
    }

    /**
     * Get conversation-specific tools.
     * Delegates to shared {@link ConversationToolDefinitions} - single source of truth.
     *
     * @param isNewConversation if true, includes set_conversation_title tool
     */
    private List<ToolDefinition> getConversationTools(boolean isNewConversation) {
        return ConversationToolDefinitions.getConversationTools(isNewConversation);
    }

    /**
     * Initial load at startup + schedule background retries until all tools are loaded.
     */
    @PostConstruct
    public void init() {
        refreshCoreTools();
        if (!reachable || coreToolsCache.size() < activeCoreToolNames().size()) {
            scheduleRetry(1);
        }
    }

    @PreDestroy
    public void shutdown() {
        retryScheduler.shutdownNow();
    }

    /**
     * Schedule a background retry with exponential backoff.
     * Retries until all expected tools are loaded or MAX_RETRIES is reached.
     */
    private void scheduleRetry(int attempt) {
        if (attempt > MAX_RETRIES) {
            Set<String> missing = getMissingTools();
            log.error("Failed to load all core tools after {} retries. Still missing: {}", MAX_RETRIES, missing);
            return;
        }
        long delay = Math.min(RETRY_INITIAL_DELAY_SEC * attempt, RETRY_MAX_DELAY_SEC);
        retryScheduler.schedule(() -> {
            refreshCoreTools();
            Set<String> missing = getMissingTools();
            if (!missing.isEmpty()) {
                log.info("Still missing core tools {} (attempt {}/{}), retrying in {}s...", missing, attempt, MAX_RETRIES, delay);
                scheduleRetry(attempt + 1);
            } else {
                log.info("All {} core tools loaded successfully.", activeCoreToolNames().size());
            }
        }, delay, TimeUnit.SECONDS);
    }

    Set<String> getMissingTools() {
        Set<String> missing = new HashSet<>(activeCoreToolNames());
        missing.removeAll(coreToolsCache.keySet());
        return missing;
    }

    Set<String> activeCoreToolNames() {
        Set<String> active = new HashSet<>(BASE_CORE_TOOL_NAMES);
        // web_search stays active when the local engine is enabled OR the CE→cloud
        // relay is wired (cloud-linked tenants then get the relayed tool;
        // per-tenant exposure is filtered in AgentContextBuilder).
        boolean webSearchExposable = webSearchRelayGate != null
            ? webSearchRelayGate.isWebSearchExposable()
            : webSearchEnabled;
        if (!webSearchExposable) {
            active.remove("web_search");
        }
        if (!imageGenerationEnabled) {
            active.remove("image_generation");
        }
        return Collections.unmodifiableSet(active);
    }

    /**
     * Refresh core tools from all owning services.
     * Orchestrator provides: workflow, application, web_search.
     * Agent-service provides: agent, skill.
     * Datasource-service provides: table.
     * Interface-service provides: interface.
     */
    public void refreshCoreTools() {
        try {
            coreToolsCache.clear();

            // Fetch from orchestrator (workflow, application, web_search)
            fetchToolsFrom(orchestratorUrl + "/api/agent-tools", "orchestrator");

            // Fetch from agent-service (agent, skill)
            fetchToolsFrom(agentServiceUrl + "/api/agent-tools", "agent-service");

            // Fetch from datasource-service (table)
            fetchToolsFrom(datasourceServiceUrl + "/api/agent-tools", "datasource-service");

            // Fetch from interface-service (interface)
            fetchToolsFrom(interfaceServiceUrl + "/api/agent-tools", "interface-service");

            // Fetch from catalog-service (catalog)
            fetchToolsFrom(catalogServiceUrl + "/api/agent-tools", "catalog-service");

            Set<String> expectedTools = activeCoreToolNames();
            log.info("Loaded {} core tools total (expected {})", coreToolsCache.size(), expectedTools.size());

            Set<String> missing = new HashSet<>(expectedTools);
            missing.removeAll(coreToolsCache.keySet());
            if (!missing.isEmpty()) {
                log.warn("Missing core tools (not registered?): {}", missing);
            }

            reachable = true;
            initialized = true;

        } catch (Exception e) {
            log.warn("Failed to fetch core tools: {}. Will retry.", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void fetchToolsFrom(String url, String source) {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);
            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                List<Map<String, Object>> tools = (List<Map<String, Object>>) response.getBody().get("tools");
                if (tools != null) {
                    int count = 0;
                    for (Map<String, Object> toolData : tools) {
                        String toolName = (String) toolData.get("name");
                        if (activeCoreToolNames().contains(toolName) && !coreToolsCache.containsKey(toolName)) {
                            ToolDefinition tool = parseToolDefinition(toolData);
                            if (tool != null) {
                                coreToolsCache.put(tool.name(), tool);
                                count++;
                            }
                        }
                    }
                    log.info("Fetched {} core tools from {} ({} total available)", count, source, tools.size());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch tools from {} ({}): {}", source, url, e.getMessage());
        }
    }

    /**
     * Parse tool definition from API response.
     */
    @SuppressWarnings("unchecked")
    private ToolDefinition parseToolDefinition(Map<String, Object> data) {
        try {
            String name = (String) data.get("name");
            String description = (String) data.get("description");
            List<String> requiredParams = (List<String>) data.getOrDefault("requiredParams", List.of());
            
            // Parse full parameter details if available
            List<ToolParameter> parameters = new ArrayList<>();
            List<Map<String, Object>> paramsList = (List<Map<String, Object>>) data.get("parameters");
            
            if (paramsList != null && !paramsList.isEmpty()) {
                for (Map<String, Object> paramData : paramsList) {
                    String paramName = (String) paramData.get("name");
                    String paramType = (String) paramData.getOrDefault("type", "string");
                    String paramDesc = (String) paramData.getOrDefault("description", "Parameter " + paramName);
                    Boolean paramRequired = (Boolean) paramData.getOrDefault("required", false);
                    List<String> enumValues = (List<String>) paramData.get("enum");
                    
                    parameters.add(ToolParameter.builder()
                        .name(paramName)
                        .type(paramType)
                        .description(paramDesc)
                        .required(paramRequired)
                        .enumValues(enumValues)
                        .build());
                }
            } else {
                // Fallback: create minimal params from requiredParams list
                for (String paramName : requiredParams) {
                    parameters.add(ToolParameter.builder()
                        .name(paramName)
                        .type("string")
                        .description("Required parameter: " + paramName)
                        .required(true)
                        .build());
                }
            }
            
            // Extract per-tool timeout if present (dynamic per tool call)
            Long timeoutMs = null;
            Object timeoutObj = data.get("timeoutMs");
            if (timeoutObj instanceof Number n) {
                timeoutMs = n.longValue();
            }

            return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .requiredParameters(requiredParams)
                .timeoutMs(timeoutMs)
                .build();

        } catch (Exception e) {
            log.warn("Failed to parse tool definition: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Check if core tools are loaded.
     */
    public boolean isInitialized() {
        return initialized && !coreToolsCache.isEmpty();
    }

    /**
     * Get count of loaded core tools.
     */
    public int getToolCount() {
        return coreToolsCache.size();
    }
}
