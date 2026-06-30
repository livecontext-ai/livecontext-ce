package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.dto.AgentTaskDispatchView;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.client.ConversationClient;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService task execution dispatch")
class AgentTaskServiceExecutionDispatchTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";
    private static final String CONVERSATION_ID = "conversation-1";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
    }

    @Test
    @DisplayName("Task worker dispatch uses a lightweight agent projection to avoid PostgreSQL LOB reads in auto-commit")
    void taskDispatchUsesProjectionToAvoidLobAutocommitFailure() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = task(taskId, agentId);
        AgentTaskDispatchView dispatchView = new AgentTaskDispatchView(
                agentId, "DeepSeek Worker", "deepseek", "deepseek-chat", true);

        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        when(agentRepository.findTaskDispatchViewByIdAndOrganizationIdStrict(agentId, ORG))
                .thenReturn(Optional.of(dispatchView));
        when(conversationClient.findOrCreateAgentConversation(agentId.toString(), TENANT, "DeepSeek Worker", ORG))
                .thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(eq(TENANT), eq(CONVERSATION_ID), contains("Complete the assigned task"),
                eq(agentId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK"), eq(taskId.toString()), eq(ORG)))
                .thenReturn(Map.of("success", true));

        TenantResolver.runWithOrgScope(ORG, () -> invokePrivate("executeAgentForTask", task));

        verify(agentRepository).findTaskDispatchViewByIdAndOrganizationIdStrict(agentId, ORG);
        verify(agentRepository, never()).findByIdAndOrganizationIdStrict(any(UUID.class), anyString());
        verify(conversationClient).sendChatSync(eq(TENANT), eq(CONVERSATION_ID), contains("Complete the assigned task"),
                eq(agentId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK"), eq(taskId.toString()), eq(ORG));
        verify(self).unlockAssigneeExecution(eq(taskId), any(UUID.class));
    }

    @Test
    @DisplayName("Task review submission side effects are not logged as dispatch failures when chat returns max-iteration failure")
    void taskReviewSubmissionSideEffectIsNotLoggedAsDispatchFailureWhenChatReturnsFailure() {
        assertTaskAdvancedStateIsNotLoggedAsDispatchFailure(AgentTaskEntity.STATUS_IN_REVIEW);
    }

    @Test
    @DisplayName("Task completion side effects are not logged as dispatch failures when chat returns max-iteration failure")
    void taskCompletedSideEffectIsNotLoggedAsDispatchFailureWhenChatReturnsFailure() {
        assertTaskAdvancedStateIsNotLoggedAsDispatchFailure(AgentTaskEntity.STATUS_COMPLETED);
    }

    private void assertTaskAdvancedStateIsNotLoggedAsDispatchFailure(String finalStatus) {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = task(taskId, agentId);
        AgentTaskEntity submittedTask = task(taskId, agentId);
        submittedTask.setStatus(finalStatus);
        AgentTaskDispatchView dispatchView = new AgentTaskDispatchView(
                agentId, "DeepSeek Worker", "deepseek", "deepseek-chat", true);
        ch.qos.logback.classic.Logger taskLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AgentTaskService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        taskLogger.addAppender(appender);

        try {
            when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
            when(agentRepository.findTaskDispatchViewByIdAndOrganizationIdStrict(agentId, ORG))
                    .thenReturn(Optional.of(dispatchView));
            when(conversationClient.findOrCreateAgentConversation(agentId.toString(), TENANT, "DeepSeek Worker", ORG))
                    .thenReturn(CONVERSATION_ID);
            when(conversationClient.sendChatSync(eq(TENANT), eq(CONVERSATION_ID), contains("Complete the assigned task"),
                    eq(agentId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK"), eq(taskId.toString()), eq(ORG)))
                    .thenReturn(Map.of("success", false, "error", "Agent execution failed"));
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(submittedTask));

            TenantResolver.runWithOrgScope(ORG, () -> invokePrivate("executeAgentForTask", task));
        } finally {
            taskLogger.detachAppender(appender);
        }

        assertThat(appender.list)
                .noneMatch(event -> Level.ERROR.equals(event.getLevel())
                        && event.getFormattedMessage().contains("[TaskExec] Agent " + agentId + " failed"));
        assertThat(appender.list)
                .anyMatch(event -> Level.INFO.equals(event.getLevel())
                        && event.getFormattedMessage().contains("advanced task " + taskId + " to " + finalStatus));
        verify(self, never()).markExecutionFailed(eq(taskId), eq(TENANT), anyString());
        verify(self).unlockAssigneeExecution(eq(taskId), any(UUID.class));
    }

    @Test
    @DisplayName("Infrastructure execution failure records FAILED event and publishes board update")
    void markExecutionFailedRecordsEventAndPublishesBoardUpdate() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        String reason = "BUDGET_EXHAUSTED scope=agent";
        AgentTaskEntity failedTask = task(taskId, agentId);
        failedTask.setStatus(AgentTaskEntity.STATUS_FAILED);
        failedTask.setErrorMessage(reason);

        when(taskRepository.markExecutionFailed(taskId, TENANT, reason)).thenReturn(1);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(failedTask));

        TenantResolver.runWithOrgScope(ORG, () -> service.markExecutionFailed(taskId, TENANT, reason));

        verify(self).recordEvent(taskId, AgentTaskEventEntity.EVT_FAILED, null, null,
                Map.of("status", AgentTaskEntity.STATUS_IN_PROGRESS),
                Map.of("status", AgentTaskEntity.STATUS_FAILED, "reason", reason));
        verify(taskBoardPublisher).publishTaskUpdated(TENANT, failedTask);
    }

    @Test
    @DisplayName("Reviewer dispatch uses the same projection so review retries do not load agent LOB columns")
    void reviewerDispatchUsesProjectionToAvoidLobAutocommitFailure() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        AgentTaskEntity task = task(taskId, UUID.randomUUID());
        task.setStatus(AgentTaskEntity.STATUS_IN_REVIEW);
        task.setReviewerAgentId(reviewerId);
        AgentTaskDispatchView dispatchView = new AgentTaskDispatchView(
                reviewerId, "DeepSeek Reviewer", "deepseek", "deepseek-chat", true);

        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));
        when(self.tryLockReviewerExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        when(agentRepository.findTaskDispatchViewByIdAndOrganizationIdStrict(reviewerId, ORG))
                .thenReturn(Optional.of(dispatchView));
        when(conversationClient.findOrCreateAgentConversation(reviewerId.toString(), TENANT, "DeepSeek Reviewer", ORG))
                .thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(eq(TENANT), eq(CONVERSATION_ID), contains("Review this task result"),
                eq(reviewerId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK_REVIEW"),
                eq(taskId.toString()), eq(ORG), anyString()))
                .thenReturn(Map.of("success", true));
        when(self.incrementReviewAttemptCount(eq(taskId), eq(TENANT), eq(reviewerId), any(UUID.class))).thenReturn(0);

        TenantResolver.runWithOrgScope(ORG, () -> invokePrivate("executeReviewerForTask", task));

        verify(agentRepository).findTaskDispatchViewByIdAndOrganizationIdStrict(reviewerId, ORG);
        verify(agentRepository, never()).findByIdAndOrganizationIdStrict(any(UUID.class), anyString());
        verify(conversationClient).sendChatSync(eq(TENANT), eq(CONVERSATION_ID), contains("Review this task result"),
                eq(reviewerId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK_REVIEW"),
                eq(taskId.toString()), eq(ORG), anyString());
        verify(self).unlockReviewerExecution(eq(taskId), any(UUID.class));
    }

    @Test
    @DisplayName("Delayed reviewer retry uses the current review row after acquiring the lock")
    void delayedReviewerRetryUsesCurrentReviewRowAfterLock() {
        UUID taskId = UUID.randomUUID();
        UUID staleReviewerId = UUID.randomUUID();
        UUID currentReviewerId = UUID.randomUUID();
        AgentTaskEntity staleTask = task(taskId, UUID.randomUUID());
        staleTask.setStatus(AgentTaskEntity.STATUS_IN_REVIEW);
        staleTask.setReviewerAgentId(staleReviewerId);
        staleTask.setTitle("Stale review title");
        staleTask.setInstructions("STALE_INSTRUCTIONS");
        staleTask.setResult("STALE_RESULT");
        AgentTaskEntity currentTask = task(taskId, UUID.randomUUID());
        currentTask.setStatus(AgentTaskEntity.STATUS_IN_REVIEW);
        currentTask.setReviewerAgentId(currentReviewerId);
        currentTask.setTitle("Current review title");
        currentTask.setInstructions("CURRENT_INSTRUCTIONS");
        currentTask.setResult("CURRENT_RESULT");
        AgentTaskDispatchView dispatchView = new AgentTaskDispatchView(
                currentReviewerId, "Current Reviewer", "deepseek", "deepseek-chat", true);

        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG))
                .thenReturn(Optional.of(staleTask))
                .thenReturn(Optional.of(currentTask));
        when(self.tryLockReviewerExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        when(agentRepository.findTaskDispatchViewByIdAndOrganizationIdStrict(currentReviewerId, ORG))
                .thenReturn(Optional.of(dispatchView));
        when(conversationClient.findOrCreateAgentConversation(currentReviewerId.toString(), TENANT,
                "Current Reviewer", ORG)).thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(eq(TENANT), eq(CONVERSATION_ID), contains("CURRENT_RESULT"),
                eq(currentReviewerId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK_REVIEW"),
                eq(taskId.toString()), eq(ORG), anyString()))
                .thenReturn(Map.of("success", true));
        when(self.incrementReviewAttemptCount(eq(taskId), eq(TENANT), eq(currentReviewerId), any(UUID.class)))
                .thenReturn(0);

        TenantResolver.runWithOrgScope(ORG, () -> invokePrivate("executeReviewerForTask", staleTask));

        verify(agentRepository).findTaskDispatchViewByIdAndOrganizationIdStrict(currentReviewerId, ORG);
        verify(agentRepository, never()).findTaskDispatchViewByIdAndOrganizationIdStrict(staleReviewerId, ORG);
        verify(conversationClient).sendChatSync(eq(TENANT), eq(CONVERSATION_ID), contains("CURRENT_RESULT"),
                eq(currentReviewerId.toString()), eq("deepseek-chat"), eq("deepseek"), eq("TASK_REVIEW"),
                eq(taskId.toString()), eq(ORG), anyString());
        verify(self).incrementReviewAttemptCount(eq(taskId), eq(TENANT), eq(currentReviewerId), any(UUID.class));
        verify(self).unlockReviewerExecution(eq(taskId), any(UUID.class));
    }

    private AgentTaskEntity task(UUID taskId, UUID agentId) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(taskId);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setAssignedToAgentId(agentId);
        task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setTitle("DeepSeek task dispatch");
        task.setInstructions("Complete the assigned task with the requested marker.");
        return task;
    }

    private void invokePrivate(String methodName, AgentTaskEntity task) {
        try {
            Method method = AgentTaskService.class.getDeclaredMethod(methodName, AgentTaskEntity.class);
            method.setAccessible(true);
            method.invoke(service, task);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
