package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.catalog.domain.ResponseFormat;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.mapping.service.MappingRegistry;
import com.apimarketplace.catalog.mapping.service.MappingResolverService;
import com.apimarketplace.catalog.service.ToolResponseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolResponseController Integration Tests")
class ToolResponseControllerIntegrationTest {

    @Mock
    private ToolResponseService toolResponseService;

    @Mock
    private MappingResolverService mappingResolverService;

    @Mock
    private MappingRegistry mappingRegistry;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private static final UUID TOOL_ID = UUID.randomUUID();
    private static final UUID RESPONSE_ID = UUID.randomUUID();
    private static final String USER_ID = "user-123";
    private static final String ADMIN_TOKEN = "test-admin-token";

    @BeforeEach
    void setUp() {
        ToolResponseController controller = new ToolResponseController(
                toolResponseService, mappingResolverService, mappingRegistry);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        // Spring @Value is not processed by standaloneSetup; inject the admin token directly
        // so mutation endpoints (POST/PUT/DELETE) pass the admin-gate when callers send
        // the X-Internal-Admin-Token header with ADMIN_TOKEN.
        ReflectionTestUtils.setField(controller, "catalogAdminToken", ADMIN_TOKEN);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    private ToolResponseDto createSampleResponse() {
        ToolResponseDto dto = new ToolResponseDto();
        dto.setId(RESPONSE_ID);
        dto.setToolId(TOOL_ID);
        dto.setName("Success Response");
        dto.setDescription("Successful response example");
        dto.setSchema("{\"type\":\"object\"}");
        dto.setExample("{\"status\":\"ok\"}");
        dto.setResponseFormat(ResponseFormat.JSON);
        dto.setStatusCode(200);
        dto.setIsDefault(true);
        dto.setIsActive(true);
        dto.setCreatedAt(LocalDateTime.now());
        dto.setUpdatedAt(LocalDateTime.now());
        dto.setCreatedBy(USER_ID);
        return dto;
    }

    @Nested
    @DisplayName("GET /api/tool-responses/tool/{toolId}")
    class GetResponsesByTool {

        @Test
        @DisplayName("should return list of responses for a tool")
        void shouldReturnListOfResponses() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.getResponsesByToolId(TOOL_ID)).thenReturn(List.of(dto));

            mockMvc.perform(get("/api/tool-responses/tool/{toolId}", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].name").value("Success Response"))
                    .andExpect(jsonPath("$[0].status_code").value(200))
                    .andExpect(jsonPath("$[0].is_default").value(true));
        }

        @Test
        @DisplayName("should return empty list when no responses exist")
        void shouldReturnEmptyList() throws Exception {
            when(toolResponseService.getResponsesByToolId(TOOL_ID)).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/tool-responses/tool/{toolId}", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(toolResponseService.getResponsesByToolId(TOOL_ID))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/tool-responses/tool/{toolId}", TOOL_ID))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-responses/{responseId}")
    class GetResponseById {

        @Test
        @DisplayName("should return response when found")
        void shouldReturnResponse() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.getResponseById(RESPONSE_ID)).thenReturn(Optional.of(dto));

