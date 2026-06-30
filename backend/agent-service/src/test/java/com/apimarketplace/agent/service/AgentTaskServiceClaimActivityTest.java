package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.execution.AgentActivityPublisher;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies AgentTaskService.claimTask emits a synthetic tool_call_started activity
 * event so the task board can shimmer the claimed card. Without this event the
 * bridge sync path (schedule/webhook) publishes execution_started with taskId=null
 * - the (agent, task) pair is only known once the agent picks the task via MCP,
 * which is what claimTask captures.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - claim emits agent activity event for shimmer")
class AgentTaskServiceClaimActivityTest {

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;
    @Mock private AgentActivityPublisher agentActivityPublisher;
    @Mock private com.apimarketplace.agent.repository.AgentTaskClaimRepository claimRepository;

    private AgentTaskService service;

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        Field f = AgentTaskService.class.getDeclaredField("agentActivityPublisher");
        f.setAccessible(true);
        f.set(service, agentActivityPublisher);
        Field cr = AgentTaskService.class.getDeclaredField("claimRepository");
        cr.setAccessible(true);
        cr.set(service, claimRepository);
        // claimTask uses TenantResolver.requireOrgId via the 3-arg path; we pass ORG explicitly
        // through the 4-arg overload so no thread-local is needed.
    }

    @Test
    @DisplayName("Successful claim publishes tool_call_started with toolName='task_claim' and the claimed taskId - fuels (agent,task)-scoped shimmer")
    void successfulClaimPublishesActivityEvent() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(taskId);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssignedToAgentId(agentId);

        when(taskRepository.claimIfAvailableByOrganizationId(taskId, ORG, agentId)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        Optional<AgentTaskEntity> result = service.claimTask(TENANT, ORG, agentId, taskId);

        assertThat(result).isPresent();
        // executionId is null (we don't have one in scope at claim time - the bridge sync path
        // generates its own UUID for execution_started, and frontend preserves currentTaskId
        // across events for the same agent).
        verify(agentActivityPublisher).publishToolCallStarted(
                eq(agentId.toString()),
                isNull(),
                eq("task_claim"),
                any(String.class),
                eq(taskId.toString()));
    }

    @Test
    @DisplayName("Failed claim (already taken) does not publish - predicate update returned 0")
    void failedClaimDoesNotPublish() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        when(taskRepository.claimIfAvailableByOrganizationId(taskId, ORG, agentId)).thenReturn(0);

        Optional<AgentTaskEntity> result = service.claimTask(TENANT, ORG, agentId, taskId);

        assertThat(result).isEmpty();
        verify(agentActivityPublisher, never()).publishToolCallStarted(
                any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Activity event fires even when taskBoardPublisher is null - pins the runAfterCommit (not publishAfterCommit) routing the code comment promises")
    void activityEventFiresEvenWhenTaskBoardPublisherIsNull() throws Exception {
        // Regression guard: claimTask uses runAfterCommit for the activity emission
        // rather than publishAfterCommit, because publishAfterCommit short-circuits
        // when taskBoardPublisher is null. If a future refactor flips back to
        // publishAfterCommit, this test must fail - otherwise the activity event
        // silently drops in any wiring where the task board publisher is unwired.
        Field tbpField = AgentTaskService.class.getDeclaredField("taskBoardPublisher");
        tbpField.setAccessible(true);
        tbpField.set(service, null);

        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(taskId);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssignedToAgentId(agentId);

        when(taskRepository.claimIfAvailableByOrganizationId(taskId, ORG, agentId)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        Optional<AgentTaskEntity> result = service.claimTask(TENANT, ORG, agentId, taskId);

        assertThat(result).isPresent();
        verify(agentActivityPublisher).publishToolCallStarted(
                eq(agentId.toString()), isNull(),
                eq("task_claim"), any(String.class), eq(taskId.toString()));
    }

    @Test
    @DisplayName("Activity publisher unwired (null) → claim still succeeds (publisher is optional)")
    void claimSucceedsWhenPublisherUnwired() throws Exception {
        Field f = AgentTaskService.class.getDeclaredField("agentActivityPublisher");
        f.setAccessible(true);
        f.set(service, null);

        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(taskId);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssignedToAgentId(agentId);

        when(taskRepository.claimIfAvailableByOrganizationId(taskId, ORG, agentId)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        Optional<AgentTaskEntity> result = service.claimTask(TENANT, ORG, agentId, taskId);

        assertThat(result).isPresent();
    }

    @Test
    @DisplayName("claimTask with executionId appends agent_task_claims row keyed by that UUID - closes the 2026-05-22 task↔execution race")
    void claimTaskWithExecutionIdWritesClaimLogRow() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String executionId = UUID.randomUUID().toString();

        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(taskId);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssignedToAgentId(agentId);

        when(taskRepository.claimIfAvailableByOrganizationId(taskId, ORG, agentId)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        Optional<AgentTaskEntity> result = service.claimTask(TENANT, ORG, agentId, taskId, executionId);

        assertThat(result).isPresent();
        org.mockito.ArgumentCaptor<com.apimarketplace.agent.domain.AgentTaskClaimEntity> captor =
                org.mockito.ArgumentCaptor.forClass(com.apimarketplace.agent.domain.AgentTaskClaimEntity.class);
        verify(claimRepository).save(captor.capture());
        com.apimarketplace.agent.domain.AgentTaskClaimEntity saved = captor.getValue();
        assertThat(saved.getExecutionId()).isEqualTo(UUID.fromString(executionId));
        assertThat(saved.getTaskId()).isEqualTo(taskId);
        assertThat(saved.getEvent()).isEqualTo(com.apimarketplace.agent.domain.AgentTaskClaimEntity.EVT_CLAIMED);
        assertThat(saved.getAgentId()).isEqualTo(agentId);
        assertThat(saved.getOrganizationId()).isEqualTo(ORG);
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
    }

    @Test
    @DisplayName("claimTask WITHOUT executionId (REST-direct admin path) skips the claim log - no NPE, no orphan row")
    void claimTaskWithoutExecutionIdSkipsClaimLog() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(taskId);
        task.setOrganizationId(ORG);

        when(taskRepository.claimIfAvailableByOrganizationId(taskId, ORG, agentId)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        // 4-arg overload - no executionId
        Optional<AgentTaskEntity> result = service.claimTask(TENANT, ORG, agentId, taskId);

        assertThat(result).isPresent();
        verify(claimRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("claimTask with executionId but failed CAS (already taken) → no claim log row (no orphan claim audit)")
    void failedClaimSkipsClaimLogEvenWhenExecutionIdProvided() {
        UUID agentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String executionId = UUID.randomUUID().toString();

        when(taskRepository.claimIfAvailableByOrganizationId(taskId, ORG, agentId)).thenReturn(0);

        Optional<AgentTaskEntity> result = service.claimTask(TENANT, ORG, agentId, taskId, executionId);

        assertThat(result).isEmpty();
        verify(claimRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
