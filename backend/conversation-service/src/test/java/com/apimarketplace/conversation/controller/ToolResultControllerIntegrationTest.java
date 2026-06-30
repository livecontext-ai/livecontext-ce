package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.entity.ToolResult;
import com.apimarketplace.conversation.service.ToolResultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ToolResultController Integration Tests")
class ToolResultControllerIntegrationTest {

    private MockMvc mockMvc;

    @Mock
    private ToolResultService toolResultService;

    @InjectMocks
    private ToolResultController toolResultController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TENANT_HEADER = "X-User-ID";
    private static final String TENANT_ID = "test-user";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(toolResultController).build();
    }

    // ================================================================
    // Helper methods
    // ================================================================

    private ToolResult buildToolResult(UUID id, String toolName, boolean success) {
        ToolResult result = new ToolResult("conv-1", TENANT_ID, toolName,
                "call-" + id.toString().substring(0, 8), success, 150L,
                "result content", null);
        result.setId(id);
        result.setCreatedAt(LocalDateTime.of(2025, 6, 1, 12, 0));
        return result;
    }

    private Map<String, Object> buildSaveRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("conversationId", "conv-1");
        request.put("toolName", "my_tool");
        request.put("toolCallId", "call-123");
        request.put("success", true);
        request.put("durationMs", 200);
        request.put("content", "tool output");
        request.put("error", null);
        request.put("metadata", Map.of("iconSlug", "wrench"));
        return request;
    }

    // ================================================================
    // POST /api/tool-results
    // ================================================================

    @Nested
    @DisplayName("POST /api/tool-results")
    class SaveToolResult {

        @Test
        @DisplayName("should save tool result and return 200 with id")
        void shouldSaveToolResult() throws Exception {
            UUID generatedId = UUID.randomUUID();
            ToolResult saved = buildToolResult(generatedId, "my_tool", true);

            when(toolResultService.save(
                    eq("conv-1"), eq(TENANT_ID), eq("my_tool"), eq("call-123"),
                    eq(true), eq(200L), eq("tool output"), isNull(), anyMap(), isNull()
            )).thenReturn(saved);

            mockMvc.perform(post("/api/tool-results")
                            .header(TENANT_HEADER, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSaveRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(generatedId.toString()))
                    .andExpect(jsonPath("$.toolCallId").value("call-123"))
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("should return 500 when service throws exception")
        void shouldReturn500WhenServiceThrows() throws Exception {
            when(toolResultService.save(
                    anyString(), anyString(), anyString(), anyString(),
                    anyBoolean(), anyLong(), anyString(), isNull(), anyMap(), isNull()
            )).thenThrow(new RuntimeException("DB error"));

            mockMvc.perform(post("/api/tool-results")
                            .header(TENANT_HEADER, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildSaveRequest())))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error").value("DB error"));
        }

        @Test
        @DisplayName("should save tool result with null toolCallId")
        void shouldSaveWithNullToolCallId() throws Exception {
            UUID generatedId = UUID.randomUUID();
            ToolResult saved = buildToolResult(generatedId, "my_tool", true);

            Map<String, Object> request = buildSaveRequest();
            request.put("toolCallId", null);

            when(toolResultService.save(
                    eq("conv-1"), eq(TENANT_ID), eq("my_tool"), isNull(),
                    eq(true), eq(200L), eq("tool output"), isNull(), anyMap(), isNull()
            )).thenReturn(saved);

            mockMvc.perform(post("/api/tool-results")
                            .header(TENANT_HEADER, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.toolCallId").value(""));
        }

        @Test
        @DisplayName("should save tool result with error message")
        void shouldSaveWithErrorMessage() throws Exception {
            UUID generatedId = UUID.randomUUID();
            ToolResult saved = buildToolResult(generatedId, "my_tool", false);
            saved.setErrorMessage("tool failed");

            Map<String, Object> request = buildSaveRequest();
            request.put("success", false);
            request.put("error", "tool failed");

            when(toolResultService.save(
                    eq("conv-1"), eq(TENANT_ID), eq("my_tool"), eq("call-123"),
                    eq(false), eq(200L), eq("tool output"), eq("tool failed"), anyMap(), isNull()
            )).thenReturn(saved);

            mockMvc.perform(post("/api/tool-results")
                            .header(TENANT_HEADER, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ================================================================
    // GET /api/tool-results/{id}
    // ================================================================

    @Nested
    @DisplayName("GET /api/tool-results/{id}")
    class GetById {

        @Test
        @DisplayName("should return tool result when found")
        void shouldReturnToolResult() throws Exception {
            UUID id = UUID.randomUUID();
            ToolResult result = buildToolResult(id, "my_tool", true);

            when(toolResultService.getById(id, TENANT_ID, null)).thenReturn(Optional.of(result));

            mockMvc.perform(get("/api/tool-results/{id}", id.toString())
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.toolName").value("my_tool"))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.content").value("result content"));
        }

        @Test
        @DisplayName("should return 404 when not found")
        void shouldReturn404WhenNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(toolResultService.getById(id, TENANT_ID, null)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/tool-results/{id}", id.toString())
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 for invalid UUID")
        void shouldReturn400ForInvalidUUID() throws Exception {
            mockMvc.perform(get("/api/tool-results/{id}", "not-a-uuid")
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should include metadata in response when present")
        void shouldIncludeMetadataInResponse() throws Exception {
            UUID id = UUID.randomUUID();
            ToolResult result = buildToolResult(id, "workflow", true);
            result.setMetadata(Map.of("iconSlug", "wrench", "displayToolName", "Build Workflow"));

            when(toolResultService.getById(id, TENANT_ID, null)).thenReturn(Optional.of(result));

            mockMvc.perform(get("/api/tool-results/{id}", id.toString())
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.iconSlug").value("wrench"))
                    .andExpect(jsonPath("$.displayToolName").value("Build Workflow"));
        }

        @Test
        @DisplayName("should handle null content gracefully")
        void shouldHandleNullContent() throws Exception {
            UUID id = UUID.randomUUID();
            ToolResult result = buildToolResult(id, "my_tool", true);
            result.setContentFull(null);

            when(toolResultService.getById(id, TENANT_ID, null)).thenReturn(Optional.of(result));

            mockMvc.perform(get("/api/tool-results/{id}", id.toString())
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").value(""));
        }
    }

    // ================================================================
    // GET /api/tool-results/by-tool-call/{toolCallId}
    // ================================================================

    @Nested
    @DisplayName("GET /api/tool-results/by-tool-call/{toolCallId}")
    class GetByToolCallId {

        @Test
        @DisplayName("should return tool result by tool call ID")
        void shouldReturnByToolCallId() throws Exception {
            UUID id = UUID.randomUUID();
            ToolResult result = buildToolResult(id, "my_tool", true);
            result.setToolCallId("call-abc");

            when(toolResultService.getByToolCallId("call-abc", TENANT_ID, null))
                    .thenReturn(Optional.of(result));

            mockMvc.perform(get("/api/tool-results/by-tool-call/{toolCallId}", "call-abc")
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.toolName").value("my_tool"));
        }

        @Test
        @DisplayName("should return 404 when tool call ID not found")
        void shouldReturn404WhenToolCallIdNotFound() throws Exception {
            when(toolResultService.getByToolCallId("unknown", TENANT_ID, null))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get("/api/tool-results/by-tool-call/{toolCallId}", "unknown")
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // GET /api/tool-results/conversation/{conversationId}
    // ================================================================

    @Nested
    @DisplayName("GET /api/tool-results/conversation/{conversationId}")
    class GetByConversation {

        @Test
        @DisplayName("should return all tool results for a conversation")
        void shouldReturnAllForConversation() throws Exception {
            UUID id1 = UUID.randomUUID();
            UUID id2 = UUID.randomUUID();
            ToolResult r1 = buildToolResult(id1, "tool_a", true);
            r1.setToolCallId("call-a");
            ToolResult r2 = buildToolResult(id2, "tool_b", false);
            r2.setToolCallId("call-b");
            r2.setErrorMessage("failed");

            when(toolResultService.getByConversation("conv-1", TENANT_ID, null))
                    .thenReturn(List.of(r1, r2));

            mockMvc.perform(get("/api/tool-results/conversation/{conversationId}", "conv-1")
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value("conv-1"))
                    .andExpect(jsonPath("$.count").value(2))
                    .andExpect(jsonPath("$.results[0].toolName").value("tool_a"))
                    .andExpect(jsonPath("$.results[1].toolName").value("tool_b"));
        }

        @Test
        @DisplayName("should return empty results when no tool results exist")
        void shouldReturnEmptyResults() throws Exception {
            when(toolResultService.getByConversation("conv-empty", TENANT_ID, null))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/tool-results/conversation/{conversationId}", "conv-empty")
                            .header(TENANT_HEADER, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value("conv-empty"))
                    .andExpect(jsonPath("$.count").value(0))
                    .andExpect(jsonPath("$.results").isEmpty());
        }
    }

    // ================================================================
    // PUT /api/tool-results/{id}/metadata
    // ================================================================

    @Nested
    @DisplayName("PUT /api/tool-results/{id}/metadata")
    class UpdateMetadata {

        @Test
        @DisplayName("should update metadata and return 200")
        void shouldUpdateMetadata() throws Exception {
            UUID id = UUID.randomUUID();
            ToolResult result = buildToolResult(id, "my_tool", true);
            result.setMetadata(new HashMap<>(Map.of("existing", "value")));

            when(toolResultService.getById(id, TENANT_ID, null)).thenReturn(Optional.of(result));

            Map<String, Object> newMetadata = Map.of("status", "completed");

            mockMvc.perform(put("/api/tool-results/{id}/metadata", id.toString())
                            .header(TENANT_HEADER, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(newMetadata)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.id").value(id.toString()));
        }

        @Test
        @DisplayName("should return 404 when result not found for metadata update")
        void shouldReturn404ForMissingResult() throws Exception {
            UUID id = UUID.randomUUID();
            when(toolResultService.getById(id, TENANT_ID, null)).thenReturn(Optional.empty());

            mockMvc.perform(put("/api/tool-results/{id}/metadata", id.toString())
                            .header(TENANT_HEADER, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 for invalid UUID in metadata update")
        void shouldReturn400ForInvalidUUIDInMetadataUpdate() throws Exception {
            mockMvc.perform(put("/api/tool-results/{id}/metadata", "invalid-uuid")
                            .header(TENANT_HEADER, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"done\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should merge metadata when existing is null")
        void shouldMergeMetadataWhenExistingNull() throws Exception {
            UUID id = UUID.randomUUID();
            ToolResult result = buildToolResult(id, "my_tool", true);
            result.setMetadata(null);

            when(toolResultService.getById(id, TENANT_ID, null)).thenReturn(Optional.of(result));

            mockMvc.perform(put("/api/tool-results/{id}/metadata", id.toString())
                            .header(TENANT_HEADER, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"newKey\":\"newValue\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }
}
