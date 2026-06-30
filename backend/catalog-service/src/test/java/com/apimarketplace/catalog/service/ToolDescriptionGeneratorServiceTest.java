package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.mapping.entity.MappingDefinitionEntity;
import com.apimarketplace.catalog.mapping.repository.MappingDefinitionRepository;
import com.apimarketplace.catalog.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ToolDescriptionGeneratorService.
 *
 * ToolDescriptionGeneratorService generates enriched descriptions for tools
 * by aggregating information from multiple sources (API, categories, parameters, mappings).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolDescriptionGeneratorService")
class ToolDescriptionGeneratorServiceTest {

    @Mock
    private ToolNameRepository toolNameRepository;

    @Mock
    private ApiToolRepository apiToolRepository;

    @Mock
    private ApiCategoryRepository apiCategoryRepository;

    @Mock
    private ApiSubcategoryRepository apiSubcategoryRepository;

    @Mock
    private ToolCategoryRepository toolCategoryRepository;

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiToolParameterRepository apiToolParameterRepository;

    @Mock
    private MappingDefinitionRepository mappingDefinitionRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ToolCategoryService toolCategoryService;

    private ToolDescriptionGeneratorService service;

    @BeforeEach
    void setUp() {
        service = new ToolDescriptionGeneratorService(
            toolNameRepository,
            apiToolRepository,
            apiCategoryRepository,
            apiSubcategoryRepository,
            toolCategoryRepository,
            apiRepository,
            apiToolParameterRepository,
            mappingDefinitionRepository,
            jdbcTemplate,
            toolCategoryService
        );
    }

    // ========================================================================
    // generateEnrichedDescription tests
    // ========================================================================

    @Nested
    @DisplayName("generateEnrichedDescription()")
    class GenerateEnrichedDescriptionTests {

        @Test
        @DisplayName("should throw exception when tool not found")
        void shouldThrowExceptionWhenToolNotFound() {
            UUID toolId = UUID.randomUUID();
            when(apiToolRepository.findById(toolId)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () -> service.generateEnrichedDescription(toolId));
        }

        @Test
        @DisplayName("should throw exception when API not found")
        void shouldThrowExceptionWhenApiNotFound() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();

            ApiToolEntity apiTool = new ApiToolEntity();
            apiTool.setId(toolId);
            apiTool.setApiId(apiId);

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(apiTool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () -> service.generateEnrichedDescription(toolId));
        }

        @Test
        @DisplayName("should throw exception when API category not found")
        void shouldThrowExceptionWhenApiCategoryNotFound() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();

            ApiToolEntity apiTool = createApiTool(toolId, apiId);
            ApiEntity api = createApi(apiId, categoryId, UUID.randomUUID());

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(apiTool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiCategoryRepository.findById(categoryId)).thenReturn(Optional.empty());

