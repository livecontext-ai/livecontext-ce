package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
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
        @DisplayName("should keep an array parameter's item type, which decides what the model is told to send")
        void shouldKeepArrayItemTypeFromTheWire() {
            // The tool declares the item type; this payload is how it travels; and the schema this
            // service hands the model is rebuilt from what is parsed here. Dropping it turns an
            // array of objects back into an array of strings for every CLI session, with nothing
            // failing anywhere in between.
            Map<String, Object> objects = new HashMap<>(Map.of(
                "name", "rows", "type", "array", "description", "Rows", "required", false));
            objects.put("itemType", "object");
            Map<String, Object> strings = new HashMap<>(Map.of(
                "name", "tags", "type", "array", "description", "Tags", "required", false));

            ResponseEntity<Map> response = new ResponseEntity<>(wrapTools(List.of(Map.of(
                "name", "table", "description", "Table tool", "id", "table-1",
                "parameters", List.of(objects, strings)))), HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(response);

            cache.refreshCoreTools();

            List<ToolParameter> params = cache.getCoreTools().get(0).parameters();
            assertThat(params).extracting(ToolParameter::name).containsExactly("rows", "tags");
            assertThat(params.get(0).itemType()).isEqualTo("object");
            // Absent on the wire means the reader's default, not a value invented here.
            assertThat(params.get(1).itemType()).isNull();
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
                Map.of("name", "web_search", "description", "Web search", "id", "web-1")
            ));

            cache.refreshCoreTools();

            assertThat(cache.activeCoreToolNames()).doesNotContain("web_search");
            assertThat(cache.getMissingTools()).doesNotContain("web_search");
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .doesNotContain("web_search")
                .contains("catalog", "workflow");
        }

        /**
         * generation debits credits on every create, so the install-level flag has to
         * reach this cache too: with the flag off catalog-service never registers the
         * provider, and a cache that still expected the tool would chase it forever and
         * hand it out the moment anything else advertised the name.
         */
        @Test
        @DisplayName("spend gate off: generation is neither expected nor cached, even when a source advertises it")
        void disabledGenerationIsNotExpectedOrCached() {
            cache = new CoreToolsCache(restTemplate, objectMapper, "http://localhost:8099",
                "http://localhost:8090", "http://localhost:8088", "http://localhost:8089", "http://localhost:8081",
                true, false, null);
            mockResponse(List.of(
                Map.of("name", "catalog", "description", "Catalog", "id", "catalog-1"),
                Map.of("name", "generation", "description", "Generation", "id", "gen-1")
            ));

            cache.refreshCoreTools();

            assertThat(cache.activeCoreToolNames()).doesNotContain("generation");
            assertThat(cache.getMissingTools()).doesNotContain("generation");
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .doesNotContain("generation")
                .contains("catalog");
        }

        @Test
        @DisplayName("spend gate on: generation is expected and cached from catalog-service")
        void enabledGenerationIsExpectedAndCached() {
            mockResponse(List.of(
                Map.of("name", "catalog", "description", "Catalog", "id", "catalog-1"),
                Map.of("name", "generation", "description", "Generation", "id", "gen-1")
            ));

            cache.refreshCoreTools();

            assertThat(cache.activeCoreToolNames()).contains("generation");
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .contains("generation", "catalog");
            // Granting only the generation module hands the agent that tool and nothing else
            assertThat(cache.getCoreTools(Set.of("generation")).stream().map(ToolDefinition::name))
                .containsExactly("generation");
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
            // so its tools (workflow/application/web_search/files) never
            // land in the cache, while every other source loads fine. On the SECOND call
            // orchestrator is back and advertises its tools.
            when(restTemplate.exchange(eq(ORCH), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor()) // initial: orchestrator unreachable -> no tools
                .thenReturn(toolsFor("workflow", "application", "web_search", "files", "mailbox", "wait"));
            when(restTemplate.exchange(eq(AGENT), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("agent", "skill", "memory", "ask_user"));
            when(restTemplate.exchange(eq(DATASOURCE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("table"));
            when(restTemplate.exchange(eq(INTERFACE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("interface"));
            when(restTemplate.exchange(eq(CATALOG), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("catalog", "generation"));

            cache.refreshCoreTools();

            // Sanity: only orchestrator-owned tools are missing after the initial load.
            assertThat(cache.getMissingTools())
                .containsExactlyInAnyOrder("workflow", "application", "web_search", "files", "mailbox", "wait");
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .containsExactlyInAnyOrder("agent", "skill", "memory", "ask_user", "table", "interface", "catalog", "generation");

            // When: the 5-minute safety net runs.
            cache.scheduledRefreshIfIncomplete();

            // Then: the missing orchestrator tools are recovered...
            assertThat(cache.getMissingTools()).isEmpty();
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .containsExactlyInAnyOrder("catalog", "generation", "table", "interface", "agent", "skill", "memory", "ask_user",
                    "workflow", "application", "web_search", "files", "mailbox", "wait");
            // ...and the originally-loaded tools were preserved (the cache was NOT cleared,
            // unlike refreshCoreTools()), so no consumer ever sees them disappear.
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name))
                .contains("agent", "skill", "memory", "ask_user", "table", "interface", "catalog", "generation");

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
                .thenReturn(toolsFor("workflow", "application", "web_search", "files", "mailbox", "wait"));
            when(restTemplate.exchange(eq(AGENT), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("agent", "skill", "memory", "ask_user"));
            when(restTemplate.exchange(eq(DATASOURCE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("table"));
            when(restTemplate.exchange(eq(INTERFACE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("interface"));
            when(restTemplate.exchange(eq(CATALOG), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("catalog", "generation"));

            cache.refreshCoreTools();
            assertThat(cache.getMissingTools()).isEmpty();

            // When: the periodic tick runs with nothing missing.
            clearInvocations(restTemplate);
            cache.scheduledRefreshIfIncomplete();

            // Then: no source is contacted and the cache is untouched.
            verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class));
            assertThat(cache.getCoreTools()).hasSize(14);
        }

        /**
         * generation is owned by catalog-service, not orchestrator. If the periodic
         * refresh did not know that, a generation-only gap would re-query orchestrator
         * forever and the tool would never appear.
         */
        @Test
        @DisplayName("a missing generation re-queries catalog-service, the service that owns it")
        void periodicRefreshRecoversGenerationFromCatalogService() {
            // Given: everything loads except catalog-service, which is down at first.
            when(restTemplate.exchange(eq(ORCH), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("workflow", "application", "web_search", "files", "mailbox", "wait"));
            when(restTemplate.exchange(eq(AGENT), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("agent", "skill", "memory", "ask_user"));
            when(restTemplate.exchange(eq(DATASOURCE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("table"));
            when(restTemplate.exchange(eq(INTERFACE), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("interface"));
            when(restTemplate.exchange(eq(CATALOG), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(toolsFor("catalog"))            // initial: generation not served yet
                .thenReturn(toolsFor("catalog", "generation"));

            cache.refreshCoreTools();
            assertThat(cache.getMissingTools()).containsExactly("generation");

            // When: the safety net runs with only generation missing.
            cache.scheduledRefreshIfIncomplete();

            // Then: it is recovered, and ONLY its owning service was re-queried.
            assertThat(cache.getMissingTools()).isEmpty();
            assertThat(cache.getCoreTools().stream().map(ToolDefinition::name)).contains("generation");
            verify(restTemplate, times(2)).exchange(eq(CATALOG), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
            verify(restTemplate, times(1)).exchange(eq(ORCH), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
            verify(restTemplate, times(1)).exchange(eq(AGENT), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
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
