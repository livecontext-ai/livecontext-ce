package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiCategoryEntity;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiSubcategoryEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AiDescriptionEnrichmentService.
 *
 * AiDescriptionEnrichmentService enriches tool descriptions using AI
 * and optimizes them for RRF search.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiDescriptionEnrichmentService")
class AiDescriptionEnrichmentServiceTest {

    @Mock
    private ApiToolRepository apiToolRepository;

    @Mock
    private LexicalIndexSyncService lexicalIndexSyncService;

    @Mock
    private ToolDescriptionGeneratorService toolDescriptionGeneratorService;

    @Mock
    private DeepInfraDescriptionService deepInfraDescriptionService;

    @Mock
    private SynthesisQualityValidator synthesisQualityValidator;

    @Mock
    private ApiToolParameterRepository apiToolParameterRepository;

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiCategoryRepository apiCategoryRepository;

    @Mock
    private ApiSubcategoryRepository apiSubcategoryRepository;

    @Mock
    private ToolCategoryService toolCategoryService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private AiDescriptionEnrichmentService aiDescriptionEnrichmentService;

    private UUID testToolId;
    private ApiToolEntity testTool;

    @BeforeEach
    void setUp() {
        testToolId = UUID.randomUUID();
        testTool = createTestTool(testToolId);
    }

    // ========================================================================
    // enrichToolDescription tests
    // ========================================================================

    @Nested
    @DisplayName("enrichToolDescription")
    class EnrichToolDescriptionTests {

        @Test
        @DisplayName("should throw exception when tool not found")
        void shouldThrowExceptionWhenToolNotFound() {
            // Arrange
            when(apiToolRepository.findById(testToolId)).thenReturn(Optional.empty());

            // Act & Assert
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                aiDescriptionEnrichmentService.enrichToolDescription(testToolId)
            );
            assertTrue(exception.getMessage().contains("Tool not found"));
        }

