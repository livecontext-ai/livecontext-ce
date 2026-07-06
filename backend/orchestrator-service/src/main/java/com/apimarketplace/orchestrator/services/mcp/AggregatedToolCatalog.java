package com.apimarketplace.orchestrator.services.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Aggregates the MCP-format tool lists of the sibling microservices (agent,
 * datasource, interface, catalog) so the cloud MCP server at {@code /mcp} exposes
 * the SAME tool surface an agent gets in the CE monolith, where every provider
 * lives in one process. Without this, the cloud {@code /mcp} only advertises the
 * tools registered locally in orchestrator (the other services' providers are not
 * on its classpath).
 *
 * <p><b>Pull model, robust to restart.</b> Each sibling is polled for
 * {@code GET /api/agent-tools/mcp/tools}; results are cached with a short TTL and
 * served stale when a sibling is briefly unreachable, so a rolling restart of one
 * service never blanks the aggregate. There is no shared state and no push, so any
 * orchestrator replica self-heals on its next refresh.
 *
 * <p><b>Microservice-only.</b> In the monolith every provider is local, so
 * {@code McpProtocolService} serves the in-process registry directly and this class
 * is never wired (its config is gated on {@code deployment.mode=microservice}).
 */
@Slf4j
public class AggregatedToolCatalog {

    private static final String MCP_TOOLS_PATH = "/api/agent-tools/mcp/tools";
    private static final long DEFAULT_TTL_MS = 60_000L;

    private final List<String> serviceBaseUrls;
    private final RestTemplate discoveryRestTemplate;
    private final long ttlMs;

    /** Last successful per-service fetch, kept for serve-stale on a transient failure. */
    private final Map<String, List<Map<String, Object>>> lastGoodPerService = new ConcurrentHashMap<>();

    /** Single-flight guard: at most one background refresh runs at a time. */
    private final AtomicBoolean refreshInProgress = new AtomicBoolean(false);
    private final ExecutorService refreshExecutor;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile long lastRefreshEpochMs = 0L;

    public AggregatedToolCatalog(List<String> serviceBaseUrls, RestTemplate discoveryRestTemplate) {
        this(serviceBaseUrls, discoveryRestTemplate, DEFAULT_TTL_MS);
    }

    AggregatedToolCatalog(List<String> serviceBaseUrls, RestTemplate discoveryRestTemplate, long ttlMs) {
        this.serviceBaseUrls = serviceBaseUrls == null ? List.of()
                : serviceBaseUrls.stream().filter(u -> u != null && !u.isBlank()).distinct().toList();
        this.discoveryRestTemplate = discoveryRestTemplate;
        this.ttlMs = ttlMs;
        this.refreshExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mcp-tool-catalog-refresh");
            t.setDaemon(true);
            return t;
        });
    }

    /** All aggregated remote tools in MCP {@code tools/list} format, name-sorted. */
    public List<Map<String, Object>> mcpTools() {
        ensureLoaded();
        return snapshot.tools();
    }

    /** True when {@code toolName} belongs to an aggregated sibling service. */
    public boolean knows(String toolName) {
        ensureLoaded();
        return snapshot.toolToServiceUrl().containsKey(toolName);
    }

    /** Base URL of the sibling that owns {@code toolName}, or {@code null} if none. */
    public String serviceUrlFor(String toolName) {
        ensureLoaded();
        return snapshot.toolToServiceUrl().get(toolName);
    }

    /**
     * Guarantees a usable snapshot without blocking the hot path once loaded: the
     * first-ever access blocks (single-flight) to populate; every later access when
     * the cache is stale returns the current (stale) snapshot immediately and kicks a
     * background refresh, so a slow or black-holing sibling never stalls MCP traffic.
     */
    private void ensureLoaded() {
        if (isFresh()) {
            return;
        }
        if (!snapshot.everLoaded()) {
            blockingInitialLoad();
        } else {
            triggerAsyncRefresh();
        }
    }

    private boolean isFresh() {
        return snapshot.everLoaded() && (System.currentTimeMillis() - lastRefreshEpochMs) < ttlMs;
    }

    private synchronized void blockingInitialLoad() {
        if (snapshot.everLoaded()) {
            return; // another thread populated the cache while we waited on the lock
        }
        refresh();
    }

    private void triggerAsyncRefresh() {
        if (!refreshInProgress.compareAndSet(false, true)) {
            return; // a refresh is already running; keep serving the current snapshot
        }
        try {
            refreshExecutor.submit(() -> {
                try {
                    refresh();
                } finally {
                    refreshInProgress.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            refreshInProgress.set(false); // executor shutting down; stay on stale
        }
    }

    /** Package-private for tests: synchronous re-poll of every sibling. */
    synchronized void refresh() {
        for (String baseUrl : serviceBaseUrls) {
            try {
                lastGoodPerService.put(baseUrl, fetchOne(baseUrl));
            } catch (Exception e) {
                // Serve stale: keep the last-good tools for this service (if any).
                log.warn("MCP aggregation: sibling {} unreachable ({}); serving {} cached tool(s)",
                        baseUrl, e.getMessage(),
                        lastGoodPerService.getOrDefault(baseUrl, List.of()).size());
            }
        }
        rebuildSnapshot();
        lastRefreshEpochMs = System.currentTimeMillis();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchOne(String baseUrl) {
        Map<String, Object> body = discoveryRestTemplate.getForObject(baseUrl + MCP_TOOLS_PATH, Map.class);
        if (body == null || !(body.get("tools") instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object t : list) {
            if (t instanceof Map<?, ?> m && m.get("name") instanceof String) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }

    private void rebuildSnapshot() {
        Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
        Map<String, String> toolToUrl = new HashMap<>();
        for (String baseUrl : serviceBaseUrls) {
            for (Map<String, Object> tool : lastGoodPerService.getOrDefault(baseUrl, List.of())) {
                String name = (String) tool.get("name");
                // First service (in URL order) to declare a name wins - deterministic.
                if (name != null && !byName.containsKey(name)) {
                    byName.put(name, tool);
                    toolToUrl.put(name, baseUrl);
                }
            }
        }
        List<Map<String, Object>> sorted = byName.values().stream()
                .sorted(Comparator.comparing(t -> String.valueOf(t.get("name"))))
                .toList();
        this.snapshot = new Snapshot(sorted, Map.copyOf(toolToUrl), true);
    }

    private record Snapshot(List<Map<String, Object>> tools,
                            Map<String, String> toolToServiceUrl,
                            boolean everLoaded) {
        static final Snapshot EMPTY = new Snapshot(List.of(), Map.of(), false);
    }
}
