package com.apimarketplace.catalog.service.submission;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.repository.*;
import com.apimarketplace.catalog.service.ProtocolConfigService;
import com.apimarketplace.catalog.service.ToolCategoryService;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApiSubmissionOrchestrator.
 *
 * ApiSubmissionOrchestrator handles processing of API submissions including
 * creation of APIs, tools, categories, subcategories, and monetization configs.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiSubmissionOrchestrator")
class ApiSubmissionOrchestratorTest {

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiToolRepository apiToolRepository;

    @Mock
    private ApiCategoryRepository categoryRepository;

    @Mock
    private ApiSubcategoryRepository subcategoryRepository;

    @Mock
    private ApiToolMonetizationRepository monetizationRepository;

    @Mock
    private ToolCategoryRepository toolCategoryRepository;

    @Mock
    private ToolNameRepository toolNameRepository;

    @Mock
    private ToolCategoryService toolCategoryService;

    @Mock
    private ToolResponseService toolResponseService;

    @Mock
    private ProtocolConfigService protocolConfigService;

    @Mock
    private com.apimarketplace.common.security.CredentialEncryptionService encryptionService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private org.springframework.data.jdbc.core.JdbcAggregateTemplate jdbcAggregateTemplate;

    @Mock
    private ApiSlugService apiSlugService;

    @Mock
    private ToolMonetizationService toolMonetizationService;

    @Mock
    private ToolParameterService toolParameterService;

    @InjectMocks
    private ApiSubmissionOrchestrator orchestrator;

    private ObjectMapper objectMapper;
    private UUID testCategoryId;
    private UUID testSubcategoryId;
    private UUID testApiId;
    private MockedStatic<UrlSafetyValidator> urlValidatorMock;

    @AfterEach
    void tearDown() {
        if (urlValidatorMock != null) {
            urlValidatorMock.close();
        }
    }

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        testCategoryId = UUID.randomUUID();
        testSubcategoryId = UUID.randomUUID();
        testApiId = UUID.randomUUID();

