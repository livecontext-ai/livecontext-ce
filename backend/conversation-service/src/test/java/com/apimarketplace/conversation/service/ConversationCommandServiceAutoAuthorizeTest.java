package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import com.apimarketplace.conversation.service.approval.ToolApprovalGateResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Switching auto-authorize OFF must also end the grant a card's "don't ask again" gave the turn
 * that is still running, or that turn keeps running sensitive actions without a card.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationCommandService - auto-authorize switched off mid-turn")
class ConversationCommandServiceAutoAuthorizeTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;
    @Mock private UserChatDefaultsService userChatDefaultsService;
    @Mock private ToolApprovalGateResolver toolApprovalGateResolver;

    private ConversationCommandService service;

    @BeforeEach
    void setUp() {
        service = new ConversationCommandService(conversationRepository, messageRepository,
                new ConversationMapper(), workflowContextProvider, userChatDefaultsService, null);
        ReflectionTestUtils.setField(service, "toolApprovalGateResolver", toolApprovalGateResolver);
        Conversation existing = new Conversation();
        existing.setId("conv-1");
        existing.setChatConfig(new HashMap<>(Map.of("autoAuthorizeTools", true)));
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(existing));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static ConversationDto withChatConfig(Map<String, Object> chatConfig) {
        ConversationDto dto = new ConversationDto();
        dto.setChatConfig(chatConfig == null ? null : new HashMap<>(chatConfig));
        return dto;
    }

    @Test
    @DisplayName("toggle switched off: the running-turn grant is deleted")
    void switchingOffClearsTheRunningTurnGrant() {
        service.updateConversation("conv-1", withChatConfig(Map.of("autoAuthorizeTools", false)));

        verify(toolApprovalGateResolver).clearConversationWideForRunningTurn("conv-1");
    }

    @Test
    @DisplayName("a config write without the key (= off, the PUT replaces chatConfig) also deletes it")
    void configWithoutTheKeyClearsTheGrant() {
        service.updateConversation("conv-1", withChatConfig(Map.of("temperature", 0.2)));

        verify(toolApprovalGateResolver).clearConversationWideForRunningTurn("conv-1");
    }

    @Test
    @DisplayName("toggle kept on: the grant stays, so the running turn keeps skipping cards")
    void keepingItOnLeavesTheGrant() {
        service.updateConversation("conv-1", withChatConfig(Map.of("autoAuthorizeTools", true)));

        verify(toolApprovalGateResolver, never()).clearConversationWideForRunningTurn(anyString());
    }

    @Test
    @DisplayName("a write that does not touch chatConfig (a rename) leaves the grant alone")
    void renameLeavesTheGrant() {
        ConversationDto rename = new ConversationDto();
        rename.setTitle("Renamed");

        service.updateConversation("conv-1", rename);

        verify(toolApprovalGateResolver, never()).clearConversationWideForRunningTurn(anyString());
    }

    @Test
    @DisplayName("without a resolver (no Redis) the update still succeeds")
    void noResolverIsTolerated() {
        ReflectionTestUtils.setField(service, "toolApprovalGateResolver", null);

        assertThatCode(() -> service.updateConversation("conv-1",
                withChatConfig(Map.of("autoAuthorizeTools", false)))).doesNotThrowAnyException();
    }
}
