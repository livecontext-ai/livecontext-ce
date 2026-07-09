package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the bug "conversations initiated from a workflow don't inherit the
 * user's Preferences chat defaults".
 *
 * <p>A workflow-assistant conversation ({@code agentId == null}, {@code workflowId} set) is
 * created by {@link ConversationCommandService#createWorkflowConversation} and then chats via
 * {@code POST /v3/chat}, where {@code AgentContextBuilder} re-reads the stored {@code chat_config}
 * on every turn. Before the fix the create path never set {@code chat_config}, so the row started
 * null and inherited none of the Preferences the user set (V312 {@code user_chat_defaults}),
 * unlike a conversation created from the message composer.
 *
 * <p>{@code self} is null here so {@code createWorkflowConversation} runs {@code persistConversation}
 * inline (the same seam {@link ConversationCommandServiceParentTest} uses), letting the mapped
 * entity's {@code chat_config} be captured at {@code repository.save}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationCommandService - workflow conversation seeds the user's chat defaults")
class ConversationCommandServiceWorkflowDefaultsTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;
    @Mock private UserChatDefaultsService userChatDefaultsService;

    private final ConversationMapper conversationMapper = new ConversationMapper();
    private ConversationCommandService service;

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final String WORKFLOW = "wf-1";

    @BeforeEach
    void setUp() {
        service = new ConversationCommandService(
                conversationRepository, messageRepository, conversationMapper, workflowContextProvider,
                userChatDefaultsService, null);
    }

    @Test
    @DisplayName("a new workflow conversation persists the chat_config seeded from user_chat_defaults")
    void newWorkflowConversationInheritsUserDefaults() {
        when(conversationRepository.findByOrganizationIdStrictAndWorkflowIdAndActiveTrue(ORG, WORKFLOW))
                .thenReturn(Optional.empty());
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("webSearch", false);
        defaults.put("systemPrompt", "Be concise.");
        when(userChatDefaultsService.seedNewConversationConfig(eq(USER), eq(ORG), isNull()))
                .thenReturn(defaults);
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            c.setId("conv-new");
            return c;
        });

        ConversationDto result = service.createWorkflowConversation(USER, ORG, WORKFLOW, "gpt-4", "openai", "My Workflow");

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        assertThat(captor.getValue().getChatConfig())
                .containsEntry("webSearch", false)
                .containsEntry("systemPrompt", "Be concise.");
        assertThat(result.getWorkflowId()).isEqualTo(WORKFLOW);
        // The user's chat defaults never carry model/provider (not whitelisted); those stay
        // whatever the caller passed.
        assertThat(result.getModel()).isEqualTo("gpt-4");
    }

    @Test
    @DisplayName("with no stored defaults the chat_config stays null (unchanged behaviour, column unset)")
    void newWorkflowConversationWithoutDefaultsLeavesChatConfigNull() {
        when(conversationRepository.findByOrganizationIdStrictAndWorkflowIdAndActiveTrue(ORG, WORKFLOW))
                .thenReturn(Optional.empty());
        when(userChatDefaultsService.seedNewConversationConfig(eq(USER), eq(ORG), isNull()))
                .thenReturn(null);
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(inv -> {
            Conversation c = inv.getArgument(0);
            c.setId("conv-new");
            return c;
        });

        service.createWorkflowConversation(USER, ORG, WORKFLOW, "gpt-4", "openai", "My Workflow");

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        assertThat(captor.getValue().getChatConfig()).isNull();
    }

    @Test
    @DisplayName("reusing an existing active workflow conversation neither re-seeds nor re-persists it")
    void reusedWorkflowConversationIsNotReseeded() {
        Conversation existing = new Conversation(USER, "My Workflow", "gpt-4", "openai");
        existing.setId("conv-existing");
        existing.setWorkflowId(WORKFLOW);
        existing.setOrganizationId(ORG);
        existing.setActive(true);
        when(conversationRepository.findByOrganizationIdStrictAndWorkflowIdAndActiveTrue(ORG, WORKFLOW))
                .thenReturn(Optional.of(existing));

        ConversationDto result = service.createWorkflowConversation(USER, ORG, WORKFLOW, "gpt-4", "openai", "My Workflow");

        assertThat(result.getId()).isEqualTo("conv-existing");
        // The seed only applies to a brand-new row: an existing conversation keeps whatever
        // config it already has, and we must not touch user_chat_defaults or the repository.
        verify(userChatDefaultsService, never()).seedNewConversationConfig(any(), any(), any());
        verify(conversationRepository, never()).save(any(Conversation.class));
    }
}
