package com.apimarketplace.agent.tool;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for CatalogToolDiscoveryService - discovers tools via catalog-service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogToolDiscoveryService")
class CatalogToolDiscoveryServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private CatalogToolDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new CatalogToolDiscoveryService(restTemplate, new ObjectMapper());
        ReflectionTestUtils.setField(service, "catalogServiceUrl", "http://localhost:8081");
        ReflectionTestUtils.setField(service, "defaultMinScore", 0.02);
    }

    @Nested
    @DisplayName("findRelevantTools()")
    class FindRelevantToolsTests {

        @Test
        @DisplayName("should return tools from search response")
        void shouldReturnToolsFromSearch() {
            Map<String, Object> tool1 = Map.of(
                    "id", "tool-1",
                    "name", "search_api",
                    "description", "Search API",
                    "apiSlug", "search",
                    "toolSlug", "query",
                    "score", 0.85
            );

            Map<String, Object> responseBody = Map.of(
                    "tools", List.of(tool1),
                    "count", 1,
                    "matchType", "full_text"
            );

            ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("search", 5, 0.01);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("search_api");
            assertThat(result.get(0).description()).isEqualTo("Search API");
        }

        @Test
        @DisplayName("should return empty list on HTTP error")
        void shouldReturnEmptyOnError() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            List<ToolDefinition> result = service.findRelevantTools("search", 5, 0.01);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty list on non-OK status")
        void shouldReturnEmptyOnNonOk() {
            ResponseEntity<Map> response = new ResponseEntity<>(null, HttpStatus.INTERNAL_SERVER_ERROR);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("search", 5, 0.01);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should filter tools below min score")
        void shouldFilterBelowMinScore() {
            Map<String, Object> highScore = Map.of(
                    "name", "good_tool",
                    "description", "Good tool",
                    "score", 0.85
            );
            Map<String, Object> lowScore = Map.of(
                    "name", "bad_tool",
                    "description", "Bad tool",
                    "score", 0.001
            );

            Map<String, Object> responseBody = Map.of(
                    "tools", List.of(highScore, lowScore)
            );

            ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("test", 10, 0.01);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("good_tool");
        }

        @Test
        @DisplayName("should limit results to maxTools")
        void shouldLimitToMaxTools() {
            List<Map<String, Object>> tools = List.of(
                    Map.of("name", "tool1", "description", "T1", "score", 0.9),
                    Map.of("name", "tool2", "description", "T2", "score", 0.8),
                    Map.of("name", "tool3", "description", "T3", "score", 0.7)
            );

            Map<String, Object> responseBody = Map.of("tools", tools);
            ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("test", 2, 0.01);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should handle empty tools array in response")
        void shouldHandleEmptyToolsArray() {
            Map<String, Object> responseBody = Map.of("tools", List.of());
            ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("test", 5, 0.01);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should fallback to 'results' key if 'tools' not present")
        void shouldFallbackToResultsKey() {
            Map<String, Object> responseBody = Map.of(
                    "results", List.of(Map.of("name", "found_tool", "description", "Found", "score", 0.5))
            );
            ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("test", 5, 0.01);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("found_tool");
        }

        @Test
        @DisplayName("should parse tools with parameters")
        void shouldParseToolsWithParameters() {
            Map<String, Object> paramMap = Map.of(
                    "name", "query",
                    "type", "string",
                    "description", "Search query",
                    "required", true
            );

            Map<String, Object> tool = Map.of(
                    "name", "search",
                    "description", "Search API",
                    "score", 0.8,
                    "parameters", List.of(paramMap)
            );

            Map<String, Object> responseBody = Map.of("tools", List.of(tool));
            ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("search", 5, 0.01);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).parameters()).hasSize(1);
            assertThat(result.get(0).parameters().get(0).name()).isEqualTo("query");
            assertThat(result.get(0).parameters().get(0).required()).isTrue();
            assertThat(result.get(0).requiredParameters()).contains("query");
        }

        @Test
        @DisplayName("should skip tools with blank name")
        void shouldSkipToolsWithBlankName() {
            Map<String, Object> tool = Map.of(
                    "name", "",
                    "description", "No name",
                    "score", 0.9
            );

            Map<String, Object> responseBody = Map.of("tools", List.of(tool));
            ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("test", 5, 0.01);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should use rrfScore when score not available")
        void shouldUseRrfScore() {
            Map<String, Object> tool = Map.of(
                    "name", "tool1",
                    "description", "Test",
                    "rrfScore", 0.5
            );

            Map<String, Object> responseBody = Map.of("tools", List.of(tool));
            ResponseEntity<Map> response = new ResponseEntity<>(responseBody, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.findRelevantTools("test", 5, 0.01);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("getAllTools()")
    class GetAllToolsTests {

        @Test
        @DisplayName("should fetch all tools for tenant")
        void shouldFetchAllTools() {
            List<Map<String, Object>> toolsList = List.of(
                    Map.of("id", "1", "name", "tool_a", "description", "Tool A"),
                    Map.of("id", "2", "name", "tool_b", "description", "Tool B")
            );

            ResponseEntity<List> response = new ResponseEntity<>(toolsList, HttpStatus.OK);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(List.class)))
                    .thenReturn(response);

            List<ToolDefinition> result = service.getAllTools("tenant-1");

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("should return empty list on error")
        void shouldReturnEmptyOnError() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(List.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            List<ToolDefinition> result = service.getAllTools("tenant-1");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getToolById()")
    class GetToolByIdTests {

        @Test
        @DisplayName("should fetch tool from catalog service")
        void shouldFetchTool() {
            Map<String, Object> toolData = Map.of(
                    "id", "tool-123",
                    "name", "my_tool",
                    "description", "A test tool"
            );

            ResponseEntity<Map> response = new ResponseEntity<>(toolData, HttpStatus.OK);
            when(restTemplate.getForEntity(anyString(), eq(Map.class))).thenReturn(response);

            ToolDefinition result = service.getToolById("tool-123");

            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("my_tool");
        }

        @Test
        @DisplayName("should use cache on second call")
        void shouldUseCacheOnSecondCall() {
            Map<String, Object> toolData = Map.of(
                    "id", "tool-123",
                    "name", "cached_tool",
                    "description", "Cached"
            );

            ResponseEntity<Map> response = new ResponseEntity<>(toolData, HttpStatus.OK);
            when(restTemplate.getForEntity(anyString(), eq(Map.class))).thenReturn(response);

            // First call
            ToolDefinition result1 = service.getToolById("tool-123");
            // Second call should use cache
            ToolDefinition result2 = service.getToolById("tool-123");

            assertThat(result1).isNotNull();
            assertThat(result2).isNotNull();

            // RestTemplate should only be called once
            verify(restTemplate, times(1)).getForEntity(anyString(), eq(Map.class));
        }

        @Test
        @DisplayName("should return null on error")
        void shouldReturnNullOnError() {
            when(restTemplate.getForEntity(anyString(), eq(Map.class)))
                    .thenThrow(new RuntimeException("Not found"));

            ToolDefinition result = service.getToolById("unknown");

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getToolByName()")
    class GetToolByNameTests {

        @Test
        @DisplayName("should return null when not in cache")
        void shouldReturnNullWhenNotCached() {
            ToolDefinition result = service.getToolByName("unknown_tool");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return cached tool found via ID lookup")
        void shouldReturnCachedTool() {
            // First populate cache by getting tool by ID
            Map<String, Object> toolData = Map.of(
                    "id", "tool-123",
                    "name", "my_tool",
                    "description", "A test tool"
            );

            ResponseEntity<Map> response = new ResponseEntity<>(toolData, HttpStatus.OK);
            when(restTemplate.getForEntity(anyString(), eq(Map.class))).thenReturn(response);

            service.getToolById("tool-123");

            // Now getToolByName should find it in cache
            ToolDefinition result = service.getToolByName("my_tool");
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("my_tool");
        }
    }
}
