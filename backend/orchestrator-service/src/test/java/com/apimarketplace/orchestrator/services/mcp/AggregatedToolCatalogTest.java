package com.apimarketplace.orchestrator.services.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AggregatedToolCatalog} merges the sibling services' MCP tool lists, dedups
 * by name deterministically, remembers which service owns each tool, and serves the
 * last-good list when a sibling is briefly unreachable (restart-robustness).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AggregatedToolCatalog")
class AggregatedToolCatalogTest {

    private static final String AGENT = "http://agent:8090";
    private static final String CATALOG = "http://catalog:8081";
    private static final String PATH = "/api/agent-tools/mcp/tools";

    @Mock
    private RestTemplate restTemplate;

    private AggregatedToolCatalog catalog(long ttlMs) {
        return new AggregatedToolCatalog(List.of(AGENT, CATALOG), restTemplate, ttlMs);
    }

    private static Map<String, Object> body(String... names) {
        List<Map<String, Object>> tools = java.util.Arrays.stream(names)
                .map(n -> Map.<String, Object>of("name", n, "description", n, "inputSchema", Map.of()))
                .toList();
        return Map.of("tools", tools);
    }

    @Test
    @DisplayName("merges tools from every sibling and records the owning service URL")
    void mergesAndRecordsOwner() {
        when(restTemplate.getForObject(eq(AGENT + PATH), eq(Map.class))).thenReturn(body("agent", "skill"));
        when(restTemplate.getForObject(eq(CATALOG + PATH), eq(Map.class))).thenReturn(body("catalog"));

        AggregatedToolCatalog c = catalog(60_000);

        assertThat(c.mcpTools()).extracting(t -> t.get("name"))
                .containsExactly("agent", "catalog", "skill"); // name-sorted
        assertThat(c.knows("agent")).isTrue();
        assertThat(c.knows("nope")).isFalse();
        assertThat(c.serviceUrlFor("agent")).isEqualTo(AGENT);
        assertThat(c.serviceUrlFor("catalog")).isEqualTo(CATALOG);
    }

    @Test
    @DisplayName("on a name clash the first service in URL order wins (deterministic)")
    void firstServiceWinsOnClash() {
        when(restTemplate.getForObject(eq(AGENT + PATH), eq(Map.class))).thenReturn(body("shared"));
        when(restTemplate.getForObject(eq(CATALOG + PATH), eq(Map.class))).thenReturn(body("shared"));

        AggregatedToolCatalog c = catalog(60_000);

        assertThat(c.mcpTools()).hasSize(1);
        assertThat(c.serviceUrlFor("shared")).isEqualTo(AGENT);
    }

    @Test
    @DisplayName("serves the last-good tools when a sibling becomes unreachable")
    void servesStaleOnFailure() {
        when(restTemplate.getForObject(eq(AGENT + PATH), eq(Map.class))).thenReturn(body("agent"));
        when(restTemplate.getForObject(eq(CATALOG + PATH), eq(Map.class))).thenReturn(body("catalog"));

        AggregatedToolCatalog c = catalog(60_000);
        c.refresh(); // synchronous initial poll: both up
        assertThat(c.knows("catalog")).isTrue();

        // catalog-service now down; agent-service still fine.
        when(restTemplate.getForObject(eq(CATALOG + PATH), eq(Map.class)))
                .thenThrow(new ResourceAccessException("connection refused"));
        c.refresh(); // synchronous re-poll: catalog throws, agent refreshes

        assertThat(c.mcpTools()).extracting(t -> t.get("name"))
                .containsExactly("agent", "catalog"); // catalog still served from cache
        assertThat(c.serviceUrlFor("catalog")).isEqualTo(CATALOG);
    }

    @Test
    @DisplayName("cold start with every sibling down yields an empty aggregate that does not re-poll within the TTL")
    void coldStartAllDownIsEmptyAndCached() {
        when(restTemplate.getForObject(eq(AGENT + PATH), eq(Map.class)))
                .thenThrow(new ResourceAccessException("down"));
        when(restTemplate.getForObject(eq(CATALOG + PATH), eq(Map.class)))
                .thenThrow(new ResourceAccessException("down"));

        AggregatedToolCatalog c = catalog(60_000);

        assertThat(c.mcpTools()).isEmpty();   // cold blocking load populates an empty snapshot
        assertThat(c.knows("agent")).isFalse();
        c.serviceUrlFor("agent");             // still fresh: no re-poll

        // A cold load must happen exactly once; a dead sibling must not be hammered every access.
        verify(restTemplate, times(1)).getForObject(eq(AGENT + PATH), eq(Map.class));
        verify(restTemplate, times(1)).getForObject(eq(CATALOG + PATH), eq(Map.class));
    }

    @Test
    @DisplayName("concurrent first-access threads poll each sibling exactly once (single-flight cold load)")
    void concurrentColdLoadPollsOnce() throws Exception {
        when(restTemplate.getForObject(eq(AGENT + PATH), eq(Map.class))).thenReturn(body("agent"));
        when(restTemplate.getForObject(eq(CATALOG + PATH), eq(Map.class))).thenReturn(body("catalog"));

        AggregatedToolCatalog c = catalog(60_000);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> tasks = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> c.mcpTools().size())
                    .collect(Collectors.toList());
            List<Integer> sizes = pool.invokeAll(tasks).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());
            assertThat(sizes).allMatch(s -> s == 2);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        // Single-flight: exactly one poll per sibling despite 8 concurrent first-accesses.
        verify(restTemplate, times(1)).getForObject(eq(AGENT + PATH), eq(Map.class));
        verify(restTemplate, times(1)).getForObject(eq(CATALOG + PATH), eq(Map.class));
    }

    @Test
    @DisplayName("within the TTL the siblings are polled once, not on every access")
    void ttlAvoidsRepolling() {
        when(restTemplate.getForObject(eq(AGENT + PATH), eq(Map.class))).thenReturn(body("agent"));
        when(restTemplate.getForObject(eq(CATALOG + PATH), eq(Map.class))).thenReturn(body("catalog"));

        AggregatedToolCatalog c = catalog(60_000);
        c.mcpTools();
        c.knows("agent");
        c.serviceUrlFor("catalog");

        verify(restTemplate, times(1)).getForObject(eq(AGENT + PATH), eq(Map.class));
        verify(restTemplate, times(1)).getForObject(eq(CATALOG + PATH), eq(Map.class));
    }

    @Test
    @DisplayName("a sibling returning no 'tools' key contributes nothing (no crash)")
    void malformedBodyContributesNothing() {
        when(restTemplate.getForObject(eq(AGENT + PATH), eq(Map.class))).thenReturn(Map.of("unexpected", 1));
        when(restTemplate.getForObject(eq(CATALOG + PATH), eq(Map.class))).thenReturn(body("catalog"));

        AggregatedToolCatalog c = catalog(60_000);

        assertThat(c.mcpTools()).extracting(t -> t.get("name")).containsExactly("catalog");
    }
}
