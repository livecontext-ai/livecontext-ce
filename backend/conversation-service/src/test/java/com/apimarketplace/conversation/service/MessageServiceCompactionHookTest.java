package com.apimarketplace.conversation.service;

import com.apimarketplace.common.event.EventBus;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.mapper.MessageMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageAttachmentRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.ChatCompactionOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-state contract: {@link MessageService#addMessage} is the single chokepoint
 * every surface (chat / workflow-agent / standalone-agent / sub-agent) funnels
 * through via {@code POST /api/conversations/{id}/messages}. Compaction must
 * therefore be dispatched from here, guarded only by {@code role == ASSISTANT}
 * - NOT by surface type.
 *
 * <p>Pins the wiring so that agent surfaces cannot silently bypass compaction
 * the way they did pre-unification (hook was only in {@code
 * ConversationAgentService.persistRemoteResults}, i.e. chat-only path).</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageService - compaction hook fires at the addMessage chokepoint for EVERY surface")
class MessageServiceCompactionHookTest {

    private static final String CONV = "conv-42";
    private static final String USER = "tenant-42";

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageAttachmentRepository messageAttachmentRepository;
    @Mock private EventBus eventBus;
    @Mock private StorageBreakdownService storageBreakdownService;
    @Mock private ChatCompactionOrchestrator compactionOrchestrator;

    private final MessageMapper messageMapper = new MessageMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                conversationRepository,
                messageRepository,
                messageAttachmentRepository,
                messageMapper,
                eventBus,
                objectMapper,
                storageBreakdownService,
                compactionOrchestrator);

        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId("msg-id");
            return m;
        });
    }

    @Test
    @DisplayName("ASSISTANT save on pure-chat conversation (agentId=null) fires afterTurnAsync")
    void assistantSave_onChat_firesHook() {
        stubConversation(null, null, null);

        messageService.addMessage(CONV, assistantDto());

        verify(compactionOrchestrator).afterTurnAsync(
                eq(CONV), eq("anthropic"), eq("claude-sonnet-4-6"), eq(USER), eq("org-1"), any());
    }

    @Test
    @DisplayName("ASSISTANT save on standalone-agent conversation (agentId set) fires the same hook")
    void assistantSave_onStandaloneAgent_firesHook() {
        stubConversation("agent-42", null, null);

        messageService.addMessage(CONV, assistantDto());

        verify(compactionOrchestrator).afterTurnAsync(
                eq(CONV), eq("anthropic"), eq("claude-sonnet-4-6"), eq(USER), eq("org-1"), any());
    }

    @Test
    @DisplayName("ASSISTANT save on workflow-agent conversation (agentId + workflowId) fires the same hook")
    void assistantSave_onWorkflowAgent_firesHook() {
        stubConversation("agent-42", "wf-run-1", null);

        messageService.addMessage(CONV, assistantDto());

        verify(compactionOrchestrator).afterTurnAsync(
                eq(CONV), eq("anthropic"), eq("claude-sonnet-4-6"), eq(USER), eq("org-1"), any());
    }

    @Test
    @DisplayName("ASSISTANT save on sub-agent conversation (parentConversationId set) fires the same hook - independent state")
    void assistantSave_onSubAgent_firesHook() {
        stubConversation("sub-77", null, "conv-parent");

        messageService.addMessage(CONV, assistantDto());

        verify(compactionOrchestrator).afterTurnAsync(
                eq(CONV), eq("anthropic"), eq("claude-sonnet-4-6"), eq(USER), eq("org-1"), any());
    }

    @Test
    @DisplayName("USER save does NOT fire the hook - compaction runs post-assistant-turn only")
    void userSave_doesNotFireHook() {
        stubConversation(null, null, null);

        MessageDto dto = new MessageDto();
        dto.setRole("user");
        dto.setContent("hello");

        messageService.addMessage(CONV, dto);

        verify(compactionOrchestrator, never())
                .afterTurnAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("TOOL save does NOT fire the hook - only completion of an assistant turn closes the turn")
    void toolSave_doesNotFireHook() {
        stubConversation(null, null, null);

        MessageDto dto = new MessageDto();
        dto.setRole("tool");
        dto.setContent("{\"result\":\"ok\"}");
        dto.setToolCallId("call-1");

        messageService.addMessage(CONV, dto);

        verify(compactionOrchestrator, never())
                .afterTurnAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SYSTEM save does NOT fire the hook")
    void systemSave_doesNotFireHook() {
        stubConversation(null, null, null);

        MessageDto dto = new MessageDto();
        dto.setRole("system");
        dto.setContent("you are helpful");

        messageService.addMessage(CONV, dto);

        verify(compactionOrchestrator, never())
                .afterTurnAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Hook failure is swallowed - message save must not break when compaction throws")
    void hookFailure_isSwallowed() {
        stubConversation(null, null, null);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(compactionOrchestrator).afterTurnAsync(any(), any(), any(), any(), any(), any());

        // No exception should escape - the caller (agent/chat) must never see compaction errors
        messageService.addMessage(CONV, assistantDto());
    }

    // ----- helpers -----

    private void stubConversation(String agentId, String workflowId, String parentConvId) {
        Conversation c = new Conversation(USER, "title", "claude-sonnet-4-6", "anthropic");
        c.setId(CONV);
        c.setAgentId(agentId);
        c.setWorkflowId(workflowId);
        c.setParentConversationId(parentConvId);
        c.setOrganizationId("org-1");
        when(conversationRepository.findById(CONV)).thenReturn(Optional.of(c));
    }

    private static MessageDto assistantDto() {
        MessageDto dto = new MessageDto();
        dto.setRole("assistant");
        dto.setContent("hi back");
        dto.setModel("claude-sonnet-4-6");
        return dto;
    }
}