        @Test
        @DisplayName("should throw exception when AI synthesis fails")
        void shouldThrowExceptionWhenAiSynthesisFails() {
            // Arrange
            when(apiToolRepository.findById(testToolId)).thenReturn(Optional.of(testTool));
            when(toolDescriptionGeneratorService.generateEnrichedDescription(testToolId))
                    .thenReturn("Basic description");
            when(deepInfraDescriptionService.generateAISummaryAndAction(eq(testToolId), anyString()))
                    .thenThrow(new RuntimeException("AI service error"));

            // Act & Assert
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                aiDescriptionEnrichmentService.enrichToolDescription(testToolId)
            );
            assertTrue(exception.getMessage().contains("AI synthesis generation failed"));
        }

        @Test
        @DisplayName("should throw exception when AI returns invalid JSON")
        void shouldThrowExceptionWhenAiReturnsInvalidJson() {
            // Arrange
            when(apiToolRepository.findById(testToolId)).thenReturn(Optional.of(testTool));
            when(toolDescriptionGeneratorService.generateEnrichedDescription(testToolId))
                    .thenReturn("Basic description");
            when(deepInfraDescriptionService.generateAISummaryAndAction(eq(testToolId), anyString()))
                    .thenReturn("not valid json");

            // Act & Assert
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                aiDescriptionEnrichmentService.enrichToolDescription(testToolId)
            );
            assertTrue(exception.getMessage().contains("invalid JSON"));
        }
    }

    // ========================================================================
    // hasEnrichedDescription tests
    // ========================================================================

    @Nested
    @DisplayName("hasEnrichedDescription")
    class HasEnrichedDescriptionTests {

        @Test
        @DisplayName("should return false when tool does not exist")
        void shouldReturnFalseWhenToolDoesNotExist() {
            // Arrange
            when(apiToolRepository.existsById(testToolId)).thenReturn(false);

            // Act
            boolean result = aiDescriptionEnrichmentService.hasEnrichedDescription(testToolId);

            // Assert
            assertFalse(result);
        }

        @Test
        @DisplayName("should return true when enriched data exists")
        void shouldReturnTrueWhenEnrichedDataExists() {
            // Arrange
            when(apiToolRepository.existsById(testToolId)).thenReturn(true);
            when(lexicalIndexSyncService.getJdbcTemplate()).thenReturn(jdbcTemplate);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(testToolId)))
                    .thenReturn(1);

            // Act
            boolean result = aiDescriptionEnrichmentService.hasEnrichedDescription(testToolId);

            // Assert
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when no enriched data exists")
        void shouldReturnFalseWhenNoEnrichedDataExists() {
            // Arrange
            when(apiToolRepository.existsById(testToolId)).thenReturn(true);
            when(lexicalIndexSyncService.getJdbcTemplate()).thenReturn(jdbcTemplate);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(testToolId)))
                    .thenReturn(0);

            // Act
            boolean result = aiDescriptionEnrichmentService.hasEnrichedDescription(testToolId);

            // Assert
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false on exception")
        void shouldReturnFalseOnException() {
            // Arrange
            when(apiToolRepository.existsById(testToolId)).thenReturn(true);
            when(lexicalIndexSyncService.getJdbcTemplate()).thenThrow(new RuntimeException("DB error"));

            // Act
            boolean result = aiDescriptionEnrichmentService.hasEnrichedDescription(testToolId);

            // Assert
            assertFalse(result);
        }
    }

    // ========================================================================
    // getEnrichedDescription tests
    // ========================================================================

    @Nested
    @DisplayName("getEnrichedDescription")
    class GetEnrichedDescriptionTests {

        @Test
        @DisplayName("should throw exception when tool not found")
        void shouldThrowExceptionWhenToolNotFound() {
            // Arrange
            when(apiToolRepository.existsById(testToolId)).thenReturn(false);

            // Act & Assert
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                aiDescriptionEnrichmentService.getEnrichedDescription(testToolId)
            );
            // The inner "Tool not found" exception gets wrapped as "Failed to get enriched description"
            // Check either the wrapper message or the cause message
            assertTrue(exception.getMessage().contains("Failed to get enriched description") ||
                      (exception.getCause() != null && exception.getCause().getMessage().contains("Tool not found")));
        }
    }

    // ========================================================================
    // enrichAllToolDescriptions tests
    // ========================================================================

    @Nested
    @DisplayName("enrichAllToolDescriptions")
    class EnrichAllToolDescriptionsTests {

        @Test
        @DisplayName("should skip tools with existing enriched descriptions")
        void shouldSkipToolsWithExistingEnrichedDescriptions() {
            // Arrange
            when(apiToolRepository.findByIsActiveTrue()).thenReturn(List.of(testTool));
            when(apiToolRepository.existsById(testToolId)).thenReturn(true);
            when(lexicalIndexSyncService.getJdbcTemplate()).thenReturn(jdbcTemplate);
            when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(testToolId)))
                    .thenReturn(1); // Has enriched description

            // Act
            aiDescriptionEnrichmentService.enrichAllToolDescriptions();

            // Assert - should not call enrichToolDescription
            verify(toolDescriptionGeneratorService, never()).generateEnrichedDescription(any());
        }

        @Test
        @DisplayName("should continue on error for individual tools")
        void shouldContinueOnErrorForIndividualTools() {
            // Arrange
            ApiToolEntity tool1 = createTestTool(UUID.randomUUID());
            ApiToolEntity tool2 = createTestTool(UUID.randomUUID());

            when(apiToolRepository.findByIsActiveTrue()).thenReturn(List.of(tool1, tool2));
            // Tool 1 has no enriched description
            when(apiToolRepository.existsById(tool1.getId())).thenReturn(true);
            when(lexicalIndexSyncService.getJdbcTemplate()).thenReturn(jdbcTemplate);
            when(jdbcTemplate.queryForObject(contains("COUNT"), eq(Integer.class), eq(tool1.getId())))
                    .thenReturn(0);
            when(jdbcTemplate.queryForObject(contains("COUNT"), eq(Integer.class), eq(tool2.getId())))
                    .thenReturn(0);
            when(apiToolRepository.existsById(tool2.getId())).thenReturn(true);

            // Tool 1 fails during enrichment
            when(apiToolRepository.findById(tool1.getId())).thenReturn(Optional.of(tool1));
            when(toolDescriptionGeneratorService.generateEnrichedDescription(tool1.getId()))
                    .thenThrow(new RuntimeException("Failed"));

            // Tool 2 also fails (mocked to throw)
            when(apiToolRepository.findById(tool2.getId())).thenReturn(Optional.of(tool2));
            when(toolDescriptionGeneratorService.generateEnrichedDescription(tool2.getId()))
                    .thenThrow(new RuntimeException("Failed"));

            // Act - should not throw, should continue
            assertDoesNotThrow(() -> aiDescriptionEnrichmentService.enrichAllToolDescriptions());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ApiToolEntity createTestTool(UUID id) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(id);
        tool.setToolNameId("get_users");
        tool.setEndpoint("/api/users");
        tool.setMethod("GET");
        tool.setApiId(UUID.randomUUID());
        return tool;
    }
}
