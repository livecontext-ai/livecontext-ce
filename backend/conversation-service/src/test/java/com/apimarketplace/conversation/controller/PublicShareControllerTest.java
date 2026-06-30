package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.dto.PublicMessageDto;
import com.apimarketplace.conversation.dto.PublicMessagePageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.service.ConversationSharingService;
import com.apimarketplace.conversation.service.MessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicShareController")
class PublicShareControllerTest {

    @Mock
    private ConversationSharingService sharingService;

    @Mock
    private MessageService messageService;

    private final ConversationMapper conversationMapper = new ConversationMapper();

    private PublicShareController controller;

    private static final String TOKEN = "cs_abc123def456";
    private static final String CONV_ID = "conv-001";
    private static final String USER_ID = "user|123";

    @BeforeEach
    void setUp() {
        controller = new PublicShareController(sharingService, conversationMapper, messageService);
    }

    // ──────────────── getSharedConversation ────────────────

    @Nested
    @DisplayName("GET /api/shared/c/{token}")
    class GetSharedConversation {

        @Test
        @DisplayName("returns conversation with sensitive fields stripped")
        void returnsConversationStripped() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            ResponseEntity<?> response = controller.getSharedConversation(TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ConversationDto dto = (ConversationDto) response.getBody();
            assertThat(dto).isNotNull();
            assertThat(dto.getTitle()).isEqualTo("Test Conversation");
            assertThat(dto.getShareMode()).isEqualTo("read");
            // Sensitive fields must be stripped - BOTH the legacy single pending_action AND
            // the pending_actions list (each can carry the user's services/rule/tool args).
            assertThat(dto.getUserId()).isNull();
            assertThat(dto.getPendingAction()).isNull();
            assertThat(dto.getPendingActions()).isNull();
            assertThat(dto.getApprovedServices()).isNull();
            // CHAT-SHARE-LEAK regression: the owner's private chatConfig (systemPrompt +
            // tool/turn config) and internal scope/resource ids must NOT reach an
            // anonymous share viewer.
            assertThat(dto.getChatConfig()).isNull();
            assertThat(dto.getOrganizationId()).isNull();
            assertThat(dto.getAgentId()).isNull();
            assertThat(dto.getWorkflowId()).isNull();
            assertThat(dto.getParentConversationId()).isNull();
        }

        @Test
        @DisplayName("CHAT-SHARE-LEAK: never leaks the owner's private chatConfig (systemPrompt) to an anonymous viewer, but keeps the public-display fields")
        void doesNotLeakChatConfig() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            ResponseEntity<?> response = controller.getSharedConversation(TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ConversationDto dto = (ConversationDto) response.getBody();
            assertThat(dto).isNotNull();
            // Pre-fix the mapper copied chatConfig straight through and it was returned
            // verbatim - the systemPrompt below would be visible to anyone with the link.
            assertThat(dto.getChatConfig()).isNull();
            // The strip must not over-reach: the fields a public reader legitimately
            // needs to render the shared transcript still survive.
            assertThat(dto.getTitle()).isEqualTo("Test Conversation");
            assertThat(dto.getShareMode()).isEqualTo("read");
            assertThat(dto.getMemoryEnabled()).isTrue();
        }

