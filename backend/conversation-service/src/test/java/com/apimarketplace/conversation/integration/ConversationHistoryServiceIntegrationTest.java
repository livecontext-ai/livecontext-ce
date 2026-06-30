package com.apimarketplace.conversation.integration;

import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ConversationHistoryService with real JPA but mocked Redis.
 * Tests the service layer with actual database interactions.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
class ConversationHistoryServiceIntegrationTest {

    @Autowired
    private ConversationHistoryService conversationHistoryService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private StorageService storageService;

    @PersistenceContext
    private EntityManager entityManager;

    private static final String USER_ID = "history-test-user";

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
    }

    // ========================== Helper Methods ==========================

    private Conversation persistConversation(String userId, String title) {
        Conversation conv = new Conversation(userId, title, "gpt-4o", "openai");
        conv.setActive(true);
        conv.setUpdatedAt(LocalDateTime.now());
        // V263 fail-loud: OrgScopedEntity requires organizationId on persist.
        // Stamp from userId since this helper persists directly without a request-bound thread.
        conv.setOrganizationId(userId);
        return conversationRepository.saveAndFlush(conv);
    }

    private void addMessage(String conversationId, String role, String content) {
        MessageDto dto = new MessageDto(role, content);
        dto.setTimestamp(java.time.Instant.now().toString());
        if ("assistant".equals(role)) {
            dto.setModel("gpt-4o");
        }
        messageService.addMessage(conversationId, dto);
    }

    // ========================== Tests ==========================

    @Nested
    @DisplayName("createConversation")
    class CreateConversation {

        @Test
        @DisplayName("should create conversation and return its ID")
        void shouldCreateConversation() {
            // Post-V261 organizationId is mandatory at persistConversation. Use the 6-arg
            // overload that threads it through the DTO so requireOrgId passes.
            String convId = conversationHistoryService.createConversation(
                    USER_ID, USER_ID, "Test Chat", "gpt-4o", "openai", null);

            assertThat(convId).isNotNull();
            assertThat(conversationRepository.findById(convId)).isPresent();
        }

        @Test
        @DisplayName("should use default title and leave model/provider null when not provided")
        void shouldUseDefaultValues() {
            // Post-V261 organizationId is mandatory. Use 6-arg overload; title/model/provider stay null.
            String convId = conversationHistoryService.createConversation(
                    USER_ID, USER_ID, null, null, null, null);

            assertThat(convId).isNotNull();
            var conv = conversationRepository.findById(convId).orElseThrow();
            assertThat(conv.getTitle()).isEqualTo("Generating title...");
            assertThat(conv.getModel()).isNull();
            assertThat(conv.getProvider()).isNull();
        }
    }

    @Nested
    @DisplayName("addMessage")
    class AddMessage {

        @Test
        @DisplayName("should add message and return its payload")
        void shouldAddMessageAndReturnPayload() {
            Conversation conv = persistConversation(USER_ID, "Chat");

            Map<String, Object> result = conversationHistoryService.addMessage(
                    conv.getId(), "user", "Hello!", "gpt-4o", null, null, USER_ID);

            assertThat(result).isNotNull();
            assertThat(result.get("role")).isEqualTo("user");
            assertThat(result.get("content")).isEqualTo("Hello!");
            assertThat(result.get("id")).isNotNull();
        }

        @Test
        @DisplayName("should add assistant message with tool calls")
        void shouldAddAssistantMessageWithToolCalls() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            String toolCalls = "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"search\"}}]";

            Map<String, Object> result = conversationHistoryService.addMessage(
                    conv.getId(), "assistant", "Let me search.", "gpt-4o", null, toolCalls, USER_ID);

            assertThat(result).isNotNull();
            assertThat(result.get("role")).isEqualTo("assistant");
            assertThat(result.get("toolCalls")).isNotNull();
        }

        @Test
        @DisplayName("should return null for non-existent conversation")
        void shouldReturnNullForNonExistent() {
            Map<String, Object> result = conversationHistoryService.addMessage(
                    "non-existent", "user", "Hello!", "gpt-4o", null, null, USER_ID);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for inactive conversation")
        void shouldReturnNullForInactiveConversation() {
            Conversation conv = persistConversation(USER_ID, "Inactive Chat");
            conv.setActive(false);
            conversationRepository.saveAndFlush(conv);

            Map<String, Object> result = conversationHistoryService.addMessage(
                    conv.getId(), "user", "Hello!", "gpt-4o", null, null, USER_ID);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("getConversationHistory")
    class GetConversationHistory {

        @Test
        @DisplayName("should return all messages in chronological order")
        void shouldReturnAllMessages() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            addMessage(conv.getId(), "user", "Hello!");
            addMessage(conv.getId(), "assistant", "Hi there!");
            addMessage(conv.getId(), "user", "How are you?");

            List<Map<String, Object>> history = conversationHistoryService
                    .getConversationHistory(conv.getId(), USER_ID);

            assertThat(history).hasSize(3);
            assertThat(history.get(0).get("role")).isEqualTo("user");
            assertThat(history.get(0).get("content")).isEqualTo("Hello!");
            assertThat(history.get(1).get("role")).isEqualTo("assistant");
            assertThat(history.get(2).get("role")).isEqualTo("user");
        }

        @Test
        @DisplayName("should return empty list for conversation with no messages")
        void shouldReturnEmptyForNoMessages() {
            Conversation conv = persistConversation(USER_ID, "Empty Chat");

            List<Map<String, Object>> history = conversationHistoryService
                    .getConversationHistory(conv.getId(), USER_ID);

            assertThat(history).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for non-existent conversation")
        void shouldReturnEmptyForNonExistent() {
            List<Map<String, Object>> history = conversationHistoryService
                    .getConversationHistory("non-existent", USER_ID);

            assertThat(history).isEmpty();
        }
    }

    @Nested
    @DisplayName("getConversationHistoryLimited")
    class GetLimitedHistory {

        @Test
        @DisplayName("should return last N messages in chronological order")
        void shouldReturnLastNMessages() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            LocalDateTime baseTime = LocalDateTime.of(2025, 1, 1, 12, 0, 0);
            for (int i = 1; i <= 10; i++) {
                addMessage(conv.getId(), "user", "Message " + i);
            }
            // Fix timestamps via native SQL to guarantee deterministic ordering
            // (@CreationTimestamp produces identical values in tight loops on Windows)
            List<Message> allMessages = messageRepository.findAll().stream()
                    .filter(m -> m.getConversation().getId().equals(conv.getId()))
                    .toList();
            for (Message msg : allMessages) {
                int num = Integer.parseInt(msg.getContent().replace("Message ", ""));
                entityManager.createNativeQuery("UPDATE conversation.messages SET created_at = :ts WHERE id = :id")
                        .setParameter("ts", baseTime.plusSeconds(num))
                        .setParameter("id", msg.getId())
                        .executeUpdate();
            }
            entityManager.flush();
            entityManager.clear();

            List<Map<String, Object>> limited = conversationHistoryService
                    .getConversationHistoryLimited(conv.getId(), USER_ID, 3);

            assertThat(limited).hasSize(3);
            // Should be in chronological order (oldest first)
            assertThat(limited.get(0).get("content")).isEqualTo("Message 8");
            assertThat(limited.get(1).get("content")).isEqualTo("Message 9");
            assertThat(limited.get(2).get("content")).isEqualTo("Message 10");
        }

        @Test
        @DisplayName("should return all messages if fewer than limit")
        void shouldReturnAllIfFewerThanLimit() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            addMessage(conv.getId(), "user", "Hello!");
            addMessage(conv.getId(), "assistant", "Hi!");

            List<Map<String, Object>> limited = conversationHistoryService
                    .getConversationHistoryLimited(conv.getId(), USER_ID, 10);

            assertThat(limited).hasSize(2);
        }
    }

    @Nested
    @DisplayName("updateConversationTitle")
    class UpdateTitle {

        @Test
        @DisplayName("should update conversation title")
        void shouldUpdateTitle() {
            Conversation conv = persistConversation(USER_ID, "Original Title");

            // V261 - ConversationQueryService.getConversationById now calls
            // TenantResolver.requireOrgId on the organizationId arg, so the test
            // must pass a non-null value. Reuse USER_ID as the org id for
            // single-tenant test parity (persistConversation stamps it too).
            boolean result = conversationHistoryService.updateConversationTitle(
                    conv.getId(), USER_ID, USER_ID, "New Title");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for non-existent conversation")
        void shouldReturnFalseForNonExistent() {
            // V261 - pass non-null orgId (see neighbouring test).
            boolean result = conversationHistoryService.updateConversationTitle(
                    "non-existent", USER_ID, USER_ID, "New Title");

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("convertToChatMessages")
    class ConvertToChatMessages {

        @Test
        @DisplayName("should convert message maps to ChatMessage format")
        void shouldConvertToChatMessages() {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "Hello!"),
                    Map.of("role", "assistant", "content", "Hi there!")
            );

            var chatMessages = conversationHistoryService.convertToChatMessages(messages);

            assertThat(chatMessages).hasSize(2);
            assertThat(chatMessages.get(0).getRole()).isEqualTo("user");
            assertThat(chatMessages.get(0).getContent()).isEqualTo("Hello!");
            assertThat(chatMessages.get(1).getRole()).isEqualTo("assistant");
        }
    }
}