        // Mock UrlSafetyValidator to bypass DNS resolution in tests
        urlValidatorMock = mockStatic(UrlSafetyValidator.class);
        urlValidatorMock.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);
        urlValidatorMock.when(() -> UrlSafetyValidator.validateUrlFormat(anyString())).thenAnswer(inv -> null);

        // Common mocks for category/subcategory lookups used across tests
        // These are lenient so they only apply when the tests reach subcategory creation
        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setId(testCategoryId);
        category.setName("Test Category");
        category.setSlug("test-category");
        when(categoryRepository.findById(testCategoryId)).thenReturn(Optional.of(category));
        when(categoryRepository.findById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            if (id.equals(testCategoryId)) {
                return Optional.of(category);
            }
            return Optional.empty();
        });

        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setId(testSubcategoryId);
        subcategory.setCategoryId(testCategoryId);
        subcategory.setName("Test Subcategory");
        subcategory.setSlug("test-subcategory");
        subcategory.setSortOrder(1);
        when(subcategoryRepository.findByCategoryId(testCategoryId)).thenReturn(new ArrayList<>(List.of(subcategory)));
        when(subcategoryRepository.findByCategoryId(any(UUID.class))).thenAnswer(inv -> {
            UUID catId = inv.getArgument(0);
            if (catId.equals(testCategoryId)) {
                return new ArrayList<>(List.of(subcategory));
            }
            return new ArrayList<>();
        });
        when(subcategoryRepository.existsById(testSubcategoryId)).thenReturn(true);

        // Mock for API slug generation when not provided in payload
        when(apiSlugService.generateUniqueSlug(anyString(), anyString())).thenReturn("test-api");
    }

    // ========================================================================
    // process() tests
    // ========================================================================

    @Nested
    @DisplayName("process()")
    class ProcessTests {

        @Test
        @DisplayName("should successfully process API submission with tools")
        void shouldSuccessfullyProcessApiSubmissionWithTools() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            tools.add(createBasicToolNode("get_users", "/api/users", "GET"));

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiToolEntity savedTool = createTestTool(savedApi.getId());

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenReturn(savedTool);
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            ApiEntity result = orchestrator.process(command);

            // Assert
            assertNotNull(result);
            assertEquals(savedApi.getId(), result.getId());
            verify(apiRepository).save(any(ApiEntity.class));
            verify(apiToolRepository, atLeastOnce()).save(any(ApiToolEntity.class));
        }

        @Test
        @DisplayName("stamps custom API with the active organization scope")
        void stampsCustomApiWithActiveOrganizationScope() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.put("source", "custom");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            TenantResolver.runWithOrgScope("org-123", () -> orchestrator.process(command));

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals("org-123", apiCaptor.getValue().getOrganizationId());
        }

        @Test
        @DisplayName("should throw exception with friendly message on duplicate API name")
        void shouldThrowExceptionWithFriendlyMessageOnDuplicateApiName() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class)))
                    .thenThrow(new DuplicateKeyException("Duplicate key"));

            // Act & Assert
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    orchestrator.process(command)
            );
            assertTrue(exception.getMessage().contains("already exists"));
        }

        @Test
        @DisplayName("should wrap generic exceptions with processing error message")
        void shouldWrapGenericExceptionsWithProcessingErrorMessage() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class)))
                    .thenThrow(new RuntimeException("Database error"));

            // Act & Assert
            RuntimeException exception = assertThrows(RuntimeException.class, () ->
                    orchestrator.process(command)
            );
            assertTrue(exception.getMessage().contains("Processing error"));
        }

        @Test
        @DisplayName("should save monetization for each tool")
        void shouldSaveMonetizationForEachTool() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            tools.add(createBasicToolNode("get_users", "/api/users", "GET"));

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiToolEntity savedTool = createTestTool(savedApi.getId());
            ApiToolMonetizationEntity monetization = new ApiToolMonetizationEntity();
            monetization.setMonetizationType("FREEMIUM");
            monetization.setPlanName("Free");

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenReturn(savedTool);
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(List.of(monetization));
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            verify(monetizationRepository).save(monetization);
        }
    }

    // ========================================================================
    // Category resolution tests
    // ========================================================================

    @Nested
    @DisplayName("Category Resolution")
    class CategoryResolutionTests {

        @Test
        @DisplayName("should use provided category ID when valid")
        void shouldUseProvidedCategoryIdWhenValid() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.put("categoryId", testCategoryId.toString());
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals(testCategoryId, apiCaptor.getValue().getCategoryId());
        }

        @Test
        @DisplayName("should create custom category when isCustomCategory is true")
        void shouldCreateCustomCategoryWhenIsCustomCategoryTrue() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.put("isCustomCategory", true);
            payload.put("selectedCategory", "My Custom Category");
            payload.remove("categoryId");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            UUID newCategoryId = UUID.randomUUID();

            // No existing category found
            when(categoryRepository.findBySlug(anyString())).thenReturn(Optional.empty());
            when(categoryRepository.findByName(anyString())).thenReturn(Optional.empty());
            // When a new category is created, return a mock entity for findById
            when(categoryRepository.findById(any(UUID.class))).thenAnswer(inv -> {
                UUID id = inv.getArgument(0);
                ApiCategoryEntity mockCat = new ApiCategoryEntity();
                mockCat.setId(id);
                mockCat.setName("My Custom Category");
                mockCat.setSlug("my-custom-category");
                return Optional.of(mockCat);
            });
            when(jdbcTemplate.update(contains("INSERT INTO api_categories"), any(Object[].class)))
                    .thenReturn(1);
            when(subcategoryRepository.findByCategoryId(any())).thenReturn(new ArrayList<>());
            when(jdbcTemplate.update(contains("INSERT INTO api_subcategories"), any(Object[].class)))
                    .thenReturn(1);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            verify(jdbcTemplate).update(contains("INSERT INTO api_categories"), any(Object[].class));
        }

        @Test
        @DisplayName("should use default 'Other' category when no category specified")
        void shouldUseDefaultOtherCategoryWhenNoCategorySpecified() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.remove("categoryId");
            payload.put("selectedCategory", "");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiCategoryEntity otherCategory = new ApiCategoryEntity();
            otherCategory.setId(UUID.randomUUID());
            otherCategory.setName("Other");

            when(categoryRepository.findBySlug("other")).thenReturn(Optional.of(otherCategory));
            when(categoryRepository.findById(otherCategory.getId())).thenReturn(Optional.of(otherCategory));
            when(subcategoryRepository.findByCategoryId(otherCategory.getId()))
                    .thenReturn(new ArrayList<>());
            when(jdbcTemplate.update(contains("INSERT INTO api_subcategories"), any(Object[].class)))
                    .thenReturn(1);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals(otherCategory.getId(), apiCaptor.getValue().getCategoryId());
        }
    }

    // ========================================================================
    // Subcategory resolution tests
    // ========================================================================

    @Nested
    @DisplayName("Subcategory Resolution")
    class SubcategoryResolutionTests {

        @Test
        @DisplayName("should use provided subcategory ID when valid")
        void shouldUseProvidedSubcategoryIdWhenValid() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.put("subcategoryId", testSubcategoryId.toString());
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(subcategoryRepository.existsById(testSubcategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals(testSubcategoryId, apiCaptor.getValue().getSubcategoryId());
        }

        @Test
        @DisplayName("should use first available subcategory when none specified")
        void shouldUseFirstAvailableSubcategoryWhenNoneSpecified() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.put("selectedSubcategory", "");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiSubcategoryEntity existingSubcategory = new ApiSubcategoryEntity();
            existingSubcategory.setId(UUID.randomUUID());
            existingSubcategory.setSortOrder(1);

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(subcategoryRepository.findByCategoryId(testCategoryId))
                    .thenReturn(new ArrayList<>(List.of(existingSubcategory)));
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals(existingSubcategory.getId(), apiCaptor.getValue().getSubcategoryId());
        }
    }

    // ========================================================================
    // Tool creation tests
    // ========================================================================

    @Nested
    @DisplayName("Tool Creation")
    class ToolCreationTests {

        @Test
        @DisplayName("should create tool with HTTP protocol by default")
        void shouldCreateToolWithHttpProtocolByDefault() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode toolNode = createBasicToolNode("get_users", "/api/users", "GET");
            toolNode.remove("protocol");
            tools.add(toolNode);

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenAnswer(inv -> {
                ApiToolEntity tool = inv.getArgument(0);
                tool.setId(UUID.randomUUID());
                return tool;
            });
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiToolEntity> toolCaptor = ArgumentCaptor.forClass(ApiToolEntity.class);
            verify(apiToolRepository, atLeastOnce()).save(toolCaptor.capture());
            // Get the first saved tool (initial save before slug generation)
            assertEquals("HTTP", toolCaptor.getAllValues().get(0).getProtocol());
        }

        @Test
        @DisplayName("should set tool status to REVIEWING by default")
        void shouldSetToolStatusToReviewingByDefault() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            tools.add(createBasicToolNode("get_users", "/api/users", "GET"));

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenAnswer(inv -> {
                ApiToolEntity tool = inv.getArgument(0);
                tool.setId(UUID.randomUUID());
                return tool;
            });
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiToolEntity> toolCaptor = ArgumentCaptor.forClass(ApiToolEntity.class);
            verify(apiToolRepository, atLeastOnce()).save(toolCaptor.capture());
            // Get the first saved tool (initial save before slug generation)
            ApiToolEntity capturedTool = toolCaptor.getAllValues().get(0);
            assertEquals("REVIEWING", capturedTool.getStatus());
            assertEquals("PENDING", capturedTool.getTestStatus());
        }

        @Test
        @DisplayName("should persist protocol config after tool save")
        void shouldPersistProtocolConfigAfterToolSave() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode toolNode = createBasicToolNode("get_users", "/api/users", "GET");
            tools.add(toolNode);

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiToolEntity savedTool = createTestTool(savedApi.getId());

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenReturn(savedTool);
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            verify(protocolConfigService).persistProtocolConfig(eq(savedTool), any(JsonNode.class));
        }
    }

    // ========================================================================
    // Stable UUID tests (catalog-service-import round-trip)
    // ========================================================================

    /**
     * Tests that a payload carrying {@code apiId} (top-level) and {@code id}
     * (per tool) - the shape emitted by {@code ApiMigrationImporter} after the
     * stable-UUID hardening - actually survives into the entities handed to
     * the persistence layer.
     *
     * <p>The contract we're defending is the one in
     * {@code scripts/api-migrations/SCHEMA.md} "Stable UUIDs": re-imports after
     * a TRUNCATE reproduce the same primary keys. If these tests regress, then
     * `{{mcp:<tool_uuid>.output.*}}` agent refs and pinned production runs
     * would silently break on every reimport.
     *
     * <p>The non-UUID path (legacy frontend create-API) is also tested so we
     * know we never accidentally route a null-id entity through
     * {@code JdbcAggregateTemplate.insert()} (which would INSERT NULL and
     * blow up on the non-null PK constraint).
     */
    @Nested
    @DisplayName("Stable UUID (importer round-trip)")
    class StableUuidTests {

        @Test
        @DisplayName("apiId in payload → jdbcAggregateTemplate.insert() called with matching UUID")
        void apiIdPreassignedRoutesToInsert() {
            // Arrange
            UUID preassignedApiId = UUID.fromString("11111111-2222-3333-4444-555555555555");

            ObjectNode payload = createBasicPayload();
            payload.put("apiId", preassignedApiId.toString());
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            savedApi.setId(preassignedApiId);

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            // Echo back the entity passed to insert() so the rest of process() sees
            // the pre-assigned id downstream (no tools to save, so this is all we need).
            when(jdbcAggregateTemplate.insert(any(ApiEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(jdbcAggregateTemplate).insert(apiCaptor.capture());
            assertEquals(preassignedApiId, apiCaptor.getValue().getId(),
                    "createApiFromSubmission must copy payload.apiId verbatim into ApiEntity.id");
            // And it must NOT have taken the legacy save() path.
            verify(apiRepository, never()).save(any(ApiEntity.class));
        }

        @Test
        @DisplayName("tool id in payload → jdbcAggregateTemplate.insert(tool) called with matching UUID")
        void toolIdPreassignedRoutesToInsert() {
            // Arrange
            UUID preassignedApiId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
            UUID preassignedToolId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

            ObjectNode payload = createBasicPayload();
            payload.put("apiId", preassignedApiId.toString());

            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode toolNode = createBasicToolNode("get_users", "/api/users", "GET");
            toolNode.put("id", preassignedToolId.toString());
            tools.add(toolNode);

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            savedApi.setId(preassignedApiId);

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(jdbcAggregateTemplate.insert(any(ApiEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(jdbcAggregateTemplate.insert(any(ApiToolEntity.class))).thenAnswer(inv -> inv.getArgument(0));
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiToolEntity> toolCaptor = ArgumentCaptor.forClass(ApiToolEntity.class);
            verify(jdbcAggregateTemplate).insert(toolCaptor.capture());
            assertEquals(preassignedToolId, toolCaptor.getValue().getId(),
                    "createToolFromData must copy payload tool.id verbatim into ApiToolEntity.id");
            // The legacy save() path must NOT have been used for the initial insert.
            // (A later slug-update save() is allowed - that hits the UPDATE branch.)
            verify(apiToolRepository, never()).save(argThat(t -> t != null && t.getId() == null));
        }

        @Test
        @DisplayName("no apiId → legacy path: apiRepository.save() called, insert(ApiEntity) never called")
        void missingApiIdRoutesToLegacySave() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            // Intentionally NO apiId - legacy frontend create-API flow.
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            verify(apiRepository).save(any(ApiEntity.class));
            verify(jdbcAggregateTemplate, never()).insert(any(ApiEntity.class));
        }

        @Test
        @DisplayName("malformed apiId → IllegalArgumentException wrapped in 'Processing error'")
        void malformedApiIdThrows() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.put("apiId", "not-a-uuid");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            // Act & Assert
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> orchestrator.process(command));
            // process() wraps everything in a RuntimeException("Processing error: " + ...).
            assertTrue(ex.getMessage().contains("must be a valid UUID"),
                    "Expected UUID validation error, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("not-a-uuid"),
                    "Expected echo of the bad value, got: " + ex.getMessage());
            verify(jdbcAggregateTemplate, never()).insert(any(ApiEntity.class));
            verify(apiRepository, never()).save(any(ApiEntity.class));
        }

        @Test
        @DisplayName("malformed tool id → IllegalArgumentException wrapped in 'Processing error'")
        void malformedToolIdThrows() {
            // Arrange
            UUID preassignedApiId = UUID.fromString("11111111-2222-3333-4444-555555555555");
            ObjectNode payload = createBasicPayload();
            payload.put("apiId", preassignedApiId.toString());

            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode toolNode = createBasicToolNode("broken_tool", "/api/broken", "GET");
            toolNode.put("id", "also-not-a-uuid");
            tools.add(toolNode);

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            savedApi.setId(preassignedApiId);
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(jdbcAggregateTemplate.insert(any(ApiEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            // Act & Assert
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> orchestrator.process(command));
            assertTrue(ex.getMessage().contains("must be a valid UUID"),
                    "Expected UUID validation error, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("also-not-a-uuid"),
                    "Expected echo of the bad tool id, got: " + ex.getMessage());
            assertTrue(ex.getMessage().contains("broken_tool"),
                    "Expected tool name in error message, got: " + ex.getMessage());
            verify(jdbcAggregateTemplate, never()).insert(any(ApiToolEntity.class));
        }
    }

    // ========================================================================
    // Custom tool data tests
    // ========================================================================

    @Nested
    @DisplayName("Custom Tool Data Processing")
    class CustomToolDataTests {

        @Test
        @DisplayName("should create custom tool category when isCustomCategory is true")
        void shouldCreateCustomToolCategoryWhenIsCustomCategoryTrue() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode toolNode = createBasicToolNode("custom_tool", "/api/custom", "POST");
            toolNode.put("isCustomCategory", true);
            toolNode.put("toolCategory", "Custom Tool Category");
            tools.add(toolNode);

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiToolEntity savedTool = createTestTool(savedApi.getId());
            ToolCategoryEntity toolCategory = new ToolCategoryEntity();
            toolCategory.setId(UUID.randomUUID());

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenReturn(savedTool);
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolCategoryBySlug(anyString()))
                    .thenReturn(Optional.empty());
            when(toolCategoryService.getToolCategoryByName(anyString()))
                    .thenReturn(Optional.empty());
            when(jdbcTemplate.update(contains("INSERT INTO tool_categories"), any(Object[].class)))
                    .thenReturn(1);
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            verify(jdbcTemplate).update(contains("INSERT INTO tool_categories"), any(Object[].class));
        }

        @Test
        @DisplayName("should reuse existing tool category when found by slug")
        void shouldReuseExistingToolCategoryWhenFoundBySlug() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode toolNode = createBasicToolNode("get_data", "/api/data", "GET");
            toolNode.put("toolCategory", "Existing Category");
            tools.add(toolNode);

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiToolEntity savedTool = createTestTool(savedApi.getId());
            ToolCategoryEntity existingCategory = new ToolCategoryEntity();
            existingCategory.setId(UUID.randomUUID());
            existingCategory.setName("Existing Category");

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenReturn(savedTool);
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolCategoryBySlug(anyString()))
                    .thenReturn(Optional.of(existingCategory));
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            verify(jdbcTemplate, never()).update(contains("INSERT INTO tool_categories"), any(Object[].class));
        }
    }

    // ========================================================================
    // API configuration tests
    // ========================================================================

    @Nested
    @DisplayName("API Configuration")
    class ApiConfigurationTests {

        @Test
        @DisplayName("should set visibility to public by default")
        void shouldSetVisibilityToPublicByDefault() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals("public", apiCaptor.getValue().getVisibility());
            assertTrue(apiCaptor.getValue().getIsPublic());
        }

        @Test
        @DisplayName("should set auth type from authorization config")
        void shouldSetAuthTypeFromAuthorizationConfig() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ObjectNode apiConfig = objectMapper.createObjectNode();
            apiConfig.put("baseUrl", "https://api.example.com");
            ObjectNode auth = objectMapper.createObjectNode();
            auth.put("type", "bearer");
            auth.put("headerName", "Authorization");
            auth.put("headerValue", "Bearer token123");
            apiConfig.set("authorization", auth);
            payload.set("apiConfig", apiConfig);
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals("bearer", apiCaptor.getValue().getAuthType());
            assertEquals("Authorization", apiCaptor.getValue().getAuthHeaderName());
        }

        @Test
        @DisplayName("should set platformCredentialName from submission data and silently ignore legacy credentialMode field (V154)")
        void shouldSetPlatformCredentialNameAndIgnoreLegacyCredentialMode() {
            // Arrange - payload simulates a legacy submission still declaring
            // `credentialMode` (now silently ignored by ApiSubmissionOrchestrator).
            ObjectNode payload = createBasicPayload();
            payload.put("credentialMode", "platform_key"); // legacy field, must be ignored
            payload.put("platformCredentialName", "my-platform-key");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert - platformCredentialName is the only field that should
            // round-trip; credentialMode is silently dropped by the importer
            // since V154 (column no longer exists on ApiEntity).
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals("my-platform-key", apiCaptor.getValue().getPlatformCredentialName());
        }
    }

    // ========================================================================
    // Slug generation tests
    // ========================================================================

    @Nested
    @DisplayName("Slug Generation")
    class SlugGenerationTests {

        @Test
        @DisplayName("should use provided API slug when available")
        void shouldUseProvidedApiSlugWhenAvailable() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.put("apiSlug", "custom-api-slug");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals("custom-api-slug", apiCaptor.getValue().getApiSlug());
        }

        @Test
        @DisplayName("should generate API slug from name when not provided")
        void shouldGenerateApiSlugFromNameWhenNotProvided() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.remove("apiSlug");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiSlugService.generateUniqueSlug(anyString(), anyString()))
                    .thenReturn("test-api-generated");
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            verify(apiSlugService).generateUniqueSlug(anyString(), eq("user-123"));
        }

        @Test
        @DisplayName("should derive icon slug from API slug")
        void shouldDeriveIconSlugFromApiSlug() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            payload.put("apiSlug", "google-sheets-api");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            // Act
            orchestrator.process(command);

            // Assert
            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            // "google-sheets-api" -> "google-sheets" -> "googlesheets"
            assertEquals("googlesheets", apiCaptor.getValue().getIconSlug());
        }
    }

    // ========================================================================
    // Icon mapping tests
    // ========================================================================

    @Nested
    @DisplayName("Icon Slug Derivation")
    class IconMappingTests {

        @Test
        @DisplayName("derives iconSlug from apiSlug, stripping the -api suffix (no brand-family collapsing)")
        void shouldDeriveSlugFromApiSlug() {
            // The earlier KNOWN_ICON_MAPPINGS table that collapsed "dalle" → "openai" was
            // removed in May 2026 (one icon = one API). The normalizer now only strips
            // the -api suffix and non-alphanumerics - distinct APIs keep distinct slugs.
            ObjectNode payload = createBasicPayload();
            payload.put("apiSlug", "dalle-api");
            ArrayNode tools = objectMapper.createArrayNode();

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);

            orchestrator.process(command);

            ArgumentCaptor<ApiEntity> apiCaptor = ArgumentCaptor.forClass(ApiEntity.class);
            verify(apiRepository).save(apiCaptor.capture());
            assertEquals("dalle", apiCaptor.getValue().getIconSlug());
        }
    }

    // ========================================================================
    // Tool response persistence tests
    // ========================================================================

    @Nested
    @DisplayName("Tool Response Persistence")
    class ToolResponsePersistenceTests {

        @Test
        @DisplayName("should persist tool response when response block is present")
        void shouldPersistToolResponseWhenResponseBlockIsPresent() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode toolNode = createBasicToolNode("get_users", "/api/users", "GET");
            ObjectNode response = objectMapper.createObjectNode();
            response.put("type", "json");
            response.put("statusCode", 200);
            response.set("example", objectMapper.createObjectNode().put("id", 1));
            toolNode.set("response", response);
            tools.add(toolNode);

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiToolEntity savedTool = createTestTool(savedApi.getId());

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenReturn(savedTool);
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            verify(toolResponseService).createResponse(any(), eq("user-123"));
        }

        @Test
        @DisplayName("should skip response persistence when no response block")
        void shouldSkipResponsePersistenceWhenNoResponseBlock() {
            // Arrange
            ObjectNode payload = createBasicPayload();
            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode toolNode = createBasicToolNode("get_users", "/api/users", "GET");
            tools.add(toolNode);

            ApiSubmissionCommand command = new ApiSubmissionCommand(
                    payload, "user-123", toList(tools)
            );

            ApiEntity savedApi = createTestApi();
            ApiToolEntity savedTool = createTestTool(savedApi.getId());

            when(categoryRepository.existsById(testCategoryId)).thenReturn(true);
            when(apiRepository.save(any(ApiEntity.class))).thenReturn(savedApi);
            when(apiToolRepository.save(any(ApiToolEntity.class))).thenReturn(savedTool);
            when(toolMonetizationService.createMonetizationFromApiData(any(), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(), any()))
                    .thenReturn(Collections.emptyList());
            when(toolCategoryService.getToolNameByToolNameId(anyString()))
                    .thenReturn(Optional.of(createTestToolName()));

            // Act
            orchestrator.process(command);

            // Assert
            verify(toolResponseService, never()).createResponse(any(), any());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ObjectNode createBasicPayload() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("apiName", "Test API");
        payload.put("apiDescription", "A test API");
        payload.put("categoryId", testCategoryId.toString());
        ObjectNode apiConfig = objectMapper.createObjectNode();
        apiConfig.put("baseUrl", "https://api.example.com");
        payload.set("apiConfig", apiConfig);
        return payload;
    }

    private ObjectNode createBasicToolNode(String name, String endpoint, String method) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("name", name);
        tool.put("endpoint", endpoint);
        tool.put("method", method);
        tool.put("description", "Test tool: " + name);
        tool.put("protocol", "HTTP");
        tool.put("toolNameId", UUID.randomUUID().toString());
        return tool;
    }

    private ApiEntity createTestApi() {
        ApiEntity api = new ApiEntity();
        api.setId(testApiId);
        api.setApiName("Test API");
        api.setApiSlug("test-api");
        api.setCategoryId(testCategoryId);
        api.setSubcategoryId(testSubcategoryId);
        return api;
    }

    private ApiToolEntity createTestTool(UUID apiId) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(UUID.randomUUID());
        tool.setApiId(apiId);
        tool.setToolNameId(UUID.randomUUID().toString());
        tool.setMethod("GET");
        tool.setEndpoint("/api/test");
        tool.setProtocol("HTTP");
        return tool;
    }

    private ToolNameEntity createTestToolName() {
        ToolNameEntity toolName = new ToolNameEntity();
        toolName.setId(UUID.randomUUID());
        toolName.setName("Test Tool");
        toolName.setSlug("test-tool");
        return toolName;
    }

    private ApiCategoryEntity createTestCategory() {
        ApiCategoryEntity category = new ApiCategoryEntity();
        category.setId(testCategoryId);
        category.setName("Test Category");
        category.setSlug("test-category");
        return category;
    }

    private ApiSubcategoryEntity createTestSubcategory() {
        ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
        subcategory.setId(testSubcategoryId);
        subcategory.setCategoryId(testCategoryId);
        subcategory.setName("Test Subcategory");
        subcategory.setSlug("test-subcategory");
        subcategory.setSortOrder(1);
        return subcategory;
    }

    private List<JsonNode> toList(ArrayNode arrayNode) {
        List<JsonNode> result = new ArrayList<>();
        arrayNode.forEach(result::add);
        return result;
    }
}
