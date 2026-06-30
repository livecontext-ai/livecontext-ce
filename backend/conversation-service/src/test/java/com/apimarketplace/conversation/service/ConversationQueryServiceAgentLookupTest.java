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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConversationQueryService#findByUserIdAndAgentId} - the read side of
 * "one agent = one conversation per workspace" used by {@code GET /conversations/agent/{id}}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationQueryService - agent conversation lookup")
class ConversationQueryServiceAgentLookupTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;

    private final ConversationMapper conversationMapper = new ConversationMapper();
    private ConversationQueryService service;

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final String AGENT = "agent-1";

    @BeforeEach
    void setUp() {
        service = new ConversationQueryService(
            conversationRepository, messageRepository, conversationMapper, workflowContextProvider);
    }

    private Conversation agentConv(String id) {
        Conversation c = new Conversation(USER, "Stale stored title", "gpt-4", "openai");
        c.setId(id);
        c.setAgentId(AGENT);
        c.setOrganizationId(ORG);
        c.setActive(true);
        return c;
    }

    @Test
    @DisplayName("returns empty when the agent has no conversation in the workspace")
    void returnsEmptyWhenAbsent() {
        when(conversationRepository
                .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT))
                .thenReturn(List.of());

        Optional<ConversationDto> result = service.findByUserIdAndAgentId(USER, ORG, AGENT);

        assertThat(result).isEmpty();
        // No title enrichment when there's nothing to return.
        verifyNoInteractions(workflowContextProvider);
    }

    @Test
    @DisplayName("returns the oldest row, retitled with the live agent name")
    void returnsOldestEnrichedWithAgentName() {
        Conversation oldest = agentConv("conv-old");
        Conversation newer = agentConv("conv-new");
        when(conversationRepository
                .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT))
                .thenReturn(List.of(oldest, newer));
        when(workflowContextProvider.getAgentName(AGENT, USER)).thenReturn("Sales Agent");

        Optional<ConversationDto> result = service.findByUserIdAndAgentId(USER, ORG, AGENT);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo("conv-old");
        // Title is the dynamic agent name, not the stale stored title.
        assertThat(result.get().getTitle()).isEqualTo("Sales Agent");
    }

    @Test
    @DisplayName("rejects a missing organizationId (post-V261 strict isolation)")
    void rejectsMissingOrg() {
        assertThatThrownBy(() -> service.findByUserIdAndAgentId(USER, null, AGENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId required");
        verifyNoInteractions(conversationRepository);
    }
}
