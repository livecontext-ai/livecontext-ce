package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.AgentTaskDispatchView;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.execution.ExecutionLinkRouter;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-066 (review finding r5-2). The assignee and reviewer turns of a task used to run in the
 * agent's ONE conversation, where a RESTRICTED task's prompt is stored RESTRICTED: the guard then
 * treated the agent's only conversation as restricted for good, refusing (or tagging) every later
 * task, schedule and chat of that agent. Now:
 * (a) a RESTRICTED task is refused BEFORE dispatch when the provider that would receive it (after
 *     its execution link) may not, the refusal stored on the task, nothing stored in a conversation;
 * (b) a RESTRICTED task's turns run in the task's own conversation; NORMAL tasks are unchanged.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService - RESTRICTED task dispatch (LC-066)")
class AgentTaskServiceRestrictedDispatchTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";
    private static final String TASK_CONVERSATION = "task-conversation";
    private static final String AGENT_CONVERSATION = "agent-conversation";

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private ExecutionLinkRouter executionLinkRouter;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    @BeforeEach
    void setUp() throws Exception {
        RestrictedDataPolicy.setLlmAllowListEnforced(true);
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        Field f = AgentTaskService.class.getDeclaredField("executionLinkRouter");
        f.setAccessible(true);
        f.set(service, executionLinkRouter);
    }

    @AfterEach
    void resetPolicy() {
        RestrictedDataPolicy.setLlmAllowListEnforced(true);
    }

    // ── (a) refusal before dispatch ─────────────────────────────────────────

    @Test
    @DisplayName("assignee on a provider outside the allow-list: task failed with the refusal, no conversation touched")
    void restrictedTaskOnDisallowedProviderIsRefusedBeforeDispatch() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "deepseek", "deepseek-chat");

        run("executeAgentForTask", task);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(self).markExecutionFailed(eq(taskId), eq(TENANT), reason.capture());
        assertThat(reason.getValue()).isEqualTo(RestrictedDataPolicy.refusalMessage("deepseek"));
        verifyNoInteractions(conversationClient);
        verify(self).unlockAssigneeExecution(eq(taskId), any(UUID.class));
    }

    @Test
    @DisplayName("the refusal follows the execution link: an allowed billed provider linked to a CLI bridge still dispatches (link dropped)")
    void linkToDisallowedTargetIsDroppedWhenTheBilledProviderIsAllowed() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "anthropic", "claude-sonnet-4-5");
        when(executionLinkRouter.runnableRoute("anthropic", "claude-sonnet-4-5", "TASK"))
                .thenReturn(new ModelExecutionLinkService.ExecutionRoute("claude-code", "claude-sonnet-4-5"));
        taskConversation(agentId, taskId);
        chatSucceeds();

        run("executeAgentForTask", task);

        verify(self, never()).markExecutionFailed(any(), anyString(), anyString());
        verify(conversationClient).sendChatSync(eq(TENANT), eq(TASK_CONVERSATION), anyString(), anyString(), anyString(),
                anyString(), eq("TASK"), anyString(), eq(ORG), isNull(), anyString(), eq(DataSensitivity.RESTRICTED));
    }

    @Test
    @DisplayName("the refusal follows the execution link: a disallowed billed provider linked to another disallowed one is refused on the TARGET")
    void linkedDisallowedTargetIsRefusedOnTheTarget() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "openrouter", "some-model");
        when(executionLinkRouter.runnableRoute("openrouter", "some-model", "TASK"))
                .thenReturn(new ModelExecutionLinkService.ExecutionRoute("claude-code", "sonnet"));

        run("executeAgentForTask", task);

        verify(self).markExecutionFailed(taskId, TENANT, RestrictedDataPolicy.refusalMessage("claude-code"));
        verifyNoInteractions(conversationClient);
    }

    @Test
    @DisplayName("reviewer on a provider outside the allow-list: task failed with the refusal, no conversation touched")
    void restrictedReviewOnDisallowedProviderIsRefusedBeforeDispatch() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, UUID.randomUUID());
        task.setStatus(AgentTaskEntity.STATUS_IN_REVIEW);
        task.setReviewerAgentId(reviewerId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));
        when(self.tryLockReviewerExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(reviewerId, "deepseek", "deepseek-chat");

        run("executeReviewerForTask", task);

        verify(self).autoFailAfterReviewerRejection(eq(taskId), eq(TENANT), eq(reviewerId), any(UUID.class),
                eq(RestrictedDataPolicy.refusalMessage("deepseek")));
        verifyNoInteractions(conversationClient);
        verify(self).unlockReviewerExecution(eq(taskId), any(UUID.class));
    }

    @Test
    @DisplayName("NORMAL task on a provider outside the allow-list: never refused for this reason (unchanged path)")
    void normalTaskIsNeverRefusedForRestrictedData() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        task.setDataSensitivity(DataSensitivity.NORMAL.name());
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "deepseek", "deepseek-chat");
        when(conversationClient.findOrCreateAgentConversation(agentId.toString(), TENANT, "Worker", ORG))
                .thenReturn(AGENT_CONVERSATION);
        chatSucceeds();

        run("executeAgentForTask", task);

        verify(conversationClient).sendChatSync(eq(TENANT), eq(AGENT_CONVERSATION), anyString(), anyString(), anyString(),
                anyString(), eq("TASK"), anyString(), eq(ORG), isNull(), anyString(), eq(DataSensitivity.NORMAL));
        verify(conversationClient, never()).findOrCreateTaskConversation(anyString(), anyString(), anyString(),
                anyString(), anyString());
        verifyNoInteractions(executionLinkRouter);
    }

    @Test
    @DisplayName("a disabled billed model is swapped for its replacement BEFORE the refusal: an allowed model replaced by a disallowed one is refused on the replacement")
    void disabledModelIsSwappedBeforeTheRestrictedProviderDecision() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "anthropic", "claude-retired");
        replacement("anthropic", "claude-retired", "deepseek", "deepseek-chat");

        run("executeAgentForTask", task);

        verify(self).markExecutionFailed(taskId, TENANT, RestrictedDataPolicy.refusalMessage("deepseek"));
        verify(executionLinkRouter).runnableRoute("deepseek", "deepseek-chat", "TASK");
        verify(executionLinkRouter, never()).runnableRoute("anthropic", "claude-retired", "TASK");
        verifyNoInteractions(conversationClient);
    }

    @Test
    @DisplayName("a disabled disallowed model replaced by an allowed one is NOT refused: the replacement is what runs")
    void disabledDisallowedModelReplacedByAllowedOneDispatches() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "deepseek", "deepseek-retired");
        replacement("deepseek", "deepseek-retired", "anthropic", "claude-sonnet-4-5");
        taskConversation(agentId, taskId);
        chatSucceeds();

        run("executeAgentForTask", task);

        verify(self, never()).markExecutionFailed(any(), anyString(), anyString());
        verify(executionLinkRouter).runnableRoute("anthropic", "claude-sonnet-4-5", "TASK");
    }

    // ── (b) per-task conversation ───────────────────────────────────────────

    @Test
    @DisplayName("the task's own conversation cannot be opened (old conversation-service): task failed, never sent to the agent's conversation")
    void missingTaskConversationFailsTheTaskInsteadOfFallingBack() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "anthropic", "claude-sonnet-4-5");
        when(conversationClient.findOrCreateTaskConversation(eq(agentId.toString()), eq(taskId.toString()),
                eq(TENANT), anyString(), eq(ORG))).thenReturn(null);

        run("executeAgentForTask", task);

        verify(self).markExecutionFailed(eq(taskId), eq(TENANT), org.mockito.ArgumentMatchers.contains("own conversation"));
        verify(conversationClient, never()).findOrCreateAgentConversation(anyString(), anyString(), anyString(), anyString());
        verify(conversationClient, never()).sendChatSync(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("reviewer whose task conversation cannot be opened: counted as a failed review attempt, auto-failed at the cap (no retry forever)")
    void reviewerWithoutTaskConversationCountsAsFailedAttempt() {
        UUID taskId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, UUID.randomUUID());
        task.setStatus(AgentTaskEntity.STATUS_IN_REVIEW);
        task.setReviewerAgentId(reviewerId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));
        when(self.tryLockReviewerExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(reviewerId, "anthropic", "claude-sonnet-4-5");
        when(conversationClient.findOrCreateTaskConversation(eq(reviewerId.toString()), eq(taskId.toString()),
                eq(TENANT), anyString(), eq(ORG))).thenReturn(null);
        // This was the last allowed attempt.
        when(self.incrementReviewAttemptCount(eq(taskId), eq(TENANT), eq(reviewerId), any(UUID.class)))
                .thenReturn(AgentTaskService.MAX_REVIEW_ATTEMPTS);

        run("executeReviewerForTask", task);

        verify(self).incrementReviewAttemptCount(eq(taskId), eq(TENANT), eq(reviewerId), any(UUID.class));
        verify(self).autoFailAfterReviewerRejection(eq(taskId), eq(TENANT), eq(reviewerId), any(UUID.class),
                org.mockito.ArgumentMatchers.contains("failed reviewer run"));
        verify(conversationClient, never()).sendChatSync(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
        verify(self).unlockReviewerExecution(eq(taskId), any(UUID.class));
    }

    @Test
    @DisplayName("the conversation title names the agent and the task id, never the task's text")
    void taskConversationTitleCarriesNoTaskText() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "anthropic", "claude-sonnet-4-5");
        taskConversation(agentId, taskId);
        chatSucceeds();

        run("executeAgentForTask", task);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        verify(conversationClient).findOrCreateTaskConversation(eq(agentId.toString()), eq(taskId.toString()),
                eq(TENANT), title.capture(), eq(ORG));
        assertThat(title.getValue()).isEqualTo("Worker - task " + taskId.toString().substring(0, 8))
                .doesNotContain("invoice");
    }

    @Test
    @DisplayName("a kickoff copy that predates the ratchet (stored RESTRICTED) still runs in the task's own conversation")
    void storedRestrictedClassWinsOverStaleKickoffCopy() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        AgentTaskEntity stale = restrictedTask(taskId, agentId);
        stale.setDataSensitivity(DataSensitivity.NORMAL.name());
        AgentTaskEntity stored = restrictedTask(taskId, agentId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(stored));
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "anthropic", "claude-sonnet-4-5");
        taskConversation(agentId, taskId);
        chatSucceeds();

        run("executeAgentForTask", stale);

        verify(conversationClient).sendChatSync(eq(TENANT), eq(TASK_CONVERSATION), anyString(), anyString(), anyString(),
                anyString(), eq("TASK"), anyString(), eq(ORG), isNull(), anyString(), eq(DataSensitivity.RESTRICTED));
        verify(conversationClient, never()).findOrCreateAgentConversation(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("LC-066 r5-1: a NORMAL subtask's prompt never carries the text of a parent that became RESTRICTED")
    void normalSubtaskPromptWithholdsRestrictedParentText() {
        UUID taskId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        AgentTaskEntity task = restrictedTask(taskId, agentId);
        task.setDataSensitivity(DataSensitivity.NORMAL.name());
        task.setParentTaskId(parentId);
        task.setDepth(1);
        AgentTaskEntity parent = restrictedTask(parentId, agentId);
        parent.setTitle("Chase invoice 9921 from ACME (from Gmail)");
        parent.setInstructions("Mail says: pay 12,400 EUR by Friday");
        when(taskRepository.findByIdAndOrganizationIdStrict(parentId, ORG)).thenReturn(Optional.of(parent));
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.of(task));
        when(self.tryLockAssigneeExecution(eq(taskId), any(UUID.class))).thenReturn(true);
        dispatchView(agentId, "deepseek", "deepseek-chat");
        when(conversationClient.findOrCreateAgentConversation(agentId.toString(), TENANT, "Worker", ORG))
                .thenReturn(AGENT_CONVERSATION);
        chatSucceeds();

        run("executeAgentForTask", task);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(conversationClient).sendChatSync(eq(TENANT), eq(AGENT_CONVERSATION), prompt.capture(), anyString(),
                anyString(), anyString(), eq("TASK"), anyString(), eq(ORG), isNull(), anyString(), eq(DataSensitivity.NORMAL));
        assertThat(prompt.getValue()).doesNotContain("invoice 9921").doesNotContain("12,400 EUR")
                .contains("task_get_context").contains(parentId.toString());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static AgentTaskEntity restrictedTask(UUID taskId, UUID agentId) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(taskId);
        task.setTenantId(TENANT);
        task.setOrganizationId(ORG);
        task.setAssignedToAgentId(agentId);
        task.setStatus(AgentTaskEntity.STATUS_IN_PROGRESS);
        task.setTitle("Pay the invoice from the last email");
        task.setInstructions("Use the IBAN in the mail");
        task.setDataSensitivity(DataSensitivity.RESTRICTED.name());
        return task;
    }

    private void dispatchView(UUID agentId, String provider, String model) {
        when(agentRepository.findTaskDispatchViewByIdAndOrganizationIdStrict(agentId, ORG))
                .thenReturn(Optional.of(new AgentTaskDispatchView(agentId, "Worker", provider, model, true)));
    }

    private void replacement(String provider, String model, String toProvider, String toModel) throws Exception {
        ModelReplacementResolver resolver = org.mockito.Mockito.mock(ModelReplacementResolver.class);
        when(resolver.substituteIfDisabled(provider, model)).thenReturn(Optional.of(
                new ModelReplacementResolver.Substitution(toProvider, toModel, provider, model, true)));
        Field f = AgentTaskService.class.getDeclaredField("modelReplacementResolver");
        f.setAccessible(true);
        f.set(service, resolver);
    }

    private void taskConversation(UUID agentId, UUID taskId) {
        when(conversationClient.findOrCreateTaskConversation(eq(agentId.toString()), eq(taskId.toString()),
                eq(TENANT), anyString(), eq(ORG))).thenReturn(TASK_CONVERSATION);
    }

    private void chatSucceeds() {
        when(conversationClient.sendChatSync(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyString(), any())).thenReturn(Map.of("success", true));
    }

    private void run(String methodName, AgentTaskEntity task) {
        TenantResolver.runWithOrgScope(ORG, () -> {
            try {
                Method method = AgentTaskService.class.getDeclaredMethod(methodName, AgentTaskEntity.class);
                method.setAccessible(true);
                method.invoke(service, task);
            } catch (InvocationTargetException e) {
                throw new AssertionError(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        });
    }
}
