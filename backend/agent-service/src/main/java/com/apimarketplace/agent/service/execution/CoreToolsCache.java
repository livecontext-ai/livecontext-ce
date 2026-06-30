package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.prompt.DefaultSystemPrompts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.*;

/**
 * Caches core tool definitions fetched from orchestrator's /api/agent-tools endpoint.
 * Used by SubAgentExecutionHandler to provide tools to sub-agents executing locally.
 *
 * Simplified version of conversation-service's CoreToolsProvider:
 * - No conversation-specific tools (set_conversation_title, get_tool_result, request_credential)
 * - Those are only needed by conversation-service's context, not by sub-agents
 */
@Slf4j
@Component
public class CoreToolsCache {

    /** Core tool names - derived from DefaultSystemPrompts (single source of truth). */
    private static final Set<String> BASE_CORE_TOOL_NAMES = DefaultSystemPrompts.getAllCoreToolNames();

    private static final int MAX_RETRY_ATTEMPTS = 20;
    private static final long INITIAL_RETRY_DELAY_MS = 3000;
    private static final long MAX_RETRY_DELAY_MS = 30000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String orchestratorUrl;
    private final String agentServiceUrl;
    private final String datasourceServiceUrl;
    private final String interfaceServiceUrl;
    private final String catalogServiceUrl;
    private final boolean webSearchEnabled;
    private final boolean imageGenerationEnabled;
    /**
     * CE→cloud web-search relay gate. When the local engine is disabled
     * (websearch.enabled=false) but the install can relay searches to a linked
     * cloud account, web_search must stay an active core tool so linked tenants
     * get it; per-tenant exposure is filtered downstream. Nullable (cloud
     * microservice deployments where the gate is irrelevant fall back to the
     * static websearch.enabled flag).
     */
    private final com.apimarketplace.agent.cloud.CeWebSearchRelayGate webSearchRelayGate;

