package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ToolSignalEntity;
import com.apimarketplace.catalog.dto.LexicalCard;
import com.apimarketplace.catalog.dto.LexicalRequest;
import com.apimarketplace.catalog.dto.LexicalResponse;
import com.apimarketplace.catalog.repository.LexicalSearchIndexRepository;
import com.apimarketplace.catalog.repository.ToolSignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for LexicalSearchService.
 *
 * LexicalSearchService performs lexical (BM25-based) searches for tools
 * with optimized batch loading to avoid N+1 queries.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LexicalSearchService")
class LexicalSearchServiceTest {

    @Mock
    private LexicalSearchIndexRepository lexicalSearchIndexRepository;

    @Mock
    private ToolSignalRepository toolSignalRepository;

    private LexicalSearchService service;

    @BeforeEach
    void setUp() {
        service = new LexicalSearchService(lexicalSearchIndexRepository, toolSignalRepository);
    }

    // ========================================================================
    // search tests
    // ========================================================================

    @Nested
    @DisplayName("search()")
    class SearchTests {

        @Test
        @DisplayName("should return empty response when no results found")
        void shouldReturnEmptyResponseWhenNoResultsFound() {
            LexicalRequest request = new LexicalRequest("query", 10, null);
            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

            LexicalResponse response = service.search(request);

            assertNotNull(response);
            assertTrue(response.cards().isEmpty());
            assertEquals(0, response.meta().get("total_results"));
        }

        @Test
        @DisplayName("should return results with tool signals")
        void shouldReturnResultsWithToolSignals() {
            UUID toolId = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("search query", 10, null);

            Map<String, Object> rawResult = createRawResult(toolId, 0.85);

            ToolSignalEntity signal = new ToolSignalEntity();
            signal.setToolId(toolId);
            signal.setProvider("gmail");
            signal.setResource("message");
            signal.setAction("list");
            signal.setRequiresUserCredentials(false);

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(rawResult));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(signal));

            LexicalResponse response = service.search(request);

            assertNotNull(response);
            assertEquals(1, response.cards().size());

