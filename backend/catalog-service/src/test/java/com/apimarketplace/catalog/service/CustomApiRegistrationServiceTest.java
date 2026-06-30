package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomApiRegistrationServiceTest {

    @Mock
    private ApiService apiService;

    @Mock
    private ApiRepository apiRepository;

    @Mock
    private ApiToolRepository apiToolRepository;

    @Mock
    private ApiToolParameterRepository apiToolParameterRepository;

    @Mock
    private LexicalIndexSyncService lexicalIndexSyncService;

    @Mock
    private ToolNextHintRepository toolNextHintRepository;

    @Mock
    private ToolResponseService toolResponseService;

    @Mock
    private com.apimarketplace.catalog.seed.CatalogSeedCredentialService catalogSeedCredentialService;

    @Mock
    private com.apimarketplace.credential.client.CredentialClient credentialClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CustomApiRegistrationService service;
    private MockedStatic<UrlSafetyValidator> urlValidatorMock;

    @BeforeEach
    void setUp() {
        service = new CustomApiRegistrationService(apiService, apiRepository,
                apiToolRepository, apiToolParameterRepository, lexicalIndexSyncService,
                toolNextHintRepository, toolResponseService, catalogSeedCredentialService,
                credentialClient, objectMapper);
        urlValidatorMock = mockStatic(UrlSafetyValidator.class);
        urlValidatorMock.when(() -> UrlSafetyValidator.validateUrl(anyString())).thenAnswer(inv -> null);
        urlValidatorMock.when(() -> UrlSafetyValidator.validateUrlFormat(anyString())).thenAnswer(inv -> null);
    }

    @AfterEach
    void tearDown() {
        urlValidatorMock.close();
    }

    @Test
    void registerCustomApiSucceeds() {
        ObjectNode json = buildValidApiJson();
        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        ApiResponse result = service.registerCustomApi(json, "tenant-1");

        assertNotNull(result);
        assertEquals(mockResponse.id(), result.id());
        verify(apiService).processApiConfiguration(any(), eq("tenant-1"));
    }

    @Test
    void registerCustomApiRejectsBlankApiName() {
        ObjectNode json = buildValidApiJson();
        json.put("apiName", "");

        assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
    }

    @Test
    void registerCustomApiRejectsMissingBaseUrl() {
        ObjectNode json = buildValidApiJson();
        json.remove("baseUrl");

        assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
    }

    @Test
    void registerCustomApiRejectsNoEndpoints() {
        ObjectNode json = buildValidApiJson();
        json.putArray("endpoints"); // empty array

        assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
    }

    @Test
    void registerCustomApiSkipsEndpointsWithBlankName() {
        ObjectNode json = buildValidApiJson();
        var endpoints = json.putArray("endpoints");
        endpoints.addObject().put("name", "").put("endpoint", "/test").put("method", "GET");

        assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"),
                "API must have at least one endpoint");
    }

    @Test
    void registerCustomApiDefaultsCategory() {
        ObjectNode json = buildValidApiJson();
        json.remove("apiCategory");
        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        assertEquals("Custom APIs", captor.getValue().selectedCategory());
    }

    @Test
    void registerCustomApiCategoryDescriptionAvoidsDuplication() {
        ObjectNode json = buildValidApiJson();
        json.put("apiCategory", "Custom APIs");
        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        // Should NOT be "Custom APIs APIs"
        assertEquals("Custom APIs", captor.getValue().categoryDescription());
    }

    @Test
    void registerCustomApiCategoryDescriptionAppendsSuffix() {
        ObjectNode json = buildValidApiJson();
        json.put("apiCategory", "Weather");
        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        assertEquals("Weather APIs", captor.getValue().categoryDescription());
    }

    @Test
    void updateCustomApiChecksTenantOwnership() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "other-tenant");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        ObjectNode updates = buildValidApiJson();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateCustomApi(apiId.toString(), updates, "tenant-1"),
                "You can only update your own custom APIs");
    }

    @Test
    void updateCustomApiRejectsNonCustomSource() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "imported", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        ObjectNode updates = buildValidApiJson();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateCustomApi(apiId.toString(), updates, "tenant-1"),
                "Only custom APIs can be updated via this endpoint");
    }

    @Test
    void updateCustomApiDeletesAndRecreates() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        ObjectNode updates = buildValidApiJson();
        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        ApiResponse result = service.updateCustomApi(apiId.toString(), updates, "tenant-1");

        assertNotNull(result);
        verify(apiService).deleteApi(apiId);
        verify(apiService).processApiConfiguration(any(), eq("tenant-1"));
    }

    @Test
    void updateCustomApiCleansUpOldCredentials() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("oldapi");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        ObjectNode updates = buildValidApiJson();
        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.updateCustomApi(apiId.toString(), updates, "tenant-1");

        // Should clean up old credentials before deleting
        verify(catalogSeedCredentialService).deleteCredentialByName("oldapi");
        verify(credentialClient).deleteTenantPlatformCredential("oldapi", "tenant-1");
        verify(apiService).deleteApi(apiId);
    }

    @Test
    void updateCustomApiRejectsNonObjectUpdates() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        JsonNode arrayNode = objectMapper.createArrayNode();

        assertThrows(IllegalArgumentException.class,
                () -> service.updateCustomApi(apiId.toString(), arrayNode, "tenant-1"),
                "Updates must be a JSON object");
    }

    @Test
    void deleteCustomApiChecksTenantOwnership() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "other-tenant");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteCustomApi(apiId.toString(), "tenant-1"));
    }

    @Test
    void deleteCustomApiRejectsNonCustomSource() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "imported", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteCustomApi(apiId.toString(), "tenant-1"));
    }

    @Test
    void deleteCustomApiSucceeds() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        service.deleteCustomApi(apiId.toString(), "tenant-1");

        verify(apiService).deleteApi(apiId);
    }

    @Test
    void deleteCustomApiThrowsWhenNotFound() {
        UUID apiId = UUID.randomUUID();
        when(apiRepository.findById(apiId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.deleteCustomApi(apiId.toString(), "tenant-1"));
    }

    @Test
    void getCustomApiDetailsChecksTenantOwnership() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "other-tenant");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.getCustomApiDetails(apiId.toString(), "tenant-1"));
    }

    @Test
    void getCustomApiDetailsRejectsNonCustomSource() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "imported", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        assertThrows(IllegalArgumentException.class,
                () -> service.getCustomApiDetails(apiId.toString(), "tenant-1"));
    }

    @Test
    void registerCustomApiConvertsDuplicateKeyToIllegalArgument() {
        // Production path: ApiSubmissionOrchestrator wraps Spring's DuplicateKeyException
        // inside a RuntimeException("Processing error: ...", cause). The service must walk
        // the cause chain and surface a friendly IllegalArgumentException with the
        // `update_api` remediation hint.
        ObjectNode json = buildValidApiJson();
        org.springframework.dao.DuplicateKeyException dup =
                new org.springframework.dao.DuplicateKeyException("duplicate key value violates unique constraint");
        when(apiService.processApiConfiguration(any(), eq("tenant-1")))
                .thenThrow(new RuntimeException("Processing error: Failed to execute InsertRoot", dup));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(thrown.getMessage().contains("already exists"),
                "surfaced message should mention duplicate");
        assertTrue(thrown.getMessage().contains("update_api"),
                "surfaced message should point at update_api remediation");
    }

    @Test
    void getCustomApiDetailsReturnsFullDetails() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        ApiResponse apiResponse = mockApiResponse(apiId);
        when(apiService.getApiById(apiId)).thenReturn(apiResponse);

        Map<String, Object> details = service.getCustomApiDetails(apiId.toString(), "tenant-1");

        assertEquals(apiId.toString(), details.get("id"));
        assertEquals("Test API", details.get("apiName"));
        assertEquals("https://localhost", details.get("baseUrl"));
        assertEquals("bearer", details.get("authType"));
        assertNotNull(details.get("endpoints"));
    }

    @Test
    void getCustomApiDetailsAllowsMatchingOrganizationScope() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "owner-tenant");
        entity.setOrganizationId("org-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiService.getApiById(apiId)).thenReturn(mockApiResponse(apiId));

        AtomicReference<Map<String, Object>> detailsRef = new AtomicReference<>();
        TenantResolver.runWithOrgScope("org-1",
                () -> detailsRef.set(service.getCustomApiDetails(apiId.toString(), "member-tenant")));

        assertEquals(apiId.toString(), detailsRef.get().get("id"));
    }

    @Test
    void listCustomApisDelegates() {
        List<Map<String, Object>> expected = List.of(Map.of("id", "123"));
        when(apiService.getCustomApisForTenant("tenant-1", null)).thenReturn(expected);

        List<Map<String, Object>> result = service.listCustomApis("tenant-1");

        assertEquals(expected, result);
    }

    // --- V83: post-processing and tool matching tests ---

    @Test
    void registerCustomApiPostProcessesApiMetadata() {
        ObjectNode json = buildValidApiJson();
        json.put("apiVersion", "v2");
        json.put("documentation", "https://docs.example.com");
        json.putObject("rateLimits").put("requestsPerSecond", 50).put("requestsPerDay", 1000000);

        UUID apiId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponse(apiId);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        service.registerCustomApi(json, "tenant-1");

        verify(apiRepository).save(any(ApiEntity.class));
        assertEquals("v2", entity.getApiVersion());
        assertEquals("https://docs.example.com", entity.getDocumentation());
        assertNotNull(entity.getRateLimits());
        assertTrue(entity.getRateLimits().contains("requestsPerSecond"));
    }

    @Test
    void registerCustomApiPostProcessesFixtures() {
        ObjectNode json = buildValidApiJson();
        var fixtures = json.putArray("apiFixtures");
        var fixture = fixtures.addObject();
        fixture.put("endpointName", "get_items");
        fixture.putObject("response").put("status", "ok");

        UUID apiId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponse(apiId);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        // Mock tool entities for fixture matching
        var toolEntity = new com.apimarketplace.catalog.domain.ApiToolEntity();
        toolEntity.setId(UUID.randomUUID());
        toolEntity.setToolSlug("test_api_get_items");
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(toolEntity));

        service.registerCustomApi(json, "tenant-1");

        verify(toolResponseService).createResponse(any(com.apimarketplace.catalog.dto.ToolResponseDto.class), eq(null));
    }

    @Test
    void registerCustomApiBuildsSynthesisPassThrough() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var synthesis = ep.putObject("synthesis");
        synthesis.put("resource", "items");
        synthesis.put("action", "list");
        synthesis.put("summary", "List all items");

        UUID apiId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponse(apiId);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        // Mock tool entities for synthesis matching
        var toolEntity = new com.apimarketplace.catalog.domain.ApiToolEntity();
        toolEntity.setId(UUID.randomUUID());
        toolEntity.setToolSlug("test_api_get_items");
        toolEntity.setEndpoint("/items");
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(toolEntity));

        service.registerCustomApi(json, "tenant-1");

        verify(lexicalIndexSyncService).sync(eq(toolEntity.getId()), any());
    }

    @Test
    void registerCustomApiBuildsHiddenParam() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var params = ep.putArray("params");
        var param = params.addObject();
        param.put("name", "secret");
        param.put("in", "query");
        param.put("type", "string");
        param.put("required", false);
        param.put("description", "A hidden param");
        param.put("hidden", true);

        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        var tools = captor.getValue().mcpTools();
        assertFalse(tools.isEmpty());
        var queryParams = tools.get(0).queryParameters();
        assertTrue(queryParams.stream().anyMatch(p ->
                "secret".equals(p.name()) && p.extras() != null && p.extras().path("hidden").asBoolean()));
    }

    @Test
    void registerCustomApiBuildsExampleWithObjectValue() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var params = ep.putArray("params");
        var param = params.addObject();
        param.put("name", "filter");
        param.put("in", "query");
        param.put("type", "string");
        param.put("required", false);
        param.put("description", "Complex filter");
        param.putObject("example").put("key", "value");

        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        var queryParams = captor.getValue().mcpTools().get(0).queryParameters();
        assertTrue(queryParams.stream().anyMatch(p ->
                "filter".equals(p.name()) && p.example() != null && p.example().contains("key")));
    }

    @Test
    void getCustomApiDetailsReturnsV83OverlayFields() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setApiVersion("v3");
        entity.setDocumentation("https://docs.test.com");
        entity.setRateLimits("{\"requestsPerSecond\":10}");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        ApiResponse apiResponse = mockApiResponse(apiId);
        when(apiService.getApiById(apiId)).thenReturn(apiResponse);
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of());

        Map<String, Object> details = service.getCustomApiDetails(apiId.toString(), "tenant-1");

        assertEquals("v3", details.get("apiVersion"));
        assertEquals("https://docs.test.com", details.get("documentation"));
        assertNotNull(details.get("rateLimits"));
    }

    @Test
    void registerCustomApiMatchesToolBySuffix() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        ep.put("nextHint", "Use get_item_details next");

        UUID apiId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponse(apiId);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        // Tool with exact suffix match
        var toolEntity = new com.apimarketplace.catalog.domain.ApiToolEntity();
        toolEntity.setId(UUID.randomUUID());
        toolEntity.setToolSlug("test_api_get_items");
        toolEntity.setEndpoint("/items");
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(toolEntity));

        service.registerCustomApi(json, "tenant-1");

        // nextHint triggers persistNextHint which saves to toolNextHintRepository
        verify(toolNextHintRepository).save(any());
    }

    @Test
    void registerCustomApiDoesNotMatchToolBySubstring() {
        ObjectNode json = buildValidApiJson();
        // Endpoint named "list" should NOT match slug "test_api_list_emails"
        var endpoints = json.putArray("endpoints");
        var ep = endpoints.addObject();
        ep.put("name", "list");
        ep.put("endpoint", "/list");
        ep.put("method", "GET");
        ep.put("description", "List");
        ep.put("nextHint", "some hint");
        var os = ep.putArray("outputSchema");
        os.addObject().put("key", "id").put("type", "string").put("description", "ID");

        UUID apiId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponse(apiId);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        var toolEntity = new com.apimarketplace.catalog.domain.ApiToolEntity();
        toolEntity.setId(UUID.randomUUID());
        toolEntity.setToolSlug("test_api_list_emails");
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(toolEntity));

        service.registerCustomApi(json, "tenant-1");

        // Should NOT match, so no hint saved
        verify(toolNextHintRepository, never()).save(any());
    }

    @Test
    void registerCustomApiBuildsBodyParamWithHidden() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        ep.put("method", "POST");
        var params = ep.putArray("params");
        var param = params.addObject();
        param.put("name", "internal_id");
        param.put("in", "body");
        param.put("type", "string");
        param.put("required", false);
        param.put("description", "Internal ID");
        param.put("hidden", true);

        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        var bodyParams = captor.getValue().mcpTools().get(0).bodyParams();
        assertTrue(bodyParams.stream().anyMatch(p ->
                "internal_id".equals(p.name()) && p.extras() != null && p.extras().path("hidden").asBoolean()));
    }

    @Test
    void registerCustomApiUsesLocationAliasForIn() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var params = ep.putArray("params");
        var param = params.addObject();
        param.put("name", "user_id");
        param.put("location", "path"); // alias for "in"
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "User ID");

        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        var pathParams = captor.getValue().mcpTools().get(0).pathParameters();
        assertTrue(pathParams.stream().anyMatch(p -> "user_id".equals(p.name())),
                "Param with location='path' should be in pathParameters");
    }

    @Test
    void registerCustomApiTruncatesLongDocumentation() {
        ObjectNode json = buildValidApiJson();
        String longDoc = "x".repeat(1500); // exceeds VARCHAR(1000)
        json.put("documentation", longDoc);

        UUID apiId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponse(apiId);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        service.registerCustomApi(json, "tenant-1");

        verify(apiRepository).save(any(ApiEntity.class));
        assertNotNull(entity.getDocumentation());
        assertEquals(1000, entity.getDocumentation().length());
    }

    @Test
    void registerCustomApiBuildsPathParamWithHidden() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        ep.put("endpoint", "/items/{item_id}");
        var params = ep.putArray("params");
        var param = params.addObject();
        param.put("name", "item_id");
        param.put("in", "path");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "Item ID");
        param.put("hidden", true);

        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest> captor =
                ArgumentCaptor.forClass(com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        var pathParams = captor.getValue().mcpTools().get(0).pathParameters();
        assertTrue(pathParams.stream().anyMatch(p ->
                "item_id".equals(p.name()) && p.extras() != null && p.extras().path("hidden").asBoolean()),
                "Path param should have hidden=true in extras");
    }

    // --- credential linking tests ---

    @Test
    void registerCustomApiLinksCredentialsWhenAuthTypeNotNone() {
        ObjectNode json = buildValidApiJson();
        json.put("authType", "bearer");

        UUID apiId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponse(apiId);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        verify(catalogSeedCredentialService).linkCredentials(
                eq(apiId), eq("testapi"), eq("bearer"), anyString(), any());
    }

    @Test
    void registerCustomApiSkipsCredentialLinkingWhenAuthTypeNone() {
        ObjectNode json = buildValidApiJson();
        // default authType is "none" when not specified

        ApiResponse mockResponse = mockApiResponse();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        verify(catalogSeedCredentialService, never()).linkCredentials(
                any(UUID.class), anyString(), anyString(), anyString(), any());
    }

    // registerCustomApiSetsCredentialModeForAuthenticatedApis removed (V154):
    // credentialMode column no longer exists. The DTO field still carries the
    // value positionally for back-compat but the importer ignores it. There is
    // nothing meaningful to assert here.

    @Test
    void deleteCustomApiCleansUpCredentialTemplate() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("myapi");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        service.deleteCustomApi(apiId.toString(), "tenant-1");

        verify(catalogSeedCredentialService).deleteCredentialByName("myapi");
        verify(credentialClient).deleteTenantPlatformCredential("myapi", "tenant-1");
        verify(apiService).deleteApi(apiId);
    }

    @Test
    void deleteCustomApiProceedsWhenCredentialClientThrows() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("myapi");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        doThrow(new RuntimeException("auth-service unreachable"))
                .when(credentialClient).deleteTenantPlatformCredential("myapi", "tenant-1");

        // Should not throw - best-effort cleanup
        service.deleteCustomApi(apiId.toString(), "tenant-1");

        verify(catalogSeedCredentialService).deleteCredentialByName("myapi");
        verify(apiService).deleteApi(apiId);
    }

    @Test
    void deleteCustomApiSkipsCredentialCleanupWhenNoPlatformCredentialName() {
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        // platformCredentialName is null by default
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));

        service.deleteCustomApi(apiId.toString(), "tenant-1");

        verify(catalogSeedCredentialService, never()).deleteCredentialByName(anyString());
        verify(credentialClient, never()).deleteTenantPlatformCredential(anyString(), anyString());
        verify(apiService).deleteApi(apiId);
    }

    // --- outputSchema required tests ---

    @Test
    void registerCustomApiRejectsMissingOutputSchema() {
        ObjectNode json = buildValidApiJson();
        // Remove outputSchema from the endpoint
        ((ObjectNode) json.path("endpoints").get(0)).remove("outputSchema");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("outputSchema"));
    }

    @Test
    void registerCustomApiRejectsEmptyOutputSchema() {
        ObjectNode json = buildValidApiJson();
        // Replace outputSchema with empty array
        ((ObjectNode) json.path("endpoints").get(0)).putArray("outputSchema");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("outputSchema"));
    }

    @Test
    void registerCustomApiDefaultsToolCategoryToApiName() {
        ObjectNode json = buildValidApiJson();
        ApiResponse mockResponse = mockApiResponse();
        ArgumentCaptor<ApiConfigurationRequest> captor = ArgumentCaptor.forClass(ApiConfigurationRequest.class);
        when(apiService.processApiConfiguration(captor.capture(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        ApiConfigurationRequest request = captor.getValue();
        assertNotNull(request.mcpTools());
        assertFalse(request.mcpTools().isEmpty());
        assertEquals("Test API", request.mcpTools().get(0).toolCategory());
    }

    @Test
    void registerCustomApiTruncatesLongToolCategory() {
        ObjectNode json = buildValidApiJson();
        String longName = "A".repeat(60);
        json.put("apiName", longName);
        ApiResponse mockResponse = mockApiResponse();
        ArgumentCaptor<ApiConfigurationRequest> captor = ArgumentCaptor.forClass(ApiConfigurationRequest.class);
        when(apiService.processApiConfiguration(captor.capture(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        String toolCategory = captor.getValue().mcpTools().get(0).toolCategory();
        assertTrue(toolCategory.length() <= 50, "toolCategory should be truncated to 50 chars");
    }

    // --- R-02: method whitelist ---

    @Test
    void registerCustomApiRejectsInvalidHttpMethod() {
        ObjectNode json = buildValidApiJson();
        ((ObjectNode) json.path("endpoints").get(0)).put("method", "FROBNICATE");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("FROBNICATE"), "Error should include the invalid method");
        assertTrue(ex.getMessage().contains("GET") || ex.getMessage().contains("Allowed"),
                "Error should list allowed methods");
    }

    @Test
    void registerCustomApiAcceptsLowercaseMethodAndNormalizes() {
        ObjectNode json = buildValidApiJson();
        ((ObjectNode) json.path("endpoints").get(0)).put("method", "post");
        ApiResponse mockResponse = mockApiResponse();
        ArgumentCaptor<ApiConfigurationRequest> captor = ArgumentCaptor.forClass(ApiConfigurationRequest.class);
        when(apiService.processApiConfiguration(captor.capture(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        assertEquals("POST", captor.getValue().mcpTools().get(0).method());
    }

    // --- R-03: param.in whitelist (no silent drop) ---

    @Test
    void registerCustomApiRejectsParamWithInHeader() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var params = ep.putArray("params");
        var param = params.addObject();
        param.put("name", "X-Trace-ID");
        param.put("in", "header"); // header is NOT allowed via params
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "Trace header");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("header"), "Error should mention the offending value");
        assertTrue(ex.getMessage().contains("query") && ex.getMessage().contains("path")
                        && ex.getMessage().contains("body"),
                "Error should list allowed 'in' values");
    }

    @Test
    void registerCustomApiRejectsParamWithUnknownIn() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var params = ep.putArray("params");
        var param = params.addObject();
        param.put("name", "weird");
        param.put("in", "unicorn");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "Nope");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("unicorn"));
    }

    // --- R-04: outputSchema.type whitelist ---

    @Test
    void registerCustomApiRejectsInvalidOutputSchemaType() {
        ObjectNode json = buildValidApiJson();
        var field = (ObjectNode) json.path("endpoints").get(0).path("outputSchema").get(0);
        field.put("type", "int"); // not in whitelist

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("int"));
        assertTrue(ex.getMessage().contains("string"), "Error should include allowed types");
    }

    @Test
    void registerCustomApiAcceptsMixedCaseOutputSchemaType() {
        // LLMs frequently send "String" / "INTEGER" / "FileRef" etc. The whitelist is
        // case-insensitive (matches validateParams' `in` treatment); canonical form is
        // preserved internally.
        ObjectNode json = buildValidApiJson();
        var outputSchema = (com.fasterxml.jackson.databind.node.ArrayNode)
                json.path("endpoints").get(0).path("outputSchema");
        outputSchema.removeAll();
        outputSchema.addObject().put("key", "id").put("type", "String").put("description", "id");
        outputSchema.addObject().put("key", "icon").put("type", "FILEREF").put("description", "icon");

        UUID apiId = UUID.randomUUID();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse(apiId));
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(buildEntity(apiId, "custom", "tenant-1")));
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of());

        assertDoesNotThrow(() -> service.registerCustomApi(json, "tenant-1"));
    }

    @Test
    void registerCustomApiRejectsInvalidNestedOutputSchemaType() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        ep.putArray("outputSchema"); // reset
        var parent = ((com.fasterxml.jackson.databind.node.ArrayNode) ep.path("outputSchema")).addObject();
        parent.put("key", "wrapper");
        parent.put("type", "object");
        parent.put("description", "Wrapper");
        var children = parent.putArray("children");
        var child = children.addObject();
        child.put("key", "nested");
        child.put("type", "garbage"); // nested invalid
        child.put("description", "Nested");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("garbage"));
    }

    // --- R-11: param.description required ---

    @Test
    void registerCustomApiRejectsParamWithBlankDescription() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var params = ep.putArray("params");
        var param = params.addObject();
        param.put("name", "page");
        param.put("in", "query");
        param.put("type", "integer");
        param.put("required", false);
        param.put("description", ""); // blank - rejected

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("description"));
    }

    // --- R-01: duplicate-key wrapping (RuntimeException with DuplicateKeyException cause) ---

    @Test
    void registerCustomApiSurfacesDuplicateKeyEvenWhenWrapped() {
        ObjectNode json = buildValidApiJson();
        // Simulate the exact wrapping done by ApiSubmissionOrchestrator:
        //   throw new RuntimeException("Processing error: " + e.getMessage(), e)
        org.springframework.dao.DuplicateKeyException duplicate =
                new org.springframework.dao.DuplicateKeyException(
                        "Failed to execute InsertRoot{entity=...}");
        RuntimeException wrapped = new RuntimeException(
                "Processing error: Failed to execute InsertRoot{entity=...}", duplicate);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenThrow(wrapped);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("already exists"),
                "Error should tell the agent the API name already exists");
        assertTrue(ex.getMessage().contains("update_api"),
                "Error should hint at update_api as the remediation");
    }

    @Test
    void registerCustomApiRethrowsUnrelatedRuntimeExceptions() {
        ObjectNode json = buildValidApiJson();
        RuntimeException other = new RuntimeException("database connection lost");
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenThrow(other);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        // Not converted to IllegalArgumentException - the caller needs the original stack
        assertFalse(ex instanceof IllegalArgumentException);
        assertEquals("database connection lost", ex.getMessage());
    }

    @Test
    void isDuplicateKeyExceptionDetectsWrappedCause() {
        org.springframework.dao.DuplicateKeyException root =
                new org.springframework.dao.DuplicateKeyException("dup");
        Throwable wrapped = new RuntimeException("layer2", new RuntimeException("layer1", root));
        assertTrue(CustomApiRegistrationService.isDuplicateKeyException(wrapped));
        assertFalse(CustomApiRegistrationService.isDuplicateKeyException(new RuntimeException("nope")));
    }

    // --- R-15: default execution_spec to {"mode":"sync"} ---

    @Test
    void registerCustomApiDefaultsExecutionSpecToSync() {
        ObjectNode json = buildValidApiJson();
        // no "execution" block on the endpoint
        ApiResponse mockResponse = mockApiResponse();
        ArgumentCaptor<ApiConfigurationRequest> captor = ArgumentCaptor.forClass(ApiConfigurationRequest.class);
        when(apiService.processApiConfiguration(captor.capture(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        var tool = captor.getValue().mcpTools().get(0);
        assertNotNull(tool.executionSpec(), "executionSpec should be defaulted, not null");
        assertEquals("sync", tool.executionSpec().path("mode").asText());
        assertEquals("sync", tool.executionMode());
    }

    // --- R-16: runtime_metadata populated for parity with importer ---

    @Test
    void registerCustomApiPopulatesRuntimeMetadataWithHttpType() {
        ObjectNode json = buildValidApiJson();
        json.put("baseUrl", "https://api.example.com/v2");
        ApiResponse mockResponse = mockApiResponse();
        ArgumentCaptor<ApiConfigurationRequest> captor = ArgumentCaptor.forClass(ApiConfigurationRequest.class);
        when(apiService.processApiConfiguration(captor.capture(), eq("tenant-1"))).thenReturn(mockResponse);

        service.registerCustomApi(json, "tenant-1");

        var tool = captor.getValue().mcpTools().get(0);
        assertNotNull(tool.runtimeMetadata());
        assertEquals("http", tool.runtimeMetadata().path("type").asText());
        assertEquals("https://api.example.com/v2", tool.runtimeMetadata().path("baseUrl").asText());
    }

    // --- helpers ---

    private ObjectNode buildValidApiJson() {
        ObjectNode json = objectMapper.createObjectNode();
        json.put("apiName", "Test API");
        json.put("baseUrl", "https://localhost");
        json.put("apiDescription", "A test API");
        var endpoints = json.putArray("endpoints");
        var ep = endpoints.addObject();
        ep.put("name", "get_items");
        ep.put("endpoint", "/items");
        ep.put("method", "GET");
        ep.put("description", "List items");
        var outputSchema = ep.putArray("outputSchema");
        var field = outputSchema.addObject();
        field.put("key", "id");
        field.put("type", "string");
        field.put("description", "Item ID");
        return json;
    }

    private ApiEntity buildEntity(UUID id, String source, String createdBy) {
        ApiEntity entity = new ApiEntity();
        entity.setId(id);
        entity.setSource(source);
        entity.setCreatedBy(createdBy);
        return entity;
    }

    private ApiResponse mockApiResponse() {
        return mockApiResponse(UUID.randomUUID());
    }

    private ApiResponse mockApiResponse(UUID id) {
        return new ApiResponse(
                id,                 // id
                "Test API",         // apiName
                "test-api",         // apiSlug
                "A test API",       // description
                "https://localhost", // baseUrl
                null,               // categoryId
                "Custom APIs",      // categoryName
                null,               // subcategoryId
                "Test API",         // subcategoryName
                true,               // isActive
                false,              // isLocal
                null,               // createdAt
                null,               // updatedAt
                "tenant-1",         // createdBy
                null,               // tools
                null,               // healthcheckEndpoint
                "private",          // visibility
                false,              // isPublic
                "bearer",           // authType
                "Authorization",    // authHeaderName
                null,               // authHeaderValue
                "FREE",             // pricingModel
                "active",           // status
                null                // platformCredentialMissing
        );
    }
}