        @Test
        @DisplayName("returns 404 when token not found")
        void returns404WhenNotFound() {
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getSharedConversation(TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 404 when share mode is off")
        void returns404WhenOff() {
            Conversation conv = buildSharedConversation("off", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            ResponseEntity<?> response = controller.getSharedConversation(TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns conversation in readwrite mode")
        void returnsReadWriteConversation() {
            Conversation conv = buildSharedConversation("readwrite", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            ResponseEntity<?> response = controller.getSharedConversation(TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            ConversationDto dto = (ConversationDto) response.getBody();
            assertThat(dto.getShareMode()).isEqualTo("readwrite");
        }
    }

    // ──────────────── getSharedMessages ────────────────

    @Nested
    @DisplayName("GET /api/shared/c/{token}/messages")
    class GetSharedMessages {

        @Test
        @DisplayName("page=0 returns the latest N messages re-sorted ASC for display")
        void pageZeroReturnsLatestNMessagesChronological() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            // Service returns DESC (newest first). Three messages on a single page.
            List<MessageDto> descPage = List.of(
                    msgWithCreatedAt("m3", "assistant", "newest", LocalDateTime.of(2026, 1, 1, 12, 0)),
                    msgWithCreatedAt("m2", "user", "middle", LocalDateTime.of(2026, 1, 1, 11, 0)),
                    msgWithCreatedAt("m1", "user", "oldest in window", LocalDateTime.of(2026, 1, 1, 10, 0)));
            when(messageService.getMessagesByConversationId(eq(CONV_ID), eq(0), eq(20)))
                    .thenReturn(new PageImpl<>(descPage, PageRequest.of(0, 20), 3));

            ResponseEntity<?> response = controller.getSharedMessages(TOKEN, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            PublicMessagePageDto body = (PublicMessagePageDto) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.items()).extracting(PublicMessageDto::id)
                    .containsExactly("m1", "m2", "m3");
            assertThat(body.hasMore()).isFalse();
        }

        @Test
        @DisplayName("page=1 returns the next-older slice and reports hasMore correctly")
        void pageOneReturnsNextOlderSlice() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            List<MessageDto> descPageOne = List.of(
                    msgWithCreatedAt("m_old2", "user", "old2", LocalDateTime.of(2026, 1, 1, 9, 0)),
                    msgWithCreatedAt("m_old1", "user", "old1", LocalDateTime.of(2026, 1, 1, 8, 0)));
            // Total = 5 across 3 pages (size 2): page=1 means there is still page=2 to fetch.
            when(messageService.getMessagesByConversationId(eq(CONV_ID), eq(1), eq(2)))
                    .thenReturn(new PageImpl<>(descPageOne, PageRequest.of(1, 2), 5));

            ResponseEntity<?> response = controller.getSharedMessages(TOKEN, 1, 2);

            PublicMessagePageDto body = (PublicMessagePageDto) response.getBody();
            assertThat(body.items()).extracting(PublicMessageDto::id)
                    .containsExactly("m_old1", "m_old2");
            assertThat(body.hasMore()).isTrue();
        }

        @Test
        @DisplayName("hasMore is false on the last page")
        void hasMoreFalseOnLastPage() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            List<MessageDto> last = List.of(
                    msgWithCreatedAt("m_first", "user", "first", LocalDateTime.of(2026, 1, 1, 7, 0)));
            when(messageService.getMessagesByConversationId(eq(CONV_ID), eq(2), eq(2)))
                    .thenReturn(new PageImpl<>(last, PageRequest.of(2, 2), 5));

            ResponseEntity<?> response = controller.getSharedMessages(TOKEN, 2, 2);

            PublicMessagePageDto body = (PublicMessagePageDto) response.getBody();
            assertThat(body.hasMore()).isFalse();
        }

        @Test
        @DisplayName("size > 50 is clamped to 50 (defends public endpoint against scraping)")
        void sizeCappedAt50WhenAbusivelyLarge() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));
            when(messageService.getMessagesByConversationId(eq(CONV_ID), eq(0), eq(50)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

            controller.getSharedMessages(TOKEN, 0, 10000);

            verify(messageService).getMessagesByConversationId(eq(CONV_ID), eq(0), eq(50));
        }

        @Test
        @DisplayName("size=0 is clamped to 1 instead of being rejected")
        void sizeZeroClampedToOne() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));
            when(messageService.getMessagesByConversationId(eq(CONV_ID), eq(0), eq(1)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

            controller.getSharedMessages(TOKEN, 0, 0);

            verify(messageService).getMessagesByConversationId(eq(CONV_ID), eq(0), eq(1));
        }

        @Test
        @DisplayName("negative size is clamped to 1 (defensive)")
        void sizeNegativeClampedToOne() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));
            when(messageService.getMessagesByConversationId(eq(CONV_ID), eq(0), eq(1)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

            controller.getSharedMessages(TOKEN, 0, -42);

            verify(messageService).getMessagesByConversationId(eq(CONV_ID), eq(0), eq(1));
        }

        @Test
        @DisplayName("negative page returns 400 (invalid intent, not just bad input)")
        void negativePageReturnsBadRequest() {
            ResponseEntity<?> response = controller.getSharedMessages(TOKEN, -1, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(messageService, never()).getMessagesByConversationId(any(), anyInt(), anyInt());
            verify(sharingService, never()).findByShareToken(any());
        }

        @Test
        @DisplayName("returns 404 when share mode is off (disabledShareReturns404)")
        void disabledShareReturns404() {
            Conversation conv = buildSharedConversation("off", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            ResponseEntity<?> response = controller.getSharedMessages(TOKEN, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(messageService, never()).getMessagesByConversationId(any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("returns 404 for non-existent token")
        void nonExistentTokenReturns404() {
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getSharedMessages(TOKEN, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns empty page (not 404) when memory is disabled")
        void memoryDisabledReturnsEmptyPage() {
            Conversation conv = buildSharedConversation("read", false);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            ResponseEntity<?> response = controller.getSharedMessages(TOKEN, 0, 20);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            PublicMessagePageDto body = (PublicMessagePageDto) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.items()).isEmpty();
            assertThat(body.hasMore()).isFalse();
            verify(messageService, never()).getMessagesByConversationId(any(), anyInt(), anyInt());
        }
    }

    // ──────────────── PublicMessageDto contract ────────────────

    @Nested
    @DisplayName("PublicMessageDto record (allowlist)")
    class PublicMessageDtoContract {

        @Test
        @DisplayName("exposes ONLY id, role, content, createdAt - adding a field to MessageDto cannot leak")
        void publicMessageDtoExposesOnlyIdRoleContentCreatedAt() {
            // Reflection-based: catches accidental record-component additions even if no
            // serializer change is made. JSON-only assertions would miss this.
            Set<String> componentNames = Arrays.stream(PublicMessageDto.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .collect(Collectors.toSet());
            assertThat(componentNames)
                    .containsExactlyInAnyOrder("id", "role", "content", "createdAt");
        }

        @Test
        @DisplayName("from(MessageDto) does not propagate sensitive fields (agentId, executionId, model, toolCalls, ...)")
        void fromMessageDtoOmitsSensitiveFields() {
            MessageDto src = new MessageDto();
            src.setId("m-1");
            src.setRole("assistant");
            src.setContent("hi");
            src.setCreatedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
            src.setAgentId("agent-secret");
            src.setExecutionId("exec-secret");
            src.setModel("gpt-internal");
            src.setToolCalls("[{\"name\":\"redact_me\",\"args\":{\"key\":\"sk-LEAK\"}}]");
            src.setToolCallId("tc-1");
            src.setToolName("redact_me");
            src.setFeedback(1);

            PublicMessageDto dto = PublicMessageDto.from(src);

            assertThat(dto.id()).isEqualTo("m-1");
            assertThat(dto.role()).isEqualTo("assistant");
            assertThat(dto.content()).isEqualTo("hi");
            assertThat(dto.createdAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 12, 0));
            // The components above are the entire surface - any sensitive field
            // is structurally absent (verified by the reflection test as well).
        }

        @Test
        @DisplayName("null createdAt survives mapping (legacy rows do not 500 the public endpoint)")
        void nullCreatedAtSurvivesMapping() {
            MessageDto src = new MessageDto();
            src.setId("m-legacy");
            src.setRole("user");
            src.setContent("legacy");
            src.setCreatedAt(null);

            PublicMessageDto dto = PublicMessageDto.from(src);

            assertThat(dto).isNotNull();
            assertThat(dto.createdAt()).isNull();
        }

        @Test
        @DisplayName("null source returns null (no NPE on caller)")
        void nullSourceReturnsNull() {
            assertThat(PublicMessageDto.from(null)).isNull();
        }

        @RepeatedTest(20)
        @DisplayName("parallel calls do not share DTO state (8 threads × 20 reps)")
        void parallelCallsDoNotShareDtoState() throws InterruptedException {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));
            // Each thread will see a different "newest content" - if the controller
            // were mutating a shared singleton DTO, threads would observe each other's content.
            when(messageService.getMessagesByConversationId(eq(CONV_ID), anyInt(), anyInt()))
                    .thenAnswer(inv -> {
                        int p = inv.getArgument(1);
                        MessageDto m = msgWithCreatedAt("m-p" + p, "user", "content-p" + p,
                                LocalDateTime.of(2026, 1, 1, 12, 0));
                        return new PageImpl<>(List.of(m), PageRequest.of(p, 1), 1);
                    });

            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicReference<AssertionError> failure = new AtomicReference<>();
            try {
                for (int i = 0; i < threads; i++) {
                    final int page = i;
                    pool.submit(() -> {
                        try {
                            start.await();
                            ResponseEntity<?> r = controller.getSharedMessages(TOKEN, page, 1);
                            PublicMessagePageDto body = (PublicMessagePageDto) r.getBody();
                            assertThat(body.items()).hasSize(1);
                            // Each thread asked for a different page; the response must
                            // carry that page's id, never another thread's payload.
                            assertThat(body.items().get(0).id()).isEqualTo("m-p" + page);
                            assertThat(body.items().get(0).content()).isEqualTo("content-p" + page);
                        } catch (AssertionError e) {
                            failure.compareAndSet(null, e);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                if (!done.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent calls did not finish within 10s");
                }
                if (failure.get() != null) {
                    throw failure.get();
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static MessageDto msgWithCreatedAt(String id, String role, String content, LocalDateTime ts) {
        MessageDto m = new MessageDto();
        m.setId(id);
        m.setRole(role);
        m.setContent(content);
        m.setCreatedAt(ts);
        return m;
    }

    // ──────────────── addSharedMessage ────────────────

    @Nested
    @DisplayName("POST /api/shared/c/{token}/messages")
    class AddSharedMessage {

        @Test
        @DisplayName("adds message in readwrite mode")
        void addsMessageInReadWriteMode() {
            Conversation conv = buildSharedConversation("readwrite", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            MessageDto input = new MessageDto("user", "New message");
            MessageDto saved = new MessageDto("user", "New message");
            saved.setId("msg-001");
            when(messageService.addMessage(eq(CONV_ID), any(MessageDto.class))).thenReturn(saved);

            ResponseEntity<?> response = controller.addSharedMessage(TOKEN, input);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            MessageDto body = (MessageDto) response.getBody();
            assertThat(body.getId()).isEqualTo("msg-001");
        }

        @Test
        @DisplayName("returns 403 in read-only mode")
        void returns403InReadOnlyMode() {
            Conversation conv = buildSharedConversation("read", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            MessageDto input = new MessageDto("user", "New message");

            ResponseEntity<?> response = controller.addSharedMessage(TOKEN, input);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(messageService, never()).addMessage(any(), any());
        }

        @Test
        @DisplayName("returns 404 when token not found")
        void returns404WhenNotFound() {
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.addSharedMessage(TOKEN, new MessageDto("user", "test"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 404 when share mode is off")
        void returns404WhenOff() {
            Conversation conv = buildSharedConversation("off", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));

            ResponseEntity<?> response = controller.addSharedMessage(TOKEN, new MessageDto("user", "test"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("sets conversationId on the message before saving")
        void setsConversationIdOnMessage() {
            Conversation conv = buildSharedConversation("readwrite", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));
            when(messageService.addMessage(eq(CONV_ID), any())).thenAnswer(inv -> inv.getArgument(1));

            MessageDto input = new MessageDto("user", "Hello");
            controller.addSharedMessage(TOKEN, input);

            assertThat(input.getConversationId()).isEqualTo(CONV_ID);
        }

        @Test
        @DisplayName("returns 400 when the shared message payload is invalid")
        void returns400WhenSharedMessagePayloadInvalid() {
            Conversation conv = buildSharedConversation("readwrite", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));
            when(messageService.addMessage(eq(CONV_ID), any()))
                    .thenThrow(new InvalidMessageException("Content is required for user messages"));

            ResponseEntity<?> response = controller.addSharedMessage(TOKEN, new MessageDto("user", " "));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("returns 409 when the shared conversation is inactive")
        void returns409WhenSharedConversationInactive() {
            Conversation conv = buildSharedConversation("readwrite", true);
            when(sharingService.findByShareToken(TOKEN)).thenReturn(Optional.of(conv));
            when(messageService.addMessage(eq(CONV_ID), any()))
                    .thenThrow(new ConversationInactiveException(CONV_ID));

            ResponseEntity<?> response = controller.addSharedMessage(TOKEN, new MessageDto("user", "Hello"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    // ──────────────── helpers ────────────────

    private Conversation buildSharedConversation(String shareMode, boolean memoryEnabled) {
        Conversation conv = new Conversation();
        conv.setId(CONV_ID);
        conv.setUserId(USER_ID);
        conv.setTitle("Test Conversation");
        conv.setShareToken(TOKEN);
        conv.setShareMode(shareMode);
        conv.setMemoryEnabled(memoryEnabled);
        conv.setActive(true);
        // Carry sensitive pending cards so the strip in getSharedConversation is genuinely
        // exercised - without these the leak of pending_actions would go unnoticed.
        Map<String, Object> single = Map.of("waiting_for", "service_approval",
                "services", List.of(Map.of("serviceType", "gmail", "serviceName", "Gmail")));
        conv.setPendingAction(single);
        conv.setPendingActions(List.of(single,
                Map.of("waiting_for", "tool_authorization", "rule", "application:acquire",
                        "application_id", "pub-secret")));
        // Owner-private config + internal scope/resource identifiers. The mapper
        // copies all of these into the DTO, so without the strip in
        // getSharedConversation they would reach an anonymous share viewer.
        conv.setChatConfig(Map.of(
                "systemPrompt", "SECRET internal instructions - do not leak",
                "toolsMode", "all",
                "turnLimits", Map.of("maxIterations", 7)));
        conv.setOrganizationId("org-private-42");
        conv.setAgentId("agent-private-1");
        conv.setWorkflowId("wf-private-1");
        conv.setParentConversationId("conv-parent-1");
        return conv;
    }
}
