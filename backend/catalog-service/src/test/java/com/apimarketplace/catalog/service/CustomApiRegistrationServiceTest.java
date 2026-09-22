package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.seed.CatalogSeedCredentialService;
import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
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

        // The catalog-side template and tool links go, because the tools they point at are
        // replaced; the re-registration rebuilds them immediately.
        verify(catalogSeedCredentialService).deleteCredentialByName("oldapi");
        verify(apiService).deleteApi(apiId);

        // The author's OWN stored key must survive. This assertion used to be its mirror image:
        // update called the same cleanup as delete_api, so editing a description threw the API
        // key away, and the response mentioned only that tool IDs had changed. Someone fixing a
        // typo lost a working key, re-entered it, edited again, and lost it again.
        verify(credentialClient, never()).deleteTenantPlatformCredential(anyString(), anyString());
    }

    @Test
    void deletingACustomApiStillDiscardsItsStoredKey() {
        // The counterweight to the test above: update must keep the key, delete must not. Without
        // this, "stop deleting the credential" could be applied one layer too high and leave every
        // deleted API's secret behind.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("oldapi");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        // Stated, not inherited from Mockito's default: BOTH deletes below are now conditional on
        // this answer, so the precondition belongs in the body rather than in a reader's head.
        when(apiRepository.existsSharedIntegrationWithCredentialKey("oldapi", "tenant-1"))
                .thenReturn(false);

        service.deleteCustomApi(apiId.toString(), "tenant-1");

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
    @SuppressWarnings("unchecked")
    void getCustomApiDetailsSplitsConstantHeadersFromDeclaredHeaderParams() {
        // Stub the tool and its parameter rows, with the header param NOT hidden. Without these
        // the discriminator is never consulted and the assertions below are vacuous.
        // Both are stored as header parameters, and they must come back as the two different
        // things they were declared as. A CONSTANT (hidden, from the `headers` map) belongs in
        // `headers`; a caller-filled param declared in:'header' belongs in `params`.
        //
        // Projecting both as constants was the round-trip defect: a visible header param with no
        // default vanished entirely, and one with a default came back frozen as a constant. The
        // Settings dialog loads these details and saves them straight back, so editing a
        // description silently deleted an input the author had asked for.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        ApiResponse response = apiResponseWithHeaderParam(apiId);
        when(apiService.getApiById(apiId)).thenReturn(response);
        UUID toolEntityId = response.tools().get(0).id();
        ApiToolEntity toolEntity = new ApiToolEntity();
        toolEntity.setId(toolEntityId);
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(toolEntity));
        ApiToolParameterEntity visible = new ApiToolParameterEntity();
        visible.setName("anthropic-version");
        visible.setIsHidden(false);
        when(apiToolParameterRepository.findByApiToolId(toolEntityId)).thenReturn(List.of(visible));

        Map<String, Object> details = service.getCustomApiDetails(apiId.toString(), "tenant-1");

        Map<String, Object> endpoint = ((List<Map<String, Object>>) details.get("endpoints")).get(0);
        List<Map<String, Object>> params = (List<Map<String, Object>>) endpoint.get("params");
        Map<String, Object> headers = (Map<String, Object>) endpoint.get("headers");

        // The tool AND its parameter rows must exist for this to prove anything: with them
        // unstubbed the lookup returns nothing, hiddenParamNames is empty for the wrong reason,
        // and the test would pass just as well if the hidden discrimination were broken outright.
        assertEquals(2, params.size(), "the query param AND the declared header param come back");
        assertTrue(params.stream().anyMatch(pm -> "q".equals(pm.get("name"))));
        Map<String, Object> headerParam = params.stream()
                .filter(pm -> "anthropic-version".equals(pm.get("name"))).findFirst()
                .orElseThrow(() -> new AssertionError("the declared header param was dropped"));
        assertEquals("header", headerParam.get("in"), "it must come back declared in:'header'");
        assertNull(headers, "nothing here is a constant, so no headers map is emitted");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCustomApiDetailsStillReturnsAHiddenHeaderAsAConstant() {
        // The other half of the split: a hidden header param IS a constant from the `headers`
        // map, and must go back there so it is re-registered as one rather than surfacing to the
        // caller as an input they are expected to fill.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        ApiResponse withHeader = apiResponseWithHeaderParam(apiId);
        when(apiService.getApiById(apiId)).thenReturn(withHeader);

        // Matched on the tool's own id, which the response carries, so there is no derived slug
        // shape to guess at and no heuristic that can miss.
        ApiToolEntity toolEntity = new ApiToolEntity();
        UUID toolEntityId = withHeader.tools().get(0).id();
        toolEntity.setId(toolEntityId);
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(toolEntity));
        ApiToolParameterEntity hidden = new ApiToolParameterEntity();
        hidden.setName("anthropic-version");
        hidden.setIsHidden(true);
        when(apiToolParameterRepository.findByApiToolId(toolEntityId)).thenReturn(List.of(hidden));

        Map<String, Object> details = service.getCustomApiDetails(apiId.toString(), "tenant-1");

        Map<String, Object> endpoint = ((List<Map<String, Object>>) details.get("endpoints")).get(0);
        List<Map<String, Object>> params = (List<Map<String, Object>>) endpoint.get("params");
        Map<String, Object> headers = (Map<String, Object>) endpoint.get("headers");

        assertEquals(1, params.size(), "only the query param stays a param");
        assertEquals("q", params.get(0).get("name"));
        assertNotNull(headers, "a hidden header must come back as a constant");
        assertEquals("2023-06-01", headers.get("anthropic-version"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getCustomApiDetailsOmitsTheHeadersMapWhenThereAreNone() {
        UUID apiId = UUID.randomUUID();
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(buildEntity(apiId, "custom", "tenant-1")));
        when(apiService.getApiById(apiId)).thenReturn(apiResponseWithQueryParamOnly(apiId));

        Map<String, Object> details = service.getCustomApiDetails(apiId.toString(), "tenant-1");

        Map<String, Object> endpoint = ((List<Map<String, Object>>) details.get("endpoints")).get(0);
        assertFalse(endpoint.containsKey("headers"), "an endpoint without static headers carries no headers key");
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
                eq(apiId), eq("testapi"), eq("bearer_token"), eq("testapi"), any(), any(), any());
    }

    @Test
    void theCredentialNameAndTheApiIconSlugAreTheSameString() {
        // The defect this pins: the two were derived from different inputs, and normalize()
        // strips a trailing "-api" from the generated slug but not from the name, so an API
        // called "Weather API" got icon_slug "weather" while its credential was "weatherapi".
        // The credential stayed self-consistent; what diverged was apis.icon_slug, which is the
        // first segment of the workflow node type, the icon file, and the text of a failed
        // call's 401. A consistency defect, not an unreachable key.
        ObjectNode json = buildValidApiJson();
        json.put("apiName", "Weather API");
        json.put("authType", "bearer");

        UUID apiId = UUID.randomUUID();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse(apiId));

        service.registerCustomApi(json, "tenant-1");

        ArgumentCaptor<ApiConfigurationRequest> requestCaptor =
                ArgumentCaptor.forClass(ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(requestCaptor.capture(), eq("tenant-1"));

        // Handed to the submission path as an EXPLICIT icon slug, which is what stops it deriving
        // its own from the generated api_slug.
        assertEquals("weatherapi", requestCaptor.getValue().iconSlug(),
                "the API must carry the same slug the credential is named by");
        verify(catalogSeedCredentialService).linkCredentials(
                eq(apiId), eq("weatherapi"), eq("bearer_token"), eq("weatherapi"), any(), any(), any());
    }

    @Test
    void anApiNameWithNoLettersOrDigitsIsRefusedWhenItNeedsACredential() {
        // The slug is the credential's name. If it reduces to nothing there is no name to store a
        // key under, and the API would register successfully with no reachable credential at all.
        ObjectNode json = buildValidApiJson();
        json.put("apiName", "!!!");
        json.put("authType", "bearer");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("apiName"), "the error must name the offending field");
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
        // Same reason as deletingACustomApiStillDiscardsItsStoredKey: both deletes below hang off
        // this answer, so it is stubbed explicitly rather than left to the default.
        when(apiRepository.existsSharedIntegrationWithCredentialKey("myapi", "tenant-1"))
                .thenReturn(false);

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
        // Third test to hang off this answer, stubbed for the same reason as the other two: the
        // client call under test is only REACHED when the key is ours.
        when(apiRepository.existsSharedIntegrationWithCredentialKey("myapi", "tenant-1"))
                .thenReturn(false);
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
    void aParamDeclaredInHeaderBecomesAHeaderParameter() {
        // in='header' used to be rejected outright, while the engine has always applied header
        // parameters. So an API needing a per-request header could not be described at all. The
        // rejection did at least SAY so, which is why this ranked below the silent defects.
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var param = ep.putArray("params").addObject();
        param.put("name", "X-Trace-ID");
        param.put("in", "header");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "Trace header");

        var headers = headersOf(json);
        assertEquals(1, headers.size(), "the declared header param must reach the tool");
        assertEquals("X-Trace-ID", headers.get(0).name());
        assertEquals(Boolean.TRUE, headers.get(0).required());
        assertEquals(Boolean.FALSE, headers.get(0).isHidden(),
                "a caller-supplied header is visible, unlike a static constant");
    }

    @Test
    void aHeaderParamCannotTakeTheHeaderTheCredentialTravelsIn() {
        // applyHeaderParameters sets a caller-supplied value for a declared header param with no
        // collision guard, so a param named after the auth header is an invitation to overwrite
        // the secret. Refused loudly rather than dropped: a declared parameter is a specific
        // request, and silently losing it hands back a tool missing an input.
        ObjectNode json = buildValidApiJson();
        json.put("authType", "bearer");
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var param = ep.putArray("params").addObject();
        param.put("name", "Authorization");
        param.put("in", "header");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "auth");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("Authorization"), "the error must name the header");
    }

    @Test
    void aHeaderParamCannotTakeAHeaderTheTransportOwns() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var param = ep.putArray("params").addObject();
        param.put("name", "Content-Length");
        param.put("in", "header");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "length");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("Content-Length"), "the error must name the header");
    }

    @Test
    void anUnknownParamLocationIsStillRefusedWithTheAllowedList() {
        ObjectNode json = buildValidApiJson();
        var ep = (ObjectNode) json.path("endpoints").get(0);
        var param = ep.putArray("params").addObject();
        param.put("name", "weird");
        param.put("in", "cookie");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "nope");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("cookie"), "Error should mention the offending value");
        assertTrue(ex.getMessage().contains("query") && ex.getMessage().contains("path")
                        && ex.getMessage().contains("body") && ex.getMessage().contains("header"),
                "Error should list allowed 'in' values, header included");
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

    // ─────────────────────────────────────────────────────────────────────
    // Static headers declared by a custom API must actually be SENT.
    //
    // Regression: the value was written to HeaderDto.value while defaultValue
    // stayed null, and ApiConfigurationConverter only propagates defaultValue
    // when non-null - so the header was accepted at registration and silently
    // dropped from the request. An API needing a constant header (Anthropic's
    // anthropic-version) answered 400 with nothing in the catalog to explain it.
    // ─────────────────────────────────────────────────────────────────────

    /** Registers {@code json} and returns the single tool the orchestrator received. */
    private ApiConfigurationRequest.McpToolDto captureTool(ObjectNode json) {
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse());
        service.registerCustomApi(json, "tenant-1");
        ArgumentCaptor<ApiConfigurationRequest> captor = ArgumentCaptor.forClass(ApiConfigurationRequest.class);
        verify(apiService).processApiConfiguration(captor.capture(), eq("tenant-1"));
        return captor.getValue().mcpTools().get(0);
    }

    private ObjectNode endpointHeaders(ObjectNode json) {
        return ((ObjectNode) json.get("endpoints").get(0)).putObject("headers");
    }

    private ObjectNode withHeader(String name, String value) {
        ObjectNode json = buildValidApiJson();
        endpointHeaders(json).put(name, value);
        return json;
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The declared auth block, end to end through registerCustomApi. Without these, an
    // implementation that parsed the block and then passed null to linkCredentials would
    // keep every other test in this file green.
    // ════════════════════════════════════════════════════════════════════════════════

    /** Runs a registration and returns the placement that actually reached linkCredentials. */
    private CatalogSeedCredentialService.ApiKeyConfig capturePlacement(ObjectNode json) {
        UUID apiId = UUID.randomUUID();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse(apiId));
        service.registerCustomApi(json, "tenant-1");
        ArgumentCaptor<CatalogSeedCredentialService.ApiKeyConfig> captor =
                ArgumentCaptor.forClass(CatalogSeedCredentialService.ApiKeyConfig.class);
        verify(catalogSeedCredentialService).linkCredentials(
                eq(apiId), anyString(), anyString(), anyString(), any(), captor.capture(), any());
        return captor.getValue();
    }

    /** Runs a registration and returns the oauth2 block that actually reached linkCredentials. */
    private JsonNode captureOAuthConfig(ObjectNode json) {
        UUID apiId = UUID.randomUUID();
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse(apiId));
        service.registerCustomApi(json, "tenant-1");
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
        verify(catalogSeedCredentialService).linkCredentials(
                eq(apiId), anyString(), anyString(), anyString(), any(), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void aDeclaredApiKeyConfigTravelsFromTheSubmittedJsonToTheCredential() {
        // The capability the whole change exists to add: an API can say where its credential
        // goes. Asserting it on the parser alone would not prove the value ever reaches the
        // credential, which is the only place it has any effect.
        ObjectNode json = buildValidApiJson();
        ObjectNode auth = json.putArray("auth").addObject();
        auth.put("type", "apikey");
        ObjectNode cfg = auth.putObject("apiKeyConfig");
        cfg.put("location", "header");
        cfg.put("headerName", "X-Api-Token");
        cfg.put("keyName", "token");
        cfg.put("prefix", "Token ");

        var placement = capturePlacement(json);

        assertNotNull(placement, "the declared placement must reach linkCredentials, not null");
        assertEquals("header", placement.location());
        assertEquals("X-Api-Token", placement.headerName());
        assertEquals("token", placement.keyName());
        assertEquals("Token ", placement.prefix());
    }

    @Test
    void aQueryPlacementTravelsThroughToo() {
        ObjectNode json = buildValidApiJson();
        ObjectNode auth = json.putArray("auth").addObject();
        auth.put("type", "apikey");
        ObjectNode cfg = auth.putObject("apiKeyConfig");
        cfg.put("location", "query");
        cfg.put("queryParamName", "api_key");

        var placement = capturePlacement(json);

        assertNotNull(placement);
        assertEquals("query", placement.location());
        assertEquals("api_key", placement.queryParamName());
    }

    @Test
    void anEmptyPrefixSurvivesTheJourneyAsAnExplicitEmptyString() {
        // "" means "send the credential raw", which several providers require. Collapsing it to
        // null restores the "Bearer " default and silently breaks those providers.
        ObjectNode json = buildValidApiJson();
        ObjectNode auth = json.putArray("auth").addObject();
        auth.put("type", "bearer");
        auth.putObject("apiKeyConfig").put("prefix", "");

        var placement = capturePlacement(json);

        assertNotNull(placement, "a config carrying only an empty prefix is still a declaration");
        assertEquals("", placement.prefix(), "an explicit empty prefix must not become null");
    }

    @Test
    void anAbsentApiKeyConfigStaysNullRatherThanBecomingAnEmptyDeclaration() {
        ObjectNode json = withAuth(buildValidApiJson(), "apikey");
        assertNull(capturePlacement(json),
                "no declaration means no placement, so the default for the auth type applies");
    }

    @Test
    void aDeclaredButEmptyApiKeyConfigIsTreatedAsNoDeclaration() {
        ObjectNode json = buildValidApiJson();
        ObjectNode auth = json.putArray("auth").addObject();
        auth.put("type", "apikey");
        auth.putObject("apiKeyConfig");

        assertNull(capturePlacement(json), "an empty block declares nothing");
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // OAuth2
    // ════════════════════════════════════════════════════════════════════════════════

    private ObjectNode withOAuth(ObjectNode json, String authUrl, String tokenUrl) {
        ObjectNode auth = json.putArray("auth").addObject();
        auth.put("type", "oauth2");
        ObjectNode cfg = auth.putObject("oauth2Config");
        if (authUrl != null) cfg.put("authorizationUrl", authUrl);
        if (tokenUrl != null) cfg.put("tokenUrl", tokenUrl);
        cfg.putArray("scopes").add("read");
        return json;
    }

    @Test
    void oauth2IsAcceptedWhenItDeclaresTheEndpointsItNeeds() {
        // oauth2 used to be accepted and then impossible to complete, because the provider
        // endpoints were never stored anywhere the OAuth engine reads. It is now available
        // exactly when it can succeed.
        ObjectNode json = withOAuth(buildValidApiJson(),
                "https://example.com/oauth/authorize", "https://example.com/oauth/token");

        JsonNode config = captureOAuthConfig(json);

        assertNotNull(config, "the provider endpoints must reach the credential");
        assertEquals("https://example.com/oauth/authorize", config.path("authorizationUrl").asText());
        assertEquals("https://example.com/oauth/token", config.path("tokenUrl").asText());
    }

    @Test
    void oauth2WithoutItsConfigIsRefusedRatherThanRegisteredIntoADeadEnd() {
        ObjectNode json = withAuth(buildValidApiJson(), "oauth2");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("oauth2Config"), "the refusal must name what is missing");
    }

    @Test
    void oauth2MissingItsTokenUrlIsRefused() {
        ObjectNode json = withOAuth(buildValidApiJson(), "https://example.com/oauth/authorize", null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("tokenUrl"), "the refusal must name the missing endpoint");
    }

    @Test
    void anOAuthEndpointRejectedByTheUrlGuardIsRefusedWithAClearMessage() {
        // A credential is about to be sent to these URLs, so they go through the same safety
        // check the baseUrl gets. This class mocks that check to a no-op for every other test,
        // so asserting on a malformed string here would prove nothing: the assertion would be
        // against a neutered validator. Make the guard refuse THIS value instead, which tests
        // what is actually ours - that the endpoints are submitted to it at all, and that its
        // refusal comes back naming the field rather than as a raw failure.
        String bad = "http://127.0.0.1/oauth/authorize";
        urlValidatorMock.when(() -> UrlSafetyValidator.validateUrlFormat(bad))
                .thenThrow(new IllegalArgumentException("URL points to a private address"));
        ObjectNode json = withOAuth(buildValidApiJson(), bad, "https://example.com/oauth/token");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("authorizationUrl"),
                "the refusal must name which endpoint was rejected");
    }

    @Test
    void anOAuthTokenEndpointIsSubmittedToTheUrlGuardToo() {
        // The authorizationUrl is the obvious one to guard; the tokenUrl receives the client
        // secret, so leaving it unchecked would be the more dangerous omission.
        String bad = "http://10.0.0.1/oauth/token";
        urlValidatorMock.when(() -> UrlSafetyValidator.validateUrlFormat(bad))
                .thenThrow(new IllegalArgumentException("URL points to a private address"));
        ObjectNode json = withOAuth(buildValidApiJson(), "https://example.com/oauth/authorize", bad);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("tokenUrl"),
                "the refusal must name which endpoint was rejected");
    }

    @Test
    void aNonOAuthApiCarriesNoOAuthConfig() {
        assertNull(captureOAuthConfig(withAuth(buildValidApiJson(), "bearer")),
                "only oauth2 has provider endpoints to store");
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The shared-credential collision
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    void aCredentialKeyOwnedBySomethingElseIsRefused() {
        // catalog.credentials.credential_name is UNIQUE with no tenant column, and registration
        // upserts into it. The rows at risk include NATIVE templates that have no catalog.apis
        // row at all: imap and smtp are both on production under variant='primary', which is
        // exactly what this path writes, so an API named "IMAP" would have upserted straight
        // over the template the Email Inbox node depends on.
        when(apiRepository.existsSharedIntegrationWithCredentialKey("imap", "tenant-1")).thenReturn(true);
        ObjectNode json = withAuth(buildValidApiJson(), "bearer");
        json.put("apiName", "IMAP");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("imap"), "the refusal must name the colliding key");
    }

    @Test
    void deletingACustomApiLeavesACredentialItDoesNotOwn() {
        // The other side of the same hazard. A row written before the guard existed can still
        // collide, and removing it would take an installation-wide template with it.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("imap");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey("imap", "tenant-1")).thenReturn(true);

        service.deleteCustomApi(apiId.toString(), "tenant-1");

        verify(catalogSeedCredentialService, never()).deleteCredentialByName(anyString());
        verify(apiService).deleteApi(apiId);
    }

    @Test
    void aFailingOwnershipLookupLeavesTheCredentialInPlace() {
        // Fail CLOSED here, unlike registration. An orphaned template is a cosmetic leak;
        // removing a shared one breaks an integration for every tenant on the installation.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("testapi");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey(eq("testapi"), any()))
                .thenThrow(new RuntimeException("db down"));

        service.deleteCustomApi(apiId.toString(), "tenant-1");

        verify(catalogSeedCredentialService, never()).deleteCredentialByName(anyString());
    }

    @Test
    void aKeylessApiIsNotSubjectedToTheCollisionCheck() {
        // With no credential there is no shared row to overwrite, so the check must not run and
        // must not refuse a legitimate name.
        ObjectNode json = buildValidApiJson();
        json.put("apiName", "Stripe");
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse());

        assertDoesNotThrow(() -> service.registerCustomApi(json, "tenant-1"));
        verify(apiRepository, never()).existsSharedIntegrationWithCredentialKey(anyString(), any());
    }

    @Test
    void aFailingCollisionLookupLetsTheRegistrationThrough() {
        // Fail OPEN. The collision is rare and recoverable; refusing every registration because
        // one query failed is neither.
        when(apiRepository.existsSharedIntegrationWithCredentialKey(anyString(), any()))
                .thenThrow(new RuntimeException("db down"));
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse());

        assertDoesNotThrow(() -> service.registerCustomApi(withAuth(buildValidApiJson(), "bearer"), "tenant-1"));
    }

    @Test
    void theCollisionCheckIsScopedToTheCaller() {
        // The hole this closes: the query exempted a key owned by ANY custom API, so the first
        // squatter disabled the guard for that key permanently, for every user after them. On
        // production the built-in Ghost integration lost its credential row and all 41 of its
        // tool_credentials links that way, and a probe registering an API named "Ghost" was then
        // accepted with credential_name 'ghost'. The owner has to reach the query for the
        // exemption to mean "mine" rather than "somebody's".
        when(apiRepository.existsSharedIntegrationWithCredentialKey(anyString(), any()))
                .thenReturn(false);
        when(apiService.processApiConfiguration(any(), eq("tenant-9"))).thenReturn(mockApiResponse());

        service.registerCustomApi(withAuth(buildValidApiJson(), "bearer"), "tenant-9");

        verify(apiRepository).existsSharedIntegrationWithCredentialKey(anyString(), eq("tenant-9"));
    }

    @Test
    void theDeletePathIsScopedToTheApiOwner() {
        // Same hole, worse consequence: here the answer decides whether a shared template is
        // DELETED. Asking "does some custom API own this key" answered yes for the very API being
        // removed, which is how the shared row went.
        // The owner and the caller MUST be different here, or the assertion cannot fail on its
        // own subject: with caller == creator both candidate values are the same string and the
        // verify passes whichever the implementation sent. An org makes them differ legitimately,
        // because ScopeGuard.isInStrictScope authorises on the ORGANIZATION and ignores the
        // creator entirely, so an org-mate may delete a row they did not create. Which of the two
        // reaches the query is the whole reason the delete site differs from the registration
        // site: asking about the caller would exempt nothing the row actually owns.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "author-A");
        entity.setOrganizationId("org-1");
        entity.setPlatformCredentialName("ghost");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey(anyString(), any()))
                .thenReturn(true);

        TenantResolver.runWithOrgScope("org-1",
                () -> service.deleteCustomApi(apiId.toString(), "member-B"));

        verify(apiRepository).existsSharedIntegrationWithCredentialKey("ghost", "author-A");
        verify(apiRepository, never()).existsSharedIntegrationWithCredentialKey(anyString(), eq("member-B"));
        verify(catalogSeedCredentialService, never()).deleteCredentialByName(anyString());
    }

    @Test
    void refusingToDeleteASquattedTemplateAlsoSpeaksForTheStoredSecret() {
        // A credential key is the same string on both sides of the delete: catalog.credentials
        // holds the template, auth.platform_credentials holds a secret FOR THAT KEY. The catalog
        // half learned to refuse a key the API does not own; the tenant half did not, so deleting
        // a custom API that squatted a shipped integration key kept the template and then deleted
        // a secret belonging to the SHIPPED integration. That is the worse of the two losses: a
        // re-import rebuilds a template, and nothing rebuilds a secret.
        // Creator and caller are deliberately DIFFERENT here (an org-mate deleting a row they did
        // not create, which ScopeGuard allows), because the two deletes use different identities:
        // the ownership question is asked about the creator, the secret delete is sent under the
        // caller. With one string standing for both, the assertions cannot tell them apart.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "author-A");
        entity.setOrganizationId("org-1");
        entity.setPlatformCredentialName("ghost");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey("ghost", "author-A"))
                .thenReturn(true);

        TenantResolver.runWithOrgScope("org-1",
                () -> service.deleteCustomApi(apiId.toString(), "member-B"));

        verify(catalogSeedCredentialService, never()).deleteCredentialByName(anyString());
        verify(credentialClient, never()).deleteTenantPlatformCredential(anyString(), anyString());
        // The API itself still goes. Refusing to touch a key that is not ours must not turn into
        // refusing to delete the API the caller asked to delete.
        verify(apiService).deleteApi(apiId);
    }

    @Test
    void anOwnedKeyIsStillDeletedUnderTheCallersTenantNotTheCreators() {
        // The positive counterpart, and the one that pins WHICH identity each half uses. The
        // ownership question must be asked about the CREATOR (author-A owns the row, so the key
        // is legitimately theirs and the delete proceeds), while the secret delete is sent under
        // the CALLER (member-B). Only a fixture where the two differ can show it: with
        // caller == creator the verify passes whichever one was sent.
        //
        // Read the last assertion as what it is - a deliberate choice, not a proof of
        // completeness. Author-A's own stored secret is NOT deleted here and is left orphaned,
        // because one member destroying another's secret off the back of an API delete is the
        // trade this fix refuses everywhere else. The service javadoc says the same.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "author-A");
        entity.setOrganizationId("org-1");
        entity.setPlatformCredentialName("mything");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey("mything", "author-A"))
                .thenReturn(false);

        TenantResolver.runWithOrgScope("org-1",
                () -> service.deleteCustomApi(apiId.toString(), "member-B"));

        verify(catalogSeedCredentialService).deleteCredentialByName("mything");
        verify(credentialClient).deleteTenantPlatformCredential("mything", "member-B");
        verify(credentialClient, never()).deleteTenantPlatformCredential(anyString(), eq("author-A"));
        // The design claim is "ask once, gate both deletes on the answer". Two lookups could
        // disagree, and this fails the day someone re-asks the question for the tenant half.
        verify(apiRepository, times(1)).existsSharedIntegrationWithCredentialKey("mything", "author-A");
    }

    @Test
    void updatingASquattingApiDeletesNeitherCredentialRowButStillReWritesTheTemplate() {
        // The update path shares the catalog half and must not have gained the tenant half from
        // the signature change: a refused ownership answer must DELETE neither row.
        //
        // "Deletes neither" is the exact claim, not "touches neither". The re-registration's
        // linkCredentials still UPSERTS catalog.credentials under the same name, installation-wide,
        // and that is deliberate: gating it would make an API carrying a shipped key permanently
        // un-editable, a regression this repo has already fixed once. Asserting the upsert here
        // keeps the intended write visible instead of leaving a silent mock call behind a test
        // whose name would otherwise deny it happens.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("ghost");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey(anyString(), any()))
                .thenReturn(true);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse());

        ObjectNode updates = withAuth(buildValidApiJson(), "bearer");
        updates.put("apiName", "Ghost");

        service.updateCustomApi(apiId.toString(), updates, "tenant-1");

        verify(catalogSeedCredentialService, never()).deleteCredentialByName(anyString());
        verify(credentialClient, never()).deleteTenantPlatformCredential(anyString(), anyString());
        verify(catalogSeedCredentialService).linkCredentials(
                any(UUID.class), eq("ghost"), anyString(), eq("ghost"), any(), any(), any());
    }

    @Test
    void anUnanswerableOwnershipQuestionKeepsTheStoredSecretToo() {
        // The ownership lookup fails CLOSED: not knowing whose key it is must not destroy
        // anything. That posture was already true of the template and silently untrue of the
        // secret, so a database hiccup during a delete cost the caller a key that was never at
        // issue.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("ghost");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey("ghost", "tenant-1"))
                .thenThrow(new RuntimeException("connection reset"));

        service.deleteCustomApi(apiId.toString(), "tenant-1");

        verify(catalogSeedCredentialService, never()).deleteCredentialByName(anyString());
        verify(credentialClient, never()).deleteTenantPlatformCredential(anyString(), anyString());
        verify(apiService).deleteApi(apiId);
    }

    @Test
    void aShippedApiCarryingTheKeyOnlyAsAnIconSlugStillRefusesRegistration() {
        // The predicate deliberately matches icon_slug as well as platform_credential_name,
        // because V233 exists precisely because platform_credential_name was NULL on real shipped
        // rows. This widens what is refused, so it is worth stating: a shipped integration that
        // carries the key only as its icon slug, and has no credential row at all, still blocks
        // the name. That is the case the Ghost incident left behind.
        when(apiRepository.existsSharedIntegrationWithCredentialKey(eq("ghost"), eq("tenant-1")))
                .thenReturn(true);
        ObjectNode json = withAuth(buildValidApiJson(), "bearer");
        json.put("apiName", "Ghost");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("ghost"), "the refusal must name the colliding key");
    }

    @Test
    void updatingAnApiWhoseKeyCollidesWithAShippedIntegrationStillWorks() {
        // update_api is delete-then-re-register in one transaction. Once the guard learned to
        // refuse a key a shipped integration holds, the re-registration refused the row it was
        // re-creating, so the update threw and rolled back: an API carrying such a key became
        // permanently un-editable. Seven of one production customer's twelve custom APIs carry
        // one, so this is the population the guard exists to protect, not an edge case.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("ghost");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey(anyString(), any()))
                .thenReturn(true);
        when(apiService.processApiConfiguration(any(), eq("tenant-1"))).thenReturn(mockApiResponse());

        ObjectNode updates = withAuth(buildValidApiJson(), "bearer");
        updates.put("apiName", "Ghost");

        assertDoesNotThrow(() -> service.updateCustomApi(apiId.toString(), updates, "tenant-1"));
    }

    @Test
    void updatingAnApiCannotClaimADIFFERENTKeyThatIsAlreadyTaken() {
        // The exemption is for the key the row already held, not a licence to rename into
        // somebody else's. Renaming "My Thing" to "Stripe" must still be refused.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("mything");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiRepository.existsSharedIntegrationWithCredentialKey(anyString(), any()))
                .thenReturn(true);

        ObjectNode updates = withAuth(buildValidApiJson(), "bearer");
        updates.put("apiName", "Stripe");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.updateCustomApi(apiId.toString(), updates, "tenant-1"));
        assertTrue(ex.getMessage().contains("stripe"), "the refusal must name the colliding key");
    }

    @Test
    void aRegistrationWithNoCallerIdentityIsCheckedAgainstShippedIntegrationsOnly() {
        // Without a caller the ownership half can never say "mine", so asking the full predicate
        // would answer "collision" for every key that has any credential row and refuse a
        // legitimate registration. This site fails OPEN, so it must ask the identity-free half.
        when(apiRepository.existsShippedIntegrationWithCredentialKey(anyString())).thenReturn(false);
        when(apiService.processApiConfiguration(any(), eq(""))).thenReturn(mockApiResponse());

        assertDoesNotThrow(() -> service.registerCustomApi(withAuth(buildValidApiJson(), "bearer"), ""));

        verify(apiRepository).existsShippedIntegrationWithCredentialKey(anyString());
        verify(apiRepository, never()).existsSharedIntegrationWithCredentialKey(anyString(), any());
    }

    @Test
    void aRegistrationWithNoCallerIdentityIsStillRefusedOverAShippedIntegration() {
        // Fail-open is about not refusing what it cannot judge, not about waving through the one
        // thing it can still judge without an identity.
        when(apiRepository.existsShippedIntegrationWithCredentialKey(anyString())).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(withAuth(buildValidApiJson(), "bearer"), ""));
        assertFalse(ex.getMessage().contains("built-in integration"),
                "the text must not assert something the check cannot know: " + ex.getMessage());
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Transport-managed headers
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    void aHeaderParamNamedAfterAHopByHopHeaderIsRefused() {
        // Validating against the static-header skip list accepted Expect, Upgrade, TE, Trailer,
        // Keep-Alive and the two Proxy- headers, all of which the runtime discards before
        // sending. Accepted and silently dropped is the failure mode being closed.
        ObjectNode json = buildValidApiJson();
        var param = ((ObjectNode) json.path("endpoints").get(0)).putArray("params").addObject();
        param.put("name", "Expect");
        param.put("in", "header");
        param.put("type", "string");
        param.put("required", true);
        param.put("description", "nope");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("Expect"), "the error must name the header");
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // credentialNameFor
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    void credentialNameForStripsTheTrailingApiExactlyAsTheSlugDoes() {
        assertEquals("weatherapi",
                CustomApiRegistrationService.credentialNameFor(withAuth(buildValidApiJson(), "bearer"), "Weather API"));
        assertEquals("weather",
                CustomApiRegistrationService.credentialNameFor(withAuth(buildValidApiJson(), "bearer"), "Weather-API"),
                "a hyphenated -api suffix IS stripped, which is the asymmetry that caused the bug");
    }

    @Test
    void credentialNameForPrefersAnExplicitIconSlug() {
        ObjectNode json = withAuth(buildValidApiJson(), "bearer");
        json.put("iconSlug", "myBrand");
        assertEquals("mybrand", CustomApiRegistrationService.credentialNameFor(json, "Weather API"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDeclaredAuthComesBackOutOfTheDetailsSoAnEditorCanHandItBack() {
        // "A declaration nobody can read back is a declaration the next edit destroys" is the
        // reason this readback exists, so it needs a test of its own: the details must carry the
        // placement, and the credential key, or the editor rebuilds the definition without them.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("testapi");
        entity.setIconSlug("testapi");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiService.getApiById(apiId)).thenReturn(apiResponseWithQueryParamOnly(apiId));

        ObjectNode stored = objectMapper.createObjectNode();
        stored.putObject("apiKeyConfig").put("headerName", "X-Api-Token");
        stored.putObject("oauth2Config").put("tokenUrl", "https://example.com/oauth/token");
        when(catalogSeedCredentialService.readDeclaredAuth("testapi")).thenReturn(stored);

        Map<String, Object> details = service.getCustomApiDetails(apiId.toString(), "tenant-1");

        assertEquals("testapi", details.get("iconSlug"), "the credential key must travel with the details");
        JsonNode auth = (JsonNode) details.get("auth");
        assertNotNull(auth, "the declared auth block must come back");
        assertEquals("X-Api-Token", auth.get(0).path("apiKeyConfig").path("headerName").asText());
        assertEquals("https://example.com/oauth/token",
                auth.get(0).path("oauth2Config").path("tokenUrl").asText());
    }

    @Test
    void noAuthBlockIsEmittedWhenNothingWasDeclared() {
        // An empty block would make an editor send `auth: [{type}]` back on every save, which is
        // a declaration of nothing and would look like an intentional reset.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        entity.setPlatformCredentialName("testapi");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        when(apiService.getApiById(apiId)).thenReturn(apiResponseWithQueryParamOnly(apiId));
        when(catalogSeedCredentialService.readDeclaredAuth("testapi"))
                .thenReturn(objectMapper.createObjectNode());

        Map<String, Object> details = service.getCustomApiDetails(apiId.toString(), "tenant-1");

        assertNull(details.get("auth"), "nothing declared means no auth block");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aHiddenHeaderWithNoConstantComesBackAsAParamRatherThanVanishing() {
        // The `headers` map is name-to-value, so it cannot hold a hidden header that has no
        // value. Sending such a param to that map dropped it from BOTH sides of the projection,
        // so the next save deleted a declared input. The form can create exactly this shape
        // (in='header' plus the hidden checkbox), so it was reachable, not theoretical.
        UUID apiId = UUID.randomUUID();
        ApiEntity entity = buildEntity(apiId, "custom", "tenant-1");
        when(apiRepository.findById(apiId)).thenReturn(Optional.of(entity));
        ApiResponse response = toolWith(apiId, List.of(param("X-Internal-Id", "header", null)));
        when(apiService.getApiById(apiId)).thenReturn(response);

        UUID toolEntityId = response.tools().get(0).id();
        ApiToolEntity toolEntity = new ApiToolEntity();
        toolEntity.setId(toolEntityId);
        when(apiToolRepository.findByApiIdAndIsActiveTrue(apiId)).thenReturn(List.of(toolEntity));
        ApiToolParameterEntity hidden = new ApiToolParameterEntity();
        hidden.setName("X-Internal-Id");
        hidden.setIsHidden(true);
        when(apiToolParameterRepository.findByApiToolId(toolEntityId)).thenReturn(List.of(hidden));

        Map<String, Object> details = service.getCustomApiDetails(apiId.toString(), "tenant-1");
        Map<String, Object> endpoint = ((List<Map<String, Object>>) details.get("endpoints")).get(0);
        List<Map<String, Object>> params = (List<Map<String, Object>>) endpoint.get("params");

        assertEquals(1, params.size(), "a valueless hidden header must survive as a param");
        assertEquals("X-Internal-Id", params.get(0).get("name"));
        assertEquals("header", params.get(0).get("in"));
        assertEquals(Boolean.TRUE, params.get(0).get("hidden"), "and keep its hidden flag");
    }

    @Test
    void anOAuthRefreshUrlIsValidatedWhenPresentAndSkippedWhenBlank() {
        // refreshUrl is optional and defaults to tokenUrl, so a typo in it would otherwise only
        // surface at the first token refresh, hours after the connection appeared to work.
        String bad = "http://127.0.0.1/oauth/refresh";
        urlValidatorMock.when(() -> UrlSafetyValidator.validateUrlFormat(bad))
                .thenThrow(new IllegalArgumentException("URL points to a private address"));
        ObjectNode withBad = withOAuth(buildValidApiJson(),
                "https://example.com/oauth/authorize", "https://example.com/oauth/token");
        ((ObjectNode) withBad.path("auth").get(0).path("oauth2Config")).put("refreshUrl", bad);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(withBad, "tenant-1"));
        assertTrue(ex.getMessage().contains("refreshUrl"), "the refusal must name the field");

        ObjectNode blank = withOAuth(buildValidApiJson(),
                "https://example.com/oauth/authorize", "https://example.com/oauth/token");
        ((ObjectNode) blank.path("auth").get(0).path("oauth2Config")).put("refreshUrl", "");
        assertNotNull(captureOAuthConfig(blank), "a blank refreshUrl is simply absent, not invalid");
    }

    @Test
    void aHiddenHeaderParamKeepsItsHiddenFlag() {
        // hidden was hardcoded false on this builder, so a declared hidden:true was accepted and
        // dropped. It also decides how the param is projected back, so losing it changed the
        // round trip as well as the caller's view.
        ObjectNode json = buildValidApiJson();
        var param = ((ObjectNode) json.path("endpoints").get(0)).putArray("params").addObject();
        param.put("name", "X-Internal-Id");
        param.put("in", "header");
        param.put("type", "string");
        param.put("required", true);
        param.put("hidden", true);
        param.put("description", "internal");

        var headers = headersOf(json);
        assertEquals(1, headers.size());
        assertEquals(Boolean.TRUE, headers.get(0).isHidden(), "a declared hidden flag must survive");
    }

    private ObjectNode withAuth(ObjectNode json, String type) {
        json.putArray("auth").addObject().put("type", type);
        return json;
    }

    private List<ApiConfigurationRequest.HeaderDto> headersOf(ObjectNode json) {
        return captureTool(json).headers();
    }

    @Test
    void staticHeaderCarriesItsValueAsDefaultValueSoTheRuntimeSendsIt() {
        var headers = headersOf(withHeader("anthropic-version", "2023-06-01"));

        assertEquals(1, headers.size());
        var header = headers.get(0);
        // THE regression: applyHeaderParameters injects from defaultValue, and
        // ApiConfigurationConverter only propagates it when non-null.
        assertEquals("2023-06-01", header.defaultValue(),
                "the value must land in defaultValue, or the header is never sent");
        assertEquals("2023-06-01", header.value(),
                "value is persisted as example_value, the hint an agent reads");
        assertTrue(header.required(), "an auto-supplied header is required");
    }

    @Test
    void autoSuppliedHeaderIsHiddenAndDescribedSoNoAgentIsAskedToFillIt() {
        var header = headersOf(withHeader("anthropic-version", "2023-06-01")).get(0);

        assertTrue(header.isHidden(),
                "the platform supplies the value; a visible required param would ask an agent for it");
        assertEquals("Required request header (auto-supplied).", header.description(),
                "a required parameter with an empty description is unusable by an agent");
    }

    @Test
    void headerNameKeepsItsOriginalCasing() {
        var header = headersOf(withHeader("Anthropic-Version", "2023-06-01")).get(0);

        assertEquals("Anthropic-Version", header.name(),
                "the name is lowercased only for comparison, never for emission");
    }

    @Test
    void everyTransportManagedHeaderIsSkipped() {
        // The HTTP client computes these; a stale Content-Length or a wrong Host
        // corrupts the request. Same six names the seed importer refuses.
        for (String name : List.of("Content-Type", "Content-Length", "Host",
                "Connection", "Transfer-Encoding", "Accept-Encoding")) {
            reset(apiService);
            var headers = headersOf(withHeader(name, "whatever"));

            assertTrue(headers.isEmpty(), name + " is owned by the transport and must never be declared");
        }
    }

    @Test
    void documentationProseIsNotInjectedAsAHeaderValue() {
        var headers = headersOf(withHeader("Xero-Tenant-Id", "obtain this via the /connections endpoint"));

        assertTrue(headers.isEmpty(), "a value carrying whitespace is prose, not a literal header value");
    }

    @Test
    void blankHeaderValueIsSkipped() {
        var headers = headersOf(withHeader("X-Note", ""));

        assertTrue(headers.isEmpty(), "an empty value would send an empty header");
    }

    @Test
    void doubleBraceTemplateIsNotInjectedAsAHeaderValue() {
        var headers = headersOf(withHeader("X-Api-Key", "{{api_key}}"));

        assertTrue(headers.isEmpty(),
                "a credential template must never be sent verbatim, and must not take an auth header name");
    }

    @Test
    void singleBraceTemplateIsNotInjectedAsAHeaderValue() {
        // The importer's own comment names this shape: a date placeholder, not a literal.
        var headers = headersOf(withHeader("X-Period", "{YYYYMM}"));

        assertTrue(headers.isEmpty(), "a single-brace placeholder is a runtime template, not a value");
    }

    @Test
    void anUnbalancedBraceIsStillTreatedAsATemplate() {
        // Each brace is checked on its own: a value carrying only one of them is a
        // malformed template, not a literal, and half the guard must not be droppable.
        assertTrue(headersOf(withHeader("X-Open", "{api_key")).isEmpty(),
                "an opening brace alone is not a literal value");

        reset(apiService);
        assertTrue(headersOf(withHeader("X-Close", "api_key}")).isEmpty(),
                "a closing brace alone is not a literal value");
    }

    @Test
    void headerValueOfExactlyMaxLengthIsAccepted() {
        var headers = headersOf(withHeader("X-Token", "a".repeat(64)));

        assertEquals(1, headers.size(), "64 characters is the limit, not one past it");
    }

    @Test
    void overlongHeaderValueIsSkipped() {
        var headers = headersOf(withHeader("X-Note", "a".repeat(65)));

        assertTrue(headers.isEmpty(), "65 characters is prose, not a literal");
    }

    @Test
    void blankHeaderNameIsSkipped() {
        var headers = headersOf(withHeader("", "2023-06-01"));

        assertTrue(headers.isEmpty(), "a nameless header cannot be sent");
    }

    @Test
    void nonScalarHeaderValueIsSkipped() {
        ObjectNode json = buildValidApiJson();
        endpointHeaders(json).putObject("X-Obj").put("nested", 1);

        assertTrue(headersOf(json).isEmpty(), "an object is not a header value");
    }

    @Test
    void headersFieldThatIsNotAnObjectIsIgnored() {
        ObjectNode json = buildValidApiJson();
        ((ObjectNode) json.get("endpoints").get(0)).putArray("headers").add("Content-Type: text/plain");

        assertTrue(headersOf(json).isEmpty(), "a malformed headers field must not throw and must emit nothing");
    }

    @Test
    void twoDistinctHeadersAreBothEmitted() {
        ObjectNode json = buildValidApiJson();
        ObjectNode headers = endpointHeaders(json);
        headers.put("anthropic-version", "2023-06-01");
        headers.put("X-Client", "livecontext");

        assertEquals(2, headersOf(json).size());
    }

    @Test
    void endpointWithoutHeadersProducesNone() {
        assertTrue(headersOf(buildValidApiJson()).isEmpty());
    }

    // ── de-duplication ───────────────────────────────────────────────────

    @Test
    void duplicateHeaderNamesCollapseCaseInsensitivelyFirstLiteralWins() {
        ObjectNode json = buildValidApiJson();
        ObjectNode headers = endpointHeaders(json);
        headers.put("anthropic-version", "2023-06-01");
        headers.put("Anthropic-Version", "1999-01-01");

        var emitted = headersOf(json);

        assertEquals(1, emitted.size(), "one header name, one param");
        assertEquals("2023-06-01", emitted.get(0).defaultValue(), "first literal occurrence wins");
    }

    @Test
    void aProseValueDoesNotReserveTheNameAndBlockALaterLiteral() {
        // Regression: de-duplication used to run BEFORE the literal check, so a
        // documentation string claimed the name and the real value was dropped -
        // the exact silent-drop class this whole feature removes.
        ObjectNode json = buildValidApiJson();
        ObjectNode headers = endpointHeaders(json);
        headers.put("anthropic-version", "see the API documentation");
        headers.put("Anthropic-Version", "2023-06-01");

        var emitted = headersOf(json);

        assertEquals(1, emitted.size(), "the literal entry must still be emitted");
        assertEquals("2023-06-01", emitted.get(0).defaultValue(),
                "a rejected value must not reserve the header name");
    }

    // ── the credential header is reserved ────────────────────────────────

    @Test
    void aHeaderNamedLikeTheApiKeyCredentialHeaderIsRefused() {
        // apikey auth injects the secret into X-API-Key. A declared param with that
        // name is required and visible, and applyHeaderParameters applies a
        // caller-supplied value with no collision guard: it could overwrite the secret.
        ObjectNode json = withAuth(buildValidApiJson(), "apikey");
        endpointHeaders(json).put("x-api-key", "attacker-literal");

        assertTrue(headersOf(json).isEmpty(), "the credential header name is reserved for the credential");
    }

    @Test
    void aHeaderNamedLikeTheBearerCredentialHeaderIsRefused() {
        ObjectNode json = withAuth(buildValidApiJson(), "bearer");
        endpointHeaders(json).put("Authorization", "Bearer-attacker-literal");

        assertTrue(headersOf(json).isEmpty(), "bearer auth injects into Authorization, so that name is reserved");
    }

    @Test
    void bearerAuthDoesNotReserveTheApiKeyHeaderName() {
        // Reserving the wrong name would silently drop a legitimate header.
        ObjectNode json = withAuth(buildValidApiJson(), "bearer");
        endpointHeaders(json).put("X-API-Key", "not-the-credential-here");

        assertEquals(1, headersOf(json).size(),
                "bearer injects into Authorization, so X-API-Key is a normal header");
    }

    @Test
    void unauthenticatedApiReservesNoHeaderName() {
        ObjectNode json = buildValidApiJson(); // no auth block at all
        endpointHeaders(json).put("x-api-key", "public-api-token");

        assertEquals(1, headersOf(json).size(), "with no credential there is nothing to protect");
    }

    @Test
    void anUnrecognisedAuthTypeIsRefusedInsteadOfBecomingAnApiKey() {
        // THE defect. An unrecognised type fell through to the X-API-Key default, so an API
        // declaring bearer_token - the spelling the catalog reports for every built-in API -
        // registered happily and sent its token in a header the provider ignores. 401 from the
        // provider, nothing on our side mentioning auth.
        ObjectNode json = withAuth(buildValidApiJson(), "token");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.registerCustomApi(json, "tenant-1"));
        assertTrue(ex.getMessage().contains("token"), "the refusal must quote what was sent");
        assertTrue(ex.getMessage().contains("bearer") && ex.getMessage().contains("apikey"),
                "the refusal must list the accepted spellings");
    }

    @Test
    void everyAliasOfAnAcceptedAuthTypeIsAccepted() {
        // The aliases are what the catalog itself reports and what the seed files use, so an
        // agent copying a working API's spelling must not be refused.
        for (String spelling : List.of("bearer", "bearer_token", "apikey", "api_key",
                "basic", "basic_auth", "none")) {
            assertDoesNotThrow(() -> CustomApiRegistrationService.validateAuthType(spelling),
                    "spelling '" + spelling + "' must be accepted");
        }
    }

    @Test
    void aBlankAuthTypeIsNoAuthEverywhere() {
        // A blank type used to be incoherent rather than wrong in one place: validation
        // canonicalised it to "none" and let it through, while four other branches compared the
        // RAW string and so took the authenticated path. The API was flagged as needing a user
        // key, x-api-key was reserved, and register_api told the agent to go and request a
        // credential - while linkCredentials hit its own blank guard and created no template at
        // all. The agent was sent to ask for a key that could never exist.
        //
        // It now means what the platform's own normalizer says it means, "none", in every branch.
        ObjectNode json = buildValidApiJson();
        json.putArray("auth").addObject().put("type", "");
        endpointHeaders(json).put("x-api-key", "literal");

        assertEquals(1, headersOf(json).size(),
                "no auth means no credential header to protect, so the declared header is kept");
        assertNull(CustomApiRegistrationService.credentialNameFor(json, "Test API"),
                "no auth means no credential is required, so none is named");
    }

    @Test
    void theLegacyObjectFormOfAuthIsStillRead() {
        // resolveAuthType tolerates {"auth": {...}} as well as the array form; the reservation
        // must not quietly fall back to the default when it meets the legacy shape.
        ObjectNode json = buildValidApiJson();
        json.putObject("auth").put("type", "bearer");
        endpointHeaders(json).put("Authorization", "literal");

        assertTrue(headersOf(json).isEmpty(), "the legacy object form declares bearer just as well");
    }

    @Test
    void theTopLevelAuthTypeFallbackIsStillRead() {
        ObjectNode json = buildValidApiJson();
        json.put("authType", "bearer");
        endpointHeaders(json).put("Authorization", "literal");

        assertTrue(headersOf(json).isEmpty(), "authType is the documented fallback spelling");
    }

    @Test
    void theReservedHeaderComesFromTheSameDerivationAsTheInjection() {
        // This used to reflect into a SECOND copy of the switch and assert the two agreed,
        // including the note that bearer_token landed on the default arm in both. They agreed on
        // the wrong answer: a bearer_token API reserved X-API-Key while its secret went into
        // Authorization, leaving the real auth header unguarded. There is one derivation now, so
        // what is worth pinning is that this class still delegates to it rather than growing a
        // copy again, and that the answer is right for the spelling that was broken.
        assertEquals("authorization",
                CustomApiRegistrationService.credentialHeaderName("bearer_token", null),
                "bearer_token is the catalog's own spelling and must reserve Authorization");
        assertEquals("authorization",
                CustomApiRegistrationService.credentialHeaderName("bearer", null));
        assertEquals("x-api-key",
                CustomApiRegistrationService.credentialHeaderName("apikey", null));
        assertEquals("authorization",
                CustomApiRegistrationService.credentialHeaderName("basic", null),
                "basic auth occupies Authorization too, via the Basic scheme");

        for (String authType : List.of("bearer", "bearer_token", "apikey", "api_key", "basic", "whatever")) {
            assertEquals(CatalogSeedCredentialService.credentialHeaderName(authType, null),
                    CustomApiRegistrationService.credentialHeaderName(authType, null),
                    "auth type '" + authType + "': this class must delegate, never re-derive");
        }
    }

    @Test
    void aBearerApiReservesAuthorizationSoADeclaredHeaderCannotTakeIt() {
        // The reservation exists so a static header cannot claim the name the credential is
        // injected into. With the old spelling bug, an API declaring bearer_token reserved the
        // wrong name, so a declared "Authorization" header sailed through and would have been
        // sent alongside - or instead of - the real credential.
        ObjectNode json = buildValidApiJson();
        json.put("authType", "bearer_token");
        endpointHeaders(json).put("Authorization", "literal");

        assertTrue(headersOf(json).isEmpty(),
                "Authorization is where a bearer credential goes, so a declared header of that "
                        + "name must be refused, whichever spelling of bearer was used");
    }

    @Test
    void aJsonNullValueIsNotSentAsTheStringNull() {
        // NullNode.isValueNode() is true and asText() is "null", so without an explicit
        // guard this ships the header `X-Foo: null`.
        ObjectNode json = buildValidApiJson();
        endpointHeaders(json).putNull("X-Foo");

        assertTrue(headersOf(json).isEmpty(), "a JSON null is not a header value");
    }

    @Test
    void aNumericHeaderValueIsAccepted() {
        // The shape the importer's own comment names (DatoCMS X-Api-Version: 3).
        ObjectNode json = buildValidApiJson();
        endpointHeaders(json).put("X-Api-Version", 3);

        var emitted = headersOf(json);

        assertEquals(1, emitted.size());
        assertEquals("3", emitted.get(0).defaultValue(), "a JSON number is a perfectly good literal");
    }

    // ── api-level requiredHeaders ────────────────────────────────────────

    @Test
    void apiLevelRequiredHeadersAreEmittedOnEveryEndpoint() {
        ObjectNode json = buildValidApiJson();
        json.putObject("requiredHeaders").put("anthropic-version", "2023-06-01");

        var emitted = headersOf(json);

        assertEquals(1, emitted.size(), "a version pin declared once must reach the endpoint");
        assertEquals("2023-06-01", emitted.get(0).defaultValue());
    }

    @Test
    void anEndpointHeaderWinsOverTheApiLevelOneOfTheSameName() {
        ObjectNode json = buildValidApiJson();
        json.putObject("requiredHeaders").put("X-Version", "api-level");
        endpointHeaders(json).put("X-Version", "endpoint-level");

        var emitted = headersOf(json);

        assertEquals(1, emitted.size());
        assertEquals("endpoint-level", emitted.get(0).defaultValue(),
                "the endpoint is more specific than the API default");
    }

    @Test
    void proseOnTheEndpointDoesNotBlockTheApiLevelLiteralOfTheSameName() {
        // The endpoint is read first, so a refused endpoint value must not reserve the
        // name against the api-level one behind it.
        ObjectNode json = buildValidApiJson();
        json.putObject("requiredHeaders").put("X-Version", "2023-06-01");
        endpointHeaders(json).put("X-Version", "see the documentation");

        var emitted = headersOf(json);

        assertEquals(1, emitted.size(), "the api-level literal must survive");
        assertEquals("2023-06-01", emitted.get(0).defaultValue());
    }

    @Test
    void apiLevelRequiredHeadersCannotTakeTheCredentialHeaderName() {
        ObjectNode json = withAuth(buildValidApiJson(), "apikey");
        json.putObject("requiredHeaders").put("X-API-Key", "attacker-literal");

        assertTrue(headersOf(json).isEmpty(), "the reservation covers both header sources");
    }

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

    private ApiResponse.ParameterResponse param(String name, String type, String defaultValue) {
        return new ApiResponse.ParameterResponse(UUID.randomUUID(), name, "string", "d",
                true, defaultValue, null, type, null);
    }

    private ApiResponse toolWith(UUID apiId, List<ApiResponse.ParameterResponse> params) {
        ApiResponse.ToolResponse tool = new ApiResponse.ToolResponse(
                UUID.randomUUID(), "get_items", "List items", "/items", "GET", "HTTP",
                null, null, null, null, null, null, true, null, null,
                params, null, null, null);
        ApiResponse base = mockApiResponse(apiId);
        return new ApiResponse(
                base.id(), base.apiName(), base.apiSlug(), base.description(), base.baseUrl(),
                base.categoryId(), base.categoryName(), base.subcategoryId(), base.subcategoryName(),
                base.isActive(), base.isLocal(), base.createdAt(), base.updatedAt(), base.createdBy(),
                List.of(tool), base.healthcheckEndpoint(), base.visibility(), base.isPublic(),
                base.authType(), base.authHeaderName(), base.authHeaderValue(), base.pricingModel(),
                base.status(), base.platformCredentialMissing());
    }

    private ApiResponse apiResponseWithHeaderParam(UUID apiId) {
        return toolWith(apiId, List.of(
                param("q", "query", null),
                param("anthropic-version", "header", "2023-06-01")));
    }

    private ApiResponse apiResponseWithQueryParamOnly(UUID apiId) {
        return toolWith(apiId, List.of(param("q", "query", null)));
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