            mockMvc.perform(get("/api/tool-responses/{responseId}", RESPONSE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Success Response"))
                    .andExpect(jsonPath("$.description").value("Successful response example"));
        }

        @Test
        @DisplayName("should return 404 when response not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(toolResponseService.getResponseById(RESPONSE_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/tool-responses/{responseId}", RESPONSE_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(toolResponseService.getResponseById(RESPONSE_ID))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(get("/api/tool-responses/{responseId}", RESPONSE_ID))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-responses/tool/{toolId}/default")
    class GetDefaultResponseByTool {

        @Test
        @DisplayName("should return default response when found")
        void shouldReturnDefaultResponse() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.getDefaultResponseByToolId(TOOL_ID)).thenReturn(Optional.of(dto));

            mockMvc.perform(get("/api/tool-responses/tool/{toolId}/default", TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.is_default").value(true));
        }

        @Test
        @DisplayName("should return 404 when no default response exists")
        void shouldReturn404WhenNoDefault() throws Exception {
            when(toolResponseService.getDefaultResponseByToolId(TOOL_ID)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/tool-responses/tool/{toolId}/default", TOOL_ID))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-responses/tool/{apiSlug}/{toolSlug}/default")
    class GetDefaultResponseByToolSlug {

        @Test
        @DisplayName("should return default response by slug")
        void shouldReturnDefaultBySlug() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.getDefaultResponseByToolSlug("my-api/get-users"))
                    .thenReturn(Optional.of(dto));

            mockMvc.perform(get("/api/tool-responses/tool/{apiSlug}/{toolSlug}/default",
                            "my-api", "get-users"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Success Response"));
        }

        @Test
        @DisplayName("should return 404 when slug not found")
        void shouldReturn404WhenSlugNotFound() throws Exception {
            when(toolResponseService.getDefaultResponseByToolSlug("unknown/tool"))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get("/api/tool-responses/tool/{apiSlug}/{toolSlug}/default",
                            "unknown", "tool"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/tool-responses")
    class CreateResponse {

        @Test
        @DisplayName("should create response and return 201")
        void shouldCreateResponse() throws Exception {
            ToolResponseDto inputDto = createSampleResponse();
            inputDto.setId(null);
            ToolResponseDto createdDto = createSampleResponse();
            when(toolResponseService.createResponse(any(ToolResponseDto.class), eq(USER_ID)))
                    .thenReturn(createdDto);

            String requestBody = objectMapper.writeValueAsString(inputDto);

            mockMvc.perform(post("/api/tool-responses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("Success Response"));
        }

        @Test
        @DisplayName("should create response with null userId when header is empty")
        void shouldCreateResponseWithNullUserId() throws Exception {
            ToolResponseDto inputDto = createSampleResponse();
            inputDto.setId(null);
            ToolResponseDto createdDto = createSampleResponse();
            when(toolResponseService.createResponse(any(ToolResponseDto.class), eq(null)))
                    .thenReturn(createdDto);

            String requestBody = objectMapper.writeValueAsString(inputDto);

            mockMvc.perform(post("/api/tool-responses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("should return 400 on validation error")
        void shouldReturn400OnValidationError() throws Exception {
            ToolResponseDto inputDto = createSampleResponse();
            inputDto.setId(null);
            when(toolResponseService.createResponse(any(ToolResponseDto.class), any()))
                    .thenThrow(new IllegalArgumentException("Invalid data"));

            String requestBody = objectMapper.writeValueAsString(inputDto);

            mockMvc.perform(post("/api/tool-responses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            ToolResponseDto inputDto = createSampleResponse();
            inputDto.setId(null);
            when(toolResponseService.createResponse(any(ToolResponseDto.class), any()))
                    .thenThrow(new RuntimeException("DB error"));

            String requestBody = objectMapper.writeValueAsString(inputDto);

            mockMvc.perform(post("/api/tool-responses")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("PUT /api/tool-responses/{responseId}")
    class UpdateResponse {

        @Test
        @DisplayName("should update response and return 200")
        void shouldUpdateResponse() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.updateResponse(eq(RESPONSE_ID), any(ToolResponseDto.class), eq(USER_ID)))
                    .thenReturn(dto);

            String requestBody = objectMapper.writeValueAsString(dto);

            mockMvc.perform(put("/api/tool-responses/{responseId}", RESPONSE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Success Response"));
        }

        @Test
        @DisplayName("should use default userId 'system' when header missing")
        void shouldUseDefaultUserId() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.updateResponse(eq(RESPONSE_ID), any(ToolResponseDto.class), eq("system")))
                    .thenReturn(dto);

            String requestBody = objectMapper.writeValueAsString(dto);

            mockMvc.perform(put("/api/tool-responses/{responseId}", RESPONSE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 400 on validation error")
        void shouldReturn400OnValidationError() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.updateResponse(eq(RESPONSE_ID), any(ToolResponseDto.class), any()))
                    .thenThrow(new IllegalArgumentException("Invalid data"));

            String requestBody = objectMapper.writeValueAsString(dto);

            mockMvc.perform(put("/api/tool-responses/{responseId}", RESPONSE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.updateResponse(eq(RESPONSE_ID), any(ToolResponseDto.class), any()))
                    .thenThrow(new RuntimeException("DB error"));

            String requestBody = objectMapper.writeValueAsString(dto);

            mockMvc.perform(put("/api/tool-responses/{responseId}", RESPONSE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("DELETE /api/tool-responses/{responseId}")
    class DeleteResponse {

        @Test
        @DisplayName("should delete response and return 204")
        void shouldDeleteResponse() throws Exception {
            doNothing().when(toolResponseService).deleteResponse(RESPONSE_ID);

            mockMvc.perform(delete("/api/tool-responses/{responseId}", RESPONSE_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isNoContent());

            verify(toolResponseService).deleteResponse(RESPONSE_ID);
        }

        @Test
        @DisplayName("should return 404 when response not found")
        void shouldReturn404WhenNotFound() throws Exception {
            doThrow(new IllegalArgumentException("Not found")).when(toolResponseService).deleteResponse(RESPONSE_ID);

            mockMvc.perform(delete("/api/tool-responses/{responseId}", RESPONSE_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            doThrow(new RuntimeException("DB error")).when(toolResponseService).deleteResponse(RESPONSE_ID);

            mockMvc.perform(delete("/api/tool-responses/{responseId}", RESPONSE_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("DELETE /api/tool-responses/tool/{toolId}")
    class DeleteResponsesByTool {

        @Test
        @DisplayName("should delete all responses for a tool and return 204")
        void shouldDeleteAllResponses() throws Exception {
            doNothing().when(toolResponseService).deleteResponsesByToolId(TOOL_ID);

            mockMvc.perform(delete("/api/tool-responses/tool/{toolId}", TOOL_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isNoContent());

            verify(toolResponseService).deleteResponsesByToolId(TOOL_ID);
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            doThrow(new RuntimeException("DB error")).when(toolResponseService).deleteResponsesByToolId(TOOL_ID);

            mockMvc.perform(delete("/api/tool-responses/tool/{toolId}", TOOL_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("PUT /api/tool-responses/{responseId}/set-default")
    class SetAsDefaultResponse {

        @Test
        @DisplayName("should set response as default and return 200")
        void shouldSetAsDefault() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            dto.setIsDefault(true);
            when(toolResponseService.setAsDefaultResponse(RESPONSE_ID, USER_ID)).thenReturn(dto);

            mockMvc.perform(put("/api/tool-responses/{responseId}/set-default", RESPONSE_ID)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.is_default").value(true));
        }

        @Test
        @DisplayName("should return 404 when response not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(toolResponseService.setAsDefaultResponse(RESPONSE_ID, USER_ID))
                    .thenThrow(new IllegalArgumentException("Not found"));

            mockMvc.perform(put("/api/tool-responses/{responseId}/set-default", RESPONSE_ID)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should use default userId 'system' when header missing")
        void shouldUseDefaultUserId() throws Exception {
            ToolResponseDto dto = createSampleResponse();
            when(toolResponseService.setAsDefaultResponse(RESPONSE_ID, "system")).thenReturn(dto);

            mockMvc.perform(put("/api/tool-responses/{responseId}/set-default", RESPONSE_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(toolResponseService.setAsDefaultResponse(RESPONSE_ID, USER_ID))
                    .thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(put("/api/tool-responses/{responseId}/set-default", RESPONSE_ID)
                            .header("X-User-ID", USER_ID)
                            .header("X-Internal-Admin-Token", ADMIN_TOKEN))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-responses/mapping/status/{apiId}/{toolId}")
    class GetMappingStatus {

        @Test
        @DisplayName("should return hasMapping true when mapping exists")
        void shouldReturnHasMappingTrue() throws Exception {
            when(mappingResolverService.hasMappingForTool(TOOL_ID.toString())).thenReturn(true);

            mockMvc.perform(get("/api/tool-responses/mapping/status/{apiId}/{toolId}",
                            API_ID, TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasMapping").value(true));
        }

        @Test
        @DisplayName("should return hasMapping false when no mapping exists")
        void shouldReturnHasMappingFalse() throws Exception {
            when(mappingResolverService.hasMappingForTool(TOOL_ID.toString())).thenReturn(false);

            mockMvc.perform(get("/api/tool-responses/mapping/status/{apiId}/{toolId}",
                            API_ID, TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.hasMapping").value(false));
        }

        @Test
        @DisplayName("should return 500 on service exception")
        void shouldReturn500OnException() throws Exception {
            when(mappingResolverService.hasMappingForTool(TOOL_ID.toString()))
                    .thenThrow(new RuntimeException("Error"));

            mockMvc.perform(get("/api/tool-responses/mapping/status/{apiId}/{toolId}",
                            API_ID, TOOL_ID))
                    .andExpect(status().isInternalServerError());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-responses/mapping/{apiId}/{toolId}")
    class GetMapping {

        @Test
        @DisplayName("should return invalid format before mapping lookup for malformed tool IDs")
        void shouldReturnInvalidFormatBeforeMappingLookupForMalformedToolIds() throws Exception {
            mockMvc.perform(get("/api/tool-responses/mapping/{apiId}/{toolId}",
                            API_ID, "not-a-uuid"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("Invalid tool ID format"));

            verifyNoInteractions(mappingResolverService, mappingRegistry);
        }

        @Test
        @DisplayName("should return no mapping for valid tool IDs without mappings")
        void shouldReturnNoMappingForValidToolIdsWithoutMappings() throws Exception {
            when(mappingResolverService.hasMappingForTool(TOOL_ID.toString())).thenReturn(false);

            mockMvc.perform(get("/api/tool-responses/mapping/{apiId}/{toolId}",
                            API_ID, TOOL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("No mapping found for this tool"));

            verify(mappingRegistry, never()).findLatestMappingVersionByToolId(any());
        }
    }

    @Nested
    @DisplayName("POST /api/tool-responses/mapping/resolve")
    class ResolveMapping {

        @Test
        @DisplayName("should return 400 when request has null toolId")
        void shouldReturn400WhenNullToolId() throws Exception {
            String requestBody = "{\"toolId\": null, \"content\": \"test\"}";

            mockMvc.perform(post("/api/tool-responses/mapping/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should resolve mapping successfully")
        void shouldResolveMappingSuccessfully() throws Exception {
            MappingResolverService.MappingResolutionResult result =
                    mock(MappingResolverService.MappingResolutionResult.class);
            when(result.isSuccess()).thenReturn(true);
            when(mappingResolverService.resolve(eq(TOOL_ID), any(byte[].class))).thenReturn(result);

            String requestBody = String.format("{\"toolId\": \"%s\", \"content\": \"test data\"}", TOOL_ID);

            mockMvc.perform(post("/api/tool-responses/mapping/resolve")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk());
        }
    }

    private static final UUID API_ID = UUID.randomUUID();
}
