package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.exception.GlobalExceptionHandler;
import com.apimarketplace.conversation.service.ConversationCommandService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.PendingActionResumeService;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationController Additional Tests")
class ConversationAdditionalTest {

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ConversationCommandService conversationCommandService;

    @Mock
    private ConversationQueryService conversationQueryService;

    @Mock
    private MessageService messageService;

    @Mock
    private PendingActionService pendingActionService;

    @Mock
    private PendingActionResumeService pendingActionResumeService;

    @Mock
    private ServiceApprovalService serviceApprovalService;

    @InjectMocks
    private ConversationController conversationController;

    private static final String USER_HEADER = "X-User-ID";
    private static final String USER_ID = "test-user";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(conversationController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private ConversationDto buildConversationDto(String id) {
        ConversationDto dto = new ConversationDto();
        dto.setId(id);
        dto.setUserId(USER_ID);
        dto.setTitle("Test Conversation");
        dto.setModel("gpt-4o");
        dto.setProvider("openai");
        dto.setActive(true);
        return dto;
    }

    private void mockConversationVisible(String conversationId, String userId) {
        ConversationDto dto = buildConversationDto(conversationId);
        dto.setUserId(userId);
        when(conversationQueryService.getConversationById(conversationId, userId, null))
                .thenReturn(Optional.of(dto));
    }

    private void mockConversationMissing(String conversationId, String userId) {
        when(conversationQueryService.getConversationById(conversationId, userId, null))
                .thenReturn(Optional.empty());
    }

    // ================================================================
    // GET /api/conversations/workflow/{workflowId}
    // ================================================================

    @Nested
    @DisplayName("GET /api/conversations/workflow/{workflowId}")
    class FindWorkflowConversation {

        @Test
        @DisplayName("should return workflow conversation when found")
        void shouldReturnWorkflowConversation() throws Exception {
            ConversationDto dto = buildConversationDto("conv-1");
            dto.setWorkflowId("wf-1");
            when(conversationQueryService.findByUserIdAndWorkflowId(USER_ID, null, "wf-1"))
                    .thenReturn(Optional.of(dto));

            mockMvc.perform(get("/api/conversations/workflow/{workflowId}", "wf-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("conv-1"))
                    .andExpect(jsonPath("$.workflowId").value("wf-1"));
        }

        @Test
        @DisplayName("should return 404 when no workflow conversation exists")
        void shouldReturn404WhenNotFound() throws Exception {
            when(conversationQueryService.findByUserIdAndWorkflowId(USER_ID, null, "wf-unknown"))
                    .thenReturn(Optional.empty());

            mockMvc.perform(get("/api/conversations/workflow/{workflowId}", "wf-unknown")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // POST /api/conversations/workflow/{workflowId}
    // ================================================================

    @Nested
    @DisplayName("POST /api/conversations/workflow/{workflowId}")
    class CreateWorkflowConversation {

        @Test
        @DisplayName("should create workflow conversation with title from body")
        void shouldCreateWithTitle() throws Exception {
            ConversationDto dto = buildConversationDto("conv-new");
            dto.setWorkflowId("wf-1");
            when(conversationCommandService.createWorkflowConversation(
                    eq(USER_ID), isNull(), eq("wf-1"), isNull(), isNull(), eq("My Workflow")))
                    .thenReturn(dto);

            mockMvc.perform(post("/api/conversations/workflow/{workflowId}", "wf-1")
                            .header(USER_HEADER, USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"My Workflow\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("conv-new"));
        }

        @Test
        @DisplayName("should create workflow conversation without body")
        void shouldCreateWithoutBody() throws Exception {
            ConversationDto dto = buildConversationDto("conv-new");
            when(conversationCommandService.createWorkflowConversation(
                    eq(USER_ID), isNull(), eq("wf-1"), isNull(), isNull(), isNull()))
                    .thenReturn(dto);

            mockMvc.perform(post("/api/conversations/workflow/{workflowId}", "wf-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk());
        }
    }

    // ================================================================
    // GET /api/conversations/search/title
    // ================================================================

    @Nested
    @DisplayName("GET /api/conversations/search/title")
    class SearchByTitle {

        @Test
        @DisplayName("should search conversations by title")
        void shouldSearchByTitle() throws Exception {
            ConversationDto dto = buildConversationDto("conv-1");
            dto.setTitle("My Test Chat");
            Page<ConversationDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1);
            when(conversationQueryService.searchConversationsByTitle(USER_ID, null, "Test", 0, 10))
                    .thenReturn(page);

            mockMvc.perform(get("/api/conversations/search/title")
                            .header(USER_HEADER, USER_ID)
                            .param("searchTerm", "Test")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].title").value("My Test Chat"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        @DisplayName("should return empty results when no match")
        void shouldReturnEmptyWhenNoMatch() throws Exception {
            Page<ConversationDto> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
            when(conversationQueryService.searchConversationsByTitle(USER_ID, null, "nonexistent", 0, 10))
                    .thenReturn(page);

            mockMvc.perform(get("/api/conversations/search/title")
                            .header(USER_HEADER, USER_ID)
                            .param("searchTerm", "nonexistent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").isEmpty())
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    // ================================================================
    // GET /api/conversations/search/content
    // ================================================================

    @Nested
    @DisplayName("GET /api/conversations/search/content")
    class SearchByContent {

        @Test
        @DisplayName("should search conversations by message content")
        void shouldSearchByContent() throws Exception {
            ConversationDto dto = buildConversationDto("conv-1");
            Page<ConversationDto> page = new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1);
            when(conversationQueryService.searchConversationsByContent(USER_ID, null, "hello", 0, 10))
                    .thenReturn(page);

            mockMvc.perform(get("/api/conversations/search/content")
                            .header(USER_HEADER, USER_ID)
                            .param("searchTerm", "hello"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value("conv-1"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }
    }

    // ================================================================
    // GET /api/conversations/recent
    // ================================================================

    @Nested
    @DisplayName("GET /api/conversations/recent")
    class GetRecentConversations {

        @Test
        @DisplayName("should return recent conversations with limit")
        void shouldReturnRecentWithLimit() throws Exception {
            List<ConversationDto> recent = List.of(
                    buildConversationDto("conv-1"),
                    buildConversationDto("conv-2")
            );
            when(conversationQueryService.getRecentConversations(USER_ID, null, 5))
                    .thenReturn(recent);

            mockMvc.perform(get("/api/conversations/recent")
                            .header(USER_HEADER, USER_ID)
                            .param("limit", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value("conv-1"));
        }

        @Test
        @DisplayName("should use default limit of 5")
        void shouldUseDefaultLimit() throws Exception {
            when(conversationQueryService.getRecentConversations(USER_ID, null, 5))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/conversations/recent")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk());

            verify(conversationQueryService).getRecentConversations(USER_ID, null, 5);
        }
    }

    // ================================================================
    // GET /api/conversations/count
    // ================================================================

    @Nested
    @DisplayName("GET /api/conversations/count")
    class GetConversationCount {

        @Test
        @DisplayName("should return conversation count")
        void shouldReturnCount() throws Exception {
            when(conversationQueryService.getConversationCount(USER_ID, null)).thenReturn(42L);

            mockMvc.perform(get("/api/conversations/count")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(content().string("42"));
        }

        @Test
        @DisplayName("should return 0 when no conversations")
        void shouldReturnZero() throws Exception {
            when(conversationQueryService.getConversationCount(USER_ID, null)).thenReturn(0L);

            mockMvc.perform(get("/api/conversations/count")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(content().string("0"));
        }
    }

    // ================================================================
    // DELETE /api/conversations/by-workflow/{workflowId}
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/conversations/by-workflow/{workflowId}")
    class DeleteByWorkflow {

        @Test
        @DisplayName("should delete conversations by workflow and return count")
        void shouldDeleteByWorkflow() throws Exception {
            // Controller calls the 3-arg (workflowId, userId, organizationId) overload -
            // organizationId is null here since the test sends no X-Organization-ID header.
            when(conversationCommandService.deleteConversationsByWorkflowId("wf-1", "user-1", null)).thenReturn(3);

            mockMvc.perform(delete("/api/conversations/by-workflow/{workflowId}", "wf-1")
                            .header("X-User-ID", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.deletedCount").value(3))
                    .andExpect(jsonPath("$.workflowId").value("wf-1"));
        }

        @Test
        @DisplayName("should return 0 when no conversations to delete")
        void shouldReturnZeroWhenNone() throws Exception {
            // Controller calls the 3-arg overload - organizationId null here.
            when(conversationCommandService.deleteConversationsByWorkflowId("wf-empty", "user-1", null)).thenReturn(0);

            mockMvc.perform(delete("/api/conversations/by-workflow/{workflowId}", "wf-empty")
                            .header("X-User-ID", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deletedCount").value(0));
        }
    }

    // ================================================================
    // GET /api/conversations/{id}/pending-action
    // ================================================================

    @Nested
    @DisplayName("GET /api/conversations/{id}/pending-action")
    class GetPendingAction {

        @Test
        @DisplayName("should return pending action when exists")
        void shouldReturnPendingAction() throws Exception {
            Map<String, Object> action = new HashMap<>();
            action.put("waiting_for", "credential:gmail");
            action.put("original_request", "Check my email");
            action.put("expires_at", Instant.now().plus(1, ChronoUnit.HOURS).toString());

            mockConversationVisible("conv-1", USER_ID);
            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.of(action));

            mockMvc.perform(get("/api/conversations/{conversationId}/pending-action", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.waiting_for").value("credential:gmail"))
                    .andExpect(jsonPath("$.original_request").value("Check my email"));
        }

        @Test
        @DisplayName("should return 404 when no pending action")
        void shouldReturn404WhenNoPendingAction() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/conversations/{conversationId}/pending-action", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // DELETE /api/conversations/{id}/pending-action
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/conversations/{id}/pending-action")
    class ClearPendingAction {

        @Test
        @DisplayName("should clear pending action and return 204")
        void shouldClearPendingAction() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            doNothing().when(pendingActionService).clearPendingAction("conv-1");

            mockMvc.perform(delete("/api/conversations/{conversationId}/pending-action", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNoContent());

            verify(pendingActionService).clearPendingAction("conv-1");
        }

        @Test
        @DisplayName("with ?key= should clear only the matching card, leaving siblings pending")
        void shouldClearOnePendingActionByKey() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            when(pendingActionService.clearOnePendingAction("conv-1", "svc:connect")).thenReturn(true);

            mockMvc.perform(delete("/api/conversations/{conversationId}/pending-action", "conv-1")
                            .param("key", "svc:connect")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNoContent());

            verify(pendingActionService).clearOnePendingAction("conv-1", "svc:connect");
            verify(pendingActionService, never()).clearPendingAction("conv-1");
        }
    }

    // ================================================================
    // POST /api/conversations/{id}/pending-action/resume
    // ================================================================

    @Nested
    @DisplayName("POST /api/conversations/{id}/pending-action/resume")
    class ResumePendingAction {

        @Test
        @DisplayName("should resume pending action and return it")
        void shouldResumeAndReturnAction() throws Exception {
            Map<String, Object> action = new HashMap<>();
            action.put("waiting_for", "credential:gmail");
            action.put("original_request", "Check email");
            mockConversationVisible("conv-1", USER_ID);
            when(pendingActionResumeService.manualResume("conv-1")).thenReturn(action);

            mockMvc.perform(post("/api/conversations/{conversationId}/pending-action/resume", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.waiting_for").value("credential:gmail"));
        }

        @Test
        @DisplayName("should return 404 when no pending action to resume")
        void shouldReturn404WhenNothingToResume() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            when(pendingActionResumeService.manualResume("conv-1")).thenReturn(null);

            mockMvc.perform(post("/api/conversations/{conversationId}/pending-action/resume", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // POST /api/conversations/{id}/services/approve
    // ================================================================

    @Nested
    @DisplayName("POST /api/conversations/{id}/services/approve")
    class ApproveServices {

        @Test
        @DisplayName("should return 200 with echoed services (no-op endpoint)")
        void shouldApproveServices() throws Exception {
            Map<String, Object> request = new HashMap<>();
            request.put("services", List.of("gmail", "slack"));
            mockConversationVisible("conv-1", USER_ID);

            mockMvc.perform(post("/api/conversations/{conversationId}/services/approve", "conv-1")
                            .header(USER_HEADER, USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value("conv-1"))
                    .andExpect(jsonPath("$.approvedServices").isArray())
                    .andExpect(jsonPath("$.newlyApproved").isArray());
        }

        @Test
        @DisplayName("should return 200 even when services list is missing (no-op)")
        void shouldReturn200WhenServicesListMissing() throws Exception {
            Map<String, Object> request = new HashMap<>();
            request.put("other", "value");
            mockConversationVisible("conv-1", USER_ID);

            mockMvc.perform(post("/api/conversations/{conversationId}/services/approve", "conv-1")
                            .header(USER_HEADER, USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value("conv-1"));
        }

        @Test
        @DisplayName("should return 200 even when services list is empty (no-op)")
        void shouldReturn200WhenServicesListEmpty() throws Exception {
            Map<String, Object> request = new HashMap<>();
            request.put("services", List.of());
            mockConversationVisible("conv-1", USER_ID);

            mockMvc.perform(post("/api/conversations/{conversationId}/services/approve", "conv-1")
                            .header(USER_HEADER, USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value("conv-1"));
        }
    }

    // ================================================================
    // GET /api/conversations/{id}/services/approved
    // ================================================================

    @Nested
    @DisplayName("GET /api/conversations/{id}/services/approved")
    class GetApprovedServices {

        @Test
        @DisplayName("should return approved services")
        void shouldReturnApprovedServices() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            when(serviceApprovalService.getApprovedServices("conv-1"))
                    .thenReturn(Set.of("gmail", "slack"));

            mockMvc.perform(get("/api/conversations/{conversationId}/services/approved", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value("conv-1"))
                    .andExpect(jsonPath("$.approvedServices").isArray());
        }

        @Test
        @DisplayName("should return empty set when no services approved")
        void shouldReturnEmptySet() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            when(serviceApprovalService.getApprovedServices("conv-1"))
                    .thenReturn(Set.of());

            mockMvc.perform(get("/api/conversations/{conversationId}/services/approved", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approvedServices").isEmpty());
        }
    }

    // ================================================================
    // DELETE /api/conversations/{id}/services/{type}
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/conversations/{id}/services/{type}")
    class RevokeServiceApproval {

        @Test
        @DisplayName("should revoke service and return updated set")
        void shouldRevokeService() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            when(serviceApprovalService.getApprovedServices("conv-1"))
                    .thenReturn(Set.of("slack"));

            mockMvc.perform(delete("/api/conversations/{conversationId}/services/{serviceType}", "conv-1", "gmail")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value("conv-1"))
                    .andExpect(jsonPath("$.revokedService").value("gmail"));

            verify(serviceApprovalService).revokeServiceApproval("conv-1", "gmail");
        }
    }

    // ================================================================
    // DELETE /api/conversations/{id} (soft delete)
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/conversations/{id}")
    class SoftDelete {

        @Test
        @DisplayName("should soft delete and return 204")
        void shouldSoftDelete() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            doNothing().when(conversationCommandService).deleteConversation("conv-1");

            mockMvc.perform(delete("/api/conversations/{conversationId}", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when conversation does not exist")
        void shouldReturn404WhenNotExists() throws Exception {
            mockConversationMissing("missing", USER_ID);

            mockMvc.perform(delete("/api/conversations/{conversationId}", "missing")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // DELETE /api/conversations/{id}/permanent
    // ================================================================

    @Nested
    @DisplayName("DELETE /api/conversations/{id}/permanent")
    class PermanentDelete {

        @Test
        @DisplayName("should permanently delete and return 204")
        void shouldPermanentlyDelete() throws Exception {
            mockConversationVisible("conv-1", USER_ID);
            doNothing().when(conversationCommandService).permanentlyDeleteConversation("conv-1");

            mockMvc.perform(delete("/api/conversations/{conversationId}/permanent", "conv-1")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when conversation does not exist")
        void shouldReturn404WhenNotExists() throws Exception {
            mockConversationMissing("missing", USER_ID);

            mockMvc.perform(delete("/api/conversations/{conversationId}/permanent", "missing")
                            .header(USER_HEADER, USER_ID))
                    .andExpect(status().isNotFound());
        }
    }

    // ================================================================
    // PUT /api/conversations/messages/{messageId}/toolCalls
    // ================================================================

    @Nested
    @DisplayName("PUT /api/conversations/messages/{messageId}/toolCalls")
    class UpdateMessageToolCalls {

        @Test
        @DisplayName("should update message tool calls")
        void shouldUpdateToolCalls() throws Exception {
            MessageDto dto = new MessageDto();
            dto.setId("msg-1");
            dto.setRole("assistant");
            dto.setToolCalls("[{\"id\":\"call_1\",\"status\":\"completed\"}]");

            when(messageService.updateMessageToolCalls(eq("msg-1"), anyString(), eq("user-1"), isNull())).thenReturn(dto);

            mockMvc.perform(put("/api/conversations/messages/{messageId}/toolCalls", "msg-1")
                            .header("X-User-ID", "user-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[{\"id\":\"call_1\",\"status\":\"completed\"}]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("msg-1"));
        }
    }

    // ================================================================
    // POST /api/conversations/events/credential-configured
    // ================================================================

    @Nested
    @DisplayName("POST /api/conversations/events/credential-configured")
    class OnCredentialConfigured {

        @Test
        @DisplayName("should handle credential configured event")
        void shouldHandleCredentialConfigured() throws Exception {
            when(pendingActionResumeService.onCredentialConfigured("gmail", USER_ID)).thenReturn(2);

            mockMvc.perform(post("/api/conversations/events/credential-configured")
                            .header(USER_HEADER, USER_ID)
                            .param("credentialType", "gmail"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credentialType").value("gmail"))
                    .andExpect(jsonPath("$.conversationsResumed").value(2));
        }
    }

    // ================================================================
    // GET /api/conversations/{id}/messages (with limit)
    // ================================================================

    // The legacy `GET /api/conversations/{id}/messages` un-paginated endpoint was removed -
    // it loaded entire conversation payloads into memory (up to 200 rows with MB-sized
    // tool_calls) and stalled the panel for seconds. All callers must now use
    // `/api/conversations/{id}/messages/page` (paginated, DESC, page 0 = newest).
}
