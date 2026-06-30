package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the agent-bound conversation paths of {@link ConversationCommandService}:
 * the idempotent find-or-create ({@code createAgentConversation}) and the workspace-scoped
 * cascade soft-delete ({@code deleteConversationsByAgentId}).
 *
 * <p>These complement {@link ConversationCommandServiceParentTest} (which constructs the
 * service with {@code self == null} and therefore cannot exercise {@code createAgentConversation},
 * whose insert path unconditionally goes through the {@code self} proxy to apply
 * {@code REQUIRES_NEW}). Here {@code self} is a mock so the proxy seam is observable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationCommandService - agent conversations (find-or-create + cascade delete)")
class ConversationCommandServiceAgentTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;
    /** Stands in for the Spring proxy self-reference so REQUIRES_NEW dispatch is observable. */
    @Mock private ConversationCommandService self;

    private final ConversationMapper conversationMapper = new ConversationMapper();
    private ConversationCommandService service;

    private static final String USER = "user-1";
    private static final String ORG = "org-1";
    private static final String AGENT = "agent-1";

    @BeforeEach
    void setUp() {
        service = new ConversationCommandService(
            conversationRepository, messageRepository, conversationMapper, workflowContextProvider, self);
    }

    private Conversation agentConv(String id, String userId, String orgId, boolean active) {
        Conversation c = new Conversation(userId, "Title", "gpt-4", "openai");
        c.setId(id);
        c.setAgentId(AGENT);
        c.setOrganizationId(orgId);
        c.setActive(active);
        return c;
    }

    // ====================================================================
    // createAgentConversation - idempotent find-or-create (G1)
    // ====================================================================

    @Nested
    @DisplayName("createAgentConversation - idempotency")
    class FindOrCreate {

        @Test
        @DisplayName("reuses the existing active conversation without inserting a second one")
        void reusesExistingActiveConversation() {
            Conversation existing = agentConv("conv-existing", USER, ORG, true);
            when(conversationRepository
                    .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT))
                    .thenReturn(List.of(existing));

            ConversationDto result = service.createAgentConversation(USER, ORG, AGENT, "gpt-4", "openai", "ignored");

            assertThat(result.getId()).isEqualTo("conv-existing");
            // The whole point of idempotency: no insert, no proxy hop, no title fetch.
            verify(self, never()).createConversationInNewTransaction(any());
            verify(conversationRepository, never()).save(any());
            verifyNoInteractions(workflowContextProvider);
        }

        @Test
        @DisplayName("returns the OLDEST row when legacy duplicates exist (stable id across retries)")
        void returnsOldestOfDuplicates() {
            // The repository finder is OrderByCreatedAtAsc, so get(0) is the oldest.
            Conversation oldest = agentConv("conv-old", USER, ORG, true);
            Conversation newer = agentConv("conv-new", USER, ORG, true);
            when(conversationRepository
                    .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT))
                    .thenReturn(List.of(oldest, newer));

            ConversationDto result = service.createAgentConversation(USER, ORG, AGENT, null, null, null);

            assertThat(result.getId()).isEqualTo("conv-old");
            verify(self, never()).createConversationInNewTransaction(any());
        }

        @Test
        @DisplayName("creates through the REQUIRES_NEW proxy when none exists, resolving the title from the agent name")
        void createsWhenNoneExists() {
            when(conversationRepository
                    .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT))
                    .thenReturn(List.of());
            when(workflowContextProvider.getAgentName(AGENT, USER)).thenReturn("Sales Agent");
            ConversationDto created = new ConversationDto();
            created.setId("conv-new");
            when(self.createConversationInNewTransaction(any())).thenReturn(created);

            ConversationDto result = service.createAgentConversation(USER, ORG, AGENT, "gpt-4", "openai", null);

            assertThat(result.getId()).isEqualTo("conv-new");
            ArgumentCaptor<ConversationDto> captor = ArgumentCaptor.forClass(ConversationDto.class);
            verify(self).createConversationInNewTransaction(captor.capture());
            ConversationDto inserted = captor.getValue();
            assertThat(inserted.getAgentId()).isEqualTo(AGENT);
            assertThat(inserted.getOrganizationId()).isEqualTo(ORG);
            assertThat(inserted.getUserId()).isEqualTo(USER);
            assertThat(inserted.getActive()).isTrue();
            assertThat(inserted.getTitle()).isEqualTo("Sales Agent");
        }

        @Test
        @DisplayName("falls back to 'Agent Chat' when no title is provided and the agent name is unknown")
        void fallsBackToDefaultTitle() {
            when(conversationRepository
                    .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT))
                    .thenReturn(List.of());
            when(workflowContextProvider.getAgentName(AGENT, USER)).thenReturn(null);
            when(self.createConversationInNewTransaction(any())).thenReturn(new ConversationDto());

            service.createAgentConversation(USER, ORG, AGENT, null, null, "   ");

            ArgumentCaptor<ConversationDto> captor = ArgumentCaptor.forClass(ConversationDto.class);
            verify(self).createConversationInNewTransaction(captor.capture());
            assertThat(captor.getValue().getTitle()).isEqualTo("Agent Chat");
        }

        @Test
        @DisplayName("rejects a missing organizationId (post-V261 strict isolation)")
        void rejectsMissingOrg() {
            assertThatThrownBy(() -> service.createAgentConversation(USER, null, AGENT, null, null, "t"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("organizationId required");
            verifyNoInteractions(conversationRepository, self);
        }
    }

    // ====================================================================
    // createAgentConversation - concurrent race convergence (G2)
    // ====================================================================

    @Nested
    @DisplayName("createAgentConversation - race-loser convergence")
    class RaceConvergence {

        @Test
        @DisplayName("returns the race winner when the insert hits the partial unique index")
        void returnsRaceWinnerOnUniqueViolation() {
            Conversation winner = agentConv("conv-winner", USER, ORG, true);
            // Pre-check empty (we think we're first); re-fetch after the violation finds the winner.
            when(conversationRepository
                    .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT))
                    .thenReturn(List.of(), List.of(winner));
            when(self.createConversationInNewTransaction(any()))
                    .thenThrow(new DataIntegrityViolationException("uq_conversations_primary_agent"));

            ConversationDto result = service.createAgentConversation(USER, ORG, AGENT, "gpt-4", "openai", "My Chat");

            assertThat(result.getId()).isEqualTo("conv-winner");
            verify(self).createConversationInNewTransaction(any());
        }

        @Test
        @DisplayName("rethrows the integrity violation when the re-fetch finds no winner (genuine constraint failure)")
        void rethrowsWhenRefetchEmpty() {
            when(conversationRepository
                    .findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(ORG, AGENT))
                    .thenReturn(List.of(), List.of());
            when(self.createConversationInNewTransaction(any()))
                    .thenThrow(new DataIntegrityViolationException("some other constraint"));

            assertThatThrownBy(() -> service.createAgentConversation(USER, ORG, AGENT, "gpt-4", "openai", "My Chat"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ====================================================================
    // deleteConversationsByAgentId - workspace-scoped cascade (G3/G4)
    // ====================================================================

    @Nested
    @DisplayName("deleteConversationsByAgentId - strict-scope cascade")
    class CascadeDelete {

        @Test
        @DisplayName("deletes a personal-scope row when orgId is null")
        void deletesPersonalRowWhenOrgNull() {
            Conversation personal = agentConv("conv-personal", USER, null, true);
            when(conversationRepository.findByAgentIdAndUserId(AGENT, USER)).thenReturn(List.of(personal));

            int count = service.deleteConversationsByAgentId(AGENT, USER, null);

            assertThat(count).isEqualTo(1);
            assertThat(personal.getActive()).isFalse();
            verify(conversationRepository).save(personal);
        }

        @Test
        @DisplayName("SKIPS an org-tagged row when orgId is null - the orphan-bug shape the AgentService fix avoids")
        void skipsOrgRowWhenOrgNull() {
            // This is exactly what happened when AgentService called the 2-arg cascade
            // (orgId defaulted to null): the org-tagged conversation survived the delete.
            Conversation orgRow = agentConv("conv-org", USER, ORG, true);
            when(conversationRepository.findByAgentIdAndUserId(AGENT, USER)).thenReturn(List.of(orgRow));

            int count = service.deleteConversationsByAgentId(AGENT, USER, null);

            assertThat(count).isZero();
            assertThat(orgRow.getActive()).isTrue();
            verify(conversationRepository, never()).save(any());
        }

        @Test
        @DisplayName("deletes the org-tagged row when the caller's org matches")
        void deletesOrgRowWhenOrgMatches() {
            Conversation orgRow = agentConv("conv-org", USER, ORG, true);
            when(conversationRepository.findByAgentIdAndUserId(AGENT, USER)).thenReturn(List.of(orgRow));

            int count = service.deleteConversationsByAgentId(AGENT, USER, ORG);

            assertThat(count).isEqualTo(1);
            assertThat(orgRow.getActive()).isFalse();
            verify(conversationRepository).save(orgRow);
        }

        @Test
        @DisplayName("skips a row tagged with a different org (no cross-workspace cascade)")
        void skipsCrossOrgRow() {
            Conversation otherOrg = agentConv("conv-other", USER, "org-2", true);
            when(conversationRepository.findByAgentIdAndUserId(AGENT, USER)).thenReturn(List.of(otherOrg));

            int count = service.deleteConversationsByAgentId(AGENT, USER, ORG);

            assertThat(count).isZero();
            assertThat(otherOrg.getActive()).isTrue();
            verify(conversationRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips already-inactive rows")
        void skipsInactiveRows() {
            Conversation inactive = agentConv("conv-inactive", USER, ORG, false);
            when(conversationRepository.findByAgentIdAndUserId(AGENT, USER)).thenReturn(List.of(inactive));

            int count = service.deleteConversationsByAgentId(AGENT, USER, ORG);

            assertThat(count).isZero();
            verify(conversationRepository, never()).save(any());
        }

        @Test
        @DisplayName("counts only the rows in the caller's active workspace across a mixed set")
        void countsOnlyMatchingRows() {
            Conversation inOrg = agentConv("conv-in", USER, ORG, true);
            Conversation otherOrg = agentConv("conv-other", USER, "org-2", true);
            Conversation inactive = agentConv("conv-inactive", USER, ORG, false);
            when(conversationRepository.findByAgentIdAndUserId(AGENT, USER))
                    .thenReturn(List.of(inOrg, otherOrg, inactive));

            int count = service.deleteConversationsByAgentId(AGENT, USER, ORG);

            assertThat(count).isEqualTo(1);
            assertThat(inOrg.getActive()).isFalse();
            assertThat(otherOrg.getActive()).isTrue();
            verify(conversationRepository).save(inOrg);
        }
    }
}
