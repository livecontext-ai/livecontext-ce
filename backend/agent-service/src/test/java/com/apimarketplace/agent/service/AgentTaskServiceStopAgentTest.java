package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.agent.dto.TaskResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for AgentTaskService.stopAgentExecution - verifies the Redis cancel key
 * is set via the agent's conversation (not execution entity) and the execution
 * lock is cleared for both assignee and reviewer roles.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - stopAgentExecution")
class AgentTaskServiceStopAgentTest {

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private AgentTaskService service;

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";

    @BeforeEach
    void setUp() throws Exception {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);

        // Inject redisTemplate via reflection (field-injected optional)
        Field rtField = AgentTaskService.class.getDeclaredField("redisTemplate");
        rtField.setAccessible(true);
        rtField.set(service, redisTemplate);

        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @DisplayName("Stops assignee: resolves conversation from agent, sets cancel key, clears lock")
    void stopsAssigneeAgent() {
        UUID taskId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        String conversationId = UUID.randomUUID().toString();
        String streamId = UUID.randomUUID().toString();

        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssigneeExecutionId(execId);
        task.setAssignedToAgentId(agentId);

        when(conversationClient.findAgentConversation(
                agentId.toString(), TENANT, ORG)).thenReturn(conversationId);
        when(valueOps.get("stream:conv:" + conversationId)).thenReturn(streamId);
        when(taskRepository.forceUnlockAssigneeExecution(taskId)).thenReturn(1);

        AgentTaskEntity updated = buildTask(taskId, AgentTaskEntity.STATUS_PENDING);
        updated.setAssigneeExecutionId(null);
        updated.setAssignedToAgentId(agentId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.of(updated));

        AtomicReference<TaskResponse> ref = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                ref.set(service.stopAgentExecution(TENANT, ORG, taskId, "assignee")));

        verify(valueOps).set(eq("agent:cancel:" + streamId), eq("stopped_by_user"), eq(Duration.ofMinutes(5)));
        verify(taskRepository).forceUnlockAssigneeExecution(taskId);
        verify(self).recordEvent(eq(taskId), eq(AgentTaskEventEntity.EVT_AGENT_STOPPED),
                isNull(), eq(TENANT), anyMap(), anyMap());
        assertThat(ref.get().status()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
    }

    @Test
    @DisplayName("Stops reviewer: resolves conversation from reviewer agent, sets cancel key, clears lock, returns to in_progress")
    void stopsReviewerAgent() {
        UUID taskId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        String conversationId = UUID.randomUUID().toString();
        String streamId = UUID.randomUUID().toString();

        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_REVIEW);
        task.setReviewerExecutionId(execId);
        task.setReviewerAgentId(reviewerId);

        when(conversationClient.findAgentConversation(
                reviewerId.toString(), TENANT, ORG)).thenReturn(conversationId);
        when(valueOps.get("stream:conv:" + conversationId)).thenReturn(streamId);
        when(taskRepository.forceUnlockReviewerExecution(taskId)).thenReturn(1);

        AgentTaskEntity updated = buildTask(taskId, AgentTaskEntity.STATUS_IN_PROGRESS);
        updated.setReviewerExecutionId(null);
        updated.setReviewerAgentId(reviewerId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.of(updated));

