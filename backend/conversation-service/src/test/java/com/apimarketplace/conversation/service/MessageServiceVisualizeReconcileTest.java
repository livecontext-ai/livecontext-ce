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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wires {@link VisualizeMarkerReconciler} into the one chokepoint every agent
 * surface funnels through ({@link MessageService#addMessage}), so a mistyped
 * visualize-marker id is corrected once - fixing the rendered card (parsed from
 * persisted content) AND the agent's next-turn self-reference (history is read
 * from persisted content). Mirrors the real 2026-06-05 prod incident.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MessageService - visualize marker reconciliation at the addMessage chokepoint")
class MessageServiceVisualizeReconcileTest {

    private static final String CONV = "conv-1";
    private static final String USER = "tenant-1";
    private static final String CORRECT = "837a75d4-9d83-4037-973e-9d8e0b6db56f";
    private static final String HALLUCINATED = "837a75d4-9d83-4047-b2e9-d3c00e46eb3a";

    private static final String TOOL_CALLS_WITH_VIZ =
            "[{\"toolName\":\"workflow\",\"id\":\"toolu_1\","
            + "\"visualization\":{\"title\":\"Photo Post Publisher (via chat)\","
            + "\"type\":\"workflow\",\"id\":\"" + CORRECT + "\"}}]";

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
                conversationRepository, messageRepository, messageAttachmentRepository,
                messageMapper, eventBus, objectMapper, storageBreakdownService, compactionOrchestrator);

        Conversation c = new Conversation(USER, "title", "claude-sonnet-4-6", "anthropic");
        c.setId(CONV);
        c.setOrganizationId("org-1");
        when(conversationRepository.findById(CONV)).thenReturn(Optional.of(c));
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId("msg-id");
            return m;
        });
    }

    @Test
    @DisplayName("ASSISTANT: hallucinated marker id is corrected to the authoritative id before persistence")
    void assistant_hallucinatedMarker_isCorrectedOnSave() {
        MessageDto dto = assistant(
                "✅ Corrigé.\n\n[visualize:workflow:" + HALLUCINATED + "]", TOOL_CALLS_WITH_VIZ);

        messageService.addMessage(CONV, dto);

        Message persisted = capturePersisted();
        assertThat(persisted.getContent())
                .contains("[visualize:workflow:" + CORRECT + "]")
                .doesNotContain(HALLUCINATED);
    }

    @Test
    @DisplayName("ASSISTANT: an already-correct marker is left byte-identical")
    void assistant_correctMarker_unchanged() {
        String content = "done [visualize:workflow:" + CORRECT + "]";
        MessageDto dto = assistant(content, TOOL_CALLS_WITH_VIZ);

        messageService.addMessage(CONV, dto);

        assertThat(capturePersisted().getContent()).isEqualTo(content);
    }

    @Test
    @DisplayName("the live WebSocket push carries the corrected id too (single fix → persisted + live)")
    void assistant_correctedContent_reachesWsPublish() {
        MessageDto dto = assistant(
                "✅ Corrigé.\n\n[visualize:workflow:" + HALLUCINATED + "]", TOOL_CALLS_WITH_VIZ);

        messageService.addMessage(CONV, dto);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(eventBus).publish(eq("ws:conversation:" + CONV), payload.capture());
        assertThat(payload.getValue())
                .contains(CORRECT)
                .doesNotContain(HALLUCINATED);
    }

    @Test
    @DisplayName("USER role is never reconciled - only assistant replies carry agent-typed markers")
    void user_markerLikeText_notTouched() {
        MessageDto dto = new MessageDto();
        dto.setRole("user");
        dto.setContent("please open [visualize:workflow:" + HALLUCINATED + "]");

        messageService.addMessage(CONV, dto);

        assertThat(capturePersisted().getContent()).contains(HALLUCINATED);
    }

    private Message capturePersisted() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(captor.capture());
        return captor.getValue();
    }

    private static MessageDto assistant(String content, String toolCalls) {
        MessageDto dto = new MessageDto();
        dto.setRole("assistant");
        dto.setContent(content);
        dto.setToolCalls(toolCalls);
        dto.setModel("claude-sonnet-4-6");
        return dto;
    }
}
