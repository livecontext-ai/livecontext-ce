package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The write gate of every entry point that appends to a caller-named conversation (chat turns,
 * stream stop, tool-result rows). Before it existed, a chat turn ran on ANY conversationId: it
 * appended the caller's message and loaded that conversation's history into the caller's agent.
 * Strict isolation: the row must be in the caller's CURRENT workspace.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationQueryService.isConversationInStrictScope")
class ConversationQueryServiceWriteAccessTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;

    private ConversationQueryService service;

    @BeforeEach
    void setUp() {
        service = new ConversationQueryService(
                conversationRepository, messageRepository, new ConversationMapper(), workflowContextProvider);
    }

    @Test
    @DisplayName("a member acting in the conversation's workspace may write, whoever created it (shared agent conversation)")
    void sameWorkspaceMayWrite() {
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation("user-1", "org-1")));

        assertThat(service.isConversationInStrictScope("conv-1", "user-1", "org-1")).isTrue();
        assertThat(service.isConversationInStrictScope("conv-1", "user-2", "org-1")).isTrue();
    }

    @Test
    @DisplayName("personal workspace: the owner of an org-less conversation may write")
    void personalOwnerMayWrite() {
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation("user-1", null)));

        assertThat(service.isConversationInStrictScope("conv-1", "user-1", null)).isTrue();
    }

    @Test
    @DisplayName("regression: another user in another workspace may not write")
    void foreignCallerRefused() {
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation("user-1", "org-1")));

        assertThat(service.isConversationInStrictScope("conv-1", "user-2", "org-2")).isFalse();
        assertThat(service.isConversationInStrictScope("conv-1", "user-2", null)).isFalse();
    }

    @Test
    @DisplayName("regression: the owner acting in ANOTHER workspace is refused (was allowed by owner-OR-org)")
    void ownerInAnotherWorkspaceRefused() {
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation("user-1", "org-1")));

        assertThat(service.isConversationInStrictScope("conv-1", "user-1", "org-2")).isFalse();
    }

    @Test
    @DisplayName("regression: a member removed from the conversation's workspace is refused (the gateway no longer resolves that org, so they arrive personal or in another org)")
    void removedMemberRefused() {
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation("user-1", "org-1")));

        assertThat(service.isConversationInStrictScope("conv-1", "user-1", null)).isFalse();
        assertThat(service.isConversationInStrictScope("conv-1", "user-1", " ")).isFalse();
    }

    @Test
    @DisplayName("personal workspace: another user's org-less conversation is refused")
    void personalForeignOwnerRefused() {
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conversation("user-1", null)));

        assertThat(service.isConversationInStrictScope("conv-1", "user-2", null)).isFalse();
    }

    @Test
    @DisplayName("an unknown conversation is refused")
    void unknownConversationRefused() {
        when(conversationRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.isConversationInStrictScope("missing", "user-1", "org-1")).isFalse();
    }

    @Test
    @DisplayName("blank conversation or user id is refused without a lookup")
    void blankIdsRefused() {
        assertThat(service.isConversationInStrictScope(null, "user-1", "org-1")).isFalse();
        assertThat(service.isConversationInStrictScope(" ", "user-1", "org-1")).isFalse();
        assertThat(service.isConversationInStrictScope("conv-1", null, "org-1")).isFalse();
        assertThat(service.isConversationInStrictScope("conv-1", "", "org-1")).isFalse();
        verifyNoInteractions(conversationRepository);
    }

    private static Conversation conversation(String userId, String organizationId) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setOrganizationId(organizationId);
        return conversation;
    }
}
