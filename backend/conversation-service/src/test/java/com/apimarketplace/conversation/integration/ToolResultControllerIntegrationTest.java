package com.apimarketplace.conversation.integration;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.ToolResult;
import com.apimarketplace.conversation.repository.ToolResultRepository;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.common.storage.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ToolResultController with real Spring context and H2 database.
 * Tests the full HTTP to database flow for tool result operations.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class ToolResultControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ToolResultRepository toolResultRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private StorageService storageService;

    /**
     * Tool-result reads are gated by {@link ConversationQueryService#getConversationById}
     * (parent-conversation scope check, 2026-05-23 fix). These integration tests do not
     * persist parent conversations, so we stub the gate to pass-through; tenant-mismatch
     * cases override the stub with {@link Optional#empty()} to assert 404.
     */
    @MockitoBean
    private ConversationQueryService conversationQueryService;

    private static final String TENANT_ID = "tenant-001";
    private static final String OTHER_TENANT = "other-tenant";
    private static final String CONVERSATION_ID = "conv-001";
    private static final String X_USER_ID = "X-User-ID";

    @BeforeEach
    void setUp() {
        toolResultRepository.deleteAll();
        // Default: every conversation lookup succeeds (parent-conversation gate is open).
        // Negative-scope tests override per-call to return Optional.empty().
        Mockito.when(conversationQueryService.getConversationById(any(), any(), any()))
                .thenReturn(Optional.of(Mockito.mock(ConversationDto.class)));
    }

    // ========================== Helper Methods ==========================

    private ToolResult persistToolResult(String conversationId, String tenantId, String toolName,
                                         String toolCallId, boolean success, String content) {
        ToolResult result = new ToolResult(conversationId, tenantId, toolName, toolCallId,
                success, 150L, content, null);
        return toolResultRepository.saveAndFlush(result);
    }

    // ========================== Tests ==========================

    @Nested
    @DisplayName("POST /api/tool-results - Save tool result")
    class SaveToolResult {

        @Test
        @DisplayName("should save a successful tool result")
        void shouldSaveSuccessfulToolResult() throws Exception {
            Map<String, Object> request = Map.of(
                    "conversationId", CONVERSATION_ID,
                    "toolName", "search_web",
                    "toolCallId", "call_001",
                    "success", true,
                    "durationMs", 250,
                    "content", "{\"results\": [\"result1\", \"result2\"]}"
            );

            mockMvc.perform(post("/api/tool-results")
                            .header(X_USER_ID, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andExpect(jsonPath("$.toolCallId").value("call_001"));

            // Verify in database
            var results = toolResultRepository.findByConversationIdAndTenantIdOrderByCreatedAtAsc(
                    CONVERSATION_ID, TENANT_ID);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).getToolName()).isEqualTo("search_web");
            assertThat(results.get(0).isSuccess()).isTrue();
        }

        @Test
        @DisplayName("should save a tool result with metadata")
        void shouldSaveToolResultWithMetadata() throws Exception {
            Map<String, Object> metadata = Map.of(
                    "iconSlug", "search",
                    "displayToolName", "Web Search",
                    "visualization", Map.of("type", "table", "columns", 3)
            );

            Map<String, Object> request = Map.of(
                    "conversationId", CONVERSATION_ID,
                    "toolName", "search_web",
                    "toolCallId", "call_002",
                    "success", true,
                    "content", "Search results",
                    "metadata", metadata
            );

            mockMvc.perform(post("/api/tool-results")
                            .header(X_USER_ID, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            var results = toolResultRepository.findByConversationIdAndTenantIdOrderByCreatedAtAsc(
                    CONVERSATION_ID, TENANT_ID);
            assertThat(results).hasSize(1);
            // Note: JSONB metadata persistence might require PostgreSQL; H2 in MODE=PostgreSQL
            // supports basic JSON, but full jsonb features may be limited.
        }

        @Test
        @DisplayName("should save a failed tool result with error message")
        void shouldSaveFailedToolResult() throws Exception {
            Map<String, Object> request = Map.of(
                    "conversationId", CONVERSATION_ID,
                    "toolName", "api_call",
                    "toolCallId", "call_003",
                    "success", false,
                    "error", "Connection timeout"
            );

            mockMvc.perform(post("/api/tool-results")
                            .header(X_USER_ID, TENANT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Nested
    @DisplayName("GET /api/tool-results/{id} - Get tool result by ID")
    class GetById {

        @Test
        @DisplayName("should return tool result by ID")
        void shouldReturnToolResultById() throws Exception {
            ToolResult saved = persistToolResult(CONVERSATION_ID, TENANT_ID, "search_web",
                    "call_010", true, "Search results content");

            mockMvc.perform(get("/api/tool-results/{id}", saved.getId().toString())
                            .header(X_USER_ID, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.toolName").value("search_web"))
                    .andExpect(jsonPath("$.content").value("Search results content"))
                    .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("should return 404 for non-existent tool result")
        void shouldReturn404ForNonExistent() throws Exception {
            mockMvc.perform(get("/api/tool-results/{id}", UUID.randomUUID().toString())
                            .header(X_USER_ID, TENANT_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 404 when caller has no access to parent conversation")
        void shouldReturn404WhenConversationOutOfScope() throws Exception {
            ToolResult saved = persistToolResult(CONVERSATION_ID, TENANT_ID, "search_web",
                    "call_011", true, "Content");

            // Override the @BeforeEach pass-through stub: this specific caller cannot see the
            // parent conversation, so the controller must return 404 even though the row exists.
            Mockito.when(conversationQueryService.getConversationById(any(), Mockito.eq(OTHER_TENANT), any()))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get("/api/tool-results/{id}", saved.getId().toString())
                            .header(X_USER_ID, OTHER_TENANT))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should return 400 for invalid UUID")
        void shouldReturn400ForInvalidUuid() throws Exception {
            mockMvc.perform(get("/api/tool-results/{id}", "not-a-uuid")
                            .header(X_USER_ID, TENANT_ID))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-results/by-tool-call/{toolCallId} - Get by tool call ID")
    class GetByToolCallId {

        @Test
        @DisplayName("should return tool result by tool call ID")
        void shouldReturnByToolCallId() throws Exception {
            persistToolResult(CONVERSATION_ID, TENANT_ID, "api_call", "call_020", true, "API response");

            mockMvc.perform(get("/api/tool-results/by-tool-call/{toolCallId}", "call_020")
                            .header(X_USER_ID, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.toolName").value("api_call"));
        }

        @Test
        @DisplayName("should return 404 for non-existent tool call ID")
        void shouldReturn404ForNonExistentToolCallId() throws Exception {
            mockMvc.perform(get("/api/tool-results/by-tool-call/{toolCallId}", "non-existent")
                            .header(X_USER_ID, TENANT_ID))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/tool-results/conversation/{conversationId} - Get by conversation")
    class GetByConversation {

        @Test
        @DisplayName("should return all tool results for a conversation")
        void shouldReturnAllForConversation() throws Exception {
            persistToolResult(CONVERSATION_ID, TENANT_ID, "tool_1", "call_030", true, "Result 1");
            persistToolResult(CONVERSATION_ID, TENANT_ID, "tool_2", "call_031", true, "Result 2");
            persistToolResult(CONVERSATION_ID, TENANT_ID, "tool_3", "call_032", false, null);

            // Different conversation should not appear
            persistToolResult("other-conv", TENANT_ID, "tool_x", "call_033", true, "Other");

            mockMvc.perform(get("/api/tool-results/conversation/{conversationId}", CONVERSATION_ID)
                            .header(X_USER_ID, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(3))
                    .andExpect(jsonPath("$.results.length()").value(3));
        }

        @Test
        @DisplayName("should return empty results for conversation with no tool results")
        void shouldReturnEmptyForNoToolResults() throws Exception {
            mockMvc.perform(get("/api/tool-results/conversation/{conversationId}", "empty-conv")
                            .header(X_USER_ID, TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.count").value(0));
        }
    }
}
