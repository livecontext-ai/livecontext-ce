package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ToolCard;
import com.apimarketplace.catalog.domain.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogV1Service")
class CatalogV1ServiceTest {

    @Mock
    private CatalogToolQueryService catalogToolQueryService;
    @Mock
    private ToolExecutionManager toolExecutionManager;
    @Mock
    private IntentResolutionManager intentResolutionManager;

    private CatalogV1Service catalogV1Service;

    @BeforeEach
    void setUp() {
        catalogV1Service = new CatalogV1Service(
                catalogToolQueryService,
                toolExecutionManager,
                intentResolutionManager
        );
    }

    @Nested
    @DisplayName("getTools")
    class GetToolsTests {

        @Test
        @DisplayName("should delegate to CatalogToolQueryService")
        void delegatesToQueryService() {
            ToolListResponse expectedResponse = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .limit(20)
                    .offset(0)
                    .build();

            when(catalogToolQueryService.getTools(eq(20), eq("analytics"), eq("search term"), any()))
                    .thenReturn(expectedResponse);

            ToolListResponse response = catalogV1Service.getTools(20, "analytics", "search term", "user123", "org456");

            assertThat(response).isEqualTo(expectedResponse);
            verify(catalogToolQueryService).getTools(eq(20), eq("analytics"), eq("search term"), any());
        }