            LexicalCard card = response.cards().get(0);
            assertEquals(toolId.toString(), card.toolId());
            assertEquals("gmail", card.provider());
            assertEquals("message", card.resource());
            assertEquals("list", card.action());
            assertEquals(0.85, card.bm25Score(), 0.001);
        }

        @Test
        @DisplayName("should fall back to lexical index data when no signal found")
        void shouldFallBackToLexicalIndexDataWhenNoSignalFound() {
            UUID toolId = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, null);

            Map<String, Object> rawResult = createRawResult(toolId, 0.75);
            rawResult.put("provider", "slack");
            rawResult.put("resource", "channel");
            rawResult.put("action", "create");

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(rawResult));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(Collections.emptyList());

            LexicalResponse response = service.search(request);

            assertEquals(1, response.cards().size());
            LexicalCard card = response.cards().get(0);
            assertEquals("slack", card.provider());
            assertEquals("channel", card.resource());
            assertEquals("create", card.action());
        }

        @Test
        @DisplayName("should filter results based on hints")
        void shouldFilterResultsBasedOnHints() {
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, Map.of("provider", "gmail"));

            Map<String, Object> rawResult1 = createRawResult(toolId1, 0.9);
            Map<String, Object> rawResult2 = createRawResult(toolId2, 0.8);

            ToolSignalEntity signal1 = createSignal(toolId1, "gmail", "message", "list");
            ToolSignalEntity signal2 = createSignal(toolId2, "slack", "channel", "list");

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(rawResult1, rawResult2));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(signal1, signal2));

            LexicalResponse response = service.search(request);

            // Only gmail result should pass hints filter
            assertEquals(1, response.cards().size());
            assertEquals("gmail", response.cards().get(0).provider());
        }

        @Test
        @DisplayName("should sort results by BM25 score descending")
        void shouldSortResultsByBm25ScoreDescending() {
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();
            UUID toolId3 = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, null);

            Map<String, Object> rawResult1 = createRawResult(toolId1, 0.5);
            Map<String, Object> rawResult2 = createRawResult(toolId2, 0.9);
            Map<String, Object> rawResult3 = createRawResult(toolId3, 0.7);

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(rawResult1, rawResult2, rawResult3));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(
                    createSignal(toolId1, "a", "a", "a"),
                    createSignal(toolId2, "b", "b", "b"),
                    createSignal(toolId3, "c", "c", "c")
                ));

            LexicalResponse response = service.search(request);

            assertEquals(3, response.cards().size());
            assertTrue(response.cards().get(0).bm25Score() > response.cards().get(1).bm25Score());
            assertTrue(response.cards().get(1).bm25Score() > response.cards().get(2).bm25Score());
        }

        @Test
        @DisplayName("should use default k when not specified")
        void shouldUseDefaultKWhenNotSpecified() {
            LexicalRequest request = new LexicalRequest("query", null, null);
            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), eq(12), any()))
                .thenReturn(Collections.emptyList());

            service.search(request);

            verify(lexicalSearchIndexRepository).searchWithFilters(anyString(), any(), any(), any(), eq(12), any());
        }

        @Test
        @DisplayName("should include metadata in response")
        void shouldIncludeMetadataInResponse() {
            LexicalRequest request = new LexicalRequest("test query", 5, null);
            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

            LexicalResponse response = service.search(request);

            Map<String, Object> meta = response.meta();
            assertEquals("test query", meta.get("query"));
            assertEquals(5, meta.get("k"));
            assertEquals(0, meta.get("total_results"));
            assertEquals(0, meta.get("filtered_results"));
            assertEquals("lexical", meta.get("search_type"));
            assertNotNull(meta.get("processing_time_ms"));
            assertNotNull(meta.get("timestamp"));
        }

        @Test
        @DisplayName("should handle search repository exception gracefully")
        void shouldHandleSearchRepositoryExceptionGracefully() {
            LexicalRequest request = new LexicalRequest("query", 10, null);
            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("Database error"));

            LexicalResponse response = service.search(request);

            assertNotNull(response);
            assertTrue(response.cards().isEmpty());
        }

        @Test
        @DisplayName("should use tool name from search result when available")
        void shouldUseToolNameFromSearchResultWhenAvailable() {
            UUID toolId = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, null);

            Map<String, Object> rawResult = createRawResult(toolId, 0.8);
            rawResult.put("tool_name", "List Gmail Messages");

            ToolSignalEntity signal = createSignal(toolId, "gmail", "message", "list");

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(rawResult));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(signal));

            LexicalResponse response = service.search(request);

            assertEquals("List Gmail Messages", response.cards().get(0).displayName());
        }

        @Test
        @DisplayName("should build display name from provider/resource/action when no tool name")
        void shouldBuildDisplayNameFromProviderResourceActionWhenNoToolName() {
            UUID toolId = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, null);

            Map<String, Object> rawResult = createRawResult(toolId, 0.8);
            rawResult.put("tool_name", "Unknown Tool");

            ToolSignalEntity signal = createSignal(toolId, "gmail", "message", "list");

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(rawResult));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(signal));

            LexicalResponse response = service.search(request);

            assertEquals("gmail message list", response.cards().get(0).displayName());
        }

        @Test
        @DisplayName("should set requiresCredentials flag from signal")
        void shouldSetRequiresCredentialsFlagFromSignal() {
            UUID toolId = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, null);

            Map<String, Object> rawResult = createRawResult(toolId, 0.8);

            ToolSignalEntity signal = createSignal(toolId, "gmail", "message", "list");
            signal.setRequiresUserCredentials(true);

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(rawResult));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(signal));

            LexicalResponse response = service.search(request);

            assertTrue(response.cards().get(0).requiresCredentials());
        }
    }

    // ========================================================================
    // searchForFusion tests
    // ========================================================================

    @Nested
    @DisplayName("searchForFusion()")
    class SearchForFusionTests {

        @Test
        @DisplayName("should return list of tool scores for fusion")
        void shouldReturnListOfToolScoresForFusion() {
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();

            List<Map<String, Object>> rows = List.of(
                Map.of("tool_id", toolId1.toString(), "bm25", 0.9),
                Map.of("tool_id", toolId2.toString(), "bm25", 0.8)
            );

            when(lexicalSearchIndexRepository.searchOptimized(anyString(), anyInt(), any())).thenReturn(rows);

            List<Map<UUID, Double>> result = service.searchForFusion("query", 10);

            assertEquals(2, result.size());
            assertEquals(0.9, result.get(0).get(toolId1), 0.001);
            assertEquals(0.8, result.get(1).get(toolId2), 0.001);
        }

        @Test
        @DisplayName("should return empty list when no results")
        void shouldReturnEmptyListWhenNoResults() {
            when(lexicalSearchIndexRepository.searchOptimized(anyString(), anyInt(), any()))
                .thenReturn(Collections.emptyList());

            List<Map<UUID, Double>> result = service.searchForFusion("query", 10);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle null BM25 scores")
        void shouldHandleNullBm25Scores() {
            UUID toolId = UUID.randomUUID();
            List<Map<String, Object>> rows = List.of(
                Map.of("tool_id", toolId.toString(), "bm25", 0)
            );

            when(lexicalSearchIndexRepository.searchOptimized(anyString(), anyInt(), any())).thenReturn(rows);

            List<Map<UUID, Double>> result = service.searchForFusion("query", 10);

            assertEquals(1, result.size());
            assertEquals(0.0, result.get(0).get(toolId), 0.001);
        }
    }

    // ========================================================================
    // matchesHints tests (indirectly tested via search)
    // ========================================================================

    @Nested
    @DisplayName("Hints matching")
    class HintsMatchingTests {

        @Test
        @DisplayName("should match when no hints provided")
        void shouldMatchWhenNoHintsProvided() {
            UUID toolId = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, null);

            Map<String, Object> rawResult = createRawResult(toolId, 0.8);
            ToolSignalEntity signal = createSignal(toolId, "any", "any", "any");

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(rawResult));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(signal));

            LexicalResponse response = service.search(request);

            assertEquals(1, response.cards().size());
        }

        @Test
        @DisplayName("should filter by action hint")
        void shouldFilterByActionHint() {
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, Map.of("action", "list"));

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(createRawResult(toolId1, 0.9), createRawResult(toolId2, 0.8)));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(
                    createSignal(toolId1, "gmail", "message", "list"),
                    createSignal(toolId2, "gmail", "message", "create")
                ));

            LexicalResponse response = service.search(request);

            assertEquals(1, response.cards().size());
            assertEquals("list", response.cards().get(0).action());
        }

        @Test
        @DisplayName("should filter by resource hint")
        void shouldFilterByResourceHint() {
            UUID toolId1 = UUID.randomUUID();
            UUID toolId2 = UUID.randomUUID();
            LexicalRequest request = new LexicalRequest("query", 10, Map.of("resource", "message"));

            when(lexicalSearchIndexRepository.searchWithFilters(anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(List.of(createRawResult(toolId1, 0.9), createRawResult(toolId2, 0.8)));
            when(toolSignalRepository.findByToolIdIn(anySet()))
                .thenReturn(List.of(
                    createSignal(toolId1, "gmail", "message", "list"),
                    createSignal(toolId2, "gmail", "calendar", "list")
                ));

            LexicalResponse response = service.search(request);

            assertEquals(1, response.cards().size());
            assertEquals("message", response.cards().get(0).resource());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private Map<String, Object> createRawResult(UUID toolId, double bm25) {
        Map<String, Object> result = new HashMap<>();
        result.put("tool_id", toolId.toString());
        result.put("bm25", bm25);
        result.put("tool_name", null);
        result.put("provider", null);
        result.put("resource", null);
        result.put("action", null);
        return result;
    }

    private ToolSignalEntity createSignal(UUID toolId, String provider, String resource, String action) {
        ToolSignalEntity signal = new ToolSignalEntity();
        signal.setToolId(toolId);
        signal.setProvider(provider);
        signal.setResource(resource);
        signal.setAction(action);
        signal.setRequiresUserCredentials(false);
        return signal;
    }
}
