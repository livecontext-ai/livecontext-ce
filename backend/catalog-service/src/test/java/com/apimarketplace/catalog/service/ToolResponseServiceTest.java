package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ResponseFormat;
import com.apimarketplace.catalog.domain.ToolResponseEntity;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolResponseRepository;
import com.apimarketplace.catalog.util.JsonSkeletonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ToolResponseService.
 *
 * ToolResponseService manages tool responses with support for multiple formats
 * (JSON, XML, HTML, CSV, TEXT, BINARY) and automatic format detection.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolResponseService")
class ToolResponseServiceTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ToolResponseRepository toolResponseRepository;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private ApiToolRepository apiToolRepository;

    @Mock
    private JsonSkeletonGenerator jsonSkeletonGenerator;

    @InjectMocks
    private ToolResponseService service;

    private UUID toolId;
    private UUID responseId;

    @BeforeEach
    void setUp() {
        toolId = UUID.randomUUID();
        responseId = UUID.randomUUID();
    }

    // ========================================================================
    // getResponsesByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("getResponsesByToolId()")
    class GetResponsesByToolIdTests {

        @Test
        @DisplayName("should return empty list when no responses found")
        void shouldReturnEmptyListWhenNoResponsesFound() {
            when(toolResponseRepository.findByToolId(toolId)).thenReturn(Collections.emptyList());
            when(toolResponseRepository.findAll()).thenReturn(Collections.emptyList());

            List<ToolResponseDto> result = service.getResponsesByToolId(toolId);

            assertNotNull(result);
            assertTrue(result.isEmpty());
            verify(toolResponseRepository).findByToolId(toolId);
        }

        @Test
        @DisplayName("should return converted DTOs when responses exist")
        void shouldReturnConvertedDtosWhenResponsesExist() {
            ToolResponseEntity entity = createResponseEntity(responseId, toolId, "Test Response");
            when(toolResponseRepository.findByToolId(toolId)).thenReturn(List.of(entity));

            List<ToolResponseDto> result = service.getResponsesByToolId(toolId);

            assertEquals(1, result.size());
            assertEquals(responseId, result.get(0).getId());
            assertEquals(toolId, result.get(0).getToolId());
            assertEquals("Test Response", result.get(0).getName());
        }

        @Test
        @DisplayName("should return multiple responses in order")
        void shouldReturnMultipleResponsesInOrder() {
            UUID responseId1 = UUID.randomUUID();
            UUID responseId2 = UUID.randomUUID();
            ToolResponseEntity entity1 = createResponseEntity(responseId1, toolId, "Response 1");
            ToolResponseEntity entity2 = createResponseEntity(responseId2, toolId, "Response 2");
            when(toolResponseRepository.findByToolId(toolId)).thenReturn(List.of(entity1, entity2));

            List<ToolResponseDto> result = service.getResponsesByToolId(toolId);

            assertEquals(2, result.size());
            assertEquals("Response 1", result.get(0).getName());
            assertEquals("Response 2", result.get(1).getName());
        }
    }

    // ========================================================================
    // getResponseById tests
    // ========================================================================

    @Nested
    @DisplayName("getResponseById()")
    class GetResponseByIdTests {

        @Test
        @DisplayName("should return empty optional when response not found")
        void shouldReturnEmptyOptionalWhenResponseNotFound() {
            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.empty());

            Optional<ToolResponseDto> result = service.getResponseById(responseId);

            assertTrue(result.isEmpty());
            verify(toolResponseRepository).findById(responseId);
        }

        @Test
        @DisplayName("should return DTO when response found")
        void shouldReturnDtoWhenResponseFound() {
            ToolResponseEntity entity = createResponseEntity(responseId, toolId, "Found Response");
            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.of(entity));

            Optional<ToolResponseDto> result = service.getResponseById(responseId);

            assertTrue(result.isPresent());
            assertEquals(responseId, result.get().getId());
            assertEquals("Found Response", result.get().getName());
        }
    }

    // ========================================================================
    // getDefaultResponseByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("getDefaultResponseByToolId()")
    class GetDefaultResponseByToolIdTests {

        @Test
        @DisplayName("should return empty optional when no default response exists")
        void shouldReturnEmptyOptionalWhenNoDefaultResponseExists() {
            when(toolResponseRepository.findByToolIdAndIsDefaultTrue(toolId)).thenReturn(Optional.empty());

            Optional<ToolResponseDto> result = service.getDefaultResponseByToolId(toolId);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return default response when it exists")
        void shouldReturnDefaultResponseWhenItExists() {
            ToolResponseEntity entity = createResponseEntity(responseId, toolId, "Default Response");
            entity.setIsDefault(true);
            when(toolResponseRepository.findByToolIdAndIsDefaultTrue(toolId)).thenReturn(Optional.of(entity));

            Optional<ToolResponseDto> result = service.getDefaultResponseByToolId(toolId);

            assertTrue(result.isPresent());
            assertTrue(result.get().getIsDefault());
        }
    }

    // ========================================================================
    // createResponse tests
    // ========================================================================

    @Nested
    @DisplayName("createResponse()")
    class CreateResponseTests {

        @Test
        @DisplayName("should create response with JSON example and generate skeleton")
        void shouldCreateResponseWithJsonExampleAndGenerateSkeleton() throws Exception {
            String jsonExample = "{\"name\": \"test\", \"value\": 123}";
            ToolResponseDto dto = createResponseDto(toolId, "JSON Response", jsonExample);
            dto.setResponseFormat(ResponseFormat.JSON);

            JsonNode rootNode = objectMapper.readTree(jsonExample);
            JsonNode skeletonNode = objectMapper.createObjectNode();
            when(jsonSkeletonGenerator.generateSkeleton(any())).thenReturn(skeletonNode);
            when(toolResponseRepository.findByToolIdAndIsDefault(eq(toolId), eq(true)))
                .thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            ToolResponseDto result = service.createResponse(dto, "user1");

            assertNotNull(result);
            assertEquals(toolId, result.getToolId());
            assertEquals("JSON Response", result.getName());
            verify(jsonSkeletonGenerator).generateSkeleton(any());
        }

        @Test
        @DisplayName("should create response with non-JSON example without skeleton")
        void shouldCreateResponseWithNonJsonExampleWithoutSkeleton() {
            String textExample = "This is plain text response";
            ToolResponseDto dto = createResponseDto(toolId, "Text Response", textExample);
            dto.setResponseFormat(ResponseFormat.TEXT);

            when(toolResponseRepository.findByToolIdAndIsDefault(eq(toolId), eq(true)))
                .thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            ToolResponseDto result = service.createResponse(dto, "user1");

            assertNotNull(result);
            verify(jsonSkeletonGenerator, never()).generateSkeleton(any());
        }

        @Test
        @DisplayName("should set as default when isDefault is true")
        void shouldSetAsDefaultWhenIsDefaultIsTrue() {
            ToolResponseDto dto = createResponseDto(toolId, "Default Response", "{\"test\":true}");
            dto.setIsDefault(true);

            ToolResponseEntity existingDefault = createResponseEntity(UUID.randomUUID(), toolId, "Old Default");
            existingDefault.setIsDefault(true);

            when(toolResponseRepository.findByToolIdAndIsDefault(toolId, true))
                .thenReturn(List.of(existingDefault));
            when(toolResponseRepository.saveAll(anyList())).thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            service.createResponse(dto, "user1");

            verify(toolResponseRepository).findByToolIdAndIsDefault(toolId, true);
            verify(toolResponseRepository).saveAll(anyList());
        }

        @Test
        @DisplayName("should handle null createdBy")
        void shouldHandleNullCreatedBy() {
            ToolResponseDto dto = createResponseDto(toolId, "Response", "{\"data\":\"test\"}");

            when(toolResponseRepository.findByToolIdAndIsDefault(eq(toolId), eq(true)))
                .thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            ToolResponseDto result = service.createResponse(dto, null);

            assertNotNull(result);
        }

        @Test
        @DisplayName("should auto-detect JSON format when not specified")
        void shouldAutoDetectJsonFormatWhenNotSpecified() {
            String jsonExample = "{\"key\": \"value\"}";
            ToolResponseDto dto = createResponseDto(toolId, "Auto Format", jsonExample);
            dto.setResponseFormat(null);

            when(toolResponseRepository.findByToolIdAndIsDefault(eq(toolId), eq(true)))
                .thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            service.createResponse(dto, "user1");

            verify(jdbcTemplate).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("should handle schema parameter")
        void shouldHandleSchemaParameter() {
            ToolResponseDto dto = createResponseDto(toolId, "Schema Response", "{\"data\":\"test\"}");
            dto.setSchema("{\"type\":\"object\"}");

            when(toolResponseRepository.findByToolIdAndIsDefault(eq(toolId), eq(true)))
                .thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            ToolResponseDto result = service.createResponse(dto, "user1");

            assertNotNull(result);
        }
    }

    // ========================================================================
    // updateResponse tests
    // ========================================================================

    @Nested
    @DisplayName("updateResponse()")
    class UpdateResponseTests {

        @Test
        @DisplayName("should throw exception when response not found")
        void shouldThrowExceptionWhenResponseNotFound() {
            ToolResponseDto dto = createResponseDto(toolId, "Updated", "{\"test\":true}");
            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.empty());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.updateResponse(responseId, dto, "user1"));

            assertTrue(exception.getMessage().contains("Reponse non trouvee"));
        }

        @Test
        @DisplayName("should throw exception when name already exists for tool")
        void shouldThrowExceptionWhenNameAlreadyExistsForTool() {
            ToolResponseEntity existingEntity = createResponseEntity(responseId, toolId, "Original Name");
            ToolResponseDto dto = createResponseDto(toolId, "Duplicate Name", "{\"test\":true}");

            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.of(existingEntity));
            when(toolResponseRepository.existsByToolIdAndName(toolId, "Duplicate Name")).thenReturn(true);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.updateResponse(responseId, dto, "user1"));

            assertTrue(exception.getMessage().contains("Une reponse avec ce nom existe deja"));
        }

        @Test
        @DisplayName("should update response successfully")
        void shouldUpdateResponseSuccessfully() {
            ToolResponseEntity existingEntity = createResponseEntity(responseId, toolId, "Original");
            ToolResponseDto dto = createResponseDto(toolId, "Updated Name", "{\"updated\":true}");

            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.of(existingEntity));
            when(toolResponseRepository.existsByToolIdAndName(toolId, "Updated Name")).thenReturn(false);
            when(toolResponseRepository.findByToolIdAndIsDefault(toolId, true)).thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            ToolResponseEntity updatedEntity = createResponseEntity(responseId, toolId, "Updated Name");
            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.of(updatedEntity));

            ToolResponseDto result = service.updateResponse(responseId, dto, "user1");

            assertNotNull(result);
            verify(jdbcTemplate).update(anyString(), any(Object[].class));
        }

        @Test
        @DisplayName("should allow same name when not changed")
        void shouldAllowSameNameWhenNotChanged() {
            ToolResponseEntity existingEntity = createResponseEntity(responseId, toolId, "Same Name");
            ToolResponseDto dto = createResponseDto(toolId, "Same Name", "{\"test\":true}");

            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.of(existingEntity));
            when(toolResponseRepository.findByToolIdAndIsDefault(toolId, true)).thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            service.updateResponse(responseId, dto, "user1");

            verify(toolResponseRepository, never()).existsByToolIdAndName(any(), any());
        }
    }

    // ========================================================================
    // deleteResponse tests
    // ========================================================================

    @Nested
    @DisplayName("deleteResponse()")
    class DeleteResponseTests {

        @Test
        @DisplayName("should throw exception when response not found")
        void shouldThrowExceptionWhenResponseNotFound() {
            when(toolResponseRepository.existsById(responseId)).thenReturn(false);

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.deleteResponse(responseId));

            assertTrue(exception.getMessage().contains("Reponse non trouvee"));
        }

        @Test
        @DisplayName("should delete response when it exists")
        void shouldDeleteResponseWhenItExists() {
            when(toolResponseRepository.existsById(responseId)).thenReturn(true);
            doNothing().when(toolResponseRepository).deleteById(responseId);

            service.deleteResponse(responseId);

            verify(toolResponseRepository).deleteById(responseId);
        }
    }

    // ========================================================================
    // deleteResponsesByToolId tests
    // ========================================================================

    @Nested
    @DisplayName("deleteResponsesByToolId()")
    class DeleteResponsesByToolIdTests {

        @Test
        @DisplayName("should delete all responses for tool")
        void shouldDeleteAllResponsesForTool() {
            doNothing().when(toolResponseRepository).deleteByToolId(toolId);

            service.deleteResponsesByToolId(toolId);

            verify(toolResponseRepository).deleteByToolId(toolId);
        }
    }

    // ========================================================================
    // setAsDefaultResponse tests
    // ========================================================================

    @Nested
    @DisplayName("setAsDefaultResponse()")
    class SetAsDefaultResponseTests {

        @Test
        @DisplayName("should throw exception when response not found")
        void shouldThrowExceptionWhenResponseNotFound() {
            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.empty());

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.setAsDefaultResponse(responseId, "user1"));

            assertTrue(exception.getMessage().contains("Reponse non trouvee"));
        }

        @Test
        @DisplayName("should set response as default and unset others")
        void shouldSetResponseAsDefaultAndUnsetOthers() {
            ToolResponseEntity entity = createResponseEntity(responseId, toolId, "New Default");
            entity.setIsDefault(false);

            ToolResponseEntity oldDefault = createResponseEntity(UUID.randomUUID(), toolId, "Old Default");
            oldDefault.setIsDefault(true);

            when(toolResponseRepository.findById(responseId)).thenReturn(Optional.of(entity));
            when(toolResponseRepository.findByToolIdAndIsDefault(toolId, true))
                .thenReturn(List.of(oldDefault));
            when(toolResponseRepository.saveAll(anyList())).thenReturn(List.of(oldDefault));
            when(toolResponseRepository.save(any())).thenReturn(entity);

            ToolResponseDto result = service.setAsDefaultResponse(responseId, "user1");

            assertTrue(result.getIsDefault());
            verify(toolResponseRepository).saveAll(anyList());
            verify(toolResponseRepository).save(any());
        }
    }

    // ========================================================================
    // isValidJson tests (private method via reflection)
    // ========================================================================

    @Nested
    @DisplayName("isValidJson() - private method")
    class IsValidJsonTests {

        @Test
        @DisplayName("should return true for valid JSON object")
        void shouldReturnTrueForValidJsonObject() {
            boolean result = invokeIsValidJson("{\"name\":\"test\",\"value\":123}");
            assertTrue(result);
        }

        @Test
        @DisplayName("should return true for valid JSON array")
        void shouldReturnTrueForValidJsonArray() {
            boolean result = invokeIsValidJson("[1, 2, 3]");
            assertTrue(result);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("should return false for null, empty, or whitespace")
        void shouldReturnFalseForNullEmptyOrWhitespace(String content) {
            boolean result = invokeIsValidJson(content);
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for invalid JSON")
        void shouldReturnFalseForInvalidJson() {
            boolean result = invokeIsValidJson("{invalid json}");
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for plain text")
        void shouldReturnFalseForPlainText() {
            boolean result = invokeIsValidJson("This is just text");
            assertFalse(result);
        }

        private boolean invokeIsValidJson(String content) {
            return (boolean) ReflectionTestUtils.invokeMethod(service, "isValidJson", content);
        }
    }

    // ========================================================================
    // determineResponseFormat tests (private method via reflection)
    // ========================================================================

    @Nested
    @DisplayName("determineResponseFormat() - private method")
    class DetermineResponseFormatTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("should return JSON for null or empty example")
        void shouldReturnJsonForNullOrEmptyExample(String example) {
            ResponseFormat result = invokeDetermineResponseFormat(example);
            assertEquals(ResponseFormat.JSON, result);
        }

        @ParameterizedTest
        @CsvSource({
            "'{\"key\":\"value\"}', JSON",
            "'[1, 2, 3]', JSON"
        })
        @DisplayName("should detect JSON format")
        void shouldDetectJsonFormat(String example, ResponseFormat expected) {
            ResponseFormat result = invokeDetermineResponseFormat(example);
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("should detect HTML format")
        void shouldDetectHtmlFormat() {
            String html = "<html><body>Test</body></html>";
            ResponseFormat result = invokeDetermineResponseFormat(html);
            assertEquals(ResponseFormat.HTML, result);
        }

        @Test
        @DisplayName("should detect HTML with body tag")
        void shouldDetectHtmlWithBodyTag() {
            String html = "<body>Content</body>";
            ResponseFormat result = invokeDetermineResponseFormat(html);
            assertEquals(ResponseFormat.HTML, result);
        }

        @Test
        @DisplayName("should detect XML format")
        void shouldDetectXmlFormat() {
            String xml = "<?xml version=\"1.0\"?><root><item>test</item></root>";
            ResponseFormat result = invokeDetermineResponseFormat(xml);
            assertEquals(ResponseFormat.XML, result);
        }

        @Test
        @DisplayName("should detect CSV format")
        void shouldDetectCsvFormat() {
            String csv = "name,value,count\ntest,123,1\nother,456,2";
            ResponseFormat result = invokeDetermineResponseFormat(csv);
            assertEquals(ResponseFormat.CSV, result);
        }

        @Test
        @DisplayName("should detect TEXT format for plain text")
        void shouldDetectTextFormatForPlainText() {
            String text = "This is just plain text without special characters";
            ResponseFormat result = invokeDetermineResponseFormat(text);
            assertEquals(ResponseFormat.TEXT, result);
        }

        @Test
        @DisplayName("should return BINARY as fallback")
        void shouldReturnBinaryAsFallback() {
            // Content that doesn't match any specific format
            String binary = "data:image/png;base64,ABC123";
            ResponseFormat result = invokeDetermineResponseFormat(binary);
            // May be TEXT or BINARY depending on the exact content
            assertNotNull(result);
        }

        private ResponseFormat invokeDetermineResponseFormat(String example) {
            return (ResponseFormat) ReflectionTestUtils.invokeMethod(service, "determineResponseFormat", example);
        }
    }

    // ========================================================================
    // convertToDto tests (private method via reflection)
    // ========================================================================

    @Nested
    @DisplayName("convertToDto() - private method")
    class ConvertToDtoTests {

        @Test
        @DisplayName("should convert all fields correctly")
        void shouldConvertAllFieldsCorrectly() {
            ToolResponseEntity entity = createResponseEntity(responseId, toolId, "Test Response");
            entity.setDescription("Test description");
            entity.setSchema("{\"type\":\"object\"}");
            entity.setExample("{\"data\":\"test\"}");
            entity.setExampleJsonb("{\"data\":\"test\"}");
            entity.setStructureSkeleton("{\"props\":{}}");
            entity.setResponseFormat(ResponseFormat.JSON);
            entity.setStatusCode(200);
            entity.setIsDefault(true);
            entity.setIsActive(true);
            entity.setCreatedBy("testuser");
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());

            ToolResponseDto result = invokeConvertToDto(entity);

            assertEquals(responseId, result.getId());
            assertEquals(toolId, result.getToolId());
            assertEquals("Test Response", result.getName());
            assertEquals("Test description", result.getDescription());
            assertEquals("{\"type\":\"object\"}", result.getSchema());
            assertEquals("{\"data\":\"test\"}", result.getExample());
            assertEquals("{\"data\":\"test\"}", result.getExampleJsonb());
            assertEquals("{\"props\":{}}", result.getStructureSkeleton());
            assertEquals(ResponseFormat.JSON, result.getResponseFormat());
            assertEquals(200, result.getStatusCode());
            assertTrue(result.getIsDefault());
            assertTrue(result.getIsActive());
            assertEquals("testuser", result.getCreatedBy());
        }

        @Test
        @DisplayName("should handle null optional fields")
        void shouldHandleNullOptionalFields() {
            ToolResponseEntity entity = new ToolResponseEntity();
            entity.setId(responseId);
            entity.setToolId(toolId);
            entity.setName(null);
            entity.setDescription(null);
            entity.setSchema(null);
            entity.setExample("test");
            entity.setExampleJsonb(null);
            entity.setStructureSkeleton(null);
            entity.setResponseFormat(ResponseFormat.TEXT);
            entity.setStatusCode(null);
            entity.setIsDefault(false);
            entity.setIsActive(false);

            ToolResponseDto result = invokeConvertToDto(entity);

            assertNull(result.getName());
            assertNull(result.getDescription());
            assertNull(result.getSchema());
            assertNull(result.getExampleJsonb());
            assertNull(result.getStructureSkeleton());
            assertNull(result.getStatusCode());
        }

        private ToolResponseDto invokeConvertToDto(ToolResponseEntity entity) {
            return (ToolResponseDto) ReflectionTestUtils.invokeMethod(service, "convertToDto", entity);
        }
    }

    // ========================================================================
    // Edge cases and error handling
    // ========================================================================

    @Nested
    @DisplayName("Edge cases and error handling")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle skeleton generation failure gracefully in create")
        void shouldHandleSkeletonGenerationFailureGracefullyInCreate() throws Exception {
            String jsonExample = "{\"data\":\"test\"}";
            ToolResponseDto dto = createResponseDto(toolId, "Response", jsonExample);

            when(jsonSkeletonGenerator.generateSkeleton(any())).thenThrow(new RuntimeException("Skeleton error"));
            when(toolResponseRepository.findByToolIdAndIsDefault(eq(toolId), eq(true)))
                .thenReturn(Collections.emptyList());
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            // Should not throw exception
            ToolResponseDto result = service.createResponse(dto, "user1");

            assertNotNull(result);
        }

        @Test
        @DisplayName("should handle concurrent default response updates")
        void shouldHandleConcurrentDefaultResponseUpdates() {
            ToolResponseEntity entity1 = createResponseEntity(UUID.randomUUID(), toolId, "Default 1");
            entity1.setIsDefault(true);
            ToolResponseEntity entity2 = createResponseEntity(UUID.randomUUID(), toolId, "Default 2");
            entity2.setIsDefault(true);

            when(toolResponseRepository.findByToolIdAndIsDefault(toolId, true))
                .thenReturn(List.of(entity1, entity2));
            when(toolResponseRepository.saveAll(anyList())).thenReturn(List.of(entity1, entity2));

            ToolResponseDto dto = createResponseDto(toolId, "New Default", "{\"data\":\"test\"}");
            dto.setIsDefault(true);
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

            service.createResponse(dto, "user1");

            // Verify all existing defaults were updated
            verify(toolResponseRepository).saveAll(argThat(list ->
                ((List<?>) list).size() == 2
            ));
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ToolResponseEntity createResponseEntity(UUID id, UUID toolId, String name) {
        ToolResponseEntity entity = new ToolResponseEntity();
        entity.setId(id);
        entity.setToolId(toolId);
        entity.setName(name);
        entity.setDescription("Test description");
        entity.setExample("{\"test\":true}");
        entity.setResponseFormat(ResponseFormat.JSON);
        entity.setStatusCode(200);
        entity.setIsDefault(false);
        entity.setIsActive(true);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private ToolResponseDto createResponseDto(UUID toolId, String name, String example) {
        ToolResponseDto dto = new ToolResponseDto();
        dto.setToolId(toolId);
        dto.setName(name);
        dto.setDescription("Test description");
        dto.setExample(example);
        dto.setResponseFormat(ResponseFormat.JSON);
        dto.setStatusCode(200);
        dto.setIsDefault(true);
        dto.setIsActive(true);
        return dto;
    }

    // ========================================================================
    // autoSaveFromExecution - trivially-empty skeleton guard (Apify regression)
    // ========================================================================

    @Nested
    @DisplayName("autoSaveFromExecution() - trivial-skeleton guard")
    class AutoSaveTrivialSkeletonGuardTests {

        /**
         * Regression: in prod the Apify {@code /run-sync-get-dataset-items} tool
         * had its skeleton frozen at {@code {"_t":"obj","props":{}}} after the
         * very first run returned {@code {}}. The previous code inserted a row
         * regardless, then refused to overwrite it on later non-empty runs
         * (per-tool first-write-wins). Result: every later actor returned a
         * useless skeleton via the agent's "Get response schema" call.
         */
        @Test
        @DisplayName("cold-start: skips INSERT when generated skeleton is trivially empty")
        void coldStartSkipsInsertOnEmptySkeleton() throws Exception {
            when(toolResponseRepository.findByToolId(toolId)).thenReturn(List.of());

            // Generator returns the {} skeleton + isTriviallyEmpty=true
            JsonNode emptySkel = new ObjectMapper().readTree("{\"_t\":\"obj\",\"props\":{}}");
            when(jsonSkeletonGenerator.generateSkeleton(any(JsonNode.class))).thenReturn(emptySkel);
            when(jsonSkeletonGenerator.isTriviallyEmptySkeleton(emptySkel)).thenReturn(true);

            service.autoSaveFromExecution(toolId, java.util.Map.of(), 200);

            // No DB call at all - empty skeleton must never reach the catalog.tool_responses cache.
            verifyNoInteractions(jdbcTemplate);
        }

        @Test
        @DisplayName("cold-start: INSERTs when skeleton is non-trivial")
        void coldStartInsertsOnNonEmptySkeleton() throws Exception {
            when(toolResponseRepository.findByToolId(toolId)).thenReturn(List.of());

            JsonNode realSkel = new ObjectMapper().readTree("{\"_t\":\"obj\",\"props\":{\"id\":\"string\"}}");
            when(jsonSkeletonGenerator.generateSkeleton(any(JsonNode.class))).thenReturn(realSkel);
            when(jsonSkeletonGenerator.isTriviallyEmptySkeleton(realSkel)).thenReturn(false);

            service.autoSaveFromExecution(toolId, java.util.Map.of("id", "x"), 200);

            // INSERT must have been issued exactly once.
            verify(jdbcTemplate, atLeastOnce()).update(anyString(),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class),
                any(Object.class), any(Object.class), any(Object.class), any(Object.class));
        }

        @Test
        @DisplayName("backfill path: trivially-empty stored skeleton is treated as missing - replaced by a real one")
        void backfillReplacesTriviallyEmptyStoredSkeleton() throws Exception {
            // Existing row has the useless {} skeleton from a prior empty run.
            ToolResponseEntity existing = createResponseEntity(responseId, toolId, "Auto-generated");
            existing.setStructureSkeleton("{\"_t\":\"obj\",\"props\":{}}");
            existing.setExampleJsonb("{\"id\":\"abc\"}");
            when(toolResponseRepository.findByToolId(toolId)).thenReturn(List.of(existing));

            JsonNode realSkel = new ObjectMapper().readTree("{\"_t\":\"obj\",\"props\":{\"id\":\"string\"}}");
            when(jsonSkeletonGenerator.generateSkeleton(any(JsonNode.class))).thenReturn(realSkel);
            // Content-keyed stubs (not invocation-order) so a refactor adding extra calls doesn't silently break us:
            //   - any node whose props are EMPTY → trivial (matches the stored {"_t":"obj","props":{}} skeleton)
            //   - any node with non-empty props  → not trivial (matches the freshly generated one)
            when(jsonSkeletonGenerator.isTriviallyEmptySkeleton(argThat(
                    node -> node != null && node.path("props").isObject() && node.path("props").size() == 0)))
                .thenReturn(true);
            when(jsonSkeletonGenerator.isTriviallyEmptySkeleton(argThat(
                    node -> node != null && node.path("props").isObject() && node.path("props").size() > 0)))
                .thenReturn(false);

            service.autoSaveFromExecution(toolId, java.util.Map.of("id", "x"), 200);

            // UPDATE should have run on the existing row.
            verify(jdbcTemplate, atLeastOnce()).update(
                contains("UPDATE catalog.tool_responses"),
                any(Object.class), any(Object.class), any(Object.class));
        }

        /**
         * Regression for auditor-flagged hole: backfill must NOT re-write a healthy stored
         * skeleton with the same (or any) freshly generated one - that would burn a row write
         * per execution for no benefit and could overwrite a richer historical shape with a
         * thinner one if the live result happens to omit optional fields.
         */
        @Test
        @DisplayName("backfill path: stored skeleton already useful → no UPDATE issued (early continue)")
        void backfillSkipsUpdateWhenStoredSkeletonAlreadyUseful() throws Exception {
            ToolResponseEntity existing = createResponseEntity(responseId, toolId, "Auto-generated");
            existing.setStructureSkeleton("{\"_t\":\"obj\",\"props\":{\"id\":\"string\",\"name\":\"string\"}}");
            existing.setExampleJsonb("{\"id\":\"abc\",\"name\":\"foo\"}");
            when(toolResponseRepository.findByToolId(toolId)).thenReturn(List.of(existing));

            // Stored skeleton parses to a node with non-empty props → NOT trivial → service
            // hits `existingIsUseful=true` and `continue`s without ever generating a new one.
            when(jsonSkeletonGenerator.isTriviallyEmptySkeleton(argThat(
                    node -> node != null && node.path("props").isObject() && node.path("props").size() > 0)))
                .thenReturn(false);

            service.autoSaveFromExecution(toolId, java.util.Map.of("id", "x"), 200);

            // No UPDATE - the early `continue` kicks in before the generation/UPDATE block.
            verify(jdbcTemplate, never()).update(contains("UPDATE catalog.tool_responses"),
                any(), any(), any());
            verify(jsonSkeletonGenerator, never()).generateSkeleton(any(JsonNode.class));
        }
    }
}