        @Test
        @DisplayName("should pass limit correctly")
        void passesLimitCorrectly() {
            ToolListResponse mockResponse = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .limit(50)
                    .build();

            when(catalogToolQueryService.getTools(eq(50), isNull(), isNull(), any()))
                    .thenReturn(mockResponse);

            catalogV1Service.getTools(50, null, null, "user", "org");

            verify(catalogToolQueryService).getTools(eq(50), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("should handle null category and search")
        void handlesNullCategoryAndSearch() {
            ToolListResponse mockResponse = ToolListResponse.builder()
                    .tools(Collections.emptyList())
                    .total(0)
                    .build();

            when(catalogToolQueryService.getTools(anyInt(), isNull(), isNull(), any()))
                    .thenReturn(mockResponse);

            catalogV1Service.getTools(10, null, null, "user", "org");

            verify(catalogToolQueryService).getTools(eq(10), isNull(), isNull(), any());
        }

        @Test
        @DisplayName("should return tools when query service finds results")
        void returnsToolsWhenFound() {
            List<ToolCard> tools = List.of(
                    ToolCard.of("tool-1", "Description 1", "Instagram", "HIGH"),
                    ToolCard.of("tool-2", "Description 2", "Facebook", "MEDIUM")
            );

            ToolListResponse expectedResponse = ToolListResponse.builder()
                    .tools(tools)
                    .total(2)
                    .limit(20)
                    .offset(0)
                    .build();

            when(catalogToolQueryService.getTools(eq(20), isNull(), isNull(), any()))
                    .thenReturn(expectedResponse);

            ToolListResponse response = catalogV1Service.getTools(20, null, null, "user", "org");

            assertThat(response.getTools()).hasSize(2);
            assertThat(response.getTotal()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("executeTool")
    class ExecuteToolTests {

        @Test
        @DisplayName("should delegate to ToolExecutionManager with all parameters")
        void delegatesToExecutionManager() {
            String toolId = "test-api/test-tool";
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .parameters(Map.of("key", "value"))
                    .build();

            ToolExecutionResponse expectedResponse = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolId)
                    .requestId("req123")
                    .build();

            when(toolExecutionManager.executeTool(toolId, request, "user123", "org456", "req123"))
                    .thenReturn(expectedResponse);

            ToolExecutionResponse response = catalogV1Service.executeTool(
                    toolId, request, "user123", "org456", "req123"
            );

            assertThat(response).isEqualTo(expectedResponse);
            verify(toolExecutionManager).executeTool(toolId, request, "user123", "org456", "req123");
        }

        @Test
        @DisplayName("should handle UUID tool identifier")
        void handlesUuidToolId() {
            UUID toolId = UUID.randomUUID();
            String toolIdStr = toolId.toString();

            ToolExecutionResponse expectedResponse = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolIdStr)
                    .build();

            when(toolExecutionManager.executeTool(eq(toolIdStr), any(), any(), any(), any()))
                    .thenReturn(expectedResponse);

            ToolExecutionResponse response = catalogV1Service.executeTool(
                    toolIdStr, null, "user", "org", "req"
            );

            assertThat(response.getToolId()).isEqualTo(toolIdStr);
        }

        @Test
        @DisplayName("should handle slug tool identifier")
        void handlesSlugToolId() {
            String toolSlug = "my-api/get-users";

            ToolExecutionResponse expectedResponse = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(toolSlug)
                    .build();

            when(toolExecutionManager.executeTool(eq(toolSlug), any(), any(), any(), any()))
                    .thenReturn(expectedResponse);

            ToolExecutionResponse response = catalogV1Service.executeTool(
                    toolSlug, null, "user", "org", "req"
            );

            assertThat(response.isSuccess()).isTrue();
        }

        @Test
        @DisplayName("should pass null request when not provided")
        void passesNullRequest() {
            ToolExecutionResponse expectedResponse = ToolExecutionResponse.builder()
                    .success(true)
                    .build();

            when(toolExecutionManager.executeTool(anyString(), isNull(), any(), any(), any()))
                    .thenReturn(expectedResponse);

            catalogV1Service.executeTool("tool-id", null, "user", "org", "req");

            verify(toolExecutionManager).executeTool(anyString(), isNull(), any(), any(), any());
        }

        @Test
        @DisplayName("should return error response when execution fails")
        void returnsErrorOnFailure() {
            ToolExecutionResponse errorResponse = ToolExecutionResponse.builder()
                    .success(false)
                    .error("Tool execution failed")
                    .build();

            when(toolExecutionManager.executeTool(anyString(), any(), any(), any(), any()))
                    .thenReturn(errorResponse);

            ToolExecutionResponse response = catalogV1Service.executeTool(
                    "failing-tool", null, "user", "org", "req"
            );

            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("Tool execution failed");
        }
    }

    @Nested
    @DisplayName("resolveIntent")
    class ResolveIntentTests {

        @Test
        @DisplayName("should delegate to IntentResolutionManager")
        void delegatesToResolutionManager() {
            String query = "get user profile from instagram";
            int limit = 5;

            IntentResolutionResponse expectedResponse = IntentResolutionResponse.builder()
                    .query(query)
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();

            when(intentResolutionManager.resolve(query, limit))
                    .thenReturn(expectedResponse);

            IntentResolutionResponse response = catalogV1Service.resolveIntent(
                    query, limit, "user123", "org456"
            );

            assertThat(response).isEqualTo(expectedResponse);
            verify(intentResolutionManager).resolve(query, limit);
        }

        @Test
        @DisplayName("should return candidates when resolution finds matches")
        void returnsCandidatesWhenFound() {
            String query = "analytics";

            List<ToolCandidate> candidates = List.of(
                    ToolCandidate.builder()
                            .toolId(UUID.randomUUID().toString())
                            .name("Analytics Tool")
                            .confidence(0.95)
                            .build(),
                    ToolCandidate.builder()
                            .toolId(UUID.randomUUID().toString())
                            .name("Stats Tool")
                            .confidence(0.85)
                            .build()
            );

            IntentResolutionResponse expectedResponse = IntentResolutionResponse.builder()
                    .query(query)
                    .candidates(candidates)
                    .totalCandidates(2)
                    .build();

            when(intentResolutionManager.resolve(query, 5))
                    .thenReturn(expectedResponse);

            IntentResolutionResponse response = catalogV1Service.resolveIntent(
                    query, 5, "user", "org"
            );

            assertThat(response.getCandidates()).hasSize(2);
            assertThat(response.getTotalCandidates()).isEqualTo(2);
        }

        @Test
        @DisplayName("should pass correct limit to resolution manager")
        void passesCorrectLimit() {
            IntentResolutionResponse mockResponse = IntentResolutionResponse.builder()
                    .query("test")
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();

            when(intentResolutionManager.resolve(anyString(), eq(10)))
                    .thenReturn(mockResponse);

            catalogV1Service.resolveIntent("test", 10, "user", "org");

            verify(intentResolutionManager).resolve("test", 10);
        }

        @Test
        @DisplayName("should return empty candidates when no matches")
        void returnsEmptyWhenNoMatches() {
            IntentResolutionResponse emptyResponse = IntentResolutionResponse.builder()
                    .query("nonexistent query")
                    .candidates(Collections.emptyList())
                    .totalCandidates(0)
                    .build();

            when(intentResolutionManager.resolve(anyString(), anyInt()))
                    .thenReturn(emptyResponse);

            IntentResolutionResponse response = catalogV1Service.resolveIntent(
                    "nonexistent query", 5, "user", "org"
            );

            assertThat(response.getCandidates()).isEmpty();
            assertThat(response.getTotalCandidates()).isZero();
        }
    }

    @Nested
    @DisplayName("Integration scenarios")
    class IntegrationScenarios {

        @Test
        @DisplayName("should support full workflow: list -> resolve -> execute")
        void supportsFullWorkflow() {
            // 1. List tools
            ToolCard toolCard = ToolCard.of("Analytics", "Get analytics", "Instagram", "HIGH");
            ToolListResponse listResponse = ToolListResponse.builder()
                    .tools(List.of(toolCard))
                    .total(1)
                    .build();
            when(catalogToolQueryService.getTools(anyInt(), any(), any(), any()))
                    .thenReturn(listResponse);

            // 2. Resolve intent
            String resolvedToolId = UUID.randomUUID().toString();
            IntentResolutionResponse resolveResponse = IntentResolutionResponse.builder()
                    .query("get analytics")
                    .candidates(List.of(ToolCandidate.builder()
                            .toolId(resolvedToolId)
                            .name("Analytics")
                            .confidence(0.95)
                            .build()))
                    .totalCandidates(1)
                    .build();
            when(intentResolutionManager.resolve(anyString(), anyInt()))
                    .thenReturn(resolveResponse);

            // 3. Execute tool
            ToolExecutionResponse executeResponse = ToolExecutionResponse.builder()
                    .success(true)
                    .toolId(resolvedToolId)
                    .result(Map.of("views", 1000))
                    .build();
            when(toolExecutionManager.executeTool(anyString(), any(), any(), any(), any()))
                    .thenReturn(executeResponse);

            // Execute workflow
            ToolListResponse tools = catalogV1Service.getTools(10, null, null, "user", "org");
            assertThat(tools.getTools()).hasSize(1);

            IntentResolutionResponse resolved = catalogV1Service.resolveIntent("get analytics", 5, "user", "org");
            assertThat(resolved.getCandidates()).hasSize(1);

            ToolExecutionResponse executed = catalogV1Service.executeTool(
                    resolved.getCandidates().get(0).getToolId(),
                    ToolExecutionRequest.builder().build(),
                    "user", "org", "req"
            );
            assertThat(executed.isSuccess()).isTrue();
        }
    }
}
