package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the input-validation, priority-normalisation and subtask depth-cap guards of
 * {@code assignTask} - the fail-fast boundary that protects the LLM/DB from oversized payloads,
 * mis-bucketed priorities, and unbounded subtask recursion (runaway agent fan-out).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - assignTask validation / priority / depth guards")
class AgentTaskServiceAssignValidationTest {

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    private static final String TENANT = "tenant-1";

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        lenient().when(taskRepository.save(any(AgentTaskEntity.class))).thenAnswer(inv -> {
            AgentTaskEntity t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID());
            return t;
        });
    }

    /** A valid backlog request (no assignee/parent), overridable per test. */
    private CreateTaskRequest backlog(String title, String instructions, String priority,
                                      Map<String, Object> ctx, Integer maxReview) {
        return new CreateTaskRequest(null, null, title, instructions, priority, ctx, null, null, maxReview);
    }

    @Nested
    @DisplayName("input validation (fail-fast, before any repository access)")
    class InputValidation {

        @Test
        @DisplayName("blank title is rejected")
        void blankTitleRejected() {
            assertThatThrownBy(() -> service.assignTask(TENANT, null, "user-1",
                    backlog("   ", "do it", "normal", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("title is required");
        }

        @Test
        @DisplayName("blank instructions are rejected")
        void blankInstructionsRejected() {
            assertThatThrownBy(() -> service.assignTask(TENANT, null, "user-1",
                    backlog("Title", "   ", "normal", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("instructions are required");
        }

        @Test
        @DisplayName("oversized instructions are rejected")
        void oversizedInstructionsRejected() {
            String big = "x".repeat(AgentTaskService.MAX_INSTRUCTIONS_BYTES + 1);
            assertThatThrownBy(() -> service.assignTask(TENANT, null, "user-1",
                    backlog("Title", big, "normal", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("instructions exceed maximum size");
        }

        @Test
        @DisplayName("oversized task_context is rejected")
        void oversizedTaskContextRejected() {
            Map<String, Object> ctx = Map.of("blob", "y".repeat(AgentTaskService.MAX_TASK_CONTEXT_BYTES + 1));
            assertThatThrownBy(() -> service.assignTask(TENANT, null, "user-1",
                    backlog("Title", "do it", "normal", ctx, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("task_context exceeds maximum size");
        }

        @Test
        @DisplayName("max_review_attempts below 1 is rejected")
        void maxReviewAttemptsTooLow() {
            assertThatThrownBy(() -> service.assignTask(TENANT, null, "user-1",
                    backlog("Title", "do it", "normal", null, 0)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max_review_attempts must be >= 1");
        }

        @Test
        @DisplayName("max_review_attempts above the ceiling is rejected")
        void maxReviewAttemptsTooHigh() {
            assertThatThrownBy(() -> service.assignTask(TENANT, null, "user-1",
                    backlog("Title", "do it", "normal", null, AgentTaskService.MAX_REVIEW_ATTEMPTS_CEILING + 1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("max_review_attempts must be <=");
        }
    }

    @Nested
    @DisplayName("priority normalisation")
    class PriorityNormalisation {

        private String savedPriority(String requestedPriority) {
            service.assignTask(TENANT, null, "user-1", backlog("Title", "do it", requestedPriority, null, null));
            ArgumentCaptor<AgentTaskEntity> captor = ArgumentCaptor.forClass(AgentTaskEntity.class);
            verify(taskRepository).save(captor.capture());
            return captor.getValue().getPriority();
        }

        @Test
        @DisplayName("alias 'p1' maps to urgent")
        void aliasP1MapsToUrgent() {
            assertThat(savedPriority("p1")).isEqualTo(AgentTaskEntity.PRIORITY_URGENT);
        }

        @Test
        @DisplayName("alias 'medium' maps to normal")
        void aliasMediumMapsToNormal() {
            assertThat(savedPriority("medium")).isEqualTo(AgentTaskEntity.PRIORITY_NORMAL);
        }

        @Test
        @DisplayName("a canonical value passes through unchanged")
        void canonicalPassesThrough() {
            assertThat(savedPriority("high")).isEqualTo(AgentTaskEntity.PRIORITY_HIGH);
        }

        @Test
        @DisplayName("an unknown priority is rejected")
        void invalidPriorityRejected() {
            assertThatThrownBy(() -> service.assignTask(TENANT, null, "user-1",
                    backlog("Title", "do it", "bogus", null, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("invalid priority");
        }
    }

    @Nested
    @DisplayName("subtask depth cap (MAX_TASK_DEPTH)")
    class DepthCap {

        private AgentTaskEntity parentAtDepth(int depth) {
            AgentTaskEntity p = new AgentTaskEntity();
            p.setId(UUID.randomUUID());
            p.setTenantId(TENANT);
            p.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
            p.setDepth(depth);
            return p;
        }

        @Test
        @DisplayName("an explicit parent already at the depth limit rejects a deeper subtask")
        void explicitParentAtLimitRejected() {
            AgentTaskEntity parent = parentAtDepth(AgentTaskService.MAX_TASK_DEPTH);
            when(taskRepository.findByIdAndTenantId(parent.getId(), TENANT)).thenReturn(Optional.of(parent));
            CreateTaskRequest req = new CreateTaskRequest(
                    null, null, "Subtask", "do it", "normal", null, null, parent.getId(), null);

            assertThatThrownBy(() -> service.assignTask(TENANT, null, "user-1", req))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("depth limit reached");
        }

        @Test
        @DisplayName("an implicit parent (caller's in-progress task) at the limit rejects a deeper subtask")
        void implicitParentAtLimitRejected() {
            UUID callerAgent = UUID.randomUUID();
            AgentTaskEntity parent = parentAtDepth(AgentTaskService.MAX_TASK_DEPTH);
            when(taskRepository.findTopByTenantIdAndAssignedToAgentIdAndStatusOrderByStartedAtDesc(
                    TENANT, callerAgent, AgentTaskEntity.STATUS_IN_PROGRESS))
                    .thenReturn(Optional.of(parent));
            CreateTaskRequest req = backlog("Subtask", "do it", "normal", null, null);

            assertThatThrownBy(() -> service.assignTask(TENANT, callerAgent, null, req))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("depth limit reached");
        }
    }
}
