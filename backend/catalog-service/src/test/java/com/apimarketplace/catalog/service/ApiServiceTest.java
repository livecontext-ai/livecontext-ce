package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.domain.dto.*;
import com.apimarketplace.catalog.repository.*;
import com.apimarketplace.catalog.service.http.HttpExecutionService;
import com.apimarketplace.catalog.service.monetization.MonetizationService;
import com.apimarketplace.catalog.service.submission.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApiService.
 *
 * ApiService processes developer API submissions and manages their execution.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiService")
class ApiServiceTest {

    @Mock private ApiRepository apiRepository;
    @Mock private ApiToolRepository apiToolRepository;
    @Mock private ApiCategoryRepository categoryRepository;
    @Mock private ApiSubcategoryRepository subcategoryRepository;
    @Mock private ToolCategoryService toolCategoryService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private ApiSubmissionCommandFactory submissionCommandFactory;
    @Mock private ApiSubmissionOrchestrator submissionOrchestrator;
    @Mock private ApiSlugService apiSlugService;
    @Mock private UserCredentialService userCredentialService;
    @Mock private HttpExecutionService httpExecutionService;
    @Mock private MonetizationService monetizationService;
    @Mock private ApiToolParameterService parameterService;
    @Mock private ProtocolConfigService protocolConfigService;
    @Mock private ApiResponseConverter responseConverter;
    @Mock private RestTemplate restTemplate;

