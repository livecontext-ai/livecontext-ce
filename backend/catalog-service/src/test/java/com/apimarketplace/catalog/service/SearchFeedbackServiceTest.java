package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.dto.CapabilityCard;
import com.apimarketplace.catalog.util.SearchScoreClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SearchFeedbackService.
 *
 * SearchFeedbackService tracks and logs search feedback for analytics.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchFeedbackService")
class SearchFeedbackServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SearchFeedbackService service;

    @BeforeEach
    void setUp() {
        service = new SearchFeedbackService(jdbcTemplate);
    }

    // ========================================================================
    // recordSearch() tests
    // ========================================================================

    @Nested
    @DisplayName("recordSearch()")
    class RecordSearchTests {

        @Test
        @DisplayName("should insert feedback record and return UUID")
        void shouldInsertFeedbackRecordAndReturnUuid() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();
            feedback.query = "search tools";
            feedback.presentedToolIds = List.of(UUID.randomUUID(), UUID.randomUUID());
            feedback.presentedScores = List.of(0.95, 0.85);

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            UUID result = service.recordSearch(feedback);

            assertNotNull(result);
            verify(jdbcTemplate).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("should handle null presentedToolIds")
        void shouldHandleNullPresentedToolIds() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();
            feedback.query = "search";
            feedback.presentedToolIds = null;
            feedback.presentedScores = null;

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            UUID result = service.recordSearch(feedback);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should handle empty presentedToolIds")
        void shouldHandleEmptyPresentedToolIds() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();
            feedback.query = "search";
            feedback.presentedToolIds = List.of();
            feedback.presentedScores = List.of();

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            UUID result = service.recordSearch(feedback);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should throw RuntimeException when database fails")
        void shouldThrowRuntimeExceptionWhenDatabaseFails() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();
            feedback.query = "search";

            when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("DB error"));

            assertThrows(RuntimeException.class, () -> service.recordSearch(feedback));
        }

        @Test
        @DisplayName("should include all feedback fields in insert")
        void shouldIncludeAllFeedbackFieldsInInsert() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();
            feedback.query = "weather api";
            feedback.presentedToolIds = List.of(UUID.randomUUID());
            feedback.presentedScores = List.of(0.9);
            feedback.selectedToolId = UUID.randomUUID();
            feedback.selectionRank = 1;
            feedback.executionSuccess = true;
            feedback.executionError = null;
            feedback.extractedProvider = "openweather";
            feedback.extractedAction = "get";
            feedback.extractedResource = "weather";
            feedback.sessionId = "session-123";
            feedback.tenantId = "tenant-456";
            feedback.searchTimeMs = 50;
            feedback.rerankingTimeMs = 20;
            feedback.searchType = "hybrid";
            feedback.rerankingEnabled = true;
            feedback.autoPickTriggered = false;

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            UUID result = service.recordSearch(feedback);

            assertNotNull(result);
            verify(jdbcTemplate).update(anyString(), any(Object[].class));
        }
    }

    // ========================================================================
    // recordSearchAsync() tests
    // ========================================================================

    @Nested
    @DisplayName("recordSearchAsync()")
    class RecordSearchAsyncTests {

        @Test
        @DisplayName("should call recordSearch internally")
        void shouldCallRecordSearchInternally() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();
            feedback.query = "test";

            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // This is async but we can still verify the interaction
            service.recordSearchAsync(feedback);

            verify(jdbcTemplate, timeout(1000)).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("should handle exceptions gracefully")
        void shouldHandleExceptionsGracefully() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();
            feedback.query = "test";

            when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenThrow(new RuntimeException("DB error"));

            // Should not throw - exception is caught internally
            assertDoesNotThrow(() -> service.recordSearchAsync(feedback));
        }
    }

    // ========================================================================
    // fromSearchResults() static method tests
    // ========================================================================

    @Nested
    @DisplayName("fromSearchResults()")
    class FromSearchResultsTests {

        @Test
        @DisplayName("should create feedback from search results")
        void shouldCreateFeedbackFromSearchResults() {
            String toolId1 = UUID.randomUUID().toString();
            String toolId2 = UUID.randomUUID().toString();

            List<CapabilityCard> results = List.of(
                new CapabilityCard(toolId1, "Tool 1", "Provider1",
                    List.of("query"), Map.of(), false, 0.95,
                    SearchScoreClassifier.Quality.EXCELLENT, "Excellent", 95),
                new CapabilityCard(toolId2, "Tool 2", "Provider2",
                    List.of("query"), Map.of(), true, 0.85,
                    SearchScoreClassifier.Quality.VERY_GOOD, "Very Good", 85)
            );

            SearchFeedbackService.SearchFeedback feedback = SearchFeedbackService.fromSearchResults(
                "search query",
                results,
                "provider",
                "get",
                "resource",
                100L,
                true,
                false
            );

            assertEquals("search query", feedback.query);
            assertEquals(2, feedback.presentedToolIds.size());
            assertEquals(2, feedback.presentedScores.size());
            assertEquals(0.95, feedback.presentedScores.get(0));
            assertEquals(0.85, feedback.presentedScores.get(1));
            assertEquals("provider", feedback.extractedProvider);
            assertEquals("get", feedback.extractedAction);
            assertEquals("resource", feedback.extractedResource);
            assertEquals(100, feedback.searchTimeMs);
            assertEquals("hybrid", feedback.searchType);
            assertTrue(feedback.rerankingEnabled);
            assertFalse(feedback.autoPickTriggered);
        }

        @Test
        @DisplayName("should handle empty results")
        void shouldHandleEmptyResults() {
            SearchFeedbackService.SearchFeedback feedback = SearchFeedbackService.fromSearchResults(
                "query",
                List.of(),
                null,
                null,
                null,
                50L,
                false,
                true
            );

            assertEquals("query", feedback.query);
            assertTrue(feedback.presentedToolIds.isEmpty());
            assertTrue(feedback.presentedScores.isEmpty());
            assertNull(feedback.extractedProvider);
            assertFalse(feedback.rerankingEnabled);
            assertTrue(feedback.autoPickTriggered);
        }
    }

    // ========================================================================
    // SearchFeedback inner class tests
    // ========================================================================

    @Nested
    @DisplayName("SearchFeedback")
    class SearchFeedbackTests {

        @Test
        @DisplayName("should have default values")
        void shouldHaveDefaultValues() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();

            assertEquals("hybrid", feedback.searchType);
            assertEquals(false, feedback.rerankingEnabled);
            assertEquals(false, feedback.autoPickTriggered);
        }

        @Test
        @DisplayName("should allow setting all fields")
        void shouldAllowSettingAllFields() {
            SearchFeedbackService.SearchFeedback feedback = new SearchFeedbackService.SearchFeedback();

            feedback.query = "test query";
            feedback.presentedToolIds = List.of(UUID.randomUUID());
            feedback.presentedScores = List.of(0.9);
            feedback.selectedToolId = UUID.randomUUID();
            feedback.selectionRank = 1;
            feedback.executionSuccess = true;
            feedback.executionError = "error message";
            feedback.extractedProvider = "provider";
            feedback.extractedAction = "action";
            feedback.extractedResource = "resource";
            feedback.sessionId = "session";
            feedback.tenantId = "tenant";
            feedback.searchTimeMs = 100;
            feedback.rerankingTimeMs = 50;
            feedback.searchType = "lexical";
            feedback.rerankingEnabled = true;
            feedback.autoPickTriggered = true;

            assertEquals("test query", feedback.query);
            assertEquals("lexical", feedback.searchType);
            assertTrue(feedback.rerankingEnabled);
            assertTrue(feedback.autoPickTriggered);
        }
    }
}