        AtomicReference<TaskResponse> ref = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                ref.set(service.stopAgentExecution(TENANT, ORG, taskId, "reviewer")));

        verify(valueOps).set(eq("agent:cancel:" + streamId), eq("stopped_by_user"), eq(Duration.ofMinutes(5)));
        verify(taskRepository).forceUnlockReviewerExecution(taskId);
        assertThat(ref.get().status()).isEqualTo(AgentTaskEntity.STATUS_IN_PROGRESS);
    }

    @Test
    @DisplayName("Rejects stop when task is not in expected status for assignee role")
    void rejectsWrongStatusForAssignee() {
        UUID taskId = UUID.randomUUID();
        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_PENDING);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG, () ->
                service.stopAgentExecution(TENANT, ORG, taskId, "assignee")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in_progress");
    }

    @Test
    @DisplayName("Rejects stop when no active execution exists for assignee")
    void rejectsNoActiveAssigneeExecution() {
        UUID taskId = UUID.randomUUID();
        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssigneeExecutionId(null);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG, () ->
                service.stopAgentExecution(TENANT, ORG, taskId, "assignee")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active assignee execution");
    }

    @Test
    @DisplayName("Rejects stop when no active execution exists for reviewer")
    void rejectsNoActiveReviewerExecution() {
        UUID taskId = UUID.randomUUID();
        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_REVIEW);
        task.setReviewerExecutionId(null);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG, () ->
                service.stopAgentExecution(TENANT, ORG, taskId, "reviewer")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no active reviewer execution");
    }

    @Test
    @DisplayName("Clears lock even when streamId is not found in Redis (agent already finished)")
    void clearsLockEvenWithoutStreamId() {
        UUID taskId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        String conversationId = UUID.randomUUID().toString();

        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssigneeExecutionId(execId);
        task.setAssignedToAgentId(agentId);

        when(conversationClient.findAgentConversation(
                agentId.toString(), TENANT, ORG)).thenReturn(conversationId);
        when(valueOps.get("stream:conv:" + conversationId)).thenReturn(null);
        when(taskRepository.forceUnlockAssigneeExecution(taskId)).thenReturn(1);

        AgentTaskEntity updated = buildTask(taskId, AgentTaskEntity.STATUS_PENDING);
        updated.setAssigneeExecutionId(null);
        updated.setAssignedToAgentId(agentId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.of(updated));

        AtomicReference<TaskResponse> ref = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                ref.set(service.stopAgentExecution(TENANT, ORG, taskId, "assignee")));

        verify(valueOps, never()).set(startsWith("agent:cancel:"), anyString(), any(Duration.class));
        verify(taskRepository).forceUnlockAssigneeExecution(taskId);
        assertThat(ref.get().status()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
    }

    @Test
    @DisplayName("Clears lock even when conversationClient returns null (no conversation found)")
    void clearsLockEvenWithoutConversation() {
        UUID taskId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssigneeExecutionId(execId);
        task.setAssignedToAgentId(agentId);

        when(conversationClient.findAgentConversation(
                agentId.toString(), TENANT, ORG)).thenReturn(null);
        when(taskRepository.forceUnlockAssigneeExecution(taskId)).thenReturn(1);

        AgentTaskEntity updated = buildTask(taskId, AgentTaskEntity.STATUS_PENDING);
        updated.setAssigneeExecutionId(null);
        updated.setAssignedToAgentId(agentId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.of(updated));

        AtomicReference<TaskResponse> ref = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                ref.set(service.stopAgentExecution(TENANT, ORG, taskId, "assignee")));

        verify(valueOps, never()).set(startsWith("agent:cancel:"), anyString(), any(Duration.class));
        verify(taskRepository).forceUnlockAssigneeExecution(taskId);
        assertThat(ref.get().status()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
    }

    @Test
    @DisplayName("Clears lock even when conversationClient throws (resilient)")
    void clearsLockEvenWhenConversationLookupFails() {
        UUID taskId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssigneeExecutionId(execId);
        task.setAssignedToAgentId(agentId);

        when(conversationClient.findAgentConversation(
                agentId.toString(), TENANT, ORG))
                .thenThrow(new RuntimeException("conversation-service unreachable"));
        when(taskRepository.forceUnlockAssigneeExecution(taskId)).thenReturn(1);

        AgentTaskEntity updated = buildTask(taskId, AgentTaskEntity.STATUS_PENDING);
        updated.setAssigneeExecutionId(null);
        updated.setAssignedToAgentId(agentId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.of(updated));

        AtomicReference<TaskResponse> ref = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                ref.set(service.stopAgentExecution(TENANT, ORG, taskId, "assignee")));

        verify(valueOps, never()).set(startsWith("agent:cancel:"), anyString(), any(Duration.class));
        verify(taskRepository).forceUnlockAssigneeExecution(taskId);
        assertThat(ref.get().status()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
    }

    @Test
    @DisplayName("Skips cancel key when assignedToAgentId is null (data inconsistency), still clears lock")
    void skipsCancelKeyWhenAgentIdNull() {
        UUID taskId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();

        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setAssigneeExecutionId(execId);
        task.setAssignedToAgentId(null);
        when(taskRepository.forceUnlockAssigneeExecution(taskId)).thenReturn(1);

        AgentTaskEntity updated = buildTask(taskId, AgentTaskEntity.STATUS_PENDING);
        updated.setAssigneeExecutionId(null);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                .thenReturn(Optional.of(task))
                .thenReturn(Optional.of(updated));

        AtomicReference<TaskResponse> ref = new AtomicReference<>();
        TenantResolver.runWithOrgScope(ORG, () ->
                ref.set(service.stopAgentExecution(TENANT, ORG, taskId, "assignee")));

        verifyNoInteractions(conversationClient);
        verify(valueOps, never()).set(startsWith("agent:cancel:"), anyString(), any(Duration.class));
        verify(taskRepository).forceUnlockAssigneeExecution(taskId);
        assertThat(ref.get().status()).isEqualTo(AgentTaskEntity.STATUS_PENDING);
    }

    @Test
    @DisplayName("Rejects invalid role parameter")
    void rejectsInvalidRole() {
        UUID taskId = UUID.randomUUID();
        AgentTaskEntity task = buildTask(taskId, AgentTaskEntity.STATUS_IN_PROGRESS);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> TenantResolver.runWithOrgScope(ORG, () ->
                service.stopAgentExecution(TENANT, ORG, taskId, "invalid")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role must be");
    }

    private AgentTaskEntity buildTask(UUID id, String status) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(id);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setStatus(status);
        task.setTitle("Test task");
        task.setInstructions("Do something");
        task.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        return task;
    }
}
