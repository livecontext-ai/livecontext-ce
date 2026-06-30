package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.service.CustomApiRegistrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatalogRegisterModuleTest {

    @Mock
    private CustomApiRegistrationService registrationService;

    private CatalogRegisterModule module;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        module = new CatalogRegisterModule(registrationService, objectMapper);
    }

    @Test
    void canHandleRecognizesAllActions() {
        assertTrue(module.canHandle("register_api"));
        assertTrue(module.canHandle("update_api"));
        assertTrue(module.canHandle("delete_api"));
        assertTrue(module.canHandle("list_custom_apis"));
        assertFalse(module.canHandle("search"));
        assertFalse(module.canHandle("execute"));
    }

    @Test
    void executeReturnsEmptyForUnhandledAction() {
        var result = module.execute("search", Map.of(), "tenant-1", null);
        assertTrue(result.isEmpty());
    }

    @Test
    void registerApiRequiresTenantId() {
        var result = module.execute("register_api", Map.of(), null, null);
        assertTrue(result.isPresent());
        assertFalse(result.get().success());
    }

    @Test
    void registerApiRequiresApiDefinition() {
        var result = module.execute("register_api", Map.of(), "tenant-1", null);
        assertTrue(result.isPresent());
        assertFalse(result.get().success());
    }

    @Test
    void registerApiSucceeds() {
        Map<String, Object> apiDef = Map.of(
                "apiName", "Test",
                "baseUrl", "https://api.test.com",
                "endpoints", List.of(Map.of("name", "get", "endpoint", "/", "method", "GET"))
        );
        ApiResponse mockResponse = mockApiResponse();
        when(registrationService.registerCustomApi(any(), eq("tenant-1"))).thenReturn(mockResponse);

        var result = module.execute("register_api", Map.of("api_definition", apiDef), "tenant-1", null);

        assertTrue(result.isPresent());
        assertTrue(result.get().success());
    }

    @Test
    void registerApiReturnsFailureOnIllegalArgument() {
        Map<String, Object> apiDef = Map.of("apiName", "Test");
        when(registrationService.registerCustomApi(any(), eq("tenant-1")))
                .thenThrow(new IllegalArgumentException("Missing baseUrl"));

        var result = module.execute("register_api", Map.of("api_definition", apiDef), "tenant-1", null);

        assertTrue(result.isPresent());
        assertFalse(result.get().success());
    }

    @Test
    void updateApiRequiresApiId() {
        var result = module.execute("update_api",
                Map.of("api_definition", Map.of()), "tenant-1", null);
        assertTrue(result.isPresent());
        assertFalse(result.get().success());
    }

    @Test
    void updateApiRequiresApiDefinition() {
        var result = module.execute("update_api",
                Map.of("api_id", UUID.randomUUID().toString()), "tenant-1", null);
        assertTrue(result.isPresent());
        assertFalse(result.get().success());
    }

    @Test
    void updateApiSucceeds() {
        String apiId = UUID.randomUUID().toString();
        Map<String, Object> apiDef = Map.of("apiName", "Updated");
        ApiResponse mockResponse = mockApiResponse();
        when(registrationService.updateCustomApi(eq(apiId), any(), eq("tenant-1"))).thenReturn(mockResponse);

        var result = module.execute("update_api",
                Map.of("api_id", apiId, "api_definition", apiDef), "tenant-1", null);

        assertTrue(result.isPresent());
        assertTrue(result.get().success());
    }

    @Test
    void deleteApiRequiresApiId() {
        var result = module.execute("delete_api", Map.of(), "tenant-1", null);
        assertTrue(result.isPresent());
        assertFalse(result.get().success());
    }

    @Test
    void deleteApiSucceeds() {
        String apiId = UUID.randomUUID().toString();
        doNothing().when(registrationService).deleteCustomApi(apiId, "tenant-1");

        var result = module.execute("delete_api",
                Map.of("api_id", apiId), "tenant-1", null);

        assertTrue(result.isPresent());
        assertTrue(result.get().success());
        verify(registrationService).deleteCustomApi(apiId, "tenant-1");
    }

    @Test
    void deleteApiReturnsFailureOnOwnershipError() {
        String apiId = UUID.randomUUID().toString();
        doThrow(new IllegalArgumentException("You can only delete your own custom APIs"))
                .when(registrationService).deleteCustomApi(apiId, "tenant-1");

        var result = module.execute("delete_api",
                Map.of("api_id", apiId), "tenant-1", null);

        assertTrue(result.isPresent());
        assertFalse(result.get().success());
    }

    @Test
    void listCustomApisSucceeds() {
        List<Map<String, Object>> apis = List.of(
                Map.of("id", "1", "name", "API 1"),
                Map.of("id", "2", "name", "API 2")
        );
        when(registrationService.listCustomApis("tenant-1")).thenReturn(apis);

        var result = module.execute("list_custom_apis", Map.of(), "tenant-1", null);

        assertTrue(result.isPresent());
        assertTrue(result.get().success());
    }

    @Test
    void blankTenantIdIsRejected() {
        var result = module.execute("register_api", Map.of(), "  ", null);
        assertTrue(result.isPresent());
        assertFalse(result.get().success());
    }

    // --- R-06: register/update must include tools[{tool_id, name}] ---

    @Test
    void registerApiResponseIncludesToolsSummary() {
        UUID apiId = UUID.randomUUID();
        UUID tool1 = UUID.randomUUID();
        UUID tool2 = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponseWithTools(apiId, List.of(
                toolResponse(tool1, "list_items"),
                toolResponse(tool2, "create_item")
        ));
        when(registrationService.registerCustomApi(any(), eq("tenant-1"))).thenReturn(mockResponse);

        var result = module.execute("register_api",
                Map.of("api_definition", Map.of("apiName", "Test")), "tenant-1", null).orElseThrow();

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        Object tools = data.get("tools");
        assertNotNull(tools, "tools[] must be present in response");
        assertTrue(tools instanceof List<?>);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> toolList = (List<Map<String, String>>) tools;
        assertEquals(2, toolList.size());
        assertEquals(tool1.toString(), toolList.get(0).get("tool_id"));
        assertEquals("list_items", toolList.get(0).get("name"));
    }

    @Test
    void updateApiResponseIncludesToolsSummary() {
        String apiId = UUID.randomUUID().toString();
        UUID newToolId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponseWithTools(
                UUID.fromString(apiId),
                List.of(toolResponse(newToolId, "renamed_tool"))
        );
        when(registrationService.updateCustomApi(eq(apiId), any(), eq("tenant-1"))).thenReturn(mockResponse);

        var result = module.execute("update_api",
                Map.of("api_id", apiId, "api_definition", Map.of("apiName", "Updated")), "tenant-1", null)
                .orElseThrow();

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        Object tools = data.get("tools");
        assertTrue(tools instanceof List<?> && ((List<?>) tools).size() == 1);
    }

    @Test
    void registerApiReturnsEmptyToolsListWhenNoToolsOnResponse() {
        ApiResponse mockResponse = mockApiResponse(); // tools=null
        when(registrationService.registerCustomApi(any(), eq("tenant-1"))).thenReturn(mockResponse);

        var result = module.execute("register_api",
                Map.of("api_definition", Map.of("apiName", "Test")), "tenant-1", null).orElseThrow();

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        Object tools = data.get("tools");
        assertNotNull(tools);
        assertTrue(tools instanceof List<?> && ((List<?>) tools).isEmpty());
    }

    // --- R-12: error code is INVALID_PARAMETER_VALUE for duplicate/validation errors ---

    @Test
    void registerApiUsesInvalidParameterValueOnIllegalArgument() {
        when(registrationService.registerCustomApi(any(), eq("tenant-1")))
                .thenThrow(new IllegalArgumentException(
                        "An API named 'Test' already exists for this user. Use catalog(action='update_api'...)"));

        var result = module.execute("register_api",
                Map.of("api_definition", Map.of("apiName", "Test")), "tenant-1", null).orElseThrow();

        assertFalse(result.success());
        assertEquals(com.apimarketplace.agent.tools.ToolErrorCode.INVALID_PARAMETER_VALUE, result.errorCode());
        assertTrue(result.error().contains("update_api"),
                "Error message should surface the update_api remediation hint");
    }

    @Test
    void toolsSummaryOmitsInactiveTools() {
        UUID apiId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID inactiveId = UUID.randomUUID();
        ApiResponse mockResponse = mockApiResponseWithTools(apiId, List.of(
                toolResponse(activeId, "active_tool"),
                toolResponseInactive(inactiveId, "ghost_tool")
        ));
        when(registrationService.registerCustomApi(any(), eq("tenant-1"))).thenReturn(mockResponse);

        var result = module.execute("register_api",
                Map.of("api_definition", Map.of("apiName", "Test")), "tenant-1", null).orElseThrow();

        assertTrue(result.success());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> tools = (List<Map<String, String>>) data.get("tools");
        assertEquals(1, tools.size(), "inactive tool must not leak into summary");
        assertEquals(activeId.toString(), tools.get(0).get("tool_id"));
        assertEquals("active_tool", tools.get(0).get("name"));
    }

    // --- helpers for the above ---

    private ApiResponse mockApiResponseWithTools(UUID apiId, List<ApiResponse.ToolResponse> tools) {
        return new ApiResponse(
                apiId, "Test API", "test-api", "A test API",
                "https://api.test.com", null, "Custom APIs", null,
                "Test API", true, false, null, null,
                "tenant-1", tools, null, "private", false,
                "bearer", "Authorization", null, "FREE", "active", null
        );
    }

    private ApiResponse.ToolResponse toolResponse(UUID id, String name) {
        return new ApiResponse.ToolResponse(
                id, name, "desc", "/endpoint", "GET", "HTTP", null,
                null, null, null, null, null,
                true, 0L, 0L, List.of(), List.of(), "active", null);
    }

    private ApiResponse.ToolResponse toolResponseInactive(UUID id, String name) {
        return new ApiResponse.ToolResponse(
                id, name, "desc", "/endpoint", "GET", "HTTP", null,
                null, null, null, null, null,
                false, 0L, 0L, List.of(), List.of(), "inactive", null);
    }

    private ApiResponse mockApiResponse() {
        return new ApiResponse(
                UUID.randomUUID(), "Test API", "test-api", "A test API",
                "https://api.test.com", null, "Custom APIs", null,
                "Test API", true, false, null, null,
                "tenant-1", null, null, "private", false,
                "bearer", "Authorization", null, "FREE", "active", null
        );
    }
}