    private ObjectMapper objectMapper;
    private ApiService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ApiService(
            apiRepository, apiToolRepository, categoryRepository, subcategoryRepository,
            toolCategoryService, jdbcTemplate, objectMapper,
            submissionCommandFactory, submissionOrchestrator, apiSlugService,
            userCredentialService, httpExecutionService, monetizationService,
            parameterService, protocolConfigService, responseConverter, restTemplate
        );
        // In production, Spring injects a proxy to this bean as `self` so that
        // @Transactional boundaries fire correctly across intra-service calls.
        // In unit tests we wire `self` to the same instance - AOP is out of
        // scope here, the goal is that the split methods delegate correctly.
        ReflectionTestUtils.setField(service, "self", service);
    }

    // ========================================================================
    // getToolCredentials tests
    // ========================================================================

    @Nested
    @DisplayName("getToolCredentials()")
    class GetToolCredentialsTests {

        @Test
        @DisplayName("should qualify credential lookup against catalog schema")
        void shouldQualifyCredentialLookupAgainstCatalogSchema() {
            UUID toolId = UUID.randomUUID();
            when(jdbcTemplate.query(
                    argThat(sql -> sql.contains("FROM catalog.tool_credentials tc")
                            && sql.contains("LEFT JOIN catalog.credentials c ON tc.credential_id = c.id")),
                    any(org.springframework.jdbc.core.RowMapper.class),
                    eq(toolId)
            )).thenReturn(List.of());

            List<Map<String, Object>> result = service.getToolCredentials(toolId);

            assertTrue(result.isEmpty());
            verify(jdbcTemplate).query(
                    argThat(sql -> sql.contains("FROM catalog.tool_credentials tc")
                            && sql.contains("LEFT JOIN catalog.credentials c ON tc.credential_id = c.id")),
                    any(org.springframework.jdbc.core.RowMapper.class),
                    eq(toolId)
            );
        }
    }

    // ========================================================================
    // getToolName tests
    // ========================================================================

    @Nested
    @DisplayName("getToolName()")
    class GetToolNameTests {

        @Test
        @DisplayName("should return tool name from toolCategoryService")
        void shouldReturnToolNameFromToolCategoryService() {
            // Arrange
            ApiToolEntity tool = new ApiToolEntity();
            tool.setToolNameId("tool-name-id");

            ToolNameEntity toolName = new ToolNameEntity();
            toolName.setName("Weather API");
            when(toolCategoryService.getToolNameByToolNameId("tool-name-id"))
                .thenReturn(Optional.of(toolName));

            // Act
            String result = service.getToolName(tool);

            // Assert
            assertEquals("Weather API", result);
        }

        @Test
        @DisplayName("should return 'Unknown Tool' when toolNameId is null")
        void shouldReturnUnknownToolWhenToolNameIdIsNull() {
            // Arrange
            ApiToolEntity tool = new ApiToolEntity();
            tool.setToolNameId(null);

            // Act
            String result = service.getToolName(tool);

            // Assert
            assertEquals("Unknown Tool", result);
        }

        @Test
        @DisplayName("should return 'Unknown Tool' when toolNameId is empty")
        void shouldReturnUnknownToolWhenToolNameIdIsEmpty() {
            // Arrange
            ApiToolEntity tool = new ApiToolEntity();
            tool.setToolNameId("   ");

            // Act
            String result = service.getToolName(tool);

            // Assert
            assertEquals("Unknown Tool", result);
        }

        @Test
        @DisplayName("should return 'Unknown Tool' when tool name not found")
        void shouldReturnUnknownToolWhenNotFound() {
            // Arrange
            ApiToolEntity tool = new ApiToolEntity();
            tool.setToolNameId("non-existent");

            when(toolCategoryService.getToolNameByToolNameId("non-existent"))
                .thenReturn(Optional.empty());

            // Act
            String result = service.getToolName(tool);

            // Assert
            assertEquals("Unknown Tool", result);
        }
    }

    // ========================================================================
    // processApiSubmission tests
    // ========================================================================

    @Nested
    @DisplayName("processApiSubmission()")
    class ProcessApiSubmissionTests {

        @Test
        @DisplayName("should process submission and return response")
        void shouldProcessSubmissionAndReturnResponse() throws Exception {
            // Arrange
            JsonNode submissionData = objectMapper.createObjectNode().put("apiName", "Test API");
            String userId = "user-123";
            ApiSubmissionCommand command = mock(ApiSubmissionCommand.class);
            ApiEntity savedApi = createTestApi();
            ApiResponse expectedResponse = createTestApiResponse();

            when(command.apiName()).thenReturn("Test API");
            when(submissionCommandFactory.from(any(JsonNode.class), eq(userId))).thenReturn(command);
            when(submissionOrchestrator.process(command)).thenReturn(savedApi);
            when(responseConverter.toApiResponse(eq(savedApi), any())).thenReturn(expectedResponse);

            // Act
            ApiResponse result = service.processApiSubmission(submissionData, userId);

            // Assert
            assertNotNull(result);
            verify(submissionCommandFactory).from(any(), eq(userId));
            verify(submissionOrchestrator).process(command);
        }
    }

    // ========================================================================
    // executeApiTool tests
    // ========================================================================

    @Nested
    @DisplayName("executeApiTool()")
    class ExecuteApiToolTests {

        @Test
        @DisplayName("should execute tool and return result with metadata")
        void shouldExecuteToolAndReturnResult() throws Exception {
            // Arrange
            UUID apiId = UUID.randomUUID();
            String toolName = "getWeather";
            JsonNode parameters = objectMapper.createObjectNode();
            String userId = "user-123";

            ApiEntity api = createTestApi();
            api.setId(apiId);

            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId("tool-name-id");
            tool.setProtocol("HTTP");

            ToolNameEntity toolNameEntity = new ToolNameEntity();
            toolNameEntity.setName(toolName);

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
            when(toolCategoryService.getToolNameByToolNameId("tool-name-id"))
                .thenReturn(Optional.of(toolNameEntity));
            // Use any() to match the UUID since we can't guarantee exact match
            when(jdbcTemplate.queryForList(anyString(), any(UUID.class)))
                .thenReturn(List.of());
            when(httpExecutionService.executeHttpCallWithCredentials(any(), any(), any(), any(), any(), any()))
                .thenReturn(new HashMap<>(Map.of("success", true, "data", "result")));

            // Act
            Map<String, Object> result = service.executeApiTool(apiId.toString(), toolName, parameters, null, userId);

            // Assert
            assertNotNull(result);
            // The result should contain toolName and apiId
            assertEquals(toolName, result.get("toolName"));
            assertEquals(apiId.toString(), result.get("apiId"));
        }

        @Test
        @DisplayName("should return error when API not found")
        void shouldReturnErrorWhenApiNotFound() throws Exception {
            // Arrange
            String apiId = UUID.randomUUID().toString();
            JsonNode parameters = objectMapper.createObjectNode();

            when(apiRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

            // Act
            Map<String, Object> result = service.executeApiTool(apiId, "toolName", parameters);

            // Assert
            assertFalse((Boolean) result.get("success"));
            assertTrue(result.get("error").toString().contains("API not found"));
        }

        @Test
        @DisplayName("should return error for unsupported protocol")
        void shouldReturnErrorForUnsupportedProtocol() throws Exception {
            // Arrange
            UUID apiId = UUID.randomUUID();
            String toolName = "mqttTool";
            JsonNode parameters = objectMapper.createObjectNode();

            ApiEntity api = createTestApi();
            api.setId(apiId);

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(UUID.randomUUID());
            tool.setApiId(apiId);
            tool.setToolNameId("tool-name-id");
            tool.setProtocol("MQTT");

            ToolNameEntity toolNameEntity = new ToolNameEntity();
            toolNameEntity.setName(toolName);

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
            when(toolCategoryService.getToolNameByToolNameId("tool-name-id"))
                .thenReturn(Optional.of(toolNameEntity));

            // Act
            Map<String, Object> result = service.executeApiTool(apiId.toString(), toolName, parameters);

            // Assert
            assertFalse((Boolean) result.get("success"));
            assertTrue(result.get("error").toString().contains("not supported"));
        }
    }

    // ========================================================================
    // testApiConnection tests
    // ========================================================================

    @Nested
    @DisplayName("testApiConnection()")
    class TestApiConnectionTests {

        @Test
        @DisplayName("should return success when health check passes")
        void shouldReturnSuccessWhenHealthCheckPasses() {
            // Arrange
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createTestApi();
            api.setId(apiId);
            api.setBaseUrl("http://api.example.com");

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(restTemplate.getForEntity(eq("http://api.example.com/health"), eq(Object.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

            // Act
            Map<String, Object> result = service.testApiConnection(apiId.toString());

            // Assert
            assertTrue((Boolean) result.get("success"));
            assertEquals(200, result.get("status"));
        }

        @Test
        @DisplayName("should return error when health check fails")
        void shouldReturnErrorWhenHealthCheckFails() {
            // Arrange
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createTestApi();
            api.setId(apiId);
            api.setBaseUrl("http://api.example.com");

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(restTemplate.getForEntity(anyString(), eq(Object.class)))
                .thenThrow(new RuntimeException("Connection refused"));

            // Act
            Map<String, Object> result = service.testApiConnection(apiId.toString());

            // Assert
            assertFalse((Boolean) result.get("success"));
            assertNotNull(result.get("error"));
        }

        @Test
        @DisplayName("should return error when API not found")
        void shouldReturnErrorWhenApiNotFoundForConnection() {
            // Arrange
            String apiId = UUID.randomUUID().toString();
            when(apiRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

            // Act
            Map<String, Object> result = service.testApiConnection(apiId);

            // Assert
            assertFalse((Boolean) result.get("success"));
        }
    }

    // ========================================================================
    // getApiById tests
    // ========================================================================

    @Nested
    @DisplayName("getApiById()")
    class GetApiByIdTests {

        @Test
        @DisplayName("should return API response when found")
        void shouldReturnApiResponseWhenFound() {
            // Arrange
            UUID id = UUID.randomUUID();
            ApiEntity api = createTestApi();
            api.setId(id);
            ApiResponse expectedResponse = createTestApiResponse();

            when(apiRepository.findById(id)).thenReturn(Optional.of(api));
            when(responseConverter.toApiResponse(eq(api), any(), eq(true))).thenReturn(expectedResponse);

            // Act
            ApiResponse result = service.getApiById(id);

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("should throw exception when API not found")
        void shouldThrowExceptionWhenApiNotFound() {
            // Arrange
            UUID id = UUID.randomUUID();
            when(apiRepository.findById(id)).thenReturn(Optional.empty());

            // Act & Assert
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.getApiById(id)
            );
            assertTrue(exception.getMessage().contains("API not found"));
        }
    }

    // ========================================================================
    // getApisByCreator tests
    // ========================================================================

    @Nested
    @DisplayName("getApisByCreator()")
    class GetApisByCreatorTests {

        @Test
        @DisplayName("should return list of APIs for creator")
        void shouldReturnListOfApisForCreator() {
            // Arrange
            String createdBy = "user-123";
            ApiEntity api1 = createTestApi();
            ApiEntity api2 = createTestApi();
            api2.setApiName("Second API");

            when(apiRepository.findByCreatedBy(createdBy)).thenReturn(List.of(api1, api2));
            when(responseConverter.toApiResponse(any(), any())).thenReturn(createTestApiResponse());

            // Act
            List<ApiResponse> result = service.getApisByCreator(createdBy);

            // Assert
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should return empty list when no APIs found")
        void shouldReturnEmptyListWhenNoApisFound() {
            // Arrange
            String createdBy = "unknown-user";
            when(apiRepository.findByCreatedBy(createdBy)).thenReturn(List.of());

            // Act
            List<ApiResponse> result = service.getApisByCreator(createdBy);

            // Assert
            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // deleteApi tests
    // ========================================================================

    @Nested
    @DisplayName("deleteApi()")
    class DeleteApiTests {

        @Test
        @DisplayName("should delete API and its tools")
        void shouldDeleteApiAndItsTools() {
            // Arrange
            UUID apiId = UUID.randomUUID();
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(UUID.randomUUID());
            tool.setApiId(apiId);

            when(apiRepository.existsById(apiId)).thenReturn(true);
            when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));

            // Act
            service.deleteApi(apiId);

            // Assert
            verify(monetizationService).deleteMonetizationsForTool(tool.getId());
            verify(parameterService).deleteAllParameters(tool.getId());
            verify(apiToolRepository).delete(tool);
            verify(apiRepository).deleteById(apiId);
        }

        @Test
        @DisplayName("should throw exception when API not found")
        void shouldThrowExceptionWhenApiNotFoundForDelete() {
            // Arrange
            UUID apiId = UUID.randomUUID();
            when(apiRepository.existsById(apiId)).thenReturn(false);

            // Act & Assert
            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> service.deleteApi(apiId)
            );
            assertTrue(exception.getMessage().contains("API not found"));
        }
    }

    // ========================================================================
    // isApiNameUniqueForUser tests
    // ========================================================================

    @Nested
    @DisplayName("isApiNameUniqueForUser()")
    class IsApiNameUniqueForUserTests {

        @Test
        @DisplayName("should return true when name is unique")
        void shouldReturnTrueWhenNameIsUnique() {
            // Arrange
            String apiName = "Unique API";
            String userId = "user-123";
            when(apiRepository.findByCreatedByAndApiName(userId, apiName)).thenReturn(List.of());

            // Act
            boolean result = service.isApiNameUniqueForUser(apiName, userId);

            // Assert
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when name exists for user")
        void shouldReturnFalseWhenNameExistsForUser() {
            // Arrange
            String apiName = "Existing API";
            String userId = "user-123";
            when(apiRepository.findByCreatedByAndApiName(userId, apiName))
                .thenReturn(List.of(createTestApi()));

            // Act
            boolean result = service.isApiNameUniqueForUser(apiName, userId);

            // Assert
            assertFalse(result);
        }
    }

    // ========================================================================
    // isApiSlugUnique tests
    // ========================================================================

    @Nested
    @DisplayName("isApiSlugUnique()")
    class IsApiSlugUniqueTests {

        @Test
        @DisplayName("should return true when slug is unique")
        void shouldReturnTrueWhenSlugIsUnique() {
            // Arrange
            String slug = "unique-api-slug";
            when(apiRepository.findByApiSlug(slug)).thenReturn(Optional.empty());

            // Act
            boolean result = service.isApiSlugUnique(slug);

            // Assert
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when slug exists")
        void shouldReturnFalseWhenSlugExists() {
            // Arrange
            String slug = "existing-slug";
            when(apiRepository.findByApiSlug(slug)).thenReturn(Optional.of(createTestApi()));

            // Act
            boolean result = service.isApiSlugUnique(slug);

            // Assert
            assertFalse(result);
        }
    }

    // ========================================================================
    // getPublicApisByCategory tests
    // ========================================================================

    @Nested
    @DisplayName("getPublicApisByCategory()")
    class GetPublicApisByCategoryTests {

        @Test
        @DisplayName("should return public APIs for category")
        void shouldReturnPublicApisForCategory() {
            // Arrange
            String categoryName = "Weather";
            UUID categoryId = UUID.randomUUID();

            ApiCategoryEntity category = new ApiCategoryEntity();
            category.setId(categoryId);

            ApiEntity activeApi = createTestApi();
            activeApi.setIsActive(true);

            ApiEntity inactiveApi = createTestApi();
            inactiveApi.setIsActive(false);

            when(categoryRepository.findBySlug("weather")).thenReturn(Optional.of(category));
            when(apiRepository.findByCategoryId(categoryId)).thenReturn(List.of(activeApi, inactiveApi));

            // Act
            List<ApiEntity> result = service.getPublicApisByCategory(categoryName);

            // Assert
            assertEquals(1, result.size());
            assertTrue(result.get(0).getIsActive());
        }

        @Test
        @DisplayName("should return empty list when category not found")
        void shouldReturnEmptyListWhenCategoryNotFound() {
            // Arrange
            String categoryName = "NonExistent";
            when(categoryRepository.findBySlug("nonexistent")).thenReturn(Optional.empty());

            // Act
            List<ApiEntity> result = service.getPublicApisByCategory(categoryName);

            // Assert
            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // validateCredentials - credentialModeOverride precedence
    // ========================================================================

    @Nested
    @DisplayName("validateCredentials - per-call override precedence (regression for agentic-path 'credentials_required' false-negative)")
    class ValidateCredentialsOverrideTests {

        @org.junit.jupiter.api.AfterEach
        void clearOverride() {
            com.apimarketplace.catalog.service.http.CredentialModeContext.clear();
        }

        @Test
        @DisplayName("api.credential_mode='user_key' + override='both' + user has nothing + platform creds set → must NOT short-circuit with credentials_required")
        void overrideBothBypassesUserKeyShortCircuit() throws Exception {
            // Arrange - API stored as user_key (typical), platform credential
            // is configured. User has no credential. Without the fix, the
            // pre-flight gate sees mode='user_key' and short-circuits before
            // HttpExecutionService.tryGetCredentialResolution can apply the
            // override. The bug surfaced as: chat-agent calling Gemini image-gen
            // got 'credentials_required' immediately, never hitting platform fallback.
            ApiEntity api = createTestApi();
            api.setPlatformCredentialName("googlegemini");
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(UUID.randomUUID());
            // Reflective invocation since validateCredentials is private.
            java.lang.reflect.Method m = ApiService.class.getDeclaredMethod(
                    "validateCredentials", ApiEntity.class, ApiToolEntity.class,
                    Class.forName("com.apimarketplace.catalog.service.ApiService$CredentialRequirement"),
                    String.class, String.class);
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> credReqCtor = Class.forName(
                    "com.apimarketplace.catalog.service.ApiService$CredentialRequirement")
                    .getDeclaredConstructor(String.class, String.class);
            credReqCtor.setAccessible(true);
            Object credReq = credReqCtor.newInstance("googlegemini", "api_key");

            // User has no creds; platform pool has the key (queried via
            // getAccessToken because that's the only path aware of
            // auth.platform_credentials).
            when(userCredentialService.getCredentialDataMap(eq("user-1"), eq("googlegemini")))
                    .thenReturn(java.util.Map.of());
            when(userCredentialService.getAccessToken(eq("PLATFORM"), eq("googlegemini")))
                    .thenReturn(java.util.Optional.of("platform-secret"));

            // Apply the per-call override that the controller sets from the request DTO.
            com.apimarketplace.catalog.service.http.CredentialModeContext.setOverride("both");

            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) m.invoke(
                    service, api, tool, credReq, "user-1", "generate_content");

            assertNull(result,
                    "validateCredentials must use the per-call override when present - without the fix it returned a credentials_required error map");
        }

        @Test
        @DisplayName("explicitSource='user' (workflow toggle=user) + user has nothing → returns credentials_required (durci, no platform fallback)")
        void noOverrideStillStrictForWorkflowPath() throws Exception {
            ApiEntity api = createTestApi();
            api.setPlatformCredentialName("googlegemini");
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(UUID.randomUUID());
            java.lang.reflect.Method m = ApiService.class.getDeclaredMethod(
                    "validateCredentials", ApiEntity.class, ApiToolEntity.class,
                    Class.forName("com.apimarketplace.catalog.service.ApiService$CredentialRequirement"),
                    String.class, String.class);
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> credReqCtor = Class.forName(
                    "com.apimarketplace.catalog.service.ApiService$CredentialRequirement")
                    .getDeclaredConstructor(String.class, String.class);
            credReqCtor.setAccessible(true);
            Object credReq = credReqCtor.newInstance("googlegemini", "api_key");

            when(userCredentialService.getCredentialDataMap(eq("user-1"), eq("googlegemini")))
                    .thenReturn(java.util.Map.of());
            // Workflow toggle=user is durci - platform credential availability
            // is irrelevant. Stub the platform side to confirm we don't even
            // bypass via that pool.
            when(userCredentialService.getAccessToken(eq("PLATFORM"), eq("googlegemini")))
                    .thenReturn(Optional.of("platform-token"));

            try {
                com.apimarketplace.catalog.service.http.CredentialModeContext.setExplicitSource("user");

                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) m.invoke(
                        service, api, tool, credReq, "user-1", "generate_content");

                assertNotNull(result, "Workflow toggle=user must reject when user has no credential, regardless of platform availability");
                assertEquals("credentials_required", result.get("error"));
            } finally {
                com.apimarketplace.catalog.service.http.CredentialModeContext.clear();
            }
        }

        @Test
        @DisplayName("explicitSource='user' + selectedCredentialId validates the selected credential id, not the default integration credential")
        void selectedCredentialIdValidatesSelectedCredential() throws Exception {
            ApiEntity api = createTestApi();
            api.setPlatformCredentialName("googlegemini");
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(UUID.randomUUID());
            java.lang.reflect.Method m = ApiService.class.getDeclaredMethod(
                    "validateCredentials", ApiEntity.class, ApiToolEntity.class,
                    Class.forName("com.apimarketplace.catalog.service.ApiService$CredentialRequirement"),
                    String.class, String.class);
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> credReqCtor = Class.forName(
                    "com.apimarketplace.catalog.service.ApiService$CredentialRequirement")
                    .getDeclaredConstructor(String.class, String.class);
            credReqCtor.setAccessible(true);
            Object credReq = credReqCtor.newInstance("googlegemini", "api_key");

            when(userCredentialService.getCredentialDataMapById(eq("user-1"), eq(42L)))
                    .thenReturn(java.util.Map.of("api_key", "selected"));
            when(userCredentialService.getAccessToken(eq("PLATFORM"), eq("googlegemini")))
                    .thenReturn(Optional.empty());

            try {
                com.apimarketplace.catalog.service.http.CredentialModeContext.setExplicitSource("user");
                com.apimarketplace.catalog.service.http.CredentialModeContext.setSelectedCredentialId(42L);

                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) m.invoke(
                        service, api, tool, credReq, "user-1", "generate_content");

                assertNull(result);
                verify(userCredentialService, never()).getCredentialDataMap(eq("user-1"), eq("googlegemini"));
            } finally {
                com.apimarketplace.catalog.service.http.CredentialModeContext.clear();
            }
        }

        @Test
        @DisplayName("explicitSource='user' + pinned selectedCredentialId DELETED but a default exists → accepts the default (take pinned, else default)")
        void selectedCredentialIdDeletedFallsBackToDefaultCredential() throws Exception {
            ApiEntity api = createTestApi();
            api.setPlatformCredentialName("googlegemini");
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(UUID.randomUUID());
            java.lang.reflect.Method m = ApiService.class.getDeclaredMethod(
                    "validateCredentials", ApiEntity.class, ApiToolEntity.class,
                    Class.forName("com.apimarketplace.catalog.service.ApiService$CredentialRequirement"),
                    String.class, String.class);
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> credReqCtor = Class.forName(
                    "com.apimarketplace.catalog.service.ApiService$CredentialRequirement")
                    .getDeclaredConstructor(String.class, String.class);
            credReqCtor.setAccessible(true);
            Object credReq = credReqCtor.newInstance("googlegemini", "api_key");

            // Pinned id 99 no longer resolves (deleted/reconnected) ...
            when(userCredentialService.getCredentialDataMapById(eq("user-1"), eq(99L)))
                    .thenReturn(java.util.Map.of());
            // ... but the user still has a default credential for the integration.
            when(userCredentialService.getCredentialDataMap(eq("user-1"), eq("googlegemini")))
                    .thenReturn(java.util.Map.of("api_key", "default"));

            try {
                com.apimarketplace.catalog.service.http.CredentialModeContext.setExplicitSource("user");
                com.apimarketplace.catalog.service.http.CredentialModeContext.setSelectedCredentialId(99L);

                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) m.invoke(
                        service, api, tool, credReq, "user-1", "generate_content");

                assertNull(result, "Deleted pinned credential must be accepted via the user's default - not credentials_required");
            } finally {
                com.apimarketplace.catalog.service.http.CredentialModeContext.clear();
            }
        }

        @Test
        @DisplayName("explicitSource='user' + pinned selectedCredentialId DELETED and NO default → still credentials_required")
        void selectedCredentialIdDeletedAndNoDefaultStillRequiresCredentials() throws Exception {
            ApiEntity api = createTestApi();
            api.setPlatformCredentialName("googlegemini");
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(UUID.randomUUID());
            java.lang.reflect.Method m = ApiService.class.getDeclaredMethod(
                    "validateCredentials", ApiEntity.class, ApiToolEntity.class,
                    Class.forName("com.apimarketplace.catalog.service.ApiService$CredentialRequirement"),
                    String.class, String.class);
            m.setAccessible(true);
            java.lang.reflect.Constructor<?> credReqCtor = Class.forName(
                    "com.apimarketplace.catalog.service.ApiService$CredentialRequirement")
                    .getDeclaredConstructor(String.class, String.class);
            credReqCtor.setAccessible(true);
            Object credReq = credReqCtor.newInstance("googlegemini", "api_key");

            when(userCredentialService.getCredentialDataMapById(eq("user-1"), eq(99L)))
                    .thenReturn(java.util.Map.of());
            when(userCredentialService.getCredentialDataMap(eq("user-1"), eq("googlegemini")))
                    .thenReturn(java.util.Map.of());
            when(userCredentialService.getAccessToken(eq("PLATFORM"), eq("googlegemini")))
                    .thenReturn(Optional.empty());

            try {
                com.apimarketplace.catalog.service.http.CredentialModeContext.setExplicitSource("user");
                com.apimarketplace.catalog.service.http.CredentialModeContext.setSelectedCredentialId(99L);

                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) m.invoke(
                        service, api, tool, credReq, "user-1", "generate_content");

                assertNotNull(result);
                assertEquals("credentials_required", result.get("error"));
            } finally {
                com.apimarketplace.catalog.service.http.CredentialModeContext.clear();
            }
        }
    }

    // ========================================================================
    // V166: per-endpoint requiredScopes integration tests
    // ========================================================================

    @Nested
    @DisplayName("executeApiTool() - V166 preflight integration")
    class PreflightIntegrationTests {

        @Test
        @DisplayName("preflight failure returns structured error map with errorCode=insufficient_scopes")
        void executeApiTool_preflightFailure_returnsStructuredErrorMap() {
            UUID apiId = UUID.randomUUID();
            String toolName = "send_message";
            String userId = "user-1";

            ApiEntity api = createTestApi();
            api.setId(apiId);
            api.setPlatformCredentialName("gmail");

            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(toolId);
            tool.setApiId(apiId);
            tool.setToolNameId("tool-name-id");
            tool.setProtocol("HTTP");

            ToolNameEntity toolNameEntity = new ToolNameEntity();
            toolNameEntity.setName(toolName);

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
            when(toolCategoryService.getToolNameByToolNameId("tool-name-id"))
                    .thenReturn(Optional.of(toolNameEntity));
            when(jdbcTemplate.queryForList(anyString(), any(UUID.class)))
                    .thenReturn(List.of());
            // The preflight throws - simulate it via the mocked HttpExecutionService
            doThrow(new com.apimarketplace.catalog.service.exception.InsufficientScopesException(
                    "send_message", apiId, "myCred", "gmail", java.util.Set.of("gmail.send")))
                    .when(httpExecutionService).preflightScopeCheck(any(), any(), any(), any());

            Map<String, Object> result = service.executeApiTool(
                    apiId.toString(), toolName, objectMapper.createObjectNode(), null, userId);

            assertFalse((Boolean) result.get("success"));
            assertEquals("insufficient_scopes", result.get("errorCode"));
            assertEquals("reconnect_credential", result.get("remediation"));
            assertEquals("gmail", result.get("integration"));
            assertEquals("myCred", result.get("credentialName"));
            assertNotNull(result.get("missingScopes"));
            assertTrue(((List<?>) result.get("missingScopes")).contains("gmail.send"));
            // Crucially: HTTP dispatch never happened - billing path was skipped.
            verify(httpExecutionService, never()).executeHttpCallTyped(any(), any(), any(), any(), any(), any(), any());
            verify(httpExecutionService, never()).executeHttpCallWithCredentials(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("preflight pass: existing happy path proceeds - billing untouched")
        void executeApiTool_preflightPasses_billingPathUnchanged() {
            UUID apiId = UUID.randomUUID();
            String toolName = "list_messages";
            String userId = "user-1";

            ApiEntity api = createTestApi();
            api.setId(apiId);

            ApiToolEntity tool = new ApiToolEntity();
            tool.setId(UUID.randomUUID());
            tool.setApiId(apiId);
            tool.setToolNameId("tool-name-id");
            tool.setProtocol("HTTP");

            ToolNameEntity toolNameEntity = new ToolNameEntity();
            toolNameEntity.setName(toolName);

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));
            when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
            when(toolCategoryService.getToolNameByToolNameId("tool-name-id"))
                    .thenReturn(Optional.of(toolNameEntity));
            when(jdbcTemplate.queryForList(anyString(), any(UUID.class)))
                    .thenReturn(List.of());
            // Preflight does nothing (mock default): caller proceeds to executeHttpCallWithCredentials
            when(httpExecutionService.executeHttpCallWithCredentials(any(), any(), any(), any(), any(), any()))
                    .thenReturn(new HashMap<>(Map.of("success", true)));

            Map<String, Object> result = service.executeApiTool(
                    apiId.toString(), toolName, objectMapper.createObjectNode(), null, userId);

            assertEquals(toolName, result.get("toolName"));
            verify(httpExecutionService).preflightScopeCheck(any(), any(), any(), any());
            verify(httpExecutionService).executeHttpCallWithCredentials(any(), any(), any(), any(), any(), any());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ApiEntity createTestApi() {
        ApiEntity api = new ApiEntity();
        api.setId(UUID.randomUUID());
        api.setApiName("Test API");
        api.setApiSlug("test-api");
        api.setDescription("Test description");
        api.setBaseUrl("http://api.test.com");
        api.setCreatedBy("user-123");
        api.setCreatedAt(System.currentTimeMillis());
        api.setUpdatedAt(System.currentTimeMillis());
        return api;
    }

    private ApiResponse createTestApiResponse() {
        return new ApiResponse(
            UUID.randomUUID(),
            "Test API",
            "test-api",
            "Test description",
            "http://api.test.com",
            null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null
        );
    }
}
