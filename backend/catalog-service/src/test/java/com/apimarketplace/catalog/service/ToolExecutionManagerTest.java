package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolExecutionManager")
class ToolExecutionManagerTest {

    @Mock
    private ToolContextService toolContextService;
    @Mock
    private ApiService apiService;
    @Mock
    private ResponseShaper responseShaper;
    @Mock
    private NextActionBuilder nextActionBuilder;
    @Mock
    private ResponseCache responseCache;
    @Mock
    private ToolNextHintRepository toolNextHintRepository;
    @Mock
    private ToolResponseService toolResponseService;
    @Mock
    private CredentialClient credentialClient;
    @Mock
    private com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay ceCatalogCloudRelay;

    private ToolExecutionManager executionManager;
    private ObjectMapper objectMapper;
    private com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator toolExecutionOrchestrator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        toolExecutionOrchestrator = new com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator(
            new com.apimarketplace.catalog.service.execution.OutputProjector(objectMapper),
            new com.apimarketplace.catalog.service.execution.BinaryResponseHandler(objectMapper),
            new com.apimarketplace.catalog.service.execution.MultipartBodyEncoder(objectMapper),
            new com.apimarketplace.catalog.service.execution.AsyncPollExecutor(new org.springframework.web.client.RestTemplate(), objectMapper),
            objectMapper);
        com.apimarketplace.catalog.service.execution.BinaryResponseHandler binaryResponseHandler =
            new com.apimarketplace.catalog.service.execution.BinaryResponseHandler(objectMapper);
        executionManager = new ToolExecutionManager(toolContextService, apiService, objectMapper, responseShaper, nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService, toolExecutionOrchestrator, binaryResponseHandler,
                /* catalogBillingService */ null, credentialClient, /* apiRepository */ null, ceCatalogCloudRelay);
        lenient().when(credentialClient.getCredentialStateVersion(anyString()))
            .thenReturn(CredentialClient.STATE_VERSION_UNAVAILABLE);
        // Default: relay does not apply - normal local path proceeds.
        lenient().when(ceCatalogCloudRelay.tryRelay(anyString(), any(), any(), any()))
            .thenReturn(Optional.empty());

