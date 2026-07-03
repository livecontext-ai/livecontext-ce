package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("CoreToolsCache")
@ExtendWith(MockitoExtension.class)
class CoreToolsCacheTest {

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private CoreToolsCache cache;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        cache = new CoreToolsCache(restTemplate, objectMapper, "http://localhost:8099",
            "http://localhost:8090", "http://localhost:8088", "http://localhost:8089", "http://localhost:8081",
            true, true, null);
    }

    /** Wrap tool maps in the envelope returned by GET /api/agent-tools */
    private Map<String, Object> wrapTools(List<Map<String, Object>> toolMaps) {
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("tools", toolMaps);
        envelope.put("count", toolMaps.size());
        return envelope;
    }

    private void mockResponse(List<Map<String, Object>> toolMaps) {
        ResponseEntity<Map> response = new ResponseEntity<>(wrapTools(toolMaps), HttpStatus.OK);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class)))
            .thenReturn(response);
    }

    /** Build a 200 OK /api/agent-tools envelope advertising exactly the named tools. */
    private ResponseEntity<Map> toolsFor(String... names) {
        List<Map<String, Object>> toolMaps = new ArrayList<>();
        for (String n : names) {
            toolMaps.add(Map.of("name", n, "description", n + " tool", "id", n + "-1"));
        }
        return new ResponseEntity<>(wrapTools(toolMaps), HttpStatus.OK);
    }

    @Nested
    @DisplayName("getCoreTools()")
    class GetCoreToolsTests {

        @Test
        @DisplayName("should return empty list when not initialized")
        void shouldReturnEmptyWhenNotInitialized() {
            assertThat(cache.isInitialized()).isFalse();
            assertThat(cache.getCoreTools()).isEmpty();
        }

        @Test
        @DisplayName("should return cached tools after successful refresh")
        void shouldReturnCachedToolsAfterRefresh() {
            // Given
            List<Map<String, Object>> toolMaps = List.of(
                Map.of("name", "agent", "description", "Agent tool", "id", "agent-1"),
                Map.of("name", "workflow", "description", "Workflow tool", "id", "workflow-1"),
                Map.of("name", "table", "description", "Table tool", "id", "table-1"),
                Map.of("name", "unknown_tool", "description", "Not a core tool", "id", "unk-1")
            );

            ResponseEntity<Map> response = new ResponseEntity<>(wrapTools(toolMaps), HttpStatus.OK);
            when(restTemplate.exchange(eq("http://localhost:8099/api/agent-tools"),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

            // When
            cache.refreshCoreTools();

            // Then
            assertThat(cache.isInitialized()).isTrue();
            List<ToolDefinition> tools = cache.getCoreTools();
            assertThat(tools).hasSize(3); // only core tools, not unknown_tool
            assertThat(tools.stream().map(ToolDefinition::name))
                .containsExactlyInAnyOrder("agent", "workflow", "table");
        }

        @Test
        @DisplayName("should filter tools by enabled names")
        void shouldFilterByEnabledNames() {
            mockResponse(List.of(
                Map.of("name", "agent", "description", "Agent tool", "id", "a1"),
                Map.of("name", "workflow", "description", "Workflow tool", "id", "w1"),
                Map.of("name", "table", "description", "Table tool", "id", "t1")
            ));

            cache.refreshCoreTools();

            // When
            List<ToolDefinition> filtered = cache.getCoreTools(Set.of("agent", "table"));

            // Then
            assertThat(filtered).hasSize(2);
            assertThat(filtered.stream().map(ToolDefinition::name))
                .containsExactlyInAnyOrder("agent", "table");
        }

        @Test
        @DisplayName("should return all tools when enabledNames is null")
        void shouldReturnAllWhenEnabledNamesNull() {
            mockResponse(List.of(
                Map.of("name", "agent", "description", "Agent", "id", "a1"),
                Map.of("name", "workflow", "description", "Workflow", "id", "w1")
            ));

            cache.refreshCoreTools();

            // When
            List<ToolDefinition> result = cache.getCoreTools(null);

            // Then
            assertThat(result).hasSize(2);
        }
    }

    @Nested
    @DisplayName("refreshCoreTools()")
    class RefreshTests {

        @Test
        @DisplayName("should parse tool parameters correctly")
        void shouldParseToolParameters() {
            // Given
            Map<String, Object> toolMap = Map.of(
                "name", "agent",
                "id", "agent-1",
                "description", "Execute agent operations",
                "parameters", List.of(
                    Map.of("name", "action", "type", "string", "description", "The action", "required", true),
                    Map.of("name", "agent_id", "type", "string", "description", "Agent UUID", "required", false)
                ),
                "requiredParameters", List.of("action"),
                "timeoutMs", 120000L
            );

            mockResponse(List.of(toolMap));

            // When
            cache.refreshCoreTools();

            // Then
            List<ToolDefinition> tools = cache.getCoreTools();
            assertThat(tools).hasSize(1);
            ToolDefinition td = tools.get(0);
            assertThat(td.name()).isEqualTo("agent");
            assertThat(td.parameters()).hasSize(2);
            assertThat(td.requiredParameters()).containsExactly("action");
            assertThat(td.timeoutMs()).isEqualTo(120000L);
        }

        @Test
        @DisplayName("should initialize empty when all sources return non-2xx")
        void shouldInitializeEmptyOnError() {
            ResponseEntity<Map> response = new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(response);

            cache.refreshCoreTools();

            // Multi-source fetch gracefully handles individual failures
            assertThat(cache.isInitialized()).isTrue();
            assertThat(cache.getCoreTools()).isEmpty();
        }

        @Test
        @DisplayName("should handle tools with minimal fields")
        void shouldHandleMinimalToolDefinition() {
            mockResponse(List.of(
                Map.of("name", "catalog", "description", "Catalog operations")
            ));

            cache.refreshCoreTools();

            List<ToolDefinition> tools = cache.getCoreTools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("catalog");
            assertThat(tools.get(0).parameters()).isNull();
        }

        @Test
        @DisplayName("CE monolith uses configured agent-service URL instead of standalone port")
        void monolithUsesConfiguredAgentServiceUrl() {
            cache = new CoreToolsCache(restTemplate, objectMapper,
                "http://monolith:8080", "http://monolith:8080",
                "http://monolith:8080", "http://monolith:8080", "http://monolith:8080",
                true, true, null);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(wrapTools(List.of()), HttpStatus.OK));

            cache.refreshCoreTools();

            verify(restTemplate, atLeastOnce()).exchange(
                eq("http://monolith:8080/api/agent-tools"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class));
            verify(restTemplate, never()).exchange(
                eq("http://127.0.0.1:8090/api/agent-tools"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class));
        }

        @Test
        @DisplayName("CE feature gate: disabled web_search is not expected or cached")
        void disabledWebSearchIsNotExpectedOrCached() {
            cache = new CoreToolsCache(restTemplate, objectMapper, "http://localhost:8099",
                "http://localhost:8090", "http://localhost:8088", "http://localhost:8089", "http://localhost:8081",
                false, true, null);
            mockResponse(List.of(
                Map.of("name", "catalog", "description", "Catalog", "id", "catalog-1"),
                Map.of("name", "workflow", "description", "Workflow", "id", "workflow-1"),
                Map.of("name", "web_search", "description", "Web search", "id", "web-1"),
                Map.of("name", "image_generation", "description", "Image generation", "id", "image-1")
            ));

            cache.refreshCoreTools();

            assertThat(cache.activeCoreToolNames()).doesNotContain("web_search");
            assertThat(cache.getMissingTools()).doesNotContain("web_search");
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .doesNotContain("web_search")
                .contains("catalog", "workflow", "image_generation");
        }

        @Test
        @DisplayName("CE→cloud relay wired: web_search stays an active core tool even with the local engine disabled")
        void relayWiredKeepsWebSearchActiveAndCached() {
            com.apimarketplace.agent.cloud.CeWebSearchRelayGate gate =
                mock(com.apimarketplace.agent.cloud.CeWebSearchRelayGate.class);
            when(gate.isWebSearchExposable()).thenReturn(true);
            cache = new CoreToolsCache(restTemplate, objectMapper, "http://localhost:8099",
                "http://localhost:8090", "http://localhost:8088", "http://localhost:8089", "http://localhost:8081",
                false, true, gate);
            mockResponse(List.of(
                Map.of("name", "catalog", "description", "Catalog", "id", "catalog-1"),
                Map.of("name", "web_search", "description", "Web search", "id", "web-1")
            ));

            cache.refreshCoreTools();

            assertThat(cache.activeCoreToolNames()).contains("web_search");
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .contains("web_search", "catalog");
        }

        @Test
        @DisplayName("CE→cloud relay NOT wired: gate says not exposable → web_search stays out (pre-relay behavior)")
        void relayNotWiredKeepsWebSearchOut() {
            com.apimarketplace.agent.cloud.CeWebSearchRelayGate gate =
                mock(com.apimarketplace.agent.cloud.CeWebSearchRelayGate.class);
            when(gate.isWebSearchExposable()).thenReturn(false);
            cache = new CoreToolsCache(restTemplate, objectMapper, "http://localhost:8099",
                "http://localhost:8090", "http://localhost:8088", "http://localhost:8089", "http://localhost:8081",
                false, true, gate);
            mockResponse(List.of(
                Map.of("name", "web_search", "description", "Web search", "id", "web-1")
            ));

            cache.refreshCoreTools();

            assertThat(cache.activeCoreToolNames()).doesNotContain("web_search");
            assertThat(cache.getCoreTools()).isEmpty();
        }
    }

    @Nested
    @DisplayName("scheduledRefreshIfIncomplete() periodic safety-net")
    class PeriodicRefreshTests {

        // The five per-service /api/agent-tools endpoints the cache fans out to,
        // matching the URLs the default cache is constructed with in setUp().
        private static final String ORCH = "http://localhost:8099/api/agent-tools";
        private static final String AGENT = "http://localhost:8090/api/agent-tools";
        private static final String DATASOURCE = "http://localhost:8088/api/agent-tools";
        private static final String INTERFACE = "http://localhost:8089/api/agent-tools";
        private static final String CATALOG = "http://localhost:8081/api/agent-tools";

        @Test
        @DisplayName("recovers tools the initial load missed WITHOUT clearing the already-cached ones, "
            + "and re-queries ONLY the source that owns the missing tools")
        void periodicRefreshRecoversMissingToolsWithoutClearingExistingCache() {
            // Given: on the initial load orchestrator is down (returns an empty tool list)
            // so its tools (workflow/application/web_search/image_generation/files) never
            // land in the cache, while every other source loads fine. On the SECOND call
            // orchestrator is back and advertises its tools.
            when(restTemplate.exchange(eq(ORCH), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor()) // initial: orchestrator unreachable -> no tools
                .thenReturn(toolsFor("workflow", "application", "web_search", "image_generation", "files", "wait"));
            when(restTemplate.exchange(eq(AGENT), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("agent", "skill"));
            when(restTemplate.exchange(eq(DATASOURCE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("table"));
            when(restTemplate.exchange(eq(INTERFACE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("interface"));
            when(restTemplate.exchange(eq(CATALOG), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("catalog"));

            cache.refreshCoreTools();

            // Sanity: only orchestrator-owned tools are missing after the initial load.
            assertThat(cache.getMissingTools())
                .containsExactlyInAnyOrder("workflow", "application", "web_search", "image_generation", "files", "wait");
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .containsExactlyInAnyOrder("agent", "skill", "table", "interface", "catalog");

            // When: the 5-minute safety net runs.
            cache.scheduledRefreshIfIncomplete();

            // Then: the missing orchestrator tools are recovered...
            assertThat(cache.getMissingTools()).isEmpty();
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .containsExactlyInAnyOrder("catalog", "table", "interface", "agent", "skill",
                    "workflow", "application", "web_search", "image_generation", "files", "wait");
            // ...and the originally-loaded tools were preserved (the cache was NOT cleared,
            // unlike refreshCoreTools()), so no consumer ever sees them disappear.
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .contains("agent", "skill", "table", "interface", "catalog");

            // And: only orchestrator is re-queried on the periodic pass (2 calls total =
            // initial + periodic); the sources whose tools already loaded are NOT re-hit
            // (1 call each = initial only).
            verify(restTemplate, times(2)).exchange(eq(ORCH), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
            verify(restTemplate, times(1)).exchange(eq(AGENT), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
            verify(restTemplate, times(1)).exchange(eq(DATASOURCE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
            verify(restTemplate, times(1)).exchange(eq(INTERFACE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
            verify(restTemplate, times(1)).exchange(eq(CATALOG), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
        }

        @Test
        @DisplayName("is a no-op (fetches nothing) once the cache is already complete")
        void periodicRefreshIsNoOpWhenCacheAlreadyComplete() {
            // Given: every source loads its tools so the cache is complete.
            when(restTemplate.exchange(eq(ORCH), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("workflow", "application", "web_search", "image_generation", "files", "wait"));
            when(restTemplate.exchange(eq(AGENT), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("agent", "skill"));
            when(restTemplate.exchange(eq(DATASOURCE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("table"));
            when(restTemplate.exchange(eq(INTERFACE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("interface"));
            when(restTemplate.exchange(eq(CATALOG), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("catalog"));

            cache.refreshCoreTools();
            assertThat(cache.getMissingTools()).isEmpty();

            // When: the periodic tick runs with nothing missing.
            clearInvocations(restTemplate);
            cache.scheduledRefreshIfIncomplete();

            // Then: no source is contacted and the cache is untouched.
            verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class));
            assertThat(cache.getCoreTools()).hasSize(11);
        }

        @Test
        @DisplayName("skips entirely while the initial load is still running (not yet initialized)")
        void periodicRefreshSkipsWhenNotYetInitialized() {
            // Given: a freshly-built cache whose initial load has not completed.
            assertThat(cache.isInitialized()).isFalse();

            // When: the periodic tick fires before initialization finished.
            cache.scheduledRefreshIfIncomplete();

            // Then: it short-circuits and never reaches any source.
            verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class));
        }
    }
}
