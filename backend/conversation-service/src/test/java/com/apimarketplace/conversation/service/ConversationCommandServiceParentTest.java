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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for parentConversationId support in ConversationCommandService.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationCommandService - parentConversationId")
class ConversationCommandServiceParentTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private WorkflowContextProvider workflowContextProvider;

    private final ConversationMapper conversationMapper = new ConversationMapper();
    private ConversationCommandService service;

    @BeforeEach
    void setUp() {
        service = new ConversationCommandService(
            conversationRepository, messageRepository, conversationMapper, workflowContextProvider, null);
    }

    @Nested
    @DisplayName("createConversation with parentConversationId")
    class CreateWithParentTests {

        @Test
        @DisplayName("should persist parentConversationId on entity")
        void shouldPersistParentConversationId() {
            ConversationDto dto = buildDto();
            dto.setParentConversationId("parent-conv-123");

            when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
                Conversation c = invocation.getArgument(0);
                c.setId("generated-id");
                return c;
            });

            ConversationDto result = service.createConversation(dto);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationRepository).save(captor.capture());

            assertThat(captor.getValue().getParentConversationId()).isEqualTo("parent-conv-123");
            assertThat(result.getParentConversationId()).isEqualTo("parent-conv-123");
        }

        @Test
        @DisplayName("should allow null parentConversationId")
        void shouldAllowNullParentConversationId() {
            ConversationDto dto = buildDto();
            // parentConversationId not set (null)

            when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
                Conversation c = invocation.getArgument(0);
                c.setId("generated-id");
                return c;
            });

            ConversationDto result = service.createConversation(dto);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationRepository).save(captor.capture());

            assertThat(captor.getValue().getParentConversationId()).isNull();
            assertThat(result.getParentConversationId()).isNull();
        }

        @Test
        @DisplayName("should persist agentId alongside parentConversationId")
        void shouldPersistAgentIdWithParent() {
            ConversationDto dto = buildDto();
            dto.setAgentId("agent-uuid-123");
            dto.setParentConversationId("parent-conv-456");

            when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
                Conversation c = invocation.getArgument(0);
                c.setId("generated-id");
                return c;
            });

            ConversationDto result = service.createConversation(dto);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationRepository).save(captor.capture());

            Conversation saved = captor.getValue();
            assertThat(saved.getAgentId()).isEqualTo("agent-uuid-123");
            assertThat(saved.getParentConversationId()).isEqualTo("parent-conv-456");
            assertThat(result.getAgentId()).isEqualTo("agent-uuid-123");
        }
    }

    @Nested
    @DisplayName("createConversation - memoryEnabled propagation (regression: schedule with_memory=false silently failed)")
    class MemoryEnabledPropagationTests {

        /**
         * Regression for the prod bug where agent schedules with {@code with_memory=false}
         * fired every cron tick but produced zero chats. {@code persistConversation} built
         * the entity without copying {@code memoryEnabled} from the DTO, so the entity's
         * default {@code true} silently overrode the caller's intent. The persisted row
         * then collided with the V212 partial unique index
         * {@code uq_conversations_primary_agent_per_user_workspace} (WHERE
         * {@code memory_enabled IS TRUE}) and threw 23505 → 500.
         */
        @Test
        @DisplayName("memoryEnabled=false on DTO with agentId reaches entity (would have been silently overridden to true)")
        void memoryEnabledFalseOnAgentDtoReachesEntity() {
            // isPrimaryAgentShape returns false for memoryEnabled=false, so this DTO
            // bypasses the createAgentConversation dedup funnel and exercises
            // persistConversation directly - exactly the prod schedule code path.
            ConversationDto dto = buildDto();
            dto.setAgentId("agent-uuid-123");
            dto.setMemoryEnabled(false);

            when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
                Conversation c = invocation.getArgument(0);
                c.setId("generated-id");
                return c;
            });

            service.createConversation(dto);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationRepository).save(captor.capture());
            assertThat(captor.getValue().getMemoryEnabled()).isFalse();
        }

        @Test
        @DisplayName("memoryEnabled=true on DTO without agentId reaches entity")
        void memoryEnabledTrueOnGeneralChatDtoReachesEntity() {
            ConversationDto dto = buildDto();
            dto.setMemoryEnabled(true);

            when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
                Conversation c = invocation.getArgument(0);
                c.setId("generated-id");
                return c;
            });

            service.createConversation(dto);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationRepository).save(captor.capture());
            assertThat(captor.getValue().getMemoryEnabled()).isTrue();
        }

        @Test
        @DisplayName("memoryEnabled=null on DTO leaves the entity default (true) untouched")
        void memoryEnabledNullDoesNotOverrideEntityDefault() {
            ConversationDto dto = buildDto();
            // memoryEnabled unset on DTO

            when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> {
                Conversation c = invocation.getArgument(0);
                c.setId("generated-id");
                return c;
            });

            service.createConversation(dto);

            ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
            verify(conversationRepository).save(captor.capture());
            assertThat(captor.getValue().getMemoryEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("createWorkflowConversation")
    class CreateWorkflowConversationTests {

        @Test
        @DisplayName("reuses existing active workflow conversation in the organization")
        void reusesExistingActiveWorkflowConversationInOrganization() {
            Conversation existing = new Conversation("user-1", "Existing", "gpt-4", "openai");
            existing.setId("conversation-existing");
            existing.setOrganizationId("org-1");
            existing.setWorkflowId("workflow-1");
            existing.setActive(true);

            when(conversationRepository.findByOrganizationIdStrictAndWorkflowIdAndActiveTrue("org-1", "workflow-1"))
                .thenReturn(Optional.of(existing));

            ConversationDto result = service.createWorkflowConversation(
                "user-1", "org-1", "workflow-1", "gpt-4", "openai", null);

            assertThat(result.getId()).isEqualTo("conversation-existing");
            verify(conversationRepository, never()).save(any());
            verifyNoInteractions(workflowContextProvider);
        }

        @Test
        @DisplayName("returns race winner when workflow conversation insert hits unique index")
        void returnsRaceWinnerWhenWorkflowConversationInsertHitsUniqueIndex() {
            Conversation winner = new Conversation("user-1", "Winner", "gpt-4", "openai");
            winner.setId("conversation-winner");
            winner.setOrganizationId("org-1");
            winner.setWorkflowId("workflow-1");
            winner.setActive(true);

            when(conversationRepository.findByOrganizationIdStrictAndWorkflowIdAndActiveTrue("org-1", "workflow-1"))
                .thenReturn(Optional.empty(), Optional.of(winner));
            when(conversationRepository.save(any(Conversation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate workflow conversation"));

            ConversationDto result = service.createWorkflowConversation(
                "user-1", "org-1", "workflow-1", "gpt-4", "openai", "Workflow Chat");

            assertThat(result.getId()).isEqualTo("conversation-winner");
            verify(conversationRepository).save(any(Conversation.class));
        }
    }

    private ConversationDto buildDto() {
        ConversationDto dto = new ConversationDto();
        dto.setUserId("user-1");
        dto.setOrganizationId("org-1");
        dto.setTitle("Test Conversation");
        dto.setModel("gpt-4");
        dto.setProvider("openai");
        dto.setActive(true);
        return dto;
    }
}
