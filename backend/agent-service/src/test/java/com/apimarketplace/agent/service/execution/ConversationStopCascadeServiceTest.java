package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * F2.1 - verifies the conversation STOP → workflow cancel cascade. Pre-fix, a
 * user clicking STOP in a chat that had spawned a workflow run via the
 * workflow tool would see the agent loop bail but the orchestrator run continue
 * to natural completion, burning credits.
 *
 * <p>Post-fix: this service queries running executions for the conversation,
 * extracts distinct workflow_run_ids, and writes
 * {@code workflow:cancel:&#123;runId&#125;} into Redis using the same prefix
 * and TTL the orchestrator already reads from
 * ({@code WorkflowRedisPublisher}/{@code UnifiedExecutionEngine.isAgentCancelSignalSet}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConversationStopCascadeService - F2.1 conv STOP → workflow cancel propagation")
class ConversationStopCascadeServiceTest {

    @Mock private AgentExecutionRepository agentExecutionRepository;
    @Mock private AgentTaskRepository agentTaskRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    @InjectMocks private ConversationStopCascadeService service;

    @BeforeEach
    void wireRedis() {
        // lenient because not every test path hits opsForValue
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("Sets workflow:cancel:{runId} for each distinct running run linked to conversation")
    void cancelsRunningWorkflowsForConversation() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        when(agentExecutionRepository.findRunningWorkflowRunIdsByConversationIdAndOrganizationId("conv-1", "org-1"))
            .thenReturn(List.of(r1, r2));

        int n = service.cancelRunningWorkflowsForConversation("conv-1", "org-1");

        assertThat(n).isEqualTo(2);
        verify(valueOps).set(eq("workflow:cancel:" + r1), eq("cancelled"), eq(Duration.ofHours(2)));
        verify(valueOps).set(eq("workflow:cancel:" + r2), eq("cancelled"), eq(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("No running runs → 0 returned, no Redis writes (regression: don't fan out on empty)")
    void noRunningRunsNoOp() {
        when(agentExecutionRepository.findRunningWorkflowRunIdsByConversationIdAndOrganizationId("conv-1", "org-1"))
            .thenReturn(List.of());

        int n = service.cancelRunningWorkflowsForConversation("conv-1", "org-1");

        assertThat(n).isZero();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Null/blank conversationId → 0, never queries DB (defensive guard)")
    void nullConversationIdGuard() {
        assertThat(service.cancelRunningWorkflowsForConversation(null, "org-1")).isZero();
        assertThat(service.cancelRunningWorkflowsForConversation("", "org-1")).isZero();
        assertThat(service.cancelRunningWorkflowsForConversation("   ", "org-1")).isZero();
        assertThat(service.cancelRunningWorkflowsForConversation("conv-1", null)).isZero();
        assertThat(service.cancelRunningWorkflowsForConversation("conv-1", "")).isZero();
        verifyNoInteractions(agentExecutionRepository);
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("DB lookup throws → swallowed, returns 0, STOP itself unaffected")
    void dbErrorSwallowedReturnsZero() {
        when(agentExecutionRepository.findRunningWorkflowRunIdsByConversationIdAndOrganizationId("conv-1", "org-1"))
            .thenThrow(new RuntimeException("db-down"));

        int n = service.cancelRunningWorkflowsForConversation("conv-1", "org-1");

        assertThat(n).isZero();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("Redis write fails for one run → others still attempted, count reflects successes")
    void partialRedisFailureCountsOnlySuccesses() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        when(agentExecutionRepository.findRunningWorkflowRunIdsByConversationIdAndOrganizationId("conv-1", "org-1"))
            .thenReturn(List.of(r1, r2));
        doThrow(new RuntimeException("redis-down")).when(valueOps)
            .set(eq("workflow:cancel:" + r1), anyString(), any(Duration.class));

        int n = service.cancelRunningWorkflowsForConversation("conv-1", "org-1");

        assertThat(n).as("only the run that succeeded counts").isEqualTo(1);
        verify(valueOps).set(eq("workflow:cancel:" + r2), eq("cancelled"), eq(Duration.ofHours(2)));
    }

    // ============================================================
    // F3.4 - task cascade
    // ============================================================

    @Test
    @DisplayName("F3.4 - cancels each running task for the conversation via cascadingCancel CTE")
    void cancelsRunningTasksForConversation() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        when(agentExecutionRepository.findRunningTaskIdsByConversationIdAndOrganizationId("conv-1", "org-1"))
            .thenReturn(List.of(t1, t2));
        when(agentTaskRepository.cascadingCancelInOrganization(eq(t1), eq("tenant-1"), eq("org-1"), anyString())).thenReturn(2);
        when(agentTaskRepository.cascadingCancelInOrganization(eq(t2), eq("tenant-1"), eq("org-1"), anyString())).thenReturn(1);

        int n = service.cancelTasksForConversation("conv-1", "tenant-1", "org-1");

        assertThat(n).as("sum of rows transitioned across all root tasks").isEqualTo(3);
        verify(agentTaskRepository).cascadingCancelInOrganization(eq(t1), eq("tenant-1"), eq("org-1"), eq("conversation_stopped"));
        verify(agentTaskRepository).cascadingCancelInOrganization(eq(t2), eq("tenant-1"), eq("org-1"), eq("conversation_stopped"));
    }

    @Test
    @DisplayName("F3.4 - null/blank conv or tenant → 0, no DB calls")
    void taskCancelGuards() {
        assertThat(service.cancelTasksForConversation(null, "t", "org-1")).isZero();
        assertThat(service.cancelTasksForConversation("", "t", "org-1")).isZero();
        assertThat(service.cancelTasksForConversation("c", null, "org-1")).isZero();
        assertThat(service.cancelTasksForConversation("c", "", "org-1")).isZero();
        assertThat(service.cancelTasksForConversation("c", "t", null)).isZero();
        assertThat(service.cancelTasksForConversation("c", "t", "")).isZero();
        verifyNoInteractions(agentExecutionRepository, agentTaskRepository);
    }

    @Test
    @DisplayName("F3.4 - execution lookup throws → swallowed, 0, no cascade")
    void taskExecLookupErrorReturnsZero() {
        when(agentExecutionRepository.findRunningTaskIdsByConversationIdAndOrganizationId("conv-1", "org-1"))
            .thenThrow(new RuntimeException("db-down"));

        int n = service.cancelTasksForConversation("conv-1", "tenant-1", "org-1");
        assertThat(n).isZero();
        verifyNoInteractions(agentTaskRepository);
    }

    @Test
    @DisplayName("F3.4 - one cascade fails, others proceed; returned count reflects successes")
    void taskPartialCascadeFailureCountsRest() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        when(agentExecutionRepository.findRunningTaskIdsByConversationIdAndOrganizationId("conv-1", "org-1"))
            .thenReturn(List.of(t1, t2));
        when(agentTaskRepository.cascadingCancelInOrganization(eq(t1), anyString(), eq("org-1"), anyString()))
            .thenThrow(new RuntimeException("constraint"));
        when(agentTaskRepository.cascadingCancelInOrganization(eq(t2), anyString(), eq("org-1"), anyString())).thenReturn(5);

        int n = service.cancelTasksForConversation("conv-1", "tenant-1", "org-1");
        assertThat(n).isEqualTo(5);
    }
}
