package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.catalog.domain.dto.ApiCreateRequest;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.service.ApiService;
import com.apimarketplace.catalog.service.AuthorizationService;
import com.apimarketplace.catalog.service.exception.AccessDeniedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiController Integration Tests")
class ApiControllerIntegrationTest {

    @Mock
    private ApiService apiService;

    @Mock
    private AuthorizationService authorizationService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final String USER_ID = "user-123";
    private static final UUID API_ID = UUID.randomUUID();
    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID SUBCATEGORY_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ApiController controller = new ApiController(apiService, authorizationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    private ApiResponse createSampleApiResponse() {
        return new ApiResponse(
                API_ID, "Test API", "test-api", "A test API",
                "https://api.example.com", CATEGORY_ID, "Technology",
                SUBCATEGORY_ID, "Cloud", true, false,
                System.currentTimeMillis(), System.currentTimeMillis(),
                USER_ID, Collections.emptyList(), "/health",
                "public", true, "bearer", "Authorization", null,
                "freemium", "active", null
        );
    }

    @Nested
    @DisplayName("GET /api/apis")
    class GetAllApis {

        @Test
        @DisplayName("should return empty list when no APIs exist")
        void shouldReturnEmptyList() throws Exception {
            when(apiService.getAllApis()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/apis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return list of APIs")
        void shouldReturnListOfApis() throws Exception {
            ApiResponse api = createSampleApiResponse();
            when(apiService.getAllApis()).thenReturn(List.of(api));

            mockMvc.perform(get("/api/apis"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].apiName").value("Test API"))
                    .andExpect(jsonPath("$[0].apiSlug").value("test-api"));
        }

        @Test
        @DisplayName("should filter by createdBy parameter")
        void shouldFilterByCreatedBy() throws Exception {
            ApiResponse api = createSampleApiResponse();
            when(apiService.getApisByCreator(USER_ID)).thenReturn(List.of(api));

            mockMvc.perform(get("/api/apis").param("createdBy", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));

            verify(apiService).getApisByCreator(USER_ID);
            verify(apiService, never()).getAllApis();
        }

        @Test
        @DisplayName("should ignore blank createdBy parameter")
        void shouldIgnoreBlankCreatedBy() throws Exception {
            when(apiService.getAllApis()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/apis").param("createdBy", "   "))
                    .andExpect(status().isOk());

            verify(apiService).getAllApis();
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(apiService.getAllApis()).thenThrow(new RuntimeException("Database error"));

            mockMvc.perform(get("/api/apis"))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/apis/me")
    class GetMyApis {

        @Test
        @DisplayName("should return user APIs when X-User-ID header is present")
        void shouldReturnUserApis() throws Exception {
            ApiResponse api = createSampleApiResponse();
            when(apiService.getApisByCreator(USER_ID)).thenReturn(List.of(api));

            mockMvc.perform(get("/api/apis/me")
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].apiName").value("Test API"));
        }

        @Test
        @DisplayName("should return 401 when X-User-ID header is missing")
        void shouldReturn401WhenHeaderMissing() throws Exception {
            mockMvc.perform(get("/api/apis/me"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return empty list when user has no APIs")
        void shouldReturnEmptyListForNewUser() throws Exception {
            when(apiService.getApisByCreator("new-user")).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/apis/me")
                            .header("X-User-ID", "new-user"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(apiService.getApisByCreator(USER_ID)).thenThrow(new RuntimeException("error"));

            mockMvc.perform(get("/api/apis/me")
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/apis/check-name")
    class CheckApiName {

        @Test
        @DisplayName("should return isUnique true when name and slug are unique")
        void shouldReturnUniqueTrue() throws Exception {
            when(apiService.isApiNameUniqueForUser("My API", USER_ID)).thenReturn(true);
            when(apiService.isApiSlugUnique("my-api")).thenReturn(true);

            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "My API")
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isUnique").value(true))
                    .andExpect(jsonPath("$.nameIsUnique").value(true))
                    .andExpect(jsonPath("$.slugIsUnique").value(true))
                    .andExpect(jsonPath("$.generatedSlug").value("my-api"));
        }

        @Test
        @DisplayName("should return isUnique false when name already exists")
        void shouldReturnUniqueFalseWhenNameExists() throws Exception {
            when(apiService.isApiNameUniqueForUser("My API", USER_ID)).thenReturn(false);
            when(apiService.isApiSlugUnique("my-api")).thenReturn(true);

            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "My API")
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isUnique").value(false))
                    .andExpect(jsonPath("$.nameIsUnique").value(false))
                    .andExpect(jsonPath("$.conflicts").isArray());
        }

        @Test
        @DisplayName("should return isUnique false when slug already exists")
        void shouldReturnUniqueFalseWhenSlugExists() throws Exception {
            when(apiService.isApiNameUniqueForUser("My API", USER_ID)).thenReturn(true);
            when(apiService.isApiSlugUnique("my-api")).thenReturn(false);

            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "My API")
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isUnique").value(false))
                    .andExpect(jsonPath("$.slugIsUnique").value(false));
        }

        @Test
        @DisplayName("should use global uniqueness check when no userId provided")
        void shouldUseGlobalCheckWithoutUserId() throws Exception {
            when(apiService.isApiNameUniqueGlobally("My API")).thenReturn(true);
            when(apiService.isApiSlugUnique("my-api")).thenReturn(true);

            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "My API"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isUnique").value(true));

            verify(apiService).isApiNameUniqueGlobally("My API");
            verify(apiService, never()).isApiNameUniqueForUser(any(), any());
        }

        @Test
        @DisplayName("should return generated slug in response")
        void shouldReturnGeneratedSlug() throws Exception {
            when(apiService.isApiNameUniqueGlobally("Hello World API")).thenReturn(true);
            when(apiService.isApiSlugUnique("hello-world-api")).thenReturn(true);

            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "Hello World API"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.generatedSlug").value("hello-world-api"));
        }

        @Test
        @DisplayName("should handle service exception gracefully")
        void shouldHandleExceptionGracefully() throws Exception {
            when(apiService.isApiNameUniqueGlobally(any())).thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/apis/check-name")
                            .param("name", "Test"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.isUnique").value(true));
        }
    }

    @Nested
    @DisplayName("GET /api/apis/health")
    class HealthCheck {

        @Test
        @DisplayName("should return UP status")
        void shouldReturnUpStatus() throws Exception {
            mockMvc.perform(get("/api/apis/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.service").value("api-management"));
        }
    }

    @Nested
    @DisplayName("DELETE /api/apis/{id}")
    class DeleteApi {

        @Test
        @DisplayName("should return 204 on successful deletion")
        void shouldReturn204OnSuccess() throws Exception {
            doNothing().when(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            doNothing().when(apiService).deleteApi(API_ID);

            mockMvc.perform(delete("/api/apis/{id}", API_ID)
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isNoContent());

            verify(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            verify(apiService).deleteApi(API_ID);
        }

        @Test
        @DisplayName("should return 404 when API not found")
        void shouldReturn404WhenNotFound() throws Exception {
            doNothing().when(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            doThrow(new RuntimeException("API not found")).when(apiService).deleteApi(API_ID);

            mockMvc.perform(delete("/api/apis/{id}", API_ID)
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 403 when user does not own API")
        void shouldReturn403WhenNotOwner() throws Exception {
            doThrow(AccessDeniedException.forApi(USER_ID, API_ID.toString()))
                    .when(authorizationService).verifyApiOwnership(USER_ID, API_ID);

            mockMvc.perform(delete("/api/apis/{id}", API_ID)
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("should return 500 on unexpected error")
        void shouldReturn500OnUnexpectedError() throws Exception {
            doNothing().when(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            doThrow(new RuntimeException("unexpected error")).when(apiService).deleteApi(API_ID);

            mockMvc.perform(delete("/api/apis/{id}", API_ID)
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("PUT /api/apis/{id}")
    class UpdateApi {

        @Test
        @DisplayName("should return updated API on success")
        void shouldReturnUpdatedApi() throws Exception {
            ApiResponse response = createSampleApiResponse();
            doNothing().when(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            when(apiService.updateApi(eq(API_ID), any(ApiCreateRequest.class), eq(USER_ID)))
                    .thenReturn(response);

            String requestBody = objectMapper.writeValueAsString(
                    new ApiCreateRequest("Test API", "A test API", "https://api.example.com",
                            CATEGORY_ID, SUBCATEGORY_ID, Collections.emptyList()));

            mockMvc.perform(put("/api/apis/{id}", API_ID)
                            .header("X-User-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.apiName").value("Test API"));
        }

        @Test
        @DisplayName("should return 404 when API not found")
        void shouldReturn404WhenNotFound() throws Exception {
            doNothing().when(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            when(apiService.updateApi(eq(API_ID), any(ApiCreateRequest.class), eq(USER_ID)))
                    .thenThrow(new RuntimeException("API not found"));

            String requestBody = objectMapper.writeValueAsString(
                    new ApiCreateRequest("Test API", "A test API", "https://api.example.com",
                            CATEGORY_ID, SUBCATEGORY_ID, Collections.emptyList()));

            mockMvc.perform(put("/api/apis/{id}", API_ID)
                            .header("X-User-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 403 when user does not own API")
        void shouldReturn403WhenNotOwner() throws Exception {
            doThrow(AccessDeniedException.forApi(USER_ID, API_ID.toString()))
                    .when(authorizationService).verifyApiOwnership(USER_ID, API_ID);

            String requestBody = objectMapper.writeValueAsString(
                    new ApiCreateRequest("Test API", "A test API", "https://api.example.com",
                            CATEGORY_ID, SUBCATEGORY_ID, Collections.emptyList()));

            mockMvc.perform(put("/api/apis/{id}", API_ID)
                            .header("X-User-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("should return 400 on bad request")
        void shouldReturn400OnBadRequest() throws Exception {
            doNothing().when(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            when(apiService.updateApi(eq(API_ID), any(ApiCreateRequest.class), eq(USER_ID)))
                    .thenThrow(new RuntimeException("Invalid data"));

            String requestBody = objectMapper.writeValueAsString(
                    new ApiCreateRequest("Test API", "A test API", "https://api.example.com",
                            CATEGORY_ID, SUBCATEGORY_ID, Collections.emptyList()));

            mockMvc.perform(put("/api/apis/{id}", API_ID)
                            .header("X-User-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("PUT /api/apis/{apiId}/tools/{toolId}")
    class UpdateTool {

        @Test
        @DisplayName("should return updated API on tool update success")
        void shouldReturnUpdatedApiOnToolUpdate() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiResponse response = createSampleApiResponse();
            doNothing().when(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            when(apiService.updateTool(eq(API_ID), eq(toolId), any(), eq(USER_ID)))
                    .thenReturn(response);

            mockMvc.perform(put("/api/apis/{apiId}/tools/{toolId}", API_ID, toolId)
                            .header("X-User-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.apiName").value("Test API"));
        }

        @Test
        @DisplayName("should return 404 when tool not found")
        void shouldReturn404WhenToolNotFound() throws Exception {
            UUID toolId = UUID.randomUUID();
            doNothing().when(authorizationService).verifyApiOwnership(USER_ID, API_ID);
            when(apiService.updateTool(eq(API_ID), eq(toolId), any(), eq(USER_ID)))
                    .thenThrow(new RuntimeException("Tool not found"));

            mockMvc.perform(put("/api/apis/{apiId}/tools/{toolId}", API_ID, toolId)
                            .header("X-User-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 403 when user does not own the API")
        void shouldReturn403WhenNotOwner() throws Exception {
            UUID toolId = UUID.randomUUID();
            doThrow(AccessDeniedException.forApi(USER_ID, API_ID.toString()))
                    .when(authorizationService).verifyApiOwnership(USER_ID, API_ID);

            mockMvc.perform(put("/api/apis/{apiId}/tools/{toolId}", API_ID, toolId)
                            .header("X-User-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("GET /api/apis/monetization/state")
    class GetMonetizationState {

        @Test
        @DisplayName("should return monetization state for authenticated user")
        void shouldReturnMonetizationState() throws Exception {
            when(apiService.getMonetizationStateByUser(USER_ID))
                    .thenReturn(java.util.Map.of("status", "active"));

            mockMvc.perform(get("/api/apis/monetization/state")
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("active"));
        }

        @Test
        @DisplayName("should return 401 when X-User-ID header is missing")
        void shouldReturn401WhenHeaderMissing() throws Exception {
            mockMvc.perform(get("/api/apis/monetization/state"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(apiService.getMonetizationStateByUser(USER_ID))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/apis/monetization/state")
                            .header("X-User-ID", USER_ID))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/apis/system/all")
    class GetAllApisSystemView {

        @Test
        @DisplayName("should return paginated APIs with default parameters and no tools")
        void shouldReturnPaginatedApisNoTools() throws Exception {
            // API without tools to avoid needing to mock getToolResponses/getToolCredentials
            ApiResponse api = new ApiResponse(
                    API_ID, "Test API", "test-api", "A test API",
                    "https://api.example.com", CATEGORY_ID, "Technology",
                    SUBCATEGORY_ID, "Cloud", true, false,
                    System.currentTimeMillis(), System.currentTimeMillis(),
                    USER_ID, null, "/health",
                    "public", true, "bearer", "Authorization", null,
                    "freemium", "active", null
            );
            when(apiService.getAllApis((String) null)).thenReturn(List.of(api));

            mockMvc.perform(get("/api/apis/system/all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isArray())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.first").value(true));
        }

        @Test
        @DisplayName("should support pagination parameters on first page")
        void shouldSupportPagination() throws Exception {
            // Use page 0 with a list to verify pagination metadata is present
            ApiResponse api = new ApiResponse(
                    API_ID, "Test API", "test-api", "A test API",
                    "https://api.example.com", CATEGORY_ID, "Technology",
                    SUBCATEGORY_ID, "Cloud", true, false,
                    System.currentTimeMillis(), System.currentTimeMillis(),
                    USER_ID, null, "/health",
                    "public", true, "bearer", "Authorization", null,
                    "freemium", "active", null
            );
            when(apiService.getAllApis((String) null)).thenReturn(List.of(api));

            mockMvc.perform(get("/api/apis/system/all")
                            .param("page", "0")
                            .param("size", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(5))
                    .andExpect(jsonPath("$.numberOfElements").value(1));
        }

        @Test
        @DisplayName("should support name filter")
        void shouldSupportNameFilter() throws Exception {
            when(apiService.getAllApis("test")).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/apis/system/all")
                            .param("name", "test"))
                    .andExpect(status().isOk());

            verify(apiService).getAllApis("test");
        }
    }
}
