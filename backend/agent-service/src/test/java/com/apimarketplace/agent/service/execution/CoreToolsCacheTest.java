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
}