        // Default mock behavior for shaper - pass through data as-is, no shaping action.
        lenient().when(responseShaper.shape(any(), any(), any(), any()))
            .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                    inv.getArgument(0),
                    java.util.List.of(),
                    ResponseShaper.Action.UNTOUCHED,
                    0, 0));
        // Default: no shaping-driven nextAction (fall through to DB hint).
        lenient().when(nextActionBuilder.build(any(), any(), any()))
            .thenReturn(java.util.Optional.empty());
    }

    @Nested
    @DisplayName("executeTool - Success Cases")
    class SuccessCases {

        @Test
        @DisplayName("should execute tool successfully with parameters")
        void executesToolSuccessfully() {
            String toolIdOrSlug = "test-api/test-tool";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "TestTool", "/api/test", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            Map<String, Object> executionResult = Map.of(
                    "success", true,
                    "data", Map.of("result", "test data"),
                    "status", 200
            );
            when(apiService.executeApiTool(eq(apiId.toString()), eq("TestTool"), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(executionResult);

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(Map.of("param1", "value1"))
                    .build();

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, request, "user123", "org456", "req789"
            );

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getToolId()).isEqualTo(toolId.toString());
            assertThat(response.getRequestId()).isEqualTo("req789");
            assertThat(response.getExecutionTimeMs()).isGreaterThanOrEqualTo(0);
            assertThat(response.getError()).isNull();
            assertThat(response.getMetadata())
                    .containsEntry("toolName", "TestTool")
                    .containsEntry("endpoint", "/api/test")
                    .containsEntry("method", "GET")
                    .containsEntry("apiId", apiId.toString());
        }

        @Test
        @DisplayName("should execute tool with empty parameters")
        void executesToolWithEmptyParameters() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "EmptyParamTool", "/api/empty", "POST");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(Map.of("success", true, "data", "ok"));

            ToolExecutionRequest request = ToolExecutionRequest.builder().build();

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, request, "user", "org", "req"
            );

            assertThat(response.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("should execute tool with null request")
        void executesToolWithNullRequest() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "NullReqTool", "/api/null", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(Map.of("success", true));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("should pass allowed parameter names to apiService")
        void passesAllowedParameterNames() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            Set<String> allowedParams = Set.of("param1", "param2", "param3");
            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "Tool", "/api", "GET");
            when(context.getAllowedParameterNames()).thenReturn(allowedParams);
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), eq(allowedParams), anyString()))
                    .thenReturn(Map.of("success", true));

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(Map.of("param1", "value"))
                    .build();

            executionManager.executeTool(toolIdOrSlug, request, "user", "org", "req");

            verify(apiService).executeApiTool(anyString(), anyString(), any(JsonNode.class), eq(allowedParams), anyString());
        }

        @Test
        @DisplayName("should convert parameters to correct JSON format")
        void convertsParametersToCorrectFormat() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "Tool", "/api", "POST");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            ArgumentCaptor<JsonNode> jsonCaptor = ArgumentCaptor.forClass(JsonNode.class);
            when(apiService.executeApiTool(anyString(), anyString(), jsonCaptor.capture(), anySet(), anyString()))
                    .thenReturn(Map.of("success", true));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", "John");
            params.put("age", 30);

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(params)
                    .build();

            executionManager.executeTool(toolIdOrSlug, request, "user", "org", "req");

            JsonNode capturedJson = jsonCaptor.getValue();
            assertThat(capturedJson.isArray()).isTrue();
            assertThat(capturedJson.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("executeTool - Error Cases")
    class ErrorCases {

        @Test
        @DisplayName("should throw ToolNotFoundException when tool not found")
        void throwsToolNotFoundWhenMissing() {
            String toolIdOrSlug = "non-existent-tool";
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    executionManager.executeTool(toolIdOrSlug, null, "user", "org", "req")
            )
                    .isInstanceOf(ToolNotFoundException.class)
                    .hasMessageContaining("non-existent-tool");
        }

        @Test
        @DisplayName("should return error response when API ID is missing")
        void returnsErrorWhenApiIdMissing() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();

            ToolContextService.ToolContext context = mock(ToolContextService.ToolContext.class);
            when(context.getApiId()).thenReturn(null);
            when(context.getToolId()).thenReturn(toolId.toString());
            when(context.getToolName()).thenReturn("TestTool");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).contains("API ID not found");
        }

        @Test
        @DisplayName("should return error response when API ID is blank")
        void returnsErrorWhenApiIdBlank() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();

            ToolContextService.ToolContext context = mock(ToolContextService.ToolContext.class);
            when(context.getApiId()).thenReturn("   ");
            when(context.getToolId()).thenReturn(toolId.toString());
            when(context.getToolName()).thenReturn("TestTool");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).contains("API ID not found");
        }

        @Test
        @DisplayName("should return error response when apiService throws exception")
        void returnsErrorWhenServiceFails() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "FailingTool", "/api/fail", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenThrow(new RuntimeException("Network error"));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("Network error");
            assertThat(response.getToolId()).isEqualTo(toolId.toString());
            assertThat(response.getMetadata()).containsEntry("toolName", "FailingTool");
        }

        @Test
        @DisplayName("should return error when execution result indicates failure")
        void returnsErrorWhenExecutionFails() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "Tool", "/api", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            Map<String, Object> failedResult = Map.of(
                    "success", false,
                    "error", "API returned 404",
                    "status", 404
            );
            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(failedResult);

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("API returned 404");
        }
    }

    @Nested
    @DisplayName("executeTool - Response Metadata")
    class ResponseMetadataTests {

        @Test
        @DisplayName("should include execution time in response")
        void includesExecutionTime() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "Tool", "/api", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenAnswer(invocation -> {
                        Thread.sleep(50); // Simulate some processing time
                        return Map.of("success", true);
                    });

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.getExecutionTimeMs()).isGreaterThanOrEqualTo(50);
        }

        @Test
        @DisplayName("should include result data when present")
        void includesResultData() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "Tool", "/api", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            Map<String, Object> resultData = Map.of("users", List.of("Alice", "Bob"));
            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(Map.of("success", true, "data", resultData));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.getResult()).isEqualTo(resultData);
        }

        @Test
        @DisplayName("should return full execution result when data is null")
        void returnsFullResultWhenDataNull() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "Tool", "/api", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            Map<String, Object> executionResult = Map.of("success", true, "status", 200);
            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(executionResult);

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.getResult()).isEqualTo(executionResult);
        }

        @Test
        @DisplayName("should include status in metadata")
        void includesStatusInMetadata() {
            String toolIdOrSlug = "tool-slug";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "Tool", "/api", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(Map.of("success", true, "status", 201));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, null, "user", "org", "req"
            );

            assertThat(response.getMetadata()).containsEntry("status", 201);
        }
    }

    private ToolContextService.ToolContext createToolContext(UUID toolId, UUID apiId, String toolName,
                                                              String endpoint, String httpMethod) {
        ToolContextService.ToolContext context = mock(ToolContextService.ToolContext.class);
        lenient().when(context.getToolId()).thenReturn(toolId.toString());
        lenient().when(context.getApiId()).thenReturn(apiId.toString());
        lenient().when(context.getToolName()).thenReturn(toolName);
        lenient().when(context.getEndpoint()).thenReturn(endpoint);
        lenient().when(context.getHttpMethod()).thenReturn(httpMethod);
        lenient().when(context.getAllowedParameterNames()).thenReturn(Set.of());
        return context;
    }

    /**
     * Regression: SerpAPI google_flights lost ALL params when optional fields
     * (stops, children) resolved to null at runtime. Null values were serialized
     * as {} (empty JSON objects) which crashed HttpExecutionService.processQueryParameters
     * (NoSuchElementException on fieldNames().next()). The exception was swallowed,
     * causing the entire parameter array to be lost.
     *
     * Fix: ToolExecutionManager now skips null values before serialization.
     */
    @Nested
    @DisplayName("null parameter filtering regression (SerpAPI google_flights)")
    class NullParameterFiltering {

        @Test
        @DisplayName("Null values in parameter map are excluded from serialized JsonNode array")
        void nullValuesExcludedFromJsonArray() {
            String toolIdOrSlug = "serpapi/google-flights";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "GoogleFlights", "/search", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            ArgumentCaptor<JsonNode> jsonCaptor = ArgumentCaptor.forClass(JsonNode.class);
            when(apiService.executeApiTool(anyString(), anyString(), jsonCaptor.capture(), anySet(), anyString()))
                    .thenReturn(Map.of("success", true, "data", Map.of()));

            // Simulate the exact production scenario: some params have values,
            // others resolve to null (optional fields not provided by agent).
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("engine", "google_flights");
            params.put("departure_id", "CDG");
            params.put("arrival_id", "DLA");
            params.put("stops", null);      // Optional - not provided
            params.put("children", null);   // Optional - not provided
            params.put("type", "1");

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(params)
                    .build();

            executionManager.executeTool(toolIdOrSlug, request, "user", "org", "req");

            JsonNode capturedJson = jsonCaptor.getValue();
            assertThat(capturedJson.isArray()).isTrue();
            // Only 4 non-null params should be serialized (engine, departure_id, arrival_id, type)
            assertThat(capturedJson.size()).isEqualTo(4);

            // Verify no entry is an empty object {}
            for (JsonNode entry : capturedJson) {
                assertThat(entry.fieldNames().hasNext())
                    .as("No entry should be an empty {} object")
                    .isTrue();
            }

            // Verify the actual params passed through
            Set<String> serializedKeys = new LinkedHashSet<>();
            for (JsonNode entry : capturedJson) {
                serializedKeys.add(entry.fieldNames().next());
            }
            assertThat(serializedKeys).containsExactlyInAnyOrder("engine", "departure_id", "arrival_id", "type");
        }

        @Test
        @DisplayName("All-null parameter map produces empty JsonNode array (no crash)")
        void allNullParametersProduceEmptyArray() {
            String toolIdOrSlug = "tool/all-null";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "AllNull", "/api", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            ArgumentCaptor<JsonNode> jsonCaptor = ArgumentCaptor.forClass(JsonNode.class);
            when(apiService.executeApiTool(anyString(), anyString(), jsonCaptor.capture(), anySet(), anyString()))
                    .thenReturn(Map.of("success", true));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("field_a", null);
            params.put("field_b", null);

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(params)
                    .build();

            executionManager.executeTool(toolIdOrSlug, request, "user", "org", "req");

            JsonNode capturedJson = jsonCaptor.getValue();
            assertThat(capturedJson.isArray()).isTrue();
            assertThat(capturedJson.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("Mixed null and non-null parameters preserve only non-null with correct values")
        void mixedParamsPreserveCorrectValues() {
            String toolIdOrSlug = "tool/mixed";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "Mixed", "/api", "POST");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            ArgumentCaptor<JsonNode> jsonCaptor = ArgumentCaptor.forClass(JsonNode.class);
            when(apiService.executeApiTool(anyString(), anyString(), jsonCaptor.capture(), anySet(), anyString()))
                    .thenReturn(Map.of("success", true));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("query", "test search");
            params.put("limit", 10);
            params.put("optional_filter", null);

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(params)
                    .build();

            executionManager.executeTool(toolIdOrSlug, request, "user", "org", "req");

            JsonNode capturedJson = jsonCaptor.getValue();
            assertThat(capturedJson.size()).isEqualTo(2);
            // Verify actual values are preserved
            assertThat(capturedJson.get(0).get("query").asText()).isEqualTo("test search");
            assertThat(capturedJson.get(1).get("limit").asInt()).isEqualTo(10);
        }
    }

    /**
     * Regression tests for the cache-hit path of the binary dehydration
     * pipeline. Iteration-4 audit identified two leaks here:
     *   (a) the cache-hit defensive sweep mutated resultData but didn't
     *       write back to responseCache, so a stale pre-dehydration entry
     *       caused fresh re-uploads on every cache hit forever;
     *   (b) {@code metadata.attachments[]} was empty on cache hits because
     *       the (no-op) second sweep produced no new assets, even though
     *       the cached tree carried FileRef Maps the agent should see.
     * Both are fixed by walking the FINAL tree to build {@code attachments}
     * and by writing back the cache when the hit-sweep actually dehydrated
     * stale b64.
     */
    @Nested
    @DisplayName("cache-hit binary dehydration regression")
    class CacheHitBinaryDehydration {

        @Test
        @DisplayName("metadata.attachments[] is populated on cache HIT when cached tree contains FileRef Maps (regression: was empty)")
        void attachmentsPopulatedOnCacheHit() {
            String toolIdOrSlug = "test-api/test-tool";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            ToolContextService.ToolContext context = createToolContext(
                toolId, apiId, "GenerateImage", "/v1/images", "POST");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            // Cached value already contains a FileRef Map (the agent's
            // EXPECTED state - the previous miss successfully dehydrated).
            Map<String, Object> cachedFileRef = Map.of(
                "_type", "file",
                "path", "tenant1/general/catalog-binary/abc.png",
                "name", "abc.png",
                "mimeType", "image/png",
                "size", 1_966_617L
            );
            Map<String, Object> cachedTree = Map.of(
                "candidates", List.of(Map.of(
                    "content", Map.of(
                        "parts", List.of(
                            Map.of("text", "Here you go"),
                            Map.of("inlineData", Map.of("data", cachedFileRef))
                        ))))
            );
            when(responseCache.get(anyString(), anyMap())).thenReturn(cachedTree);

            // billingScopeKind=STREAM: only chat-agent callers consult the
            // response cache; workflow callers (RUN scope) bypass it entirely
            // since the workflow restart that motivated this fix.
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("prompt", "x"))
                .billingScopeKind("STREAM")
                .build();

            ToolExecutionResponse response = executionManager.executeTool(
                toolIdOrSlug, request, "user-1", null, "req-1");

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMetadata()).containsKey("attachments");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> attachments =
                (List<Map<String, Object>>) response.getMetadata().get("attachments");
            assertThat(attachments)
                .as("Cache hit must surface FileRefs via metadata.attachments[] - agents iterate this list to cite assets")
                .hasSize(1);
            assertThat(attachments.get(0))
                .containsEntry("_type", "file")
                .containsEntry("path", "tenant1/general/catalog-binary/abc.png");
        }

        @Test
        @DisplayName("inlineBinaries=true skips dehydration on cache-hit path (workflow opt-out)")
        void inlineBinariesSkipsDehydrationOnCacheHit() {
            String toolIdOrSlug = "test-api/test-tool";
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            ToolContextService.ToolContext context = createToolContext(
                toolId, apiId, "Tool", "/v1/x", "POST");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            // Cached b64 string (legacy stale entry) - would normally be
            // re-dehydrated on hit, but the opt-out must skip the sweep.
            String b64 = "AAAAAAAAAAAAAAAAAAAAAAAA"; // tiny - won't trip threshold anyway
            Map<String, Object> cachedTree = Map.of("data", b64);
            when(responseCache.get(anyString(), anyMap())).thenReturn(cachedTree);

            // Chat-agent (STREAM) scope is required to reach the cache after
            // the workflow-bypass change; RUN/no-scope short-circuits before
            // the cache-hit dehydration branch this test guards.
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("prompt", "x"))
                .inlineBinaries(Boolean.TRUE)
                .billingScopeKind("STREAM")
                .build();

            ToolExecutionResponse response = executionManager.executeTool(
                toolIdOrSlug, request, "user-1", null, "req-2");

            assertThat(response.isSuccess()).isTrue();
            // The opt-out path must NOT call responseCache.put again - that
            // would hide a regression where the sweep ran despite opt-out.
            verify(responseCache, never()).put(anyString(), anyMap(), any());
        }
    }

    /**
     * Workflow-bypass regression - locks the rule that the 5-min response
     * cache is scoped to chat-agent (STREAM) callers only.
     *
     * <p>Origin: Gmail Auto-Labeler workflow fired twice within 30s (15:44:11
     * → 15:44:40). Second fire's {@code fetch_emails} returned the first
     * fire's cached IDs (4 emails, identical list) → the agent re-classified
     * + re-labelled the same messages, double-charging LLM and Gmail quotas.
     * The cache key {@code toolId + userId + params} collided because cron
     * filter params don't change between fires within the TTL window.
     *
     * <p>Fix: cache HIT/PUT only when {@code billingScopeKind == "STREAM"}.
     * Workflow callers (scope=RUN via {@code CatalogToolsGateway}, or no
     * scope at all for internal/test fixtures) always make a fresh upstream
     * call. The chat-agent expand-pattern that motivated the cache remains
     * intact for STREAM-scoped flows.
     */
    @Nested
    @DisplayName("workflow cache bypass - scope=RUN/null skips ResponseCache, scope=STREAM uses it")
    class WorkflowCacheBypass {

        private final String toolIdOrSlug = "gmail/list-messages";

        @org.junit.jupiter.api.BeforeEach
        void stubSuccessfulExecution() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            ToolContextService.ToolContext context = createToolContext(
                toolId, apiId, "ListMessages", "/gmail/v1/users/me/messages", "GET");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));
            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(Map.of("success", true, "data", Map.of("messages", List.of())));
        }

        @Test
        @DisplayName("scope=RUN (workflow caller) - cache.get() and cache.put() are NEVER invoked")
        void workflowRunScopeBypassesCacheEntirely() {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(Map.of("q", "newer_than:1h is:unread"))
                    .billingScopeKind("RUN")
                    .billingScopeId("workflow-run-uuid")
                    .build();

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, request, "user-1", null, "req-1");

            assertThat(response.isSuccess()).isTrue();
            // The original Gmail bug: 2nd fire HIT the cache and returned the
            // 1st fire's tree. With bypass, get() is never even consulted.
            verify(responseCache, never()).get(anyString(), anyMap());
            verify(responseCache, never()).put(anyString(), anyMap(), any());
            // And the upstream API IS hit (no cache short-circuit).
            verify(apiService).executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString());
        }

        @Test
        @DisplayName("scope=null (internal/test caller) - cache bypassed, defaults to workflow semantics")
        void nullScopeBypassesCache() {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(Map.of("q", "x"))
                    // No billingScopeKind - falls through to workflow default.
                    .build();

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, request, "user-1", null, "req-2");

            assertThat(response.isSuccess()).isTrue();
            verify(responseCache, never()).get(anyString(), anyMap());
            verify(responseCache, never()).put(anyString(), anyMap(), any());
        }

        @Test
        @DisplayName("scope=STREAM (chat-agent caller) - cache.get() consulted and cache.put() populated on miss")
        void streamScopeStillUsesCache() {
            when(responseCache.get(anyString(), anyMap())).thenReturn(null); // miss path

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(Map.of("q", "x"))
                    .billingScopeKind("STREAM")
                    .billingScopeId("stream-id")
                    .build();

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, request, "user-1", null, "req-3");

            assertThat(response.isSuccess()).isTrue();
            verify(responseCache).get(anyString(), anyMap());
            verify(responseCache).put(anyString(), anyMap(), any());
        }

        @Test
        @DisplayName("scope=stream (lowercase) - match is case-insensitive, cache still used")
        void streamScopeMatchIsCaseInsensitive() {
            when(responseCache.get(anyString(), anyMap())).thenReturn(null);

            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(Map.of("q", "x"))
                    .billingScopeKind("stream") // header canonicalisation defence
                    .build();

            executionManager.executeTool(toolIdOrSlug, request, "user-1", null, "req-4");

            verify(responseCache).get(anyString(), anyMap());
        }
    }

    /**
     * CE cloud relay branch: when the relay applies (platform credentialSource, no local
     * platform credential, cloud-linked install with CLOUD catalog source), the relayed
     * response IS the tool result - no local execution, no local billing. When the relay
     * does not apply (empty), the local path runs unchanged.
     */
    @Nested
    @DisplayName("CE cloud relay branch")
    class CeCloudRelayBranch {

        private final String toolIdOrSlug = "telegram/send-message";

        @Test
        @DisplayName("relay returns a response - manager returns it WITHOUT executing apiService or billing")
        void relayedResponseShortCircuitsLocalExecution() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "SendMessage", "/send", "POST");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            ToolExecutionResponse relayed = ToolExecutionResponse.builder()
                    .success(true)
                    .result(Map.of("ok", true))
                    .toolId(toolId.toString())
                    .requestId("req-relay")
                    .build();
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .credentialSource("platform")
                    .parameters(Map.of("chat_id", "1"))
                    .build();
            when(ceCatalogCloudRelay.tryRelay(toolIdOrSlug, request, "user-1", "req-relay"))
                    .thenReturn(Optional.of(relayed));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug, request, "user-1", null, "req-relay");

            assertThat(response).isSameAs(relayed);
            verifyNoInteractions(apiService);
            verify(responseCache, never()).get(anyString(), anyMap());
            verify(responseCache, never()).put(anyString(), anyMap(), any());
        }

        @Test
        @DisplayName("relay returns a FAILED response - still terminal, no local fallback execution")
        void relayedFailureIsTerminal() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "SendMessage", "/send", "POST");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));

            ToolExecutionResponse relayedFailure = ToolExecutionResponse.builder()
                    .success(false)
                    .error("Platform credentials via LiveContext Cloud require an active subscription on the linked cloud account.")
                    .build();
            when(ceCatalogCloudRelay.tryRelay(eq(toolIdOrSlug), any(), anyString(), anyString()))
                    .thenReturn(Optional.of(relayedFailure));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug,
                    ToolExecutionRequest.builder().credentialSource("platform").build(),
                    "user-1", null, "req-x");

            assertThat(response).isSameAs(relayedFailure);
            verifyNoInteractions(apiService);
        }

        @Test
        @DisplayName("relay returns empty - the normal local execution path proceeds")
        void emptyRelayFallsThroughToLocalPath() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            ToolContextService.ToolContext context = createToolContext(toolId, apiId, "SendMessage", "/send", "POST");
            when(toolContextService.loadToolContext(toolIdOrSlug)).thenReturn(Optional.of(context));
            when(ceCatalogCloudRelay.tryRelay(anyString(), any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                    .thenReturn(Map.of("success", true, "data", Map.of("ok", true)));

            ToolExecutionResponse response = executionManager.executeTool(
                    toolIdOrSlug,
                    ToolExecutionRequest.builder().credentialSource("platform").build(),
                    "user-1", null, "req-y");

            assertThat(response.isSuccess()).isTrue();
            verify(apiService).executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString());
        }

        @Test
        @DisplayName("unknown tool still throws ToolNotFoundException locally - relay never consulted")
        void unknownToolStillThrowsLocally() {
            when(toolContextService.loadToolContext("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> executionManager.executeTool(
                    "nope",
                    ToolExecutionRequest.builder().credentialSource("platform").build(),
                    "user-1", null, "req-z"))
                    .isInstanceOf(ToolNotFoundException.class);
            verifyNoInteractions(ceCatalogCloudRelay);
        }
    }
}