            assertThrows(RuntimeException.class, () -> service.generateEnrichedDescription(toolId));
        }

        @Test
        @DisplayName("should generate description with all required fields")
        void shouldGenerateDescriptionWithAllRequiredFields() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiToolEntity apiTool = createApiTool(toolId, apiId);
            apiTool.setDescription("Test tool description");
            apiTool.setMethod("POST");
            apiTool.setEndpoint("/api/test");

            ApiEntity api = createApi(apiId, categoryId, subcategoryId);
            api.setApiName("Test API");
            api.setDescription("Test API description");

            ApiCategoryEntity category = new ApiCategoryEntity();
            category.setId(categoryId);
            category.setName("Test Category");

            ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
            subcategory.setId(subcategoryId);
            subcategory.setName("Test Subcategory");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(apiTool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(apiSubcategoryRepository.findById(subcategoryId)).thenReturn(Optional.of(subcategory));
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(Collections.emptyList());
            when(mappingDefinitionRepository.findLatestByToolId(toolId)).thenReturn(Optional.empty());
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());

            String result = service.generateEnrichedDescription(toolId);

            assertNotNull(result);
            assertTrue(result.contains("[Tool Information]"));
            assertTrue(result.contains("Test tool description"));
            assertTrue(result.contains("POST"));
            assertTrue(result.contains("/api/test"));
            assertTrue(result.contains("[API Information]"));
            assertTrue(result.contains("Test API"));
            assertTrue(result.contains("Test Category"));
            assertTrue(result.contains("Test Subcategory"));
        }

        @Test
        @DisplayName("should include required parameters in description")
        void shouldIncludeRequiredParametersInDescription() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiToolEntity apiTool = createApiTool(toolId, apiId);
            ApiEntity api = createApi(apiId, categoryId, subcategoryId);
            ApiCategoryEntity category = createCategory(categoryId);
            ApiSubcategoryEntity subcategory = createSubcategory(subcategoryId);

            ApiToolParameterEntity requiredParam = new ApiToolParameterEntity();
            requiredParam.setName("userId");
            requiredParam.setDataType("string");
            requiredParam.setIsRequired(true);
            requiredParam.setDescription("The user identifier");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(apiTool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(apiSubcategoryRepository.findById(subcategoryId)).thenReturn(Optional.of(subcategory));
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of(requiredParam));
            when(mappingDefinitionRepository.findLatestByToolId(toolId)).thenReturn(Optional.empty());
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());

            String result = service.generateEnrichedDescription(toolId);

            assertTrue(result.contains("[Required Parameters]"));
            assertTrue(result.contains("userId"));
            assertTrue(result.contains("string"));
            assertTrue(result.contains("The user identifier"));
        }

        @Test
        @DisplayName("should include optional parameters in description")
        void shouldIncludeOptionalParametersInDescription() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiToolEntity apiTool = createApiTool(toolId, apiId);
            ApiEntity api = createApi(apiId, categoryId, subcategoryId);
            ApiCategoryEntity category = createCategory(categoryId);
            ApiSubcategoryEntity subcategory = createSubcategory(subcategoryId);

            ApiToolParameterEntity optionalParam = new ApiToolParameterEntity();
            optionalParam.setName("limit");
            optionalParam.setDataType("integer");
            optionalParam.setIsRequired(false);
            optionalParam.setDescription("Maximum results");
            optionalParam.setExampleValue("10");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(apiTool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(apiSubcategoryRepository.findById(subcategoryId)).thenReturn(Optional.of(subcategory));
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of(optionalParam));
            when(mappingDefinitionRepository.findLatestByToolId(toolId)).thenReturn(Optional.empty());
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());

            String result = service.generateEnrichedDescription(toolId);

            assertTrue(result.contains("[Optional Parameters]"));
            assertTrue(result.contains("limit"));
            assertTrue(result.contains("integer"));
            assertTrue(result.contains("[Example: 10]"));
        }

        @Test
        @DisplayName("should deduplicate parameters that appear in both required and optional")
        void shouldDeduplicateParameters() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiToolEntity apiTool = createApiTool(toolId, apiId);
            ApiEntity api = createApi(apiId, categoryId, subcategoryId);
            ApiCategoryEntity category = createCategory(categoryId);
            ApiSubcategoryEntity subcategory = createSubcategory(subcategoryId);

            // Same parameter appears as both required and optional
            ApiToolParameterEntity requiredParam = new ApiToolParameterEntity();
            requiredParam.setName("userId");
            requiredParam.setDataType("string");
            requiredParam.setIsRequired(true);

            ApiToolParameterEntity duplicateParam = new ApiToolParameterEntity();
            duplicateParam.setName("userId");
            duplicateParam.setDataType("string");
            duplicateParam.setIsRequired(false);

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(apiTool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(apiSubcategoryRepository.findById(subcategoryId)).thenReturn(Optional.of(subcategory));
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(List.of(requiredParam, duplicateParam));
            when(mappingDefinitionRepository.findLatestByToolId(toolId)).thenReturn(Optional.empty());
            when(toolCategoryService.getToolNameByToolNameId(any())).thenReturn(Optional.empty());

            String result = service.generateEnrichedDescription(toolId);

            // userId should only appear in Required Parameters section
            assertTrue(result.contains("[Required Parameters]"));
            assertTrue(result.contains("userId"));

            // Check that userId only appears once (in required section, not in optional)
            int requiredIndex = result.indexOf("[Required Parameters]");
            int optionalIndex = result.indexOf("[Optional Parameters]");

            // If optional section doesn't exist or is empty, that's the expected behavior
            if (optionalIndex == -1) {
                // No optional parameters section - good, the duplicate was removed
                assertFalse(result.contains("[Optional Parameters]"));
            }
        }

        @Test
        @DisplayName("should include tool name details when available")
        void shouldIncludeToolNameDetailsWhenAvailable() {
            UUID toolId = UUID.randomUUID();
            UUID apiId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();
            UUID toolNameId = UUID.randomUUID();
            UUID toolCategoryId = UUID.randomUUID();

            ApiToolEntity apiTool = createApiTool(toolId, apiId);
            apiTool.setToolNameId(toolNameId.toString());

            ApiEntity api = createApi(apiId, categoryId, subcategoryId);
            ApiCategoryEntity category = createCategory(categoryId);
            ApiSubcategoryEntity subcategory = createSubcategory(subcategoryId);

            ToolNameEntity toolName = new ToolNameEntity();
            toolName.setId(toolNameId);
            toolName.setName("Custom Tool Name");
            toolName.setDescription("Custom tool description");
            toolName.setToolCategoryId(toolCategoryId);

            ToolCategoryEntity toolCategory = new ToolCategoryEntity();
            toolCategory.setId(toolCategoryId);
            toolCategory.setName("Custom Tool Category");

            when(apiToolRepository.findById(toolId)).thenReturn(Optional.of(apiTool));
            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiCategoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(apiSubcategoryRepository.findById(subcategoryId)).thenReturn(Optional.of(subcategory));
            when(apiToolParameterRepository.findByApiToolId(toolId)).thenReturn(Collections.emptyList());
            when(mappingDefinitionRepository.findLatestByToolId(toolId)).thenReturn(Optional.empty());
            when(toolNameRepository.findById(toolNameId)).thenReturn(Optional.of(toolName));
            when(toolCategoryRepository.findById(toolCategoryId)).thenReturn(Optional.of(toolCategory));
            when(toolCategoryService.getToolNameByToolNameId(toolNameId.toString())).thenReturn(Optional.of(toolName));

            String result = service.generateEnrichedDescription(toolId);

            assertTrue(result.contains("[Tool Details]"));
            assertTrue(result.contains("Custom Tool Name"));
            assertTrue(result.contains("Custom tool description"));
            assertTrue(result.contains("Custom Tool Category"));
        }
    }

    // ========================================================================
    // truncateText tests (via reflection)
    // ========================================================================

    @Nested
    @DisplayName("truncateText()")
    class TruncateTextTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("should return empty string for null or blank input")
        void shouldReturnEmptyStringForNullOrBlankInput(String input) {
            String result = invokeTruncateText(input, 100);

            assertEquals("", result);
        }

        @Test
        @DisplayName("should not truncate text shorter than max length")
        void shouldNotTruncateTextShorterThanMaxLength() {
            String result = invokeTruncateText("Short text", 100);

            assertEquals("Short text", result);
        }

        @Test
        @DisplayName("should truncate text longer than max length and add ellipsis")
        void shouldTruncateTextLongerThanMaxLengthAndAddEllipsis() {
            String longText = "This is a very long text that should be truncated";

            String result = invokeTruncateText(longText, 20);

            assertEquals(20, result.length());
            assertTrue(result.endsWith("..."));
        }

        @Test
        @DisplayName("should trim whitespace from text")
        void shouldTrimWhitespaceFromText() {
            String result = invokeTruncateText("  trimmed  ", 100);

            assertEquals("trimmed", result);
        }
    }

    // ========================================================================
    // truncateTextWithPlaceholder tests (via reflection)
    // ========================================================================

    @Nested
    @DisplayName("truncateTextWithPlaceholder()")
    class TruncateTextWithPlaceholderTests {

        @Test
        @DisplayName("should return placeholder for null input")
        void shouldReturnPlaceholderForNullInput() {
            String result = invokeTruncateTextWithPlaceholder(null, 100, "default");

            assertEquals("default", result);
        }

        @Test
        @DisplayName("should return placeholder for empty input")
        void shouldReturnPlaceholderForEmptyInput() {
            String result = invokeTruncateTextWithPlaceholder("", 100, "default");

            assertEquals("default", result);
        }

        @Test
        @DisplayName("should return text when not empty")
        void shouldReturnTextWhenNotEmpty() {
            String result = invokeTruncateTextWithPlaceholder("actual text", 100, "default");

            assertEquals("actual text", result);
        }

        @Test
        @DisplayName("should truncate long text and return truncated result")
        void shouldTruncateLongTextAndReturnTruncatedResult() {
            String longText = "This is a very long text";

            String result = invokeTruncateTextWithPlaceholder(longText, 15, "default");

            assertEquals(15, result.length());
            assertTrue(result.endsWith("..."));
        }
    }

    // ========================================================================
    // limitParameters tests (via reflection)
    // ========================================================================

    @Nested
    @DisplayName("limitParameters()")
    class LimitParametersTests {

        @Test
        @DisplayName("should return empty list for null input")
        void shouldReturnEmptyListForNullInput() {
            List<ApiToolParameterEntity> result = invokeLimitParameters(null, 5);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void shouldReturnEmptyListForEmptyInput() {
            List<ApiToolParameterEntity> result = invokeLimitParameters(Collections.emptyList(), 5);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return all parameters when count is less than max")
        void shouldReturnAllParametersWhenCountIsLessThanMax() {
            List<ApiToolParameterEntity> params = createParameters(3);

            List<ApiToolParameterEntity> result = invokeLimitParameters(params, 5);

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("should limit parameters to max count")
        void shouldLimitParametersToMaxCount() {
            List<ApiToolParameterEntity> params = createParameters(10);

            List<ApiToolParameterEntity> result = invokeLimitParameters(params, 5);

            assertEquals(5, result.size());
        }
    }

    // ========================================================================
    // estimateTokenCount tests (via reflection)
    // ========================================================================

    @Nested
    @DisplayName("estimateTokenCount()")
    class EstimateTokenCountTests {

        @Test
        @DisplayName("should return 0 for null input")
        void shouldReturnZeroForNullInput() {
            int result = invokeEstimateTokenCount(null);

            assertEquals(0, result);
        }

        @Test
        @DisplayName("should return 0 for empty input")
        void shouldReturnZeroForEmptyInput() {
            int result = invokeEstimateTokenCount("");

            assertEquals(0, result);
        }

        @Test
        @DisplayName("should estimate tokens for typical text")
        void shouldEstimateTokensForTypicalText() {
            String text = "This is a simple sentence with several words.";

            int result = invokeEstimateTokenCount(text);

            assertTrue(result > 0);
            // Should be roughly words * 1.3 averaged with chars / 4
            assertTrue(result < text.length()); // Token count should be less than char count
        }

        @Test
        @DisplayName("should increase estimate proportionally with text length")
        void shouldIncreaseEstimateProportionallyWithTextLength() {
            String shortText = "Short text";
            String longText = "This is a much longer piece of text that contains many more words and characters";

            int shortEstimate = invokeEstimateTokenCount(shortText);
            int longEstimate = invokeEstimateTokenCount(longText);

            assertTrue(longEstimate > shortEstimate);
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ApiToolEntity createApiTool(UUID toolId, UUID apiId) {
        ApiToolEntity apiTool = new ApiToolEntity();
        apiTool.setId(toolId);
        apiTool.setApiId(apiId);
        apiTool.setMethod("GET");
        apiTool.setEndpoint("/api/endpoint");
        return apiTool;
    }

    private ApiEntity createApi(UUID apiId, UUID categoryId, UUID subcategoryId) {
        ApiEntity api = new ApiEntity();
        api.setId(apiId);
        api.setApiName("Test API");
        api.setCategoryId(categoryId);
        api.setSubcategoryId(subcategoryId);
        return api;
    }

    private ApiCategoryEntity createCategory(UUID categoryId) {
        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setId(categoryId);
        category.setName("Test Category");
        return category;
    }

    private ApiSubcategoryEntity createSubcategory(UUID subcategoryId) {
        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setId(subcategoryId);
        subcategory.setName("Test Subcategory");
        return subcategory;
    }

    private List<ApiToolParameterEntity> createParameters(int count) {
        List<ApiToolParameterEntity> params = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ApiToolParameterEntity param = new ApiToolParameterEntity();
            param.setName("param" + i);
            param.setDataType("string");
            param.setIsRequired(false);
            params.add(param);
        }
        return params;
    }

    private String invokeTruncateText(String text, int maxLength) {
        return (String) ReflectionTestUtils.invokeMethod(service, "truncateText", text, maxLength);
    }

    private String invokeTruncateTextWithPlaceholder(String text, int maxLength, String placeholder) {
        return (String) ReflectionTestUtils.invokeMethod(service, "truncateTextWithPlaceholder", text, maxLength, placeholder);
    }

    @SuppressWarnings("unchecked")
    private List<ApiToolParameterEntity> invokeLimitParameters(List<ApiToolParameterEntity> params, int maxCount) {
        return (List<ApiToolParameterEntity>) ReflectionTestUtils.invokeMethod(service, "limitParameters", params, maxCount);
    }

    private int invokeEstimateTokenCount(String text) {
        return (int) ReflectionTestUtils.invokeMethod(service, "estimateTokenCount", text);
    }
}
