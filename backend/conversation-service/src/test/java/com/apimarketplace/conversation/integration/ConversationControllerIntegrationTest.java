package com.apimarketplace.conversation.integration;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.CreateConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import com.apimarketplace.common.storage.service.StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for ConversationController with real Spring context, H2 database,
 * and MockMvc for HTTP layer testing.
 *
 * External dependencies (Redis, LLM, external HTTP) are mocked.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class ConversationControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private StorageService storageService;

    private static final String USER_ID = "test-user-001";
    private static final String X_USER_ID = "X-User-ID";

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
    }

    // ========================== Helper Methods ==========================

    private Conversation persistConversation(String userId, String title, String model, String provider) {
        Conversation conv = new Conversation(userId, title, model, provider);
        conv.setActive(true);
        conv.setUpdatedAt(LocalDateTime.now());
        conv.setOrganizationId(userId);  // V263 OrgScopedEntity
        return conversationRepository.saveAndFlush(conv);
    }

    // ========================== Tests ==========================

    @Nested
    @DisplayName("POST /api/conversations - Create conversation")
    class CreateConversation {

        @Test
        @DisplayName("should create a new conversation and return 201")
        void shouldCreateConversation() throws Exception {
            CreateConversationDto dto = new CreateConversationDto("My First Chat", "gpt-4o", "openai");
            dto.setActive(true);

            MvcResult result = mockMvc.perform(post("/api/conversations")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.title").value("My First Chat"))
                    .andExpect(jsonPath("$.model").value("gpt-4o"))
                    .andExpect(jsonPath("$.provider").value("openai"))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.id").isNotEmpty())
                    .andReturn();

            // Verify persisted in database
            ConversationDto response = objectMapper.readValue(
                    result.getResponse().getContentAsString(), ConversationDto.class);
            assertThat(conversationRepository.findById(response.getId())).isPresent();
        }

        @Test
        @DisplayName("should create conversation with workflow ID")
        void shouldCreateConversationWithWorkflowId() throws Exception {
            CreateConversationDto dto = new CreateConversationDto("Workflow Chat", "gpt-4o", "openai");
            dto.setWorkflowId("wf-123");
            dto.setActive(true);

            mockMvc.perform(post("/api/conversations")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.workflowId").value("wf-123"));
        }
    }

    @Nested
    @DisplayName("GET /api/conversations/{id} - Get conversation by ID")
    class GetConversation {

        @Test
        @DisplayName("should return conversation by ID")
        void shouldReturnConversationById() throws Exception {
            Conversation saved = persistConversation(USER_ID, "Test Chat", "gpt-4o", "openai");

            mockMvc.perform(get("/api/conversations/{id}", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(saved.getId()))
                    .andExpect(jsonPath("$.title").value("Test Chat"))
                    .andExpect(jsonPath("$.model").value("gpt-4o"));
        }

        @Test
        @DisplayName("should return 404 for non-existent conversation")
        void shouldReturn404ForNonExistent() throws Exception {
            mockMvc.perform(get("/api/conversations/{id}", "non-existent")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET /api/conversations - List conversations with pagination")
    class ListConversations {

        @Test
        @DisplayName("should return paginated conversations for user")
        void shouldReturnPaginatedConversations() throws Exception {
            for (int i = 0; i < 5; i++) {
                persistConversation(USER_ID, "Chat " + i, "gpt-4o", "openai");
            }
            // Another user's conversation should not appear
            persistConversation("other-user", "Other Chat", "gpt-4o", "openai");

            mockMvc.perform(get("/api/conversations")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .param("page", "0")
                            .param("size", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.numberOfElements").value(3))
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }

        @Test
        @DisplayName("should return empty page for user with no conversations")
        void shouldReturnEmptyPageForNoConversations() throws Exception {
            mockMvc.perform(get("/api/conversations")
                            .header(X_USER_ID, "empty-user")
                            .header("X-Organization-ID", "empty-user")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.numberOfElements").value(0))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }
    }

    @Nested
    @DisplayName("PUT /api/conversations/{id} - Update conversation")
    class UpdateConversation {

        @Test
        @DisplayName("should update conversation title")
        void shouldUpdateConversationTitle() throws Exception {
            Conversation saved = persistConversation(USER_ID, "Original Title", "gpt-4o", "openai");

            ConversationDto updateDto = new ConversationDto();
            updateDto.setUserId(USER_ID);
            updateDto.setTitle("Updated Title");
            updateDto.setModel("gpt-4o");
            updateDto.setProvider("openai");
            updateDto.setActive(true);

            mockMvc.perform(put("/api/conversations/{id}", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Updated Title"));

            // Verify persisted in database
            Conversation updated = conversationRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.getTitle()).isEqualTo("Updated Title");
        }
    }

    @Nested
    @DisplayName("DELETE /api/conversations/{id} - Soft delete")
    class DeleteConversation {

        @Test
        @DisplayName("should soft delete conversation (set active=false)")
        void shouldSoftDeleteConversation() throws Exception {
            Conversation saved = persistConversation(USER_ID, "To Delete", "gpt-4o", "openai");

            mockMvc.perform(delete("/api/conversations/{id}", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isNoContent());

            // Verify soft deleted (still in DB but inactive)
            Conversation deleted = conversationRepository.findById(saved.getId()).orElseThrow();
            assertThat(deleted.getActive()).isFalse();
        }

        @Test
        @DisplayName("should return 404 when deleting non-existent conversation")
        void shouldReturn404WhenDeletingNonExistent() throws Exception {
            mockMvc.perform(delete("/api/conversations/{id}", "non-existent")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/conversations/{id}/permanent - Permanent delete")
    class PermanentDeleteConversation {

        @Test
        @DisplayName("should permanently delete conversation and its messages")
        void shouldPermanentlyDelete() throws Exception {
            Conversation saved = persistConversation(USER_ID, "To Permanently Delete", "gpt-4o", "openai");

            mockMvc.perform(delete("/api/conversations/{id}/permanent", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isNoContent());

            assertThat(conversationRepository.findById(saved.getId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("POST /api/conversations/{id}/messages - Add message")
    class AddMessage {

        @Test
        @DisplayName("should add a USER message to conversation")
        void shouldAddUserMessage() throws Exception {
            Conversation saved = persistConversation(USER_ID, "Chat", "gpt-4o", "openai");

            MessageDto messageDto = new MessageDto("user", "Hello, how are you?");
            messageDto.setTimestamp("2025-01-01T10:00:00Z");

            mockMvc.perform(post("/api/conversations/{id}/messages", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(messageDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("user"))
                    .andExpect(jsonPath("$.content").value("Hello, how are you?"));
        }

        @Test
        @DisplayName("should add an ASSISTANT message with tool calls")
        void shouldAddAssistantMessageWithToolCalls() throws Exception {
            Conversation saved = persistConversation(USER_ID, "Chat", "gpt-4o", "openai");

            MessageDto messageDto = new MessageDto();
            messageDto.setRole("assistant");
            messageDto.setContent("Let me check that for you.");
            messageDto.setToolCalls("[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"search\",\"arguments\":\"{}\"}}]");
            messageDto.setModel("gpt-4o");

            mockMvc.perform(post("/api/conversations/{id}/messages", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(messageDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("assistant"))
                    .andExpect(jsonPath("$.toolCalls").isNotEmpty());
        }

        @Test
        @DisplayName("should add a TOOL result message")
        void shouldAddToolResultMessage() throws Exception {
            Conversation saved = persistConversation(USER_ID, "Chat", "gpt-4o", "openai");

            MessageDto messageDto = MessageDto.toolResult("call_1", "search_tool", "{\"results\": []}");

            mockMvc.perform(post("/api/conversations/{id}/messages", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(messageDto)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role").value("tool"))
                    .andExpect(jsonPath("$.toolCallId").value("call_1"))
                    .andExpect(jsonPath("$.toolName").value("search_tool"));
        }
    }

    @Nested
    @DisplayName("GET /api/conversations/{id}/messages/page - Get messages (paginated)")
    class GetMessages {

        @Test
        @DisplayName("should return paginated messages - DESC so page 0 is the newest batch")
        void shouldReturnMessages() throws Exception {
            // Regression: the legacy un-paginated `/messages` endpoint was removed. All
            // callers now use `/messages/page` which returns a Spring `Page<MessageDto>`
            // DESC by createdAt - page 0 = newest message first.
            Conversation saved = persistConversation(USER_ID, "Chat", "gpt-4o", "openai");

            MessageDto msg1 = new MessageDto("user", "Hello!");
            msg1.setTimestamp("2025-01-01T10:00:00Z");

            MessageDto msg2 = new MessageDto("assistant", "Hi there!");
            msg2.setModel("gpt-4o");
            msg2.setTimestamp("2025-01-01T10:00:01Z");

            mockMvc.perform(post("/api/conversations/{id}/messages", saved.getId())
                    .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(msg1)));

            mockMvc.perform(post("/api/conversations/{id}/messages", saved.getId())
                    .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(msg2)));

            mockMvc.perform(get("/api/conversations/{id}/messages/page", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content.length()").value(2))
                    // DESC order: assistant (newer) first, then user.
                    .andExpect(jsonPath("$.content[0].role").value("assistant"))
                    .andExpect(jsonPath("$.content[1].role").value("user"))
                    .andExpect(jsonPath("$.totalElements").value(2));
        }
    }

    @Nested
    @DisplayName("Service approval endpoints")
    class ServiceApprovals {

        @Test
        @DisplayName("should approve services (no-op, returns echoed services)")
        void shouldApproveServices() throws Exception {
            Conversation saved = persistConversation(USER_ID, "Chat", "gpt-4o", "openai");

            Map<String, Object> request = Map.of("services", List.of("gmail", "slack"));

            mockMvc.perform(post("/api/conversations/{id}/services/approve", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conversationId").value(saved.getId()))
                    .andExpect(jsonPath("$.approvedServices").isArray())
                    .andExpect(jsonPath("$.newlyApproved").isArray());
        }

        @Test
        @DisplayName("should get approved services for a conversation")
        void shouldGetApprovedServices() throws Exception {
            Conversation saved = persistConversation(USER_ID, "Chat", "gpt-4o", "openai");
            saved.approveService("gmail");
            conversationRepository.saveAndFlush(saved);

            mockMvc.perform(get("/api/conversations/{id}/services/approved", saved.getId())
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approvedServices").isArray());
        }

        @Test
        @DisplayName("should revoke service approval")
        void shouldRevokeServiceApproval() throws Exception {
            Conversation saved = persistConversation(USER_ID, "Chat", "gpt-4o", "openai");
            saved.approveService("gmail");
            saved.approveService("slack");
            conversationRepository.saveAndFlush(saved);

            mockMvc.perform(delete("/api/conversations/{id}/services/{serviceType}", saved.getId(), "gmail")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isOk());

            // Verify revoked
            Conversation updated = conversationRepository.findById(saved.getId()).orElseThrow();
            assertThat(updated.isServiceApproved("gmail")).isFalse();
            assertThat(updated.isServiceApproved("slack")).isTrue();
        }
    }

    @Nested
    @DisplayName("Workflow conversation endpoints")
    class WorkflowConversations {

        @Test
        @DisplayName("should find existing workflow conversation")
        void shouldFindWorkflowConversation() throws Exception {
            Conversation conv = persistConversation(USER_ID, "WF Chat", "gpt-4o", "openai");
            conv.setWorkflowId("wf-abc");
            conversationRepository.saveAndFlush(conv);

            mockMvc.perform(get("/api/conversations/workflow/{workflowId}", "wf-abc")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.workflowId").value("wf-abc"));
        }

        @Test
        @DisplayName("should return 404 for non-existent workflow conversation")
        void shouldReturn404ForNonExistentWorkflow() throws Exception {
            mockMvc.perform(get("/api/conversations/workflow/{workflowId}", "non-existent")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Search endpoints")
    class SearchEndpoints {

        @Test
        @DisplayName("should search conversations by title")
        void shouldSearchByTitle() throws Exception {
            persistConversation(USER_ID, "Machine learning basics", "gpt-4o", "openai");
            persistConversation(USER_ID, "Kubernetes deployment", "gpt-4o", "openai");

            mockMvc.perform(get("/api/conversations/search/title")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .param("searchTerm", "machine"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.numberOfElements").value(1));
        }
    }

    @Nested
    @DisplayName("Count and recent endpoints")
    class CountAndRecent {

        @Test
        @DisplayName("should return conversation count for user")
        void shouldReturnCount() throws Exception {
            persistConversation(USER_ID, "Chat 1", "gpt-4o", "openai");
            persistConversation(USER_ID, "Chat 2", "gpt-4o", "openai");

            mockMvc.perform(get("/api/conversations/count")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(content().string("2"));
        }

        @Test
        @DisplayName("should return recent conversations for user")
        void shouldReturnRecentConversations() throws Exception {
            persistConversation(USER_ID, "Chat 1", "gpt-4o", "openai");
            persistConversation(USER_ID, "Chat 2", "gpt-4o", "openai");
            persistConversation(USER_ID, "Chat 3", "gpt-4o", "openai");

            mockMvc.perform(get("/api/conversations/recent")
                            .header(X_USER_ID, USER_ID)
                            .header("X-Organization-ID", USER_ID)
                            .param("limit", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2));
        }
    }

    @Nested
    @DisplayName("Delete by workflow")
    class DeleteByWorkflow {

        @Test
        @DisplayName("should soft delete all conversations for a workflow")
        void shouldSoftDeleteByWorkflow() throws Exception {
            Conversation conv1 = persistConversation(USER_ID, "WF Chat 1", "gpt-4o", "openai");
            conv1.setWorkflowId("wf-to-delete");
            conversationRepository.saveAndFlush(conv1);

            Conversation conv2 = persistConversation(USER_ID, "WF Chat 2", "gpt-4o", "openai");
            conv2.setWorkflowId("wf-to-delete");
            conversationRepository.saveAndFlush(conv2);

            // V263 OrgScopedEntity: persistConversation stamps organizationId=userId;
            // the controller passes orgId to the service which enforces strict-scope
            // (callerOrgId must == row.organizationId). Without the header, orgId=null
            // and ScopeGuard rejects every row → deletedCount=0.
            mockMvc.perform(delete("/api/conversations/by-workflow/{workflowId}", "wf-to-delete")
                            .header("X-User-ID", USER_ID)
                            .header("X-Organization-ID", USER_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deletedCount").value(2));

            // Verify both are soft deleted
            List<Conversation> all = conversationRepository.findByWorkflowId("wf-to-delete");
            assertThat(all).allMatch(c -> !c.getActive());
        }
    }
}