    private final ConcurrentHashMap<String, ToolDefinition> cache = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile boolean reachable = false;

    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "core-tools-cache-retry");
        t.setDaemon(true);
        return t;
    });

    public CoreToolsCache(RestTemplate restTemplate, ObjectMapper objectMapper,
                          @Value("${services.orchestrator-url:http://127.0.0.1:8099}") String orchestratorUrl,
                          @Value("${services.agent-url:http://127.0.0.1:8090}") String agentServiceUrl,
                          @Value("${services.datasource-url:http://127.0.0.1:8088}") String datasourceServiceUrl,
                          @Value("${services.interface-url:http://127.0.0.1:8089}") String interfaceServiceUrl,
                          @Value("${services.catalog-url:http://127.0.0.1:8081}") String catalogServiceUrl,
                          @Value("${websearch.enabled:true}") boolean webSearchEnabled,
                          @Value("${image-generation.enabled:true}") boolean imageGenerationEnabled,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          com.apimarketplace.agent.cloud.CeWebSearchRelayGate webSearchRelayGate) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.orchestratorUrl = orchestratorUrl;
        this.agentServiceUrl = agentServiceUrl;
        this.datasourceServiceUrl = datasourceServiceUrl;
        this.interfaceServiceUrl = interfaceServiceUrl;
        this.catalogServiceUrl = catalogServiceUrl;
        this.webSearchEnabled = webSearchEnabled;
        this.imageGenerationEnabled = imageGenerationEnabled;
        this.webSearchRelayGate = webSearchRelayGate;
    }

    @PostConstruct
    void init() {
        try {
            refreshCoreTools();
        } catch (Exception e) {
            log.warn("[CORE_TOOLS_CACHE] Initial load failed, scheduling retry: {}", e.getMessage());
        }
        if (!reachable || getMissingTools().size() > 0) {
            scheduleRetry(1);
        }
    }

    /**
     * Periodic safety net - refetch missing tools every 5 minutes.
     * <p>
     * The PostConstruct retry chain gives up after 20 attempts (~6 min total).
     * If a downstream service (e.g. orchestrator) is down longer than that
     * during agent-service boot, the cache stays incomplete forever. Concrete
     * incident (2026-05-02): orchestrator boot loop lasted 7 min during a
     * coordinated restart; agent-service exhausted its retries before
     * orchestrator stabilised, leaving {@code workflow}, {@code application},
     * {@code web_search} and {@code image_generation} permanently absent
     * until an agent-service restart.
     * <p>
     * This task is a no-op when nothing is missing (cheap enough). When tools
     * ARE missing, it calls only the affected sources to avoid clearing the
     * cache for tools that loaded fine.
     */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 300_000L)
    void scheduledRefreshIfIncomplete() {
        if (!initialized) {
            log.info("[CORE_TOOLS_CACHE] Periodic tick - initial load still running, skipping.");
            return;
        }
        Set<String> missing = getMissingTools();
        if (missing.isEmpty()) {
            log.info("[CORE_TOOLS_CACHE] Periodic tick - cache complete ({} tools), no-op.", cache.size());
            return;
        }
        log.info("[CORE_TOOLS_CACHE] Periodic refresh - cache still missing {}", missing);
        try {
            refreshMissingFromSources(missing);
            Set<String> stillMissing = getMissingTools();
            if (stillMissing.isEmpty()) {
                log.info("[CORE_TOOLS_CACHE] Periodic refresh recovered all tools.");
            } else {
                log.info("[CORE_TOOLS_CACHE] Periodic refresh still missing {}; will retry in 5 min.",
                    stillMissing);
            }
        } catch (Exception e) {
            log.warn("[CORE_TOOLS_CACHE] Periodic refresh failed: {}", e.getMessage());
        }
    }

    /**
     * Refetch only from sources that own at least one missing tool. Unlike
     * {@link #refreshCoreTools()} this does NOT clear the existing cache -
     * tools that loaded successfully on previous calls are preserved.
     */
    private void refreshMissingFromSources(Set<String> missing) {
        // Tools owned by orchestrator: workflow, application, web_search,
        // image_generation, files, get_connected_services, store_file, download_file
        if (missing.stream().anyMatch(t -> t.equals("workflow") || t.equals("application")
                || t.equals("web_search") || t.equals("image_generation") || t.equals("files"))) {
            fetchToolsFrom(orchestratorUrl + "/api/agent-tools", "orchestrator");
        }
        if (missing.contains("agent") || missing.contains("skill")) {
            fetchToolsFrom(agentServiceUrl + "/api/agent-tools", "agent-service");
        }
        if (missing.contains("table")) {
            fetchToolsFrom(datasourceServiceUrl + "/api/agent-tools", "datasource-service");
        }
        if (missing.contains("interface")) {
            fetchToolsFrom(interfaceServiceUrl + "/api/agent-tools", "interface-service");
        }
        if (missing.contains("catalog")) {
            fetchToolsFrom(catalogServiceUrl + "/api/agent-tools", "catalog-service");
        }
    }

    Set<String> getMissingTools() {
        Set<String> missing = new HashSet<>(activeCoreToolNames());
        missing.removeAll(cache.keySet());
        return missing;
    }

    Set<String> activeCoreToolNames() {
        Set<String> active = new HashSet<>(BASE_CORE_TOOL_NAMES);
        // web_search stays active when the local engine is enabled OR the CE→cloud
        // relay is wired (linked tenants then get the relayed tool; per-tenant
        // exposure is filtered where a tenant id is available).
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
     * Get all cached core tool definitions.
     */
    public List<ToolDefinition> getCoreTools() {
        if (!initialized) {
            log.warn("[CORE_TOOLS_CACHE] Not yet initialized - returning empty tools");
            return List.of();
        }
        return List.copyOf(cache.values());
    }

    /**
     * Get core tools filtered by enabled tool names (from agent's toolsConfig).
     *
     * @param enabledToolNames set of tool names to include (null = all)
     */
    public List<ToolDefinition> getCoreTools(Set<String> enabledToolNames) {
        if (!initialized) {
            log.warn("[CORE_TOOLS_CACHE] Not yet initialized - returning empty tools");
            return List.of();
        }
        if (enabledToolNames == null) {
            return getCoreTools();
        }
        return cache.values().stream()
            .filter(td -> enabledToolNames.contains(td.name()))
            .toList();
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ==================== Internal ====================

    /**
     * Fetch core tools from all owning services.
     * Orchestrator provides: workflow, application, web_search.
     * Agent-service (local) provides: agent, skill.
     * Datasource-service provides: table.
     * Interface-service provides: interface.
     */
    @SuppressWarnings("unchecked")
    void refreshCoreTools() {
        cache.clear();

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

        initialized = true;
        reachable = true;
        log.info("[CORE_TOOLS_CACHE] Loaded {} core tools total", cache.size());

        for (String expected : activeCoreToolNames()) {
            if (!cache.containsKey(expected)) {
                log.warn("[CORE_TOOLS_CACHE] Expected tool '{}' not found", expected);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void fetchToolsFrom(String url, String source) {
        try {
            log.info("[CORE_TOOLS_CACHE] Fetching from {}", url);
            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return;

            List<Map<String, Object>> toolMaps = (List<Map<String, Object>>) response.getBody().get("tools");
            if (toolMaps == null) return;

            int loaded = 0;
            for (Map<String, Object> toolMap : toolMaps) {
                String name = (String) toolMap.get("name");
                if (name != null && activeCoreToolNames().contains(name) && !cache.containsKey(name)) {
                    ToolDefinition td = parseToolDefinition(toolMap);
                    if (td != null) {
                        cache.put(name, td);
                        loaded++;
                    }
                }
            }
            log.info("[CORE_TOOLS_CACHE] Loaded {} tools from {} ({} available)", loaded, source, toolMaps.size());
        } catch (Exception e) {
            log.warn("[CORE_TOOLS_CACHE] Failed to fetch from {} ({}): {}", source, url, e.getMessage());
        }
    }

    private void scheduleRetry(int attempt) {
        if (attempt > MAX_RETRY_ATTEMPTS) {
            log.error("[CORE_TOOLS_CACHE] Failed to load all core tools after {} attempts. Still missing: {}", MAX_RETRY_ATTEMPTS, getMissingTools());
            return;
        }

        long delay = Math.min(INITIAL_RETRY_DELAY_MS * attempt, MAX_RETRY_DELAY_MS);
        log.info("[CORE_TOOLS_CACHE] Scheduling retry #{} in {}ms", attempt, delay);

        retryScheduler.schedule(() -> {
            try {
                refreshCoreTools();
                Set<String> missing = getMissingTools();
                if (!missing.isEmpty()) {
                    log.info("[CORE_TOOLS_CACHE] Retry #{} - still missing: {}", attempt, missing);
                    scheduleRetry(attempt + 1);
                } else {
                    log.info("[CORE_TOOLS_CACHE] All {} core tools loaded successfully.", activeCoreToolNames().size());
                }
            } catch (Exception e) {
                log.warn("[CORE_TOOLS_CACHE] Retry #{} failed: {}", attempt, e.getMessage());
                scheduleRetry(attempt + 1);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("unchecked")
    private ToolDefinition parseToolDefinition(Map<String, Object> map) {
        try {
            String id = (String) map.get("id");
            String name = (String) map.get("name");
            String description = (String) map.get("description");

            // Parse parameters
            List<ToolParameter> parameters = null;
            Object paramsObj = map.get("parameters");
            if (paramsObj instanceof List<?> paramsList) {
                parameters = new ArrayList<>();
                for (Object paramObj : paramsList) {
                    if (paramObj instanceof Map<?, ?> paramMap) {
                        parameters.add(parseToolParameter((Map<String, Object>) paramMap));
                    }
                }
            }

            // Parse required parameters
            List<String> requiredParameters = null;
            Object reqObj = map.get("requiredParameters");
            if (reqObj instanceof List<?>) {
                requiredParameters = (List<String>) reqObj;
            }

            // Parse metadata
            Map<String, Object> metadata = null;
            Object metaObj = map.get("metadata");
            if (metaObj instanceof Map) {
                metadata = (Map<String, Object>) metaObj;
            }

            // Parse timeout
            Long timeoutMs = null;
            Object timeoutObj = map.get("timeoutMs");
            if (timeoutObj instanceof Number n) {
                timeoutMs = n.longValue();
            }

            return ToolDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .parameters(parameters)
                .requiredParameters(requiredParameters)
                .metadata(metadata)
                .timeoutMs(timeoutMs)
                .build();

        } catch (Exception e) {
            log.warn("[CORE_TOOLS_CACHE] Failed to parse tool definition: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private ToolParameter parseToolParameter(Map<String, Object> map) {
        return ToolParameter.builder()
            .name((String) map.get("name"))
            .type((String) map.get("type"))
            .description((String) map.get("description"))
            .required(Boolean.TRUE.equals(map.get("required")))
            .enumValues(map.get("enumValues") instanceof List<?> ev ? (List<String>) ev : null)
            .build();
    }
}
