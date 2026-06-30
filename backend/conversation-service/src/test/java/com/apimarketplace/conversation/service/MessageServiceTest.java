package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.ConversationNotFoundException;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import com.apimarketplace.conversation.mapper.MessageMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.apimarketplace.common.event.EventBus;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private com.apimarketplace.conversation.repository.MessageAttachmentRepository messageAttachmentRepository;

    @Mock
    private EventBus eventBus;

    @Mock
    private StorageBreakdownService storageBreakdownService;

    private final MessageMapper messageMapper = new MessageMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(conversationRepository, messageRepository, messageAttachmentRepository, messageMapper, eventBus, objectMapper, storageBreakdownService, null);
    }

    @Test
    void addMessageThrowsWhenConversationMissing() {
        when(conversationRepository.findById("conv-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> messageService.addMessage("conv-id", buildDto()))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void addMessageRejectsInactiveConversation() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setId("conv-id");
        conversation.setActive(false);
        when(conversationRepository.findById("conv-id")).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> messageService.addMessage("conv-id", buildDto()))
                .isInstanceOf(ConversationInactiveException.class);
    }

    @Test
    void addMessageValidatesRole() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setId("conv-id");
        when(conversationRepository.findById("conv-id")).thenReturn(Optional.of(conversation));

        MessageDto dto = buildDto();
        dto.setRole(null);

        assertThatThrownBy(() -> messageService.addMessage("conv-id", dto))
                .isInstanceOf(InvalidMessageException.class);
    }

    @Test
    void addMessagePersistsMessageAndUpdatesConversation() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setId("conv-id");
        when(conversationRepository.findById("conv-id")).thenReturn(Optional.of(conversation));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
            Message message = invocation.getArgument(0);
            message.setId("message-id");
            message.setConversation(conversation);
            return message;
        });

        MessageDto result = messageService.addMessage("conv-id", buildDto());

        assertThat(result.getId()).isEqualTo("message-id");
        verify(conversationRepository).save(conversation);
    }

    @Test
    void addMessageIsTransactionalSoLazyConversationMessagesCanBeUpdated() throws NoSuchMethodException {
        Transactional transactional = MessageService.class
                .getMethod("addMessage", String.class, MessageDto.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
    }

    // The un-paginated `getMessagesByConversationId(String)` was removed (and so was its
    // sibling `getMessagesByExecutionId(String, String)` plus `getLatestExecutionMessages`).
    // They unbounded-fetched MB-sized tool_calls payloads into memory; all callers must now
    // go through the paginated 3-/4-arg overload below.

    @Test
    void getMessagesPagedReturnsPage() {
        Message message = new Message(Message.MessageRole.USER, "hello");
        message.setConversation(new Conversation());
        Page<Message> page = new PageImpl<>(List.of(message), PageRequest.of(0, 20), 1);
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq("conv-id"), any()))
                .thenReturn(page);

        Page<MessageDto> result = messageService.getMessagesByConversationId("conv-id", 0, 20);

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getMessagesPagedWithExecutionIdScopesToExecutionPagedQuery() {
        // Regression: prior implementation had no executionId-scoped paginated query, so the right
        // side panel hit the un-paginated /messages endpoint (loads all rows + Java subList 200).
        Message message = new Message(Message.MessageRole.ASSISTANT, "scoped");
        message.setConversation(new Conversation());
        Page<Message> page = new PageImpl<>(List.of(message), PageRequest.of(0, 10), 1);
        when(messageRepository.findByConversationIdAndExecutionIdOrderByCreatedAtDesc(
                eq("conv-id"), eq("exec-42"), any()))
                .thenReturn(page);

        Page<MessageDto> result = messageService.getMessagesByConversationId("conv-id", 0, 10, "exec-42");

        assertThat(result.getTotalElements()).isEqualTo(1);
        // The un-scoped repo method must NOT be touched - that would re-introduce the bug.
        verify(messageRepository, org.mockito.Mockito.never())
                .findByConversationIdOrderByCreatedAtDesc(eq("conv-id"), any());
    }

    @Test
    void getMessagesPagedWithLatestResolvesExecutionIdBeforeQuerying() {
        when(messageRepository.findLatestExecutionIds(eq("conv-id"), any()))
                .thenReturn(List.of("exec-latest"));
        Message message = new Message(Message.MessageRole.ASSISTANT, "latest-scoped");
        message.setConversation(new Conversation());
        Page<Message> page = new PageImpl<>(List.of(message), PageRequest.of(0, 10), 1);
        when(messageRepository.findByConversationIdAndExecutionIdOrderByCreatedAtDesc(
                eq("conv-id"), eq("exec-latest"), any()))
                .thenReturn(page);

        Page<MessageDto> result = messageService.getMessagesByConversationId("conv-id", 0, 10, "latest");

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getMessagesPagedWithLatestFallsBackToUnscopedWhenNoExecutionExists() {
        when(messageRepository.findLatestExecutionIds(eq("conv-id"), any()))
                .thenReturn(List.of());
        Message message = new Message(Message.MessageRole.USER, "fallback");
        message.setConversation(new Conversation());
        Page<Message> page = new PageImpl<>(List.of(message), PageRequest.of(0, 10), 1);
        when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq("conv-id"), any()))
                .thenReturn(page);

        Page<MessageDto> result = messageService.getMessagesByConversationId("conv-id", 0, 10, "latest");

        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    private MessageDto buildDto() {
        MessageDto dto = new MessageDto();
        dto.setRole("user");
        dto.setContent("hello");
        dto.setModel("model");
        dto.setTimestamp("ts");
        dto.setToolCalls("[]");
        return dto;
    }

    /**
     * Regression tests for the cross-workspace message-read leak fixed on
     * 2026-05-18 via {@code ScopeGuard.isInStrictScope}. Pre-fix,
     * {@code isConversationInScope} used an owner-OR-org predicate so a user
     * currently in OrgA workspace could still load the messages / mutate
     * feedback / mutate toolCalls on a conversation owned by them but tagged
     * with a different scope (their personal workspace, org=NULL). Strict
     * isolation: org workspace must match the row's organization_id; personal
     * workspace must match userId AND org=NULL.
     */
    @Nested
    @DisplayName("Strict-isolation scope (2026-05-18 ScopeGuard alignment)")
    class StrictIsolationScope {

        private Conversation personalConv() {
            Conversation conv = new Conversation("user-A", "title", "model", "provider");
            conv.setId("conv-personal");
            conv.setOrganizationId(null);
            return conv;
        }

        private Conversation orgConv(String orgId) {
            Conversation conv = new Conversation("user-A", "title", "model", "provider");
            conv.setId("conv-org");
            conv.setOrganizationId(orgId);
            return conv;
        }

        @Test
        @DisplayName("getMessagesPage returns empty when caller in OrgA but conv is personal (org=NULL)")
        void getMessagesPageBlocksPersonalConvWhenCallerInOrg() {
            // Pre-fix: isConversationInScope returned true because userId matched the
            // conv owner, leaking personal messages to a caller currently viewing OrgA.
            when(conversationRepository.findById("conv-personal"))
                    .thenReturn(Optional.of(personalConv()));

            Page<MessageDto> result = messageService.getMessagesByConversationIdScoped(
                    "conv-personal", 0, 20, null, "user-A", "org-A");

            assertThat(result.getTotalElements()).isZero();
            verify(messageRepository, never())
                    .findByConversationIdOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("getMessagesPage returns empty when caller in OrgA but conv tagged with OrgB")
        void getMessagesPageBlocksConvFromDifferentOrg() {
            when(conversationRepository.findById("conv-org"))
                    .thenReturn(Optional.of(orgConv("org-B")));

            Page<MessageDto> result = messageService.getMessagesByConversationIdScoped(
                    "conv-org", 0, 20, null, "user-A", "org-A");

            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("getMessagesPage returns empty when caller in personal scope but conv tagged with an org")
        void getMessagesPageBlocksOrgConvWhenCallerInPersonalScope() {
            // Symmetric to BlocksPersonalConvWhenCallerInOrg: even when the
            // user owns the conv (userId matches), strict-isolation must
            // reject a conv tagged with an org while the caller's active
            // workspace is personal (orgId null). This guards the
            // `conv.getOrganizationId() == null` clause in the personal-scope
            // predicate against a future regression that would re-introduce
            // the OR-pattern.
            when(conversationRepository.findById("conv-org"))
                    .thenReturn(Optional.of(orgConv("org-B")));

            Page<MessageDto> result = messageService.getMessagesByConversationIdScoped(
                    "conv-org", 0, 20, null, "user-A", null);

            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("getMessagesPage returns page when caller in personal scope and conv matches")
        void getMessagesPageReturnsPageInPersonalScope() {
            Conversation conv = personalConv();
            when(conversationRepository.findById("conv-personal"))
                    .thenReturn(Optional.of(conv));
            Message message = new Message(Message.MessageRole.USER, "hi");
            message.setConversation(conv);
            Page<Message> page = new PageImpl<>(List.of(message), PageRequest.of(0, 20), 1);
            when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq("conv-personal"), any()))
                    .thenReturn(page);

            Page<MessageDto> result = messageService.getMessagesByConversationIdScoped(
                    "conv-personal", 0, 20, null, "user-A", null);

            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("getMessagesPage returns page when caller in OrgA and conv tagged with OrgA")
        void getMessagesPageReturnsPageInOrgScope() {
            Conversation conv = orgConv("org-A");
            when(conversationRepository.findById("conv-org"))
                    .thenReturn(Optional.of(conv));
            Message message = new Message(Message.MessageRole.ASSISTANT, "hello");
            message.setConversation(conv);
            Page<Message> page = new PageImpl<>(List.of(message), PageRequest.of(0, 20), 1);
            when(messageRepository.findByConversationIdOrderByCreatedAtDesc(eq("conv-org"), any()))
                    .thenReturn(page);

            Page<MessageDto> result = messageService.getMessagesByConversationIdScoped(
                    "conv-org", 0, 20, null, "user-A", "org-A");

            assertThat(result.getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("updateMessageFeedback rejects owner when caller is in a different active org")
        void updateMessageFeedbackBlocksPersonalConvWhenCallerInOrg() {
            Conversation conv = personalConv();
            Message message = new Message(Message.MessageRole.ASSISTANT, "x");
            message.setId("msg-1");
            message.setConversation(conv);
            when(messageRepository.findById("msg-1")).thenReturn(Optional.of(message));

            assertThatThrownBy(() ->
                    messageService.updateMessageFeedback("msg-1", 1, "user-A", "org-A"))
                    .isInstanceOf(InvalidMessageException.class);

            verify(messageRepository, never()).save(any(Message.class));
        }

        @Test
        @DisplayName("updateMessageToolCalls rejects owner when caller is in a different active org")
        void updateMessageToolCallsBlocksPersonalConvWhenCallerInOrg() {
            Conversation conv = personalConv();
            Message message = new Message(Message.MessageRole.ASSISTANT, "x");
            message.setId("msg-1");
            message.setConversation(conv);
            when(messageRepository.findById("msg-1")).thenReturn(Optional.of(message));

            assertThatThrownBy(() ->
                    messageService.updateMessageToolCalls("msg-1", "[]", "user-A", "org-A"))
                    .isInstanceOf(InvalidMessageException.class);

            verify(messageRepository, never()).save(any(Message.class));
        }
    }

    /**
     * Regression tests for {@link MessageService#persistAttemptAndError}. This method
     * is called from the sync chat 402 short-circuit (cloud + CE) to leave a typed
     * trace in the conversation when a fire was throttled. The CONTRACT is:
     *
     * <ul>
     *   <li>Each addMessage call is independent (own try/catch). First failing must
     *       NOT prevent second from running, and vice-versa.</li>
     *   <li>Persistence failures are logged at ERROR (best-effort observability).</li>
     *   <li>Blank/null conversationId short-circuits (no work, no logs).</li>
     * </ul>
     *
     * Without these tests, the prior commit's must-fix #2 (partial-state OK, was
     * single-tx rollback) can be silently reverted and no controller-level test
     * would catch it.
     */
    @Nested
    @DisplayName("persistAttemptAndError - sync 402 short-circuit helper")
    class PersistAttemptAndErrorTests {

        @Test
        @DisplayName("Happy path: both user + assistant messages persisted to the conversation")
        void happyPath() {
            Conversation conv = new Conversation("user-1", "Title", "model", "provider");
            conv.setId("conv-1");
            conv.setOrganizationId("org-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            when(messageRepository.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

            messageService.persistAttemptAndError("conv-1", "scheduled prompt", "[Error] Insufficient credits");

            // 2 saves to messageRepository (user + assistant), 2 saves to conversationRepository (updated_at).
            verify(messageRepository, org.mockito.Mockito.times(2)).save(any(Message.class));
        }

        @Test
        @DisplayName("First addMessage failing does NOT prevent the second from running (independent try/catch contract)")
        void firstFailureDoesNotBlockSecond() {
            Conversation conv = new Conversation("user-1", "Title", "model", "provider");
            conv.setId("conv-1");
            conv.setOrganizationId("org-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            // Throw on first message save (user role), succeed on second (assistant).
            when(messageRepository.save(any(Message.class)))
                    .thenThrow(new RuntimeException("DB hiccup on user message"))
                    .thenAnswer(inv -> inv.getArgument(0));

            messageService.persistAttemptAndError("conv-1", "scheduled prompt", "[Error] Insufficient credits");

            // Both saves attempted. The assistant message must persist even though the user one threw.
            verify(messageRepository, org.mockito.Mockito.times(2)).save(any(Message.class));
        }

        @Test
        @DisplayName("Second addMessage failing leaves the first one committed (partial-state OK contract)")
        void secondFailureLeavesFirstCommitted() {
            Conversation conv = new Conversation("user-1", "Title", "model", "provider");
            conv.setId("conv-1");
            conv.setOrganizationId("org-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));
            // First save succeeds, second throws.
            when(messageRepository.save(any(Message.class)))
                    .thenAnswer(inv -> inv.getArgument(0))
                    .thenThrow(new RuntimeException("DB hiccup on assistant message"));

            messageService.persistAttemptAndError("conv-1", "scheduled prompt", "[Error] Insufficient credits");

            // Both attempted; first must NOT have been rolled back by the second's failure.
            // Mockito captures both call attempts - pre-fix (single outer tx) the second's throw
            // would propagate and the test would see only 1 invocation.
            verify(messageRepository, org.mockito.Mockito.times(2)).save(any(Message.class));
        }

        @Test
        @DisplayName("Blank conversationId short-circuits - no repo calls, no exceptions")
        void blankConversationIdShortCircuits() {
            messageService.persistAttemptAndError("", "prompt", "[Error] x");
            messageService.persistAttemptAndError(null, "prompt", "[Error] x");
            messageService.persistAttemptAndError("   ", "prompt", "[Error] x");

            verify(conversationRepository, never()).findById(any());
            verify(messageRepository, never()).save(any(Message.class));
        }
    }

    /**
     * Regression tests for the message ORDERING fix (queue / stop / error relaunch reorder).
     * The frontend orders the live chat by the server-monotonic {@code createdAt} rather than the
     * client/server-mixed {@code timestamp}, but that is only skew-immune if the live
     * {@code message_added} WebSocket payload actually carries {@code createdAt}. This pins the
     * BACKEND half of that 3-layer contract: {@link MessageService#addMessage} publishes a
     * {@code message_added} event whose {@code message} object includes {@code createdAt}.
     *
     * <p>Pre-fix the payload omitted {@code createdAt} entirely, so a live-delivered message had
     * no authoritative key and the frontend fell back to {@code timestamp} (the reorder bug). Drop
     * the {@code msg.put("createdAt", ...)} line and these tests fail (the key is missing).
     */
    @Nested
    @DisplayName("publishMessageAdded - message_added WS payload carries createdAt")
    class PublishMessageAddedWsPayload {

        // Match the production (Spring-configured) ObjectMapper: JavaTime module so LocalDateTime
        // serialises, and dates as ISO-8601 strings (not numeric arrays) - the TZ-less
        // LocalDateTime form the frontend's sortMessagesByTime expects. The class-level
        // `new ObjectMapper()` cannot serialise a non-null LocalDateTime, so build a dedicated one.
        private final ObjectMapper timeAwareMapper = JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        private MessageService serviceWithTimeAwareMapper() {
            return new MessageService(conversationRepository, messageRepository,
                    messageAttachmentRepository, messageMapper, eventBus, timeAwareMapper,
                    storageBreakdownService, null);
        }

        @Test
        @DisplayName("includes the server-monotonic createdAt so a live message orders right without a reload")
        void messageAddedPayloadCarriesCreatedAt() throws Exception {
            LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 0, 0, 30);
            Conversation conversation = new Conversation("user", "title", "model", "provider");
            conversation.setId("conv-id");
            when(conversationRepository.findById("conv-id")).thenReturn(Optional.of(conversation));
            when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
                Message message = invocation.getArgument(0);
                message.setId("message-id");
                message.setConversation(conversation);
                // The value Hibernate's @CreationTimestamp would assign on persist.
                message.setCreatedAt(createdAt);
                return message;
            });

            serviceWithTimeAwareMapper().addMessage("conv-id", buildDto());

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), jsonCaptor.capture());
            String publishedJson = jsonCaptor.getValue();

            JsonNode createdAtNode = timeAwareMapper.readTree(publishedJson)
                    .path("message").path("createdAt");
            assertThat(createdAtNode.isMissingNode())
                    .as("message_added payload must contain message.createdAt")
                    .isFalse();
            assertThat(createdAtNode.isNull()).isFalse();
            assertThat(createdAtNode.asText()).isEqualTo("2026-01-01T00:00:30");
        }

        @Test
        @DisplayName("createdAt is serialised inside the message object, not the event envelope")
        void createdAtLivesUnderTheMessageObject() throws Exception {
            LocalDateTime createdAt = LocalDateTime.of(2026, 6, 23, 8, 15, 0);
            Conversation conversation = new Conversation("user", "title", "model", "provider");
            conversation.setId("conv-id");
            when(conversationRepository.findById("conv-id")).thenReturn(Optional.of(conversation));
            when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> {
                Message message = invocation.getArgument(0);
                message.setId("message-id");
                message.setConversation(conversation);
                message.setCreatedAt(createdAt);
                return message;
            });

            serviceWithTimeAwareMapper().addMessage("conv-id", buildDto());

            ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
            verify(eventBus).publish(anyString(), jsonCaptor.capture());
            JsonNode root = timeAwareMapper.readTree(jsonCaptor.getValue());

            assertThat(root.path("type").asText()).isEqualTo("message_added");
            // The frontend reads event.message.createdAt; it must not be hoisted to the envelope.
            assertThat(root.path("createdAt").isMissingNode()).isTrue();
            assertThat(root.path("message").path("createdAt").asText()).isEqualTo("2026-06-23T08:15:00");
        }
    }
}
