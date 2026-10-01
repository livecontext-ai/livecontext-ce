package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService;
import com.apimarketplace.orchestrator.execution.v2.services.V2ExecutionEventService;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepContextManager;
import com.apimarketplace.orchestrator.execution.v2.services.V2StepByStepService;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.agent.AgentConversationManager;
import com.apimarketplace.orchestrator.services.completion.StepCompletionContext;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.resume.ExecutionContextManager;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code retryCount} and {@code timeoutMs} on an agent node that ran on the async queue.
 *
 * <p>The node yields, so NodePolicyRunner never sees the attempt end: before 2026-09-29 a queued
 * agent was attempted once whatever its retryCount, and its timeoutMs bounded only the dispatch.
 * The delivery now judges the attempt with the runner's own rule ({@code afterFailedAttempt}): a
 * failure the policy retries is reported as a non-final attempt and sent again through
 * {@link AgentAttemptScheduler}, without persisting, completing or traversing anything; every
 * other outcome carries the runner's annotations.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentAsyncCompletionService - nodePolicy retry and timeout on a queued agent")
class AgentAsyncCompletionAttemptPolicyTest {

    private static final String NODE = "agent:writer";

    @Mock private PendingAgentRegistry registry;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private SplitContextManager splitContextManager;
    @Mock private RunningNodeTracker runningNodeTracker;
    @Mock private SplitCoalesceTracker splitCoalesceTracker;
    @Mock private NodeSearchService nodeSearchService;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private V2StepByStepService v2StepByStepService;
    @Mock private WorkflowResumeService workflowResumeService;
    @Mock private ExecutionContextManager executionContextManager;
    @Mock private V2ExecutionEventService v2ExecutionEventService;
    @Mock private V2StepByStepContextManager v2StepByStepContextManager;
    @Mock private V2SkipPropagationService skipPropagationService;
    @Mock private com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService signalResumeService;
    @Mock private AgentAttemptScheduler attemptScheduler;
    @Mock private RedisPendingAgentStore pendingStore;
    @Mock private AgentConversationManager conversationManager;
    @Mock private com.apimarketplace.agent.client.AgentClient agentClient;
    @Mock private ExecutionTree tree;
    @Mock private ExecutionNode agentNode;
    @Mock private WorkflowRunEntity runEntity;
    @Mock private WorkflowExecution execution;
    @Mock private WorkflowPlan plan;

    private AgentAsyncCompletionService service;

    @BeforeEach
    void setUp() {
        service = new AgentAsyncCompletionService(
            registry, stepCompletionOrchestrator, splitContextManager,
            runningNodeTracker, splitCoalesceTracker, nodeSearchService, runRepository);
        ReflectionTestUtils.setField(service, "v2StepByStepService", v2StepByStepService);
        ReflectionTestUtils.setField(service, "workflowResumeService", workflowResumeService);
        ReflectionTestUtils.setField(service, "executionContextManager", executionContextManager);
        ReflectionTestUtils.setField(service, "v2ExecutionEventService", v2ExecutionEventService);
        ReflectionTestUtils.setField(service, "v2StepByStepContextManager", v2StepByStepContextManager);
        ReflectionTestUtils.setField(service, "signalResumeService", signalResumeService);
        ReflectionTestUtils.setField(service, "skipPropagationService", skipPropagationService);
        ReflectionTestUtils.setField(service, "attemptScheduler", attemptScheduler);
        ReflectionTestUtils.setField(service, "pendingStore", pendingStore);
        ReflectionTestUtils.setField(service, "conversationManager", conversationManager);
        ReflectionTestUtils.setField(service, "perItemTraversalEnabled", true);

        when(execution.getPlan()).thenReturn(plan);
        when(plan.findAgent(anyString())).thenReturn(Optional.empty());
        when(v2StepByStepContextManager.getTree(anyString())).thenReturn(tree);
        when(nodeSearchService.findNodeFromAllRoots(tree, NODE)).thenReturn(agentNode);
        when(agentNode.getNodeId()).thenReturn(NODE);
        WorkflowRunState state = mock(WorkflowRunState.class);
        when(workflowResumeService.reconstructState(anyString())).thenReturn(state);
        when(executionContextManager.rebuildExecutionContext(anyString(), any())).thenReturn(execution);
        when(runRepository.findByRunIdPublic(anyString())).thenReturn(Optional.of(runEntity));
        when(runEntity.getStatus()).thenReturn(RunStatus.RUNNING);
        when(attemptScheduler.canResend(anyString())).thenReturn(true);
        when(attemptScheduler.scheduleResend(any(), any(), anyLong())).thenReturn(true);
        when(attemptScheduler.claimRetry(anyString())).thenReturn(true);
    }

    private void policy(NodePolicy policy) {
        when(plan.getNodePolicy(NODE)).thenReturn(policy);
    }

    private PendingAgent deliverable(String correlationId, int attempt, Map<String, Object> splitItemData) {
        PendingAgent pending = new PendingAgent(correlationId, "run-1", NODE, "Writer", "trigger:cron", 2, 0, "0",
            "agent", "tenant-1", splitItemData, null, "conv-1", "stream-1", "exec-1", "model-x", "system", "user",
            Instant.now(), "org-1", null, attempt, 0L);
        when(registry.consume(correlationId)).thenReturn(Optional.of(pending));
        return pending;
    }

    private static AgentResultMessage failure(String correlationId, String error) {
        return new AgentResultMessage(correlationId, "run-1", NODE, null, false, error, "agent", Instant.now());
    }

    private static AgentResultMessage success(String correlationId) {
        return new AgentResultMessage(correlationId, "run-1", NODE, new HashMap<>(Map.of("text", "done")),
            true, null, "agent", Instant.now());
    }

    @Nested
    @DisplayName("A failure the policy retries")
    class Retried {

        @Test
        @DisplayName("REGRESSION: attempt 1 of 3 fails -> the next attempt is scheduled after the backoff, nothing is persisted, completed or traversed")
        void failedAttemptIsSentAgain() {
            policy(new NodePolicy(2, 750L, false));
            PendingAgent pending = deliverable("c1", 1, null);
            when(conversationManager.startExecution(eq("conv-1"), eq("user"), eq("tenant-1"), anyString(), eq("model-x"), eq(true)))
                .thenReturn(new AgentConversationManager.StreamSession("conv-1", "stream-2"));

            boolean delivered = service.onAgentResult(failure("c1", "Classification error: I/O error on POST request"));

            assertThat(delivered).isTrue();
            ArgumentCaptor<PendingAgent> next = ArgumentCaptor.forClass(PendingAgent.class);
            verify(attemptScheduler).scheduleResend(eq(pending), next.capture(), eq(750L));
            assertThat(next.getValue().attempt()).isEqualTo(2);
            assertThat(next.getValue().correlationId()).isNotEqualTo("c1");
            assertThat(next.getValue().executionId()).isNotEqualTo("exec-1");
            assertThat(next.getValue().streamId()).as("its own stream, prompt not saved again").isEqualTo("stream-2");
            // Reported as a non-final attempt: a step event annotated 1/3, no row.
            ArgumentCaptor<StepCompletionContext> reported = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).completeAttempt(reported.capture(), eq("trigger:cron"));
            assertThat(reported.getValue().result().status()).isEqualTo(NodeStatus.FAILED);
            assertThat(reported.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 1)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 3)
                .doesNotContainKey(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT);
            // The node is still running: no terminal completion, no tracker decrement, no cascade.
            verify(stepCompletionOrchestrator, never()).complete(any(), anyString());
            verify(runningNodeTracker, never()).markCompleted(anyString(), anyInt(), anyString());
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
            org.mockito.Mockito.verifyNoInteractions(v2StepByStepService);
        }

        @Test
        @DisplayName("a split item's failed attempt does not arrive at the barrier: only its final result will")
        void splitItemAttemptDoesNotArrive() {
            policy(new NodePolicy(1, 0L, false));
            deliverable("c1", 1, Map.of("splitNodeId", "core:split", "itemIndex", 0, "workflowItemIndex", 0));

            service.onAgentResult(failure("c1", "Bridge execution failed: no response from bridge server"));

            verify(attemptScheduler).scheduleResend(any(), any(), eq(0L));
            verify(splitCoalesceTracker, never()).arrive(anyString(), anyString(), anyInt(), anyInt(), any());
        }

        @Test
        @DisplayName("the last attempt ends the execution: persisted FAILED, annotated final, the kept request dropped")
        void lastAttemptEndsTheExecution() {
            policy(new NodePolicy(2, 750L, false));
            deliverable("c3", 3, null);

            service.onAgentResult(failure("c3", "Provider timeout"));

            verify(attemptScheduler, never()).scheduleResend(any(), any(), anyLong());
            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 3)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 3)
                .containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true)
                .doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
            verify(attemptScheduler).forget("c3");
            verify(skipPropagationService).cascadeFailureToSuccessors(
                any(), eq(agentNode), eq(0), eq(2), eq("trigger:cron"), eq(false), eq(V2SkipPropagationService.SOURCE_ASYNC));
        }

        @Test
        @DisplayName("REGRESSION: a continued failure at the tail of a loop body still iterates, as on the synchronous path")
        void continuedFailureAdvancesTheLoop() {
            policy(new NodePolicy(0, 0L, true));
            deliverable("c1", 1, null);

            service.onAgentResult(failure("c1", "Provider timeout"));

            verify(signalResumeService).advanceLoopBackEdgeForAsyncCompletedNode(
                eq("run-1"), any(), eq(NODE), eq(0), eq(2), eq("trigger:cron"), any());
        }

        @Test
        @DisplayName("an ordinary failure (not continued) does not advance the loop: it cascades")
        void plainFailureDoesNotAdvanceTheLoop() {
            policy(new NodePolicy(0, 0L, false, 30_000L, false, null));
            deliverable("c1", 1, null);

            service.onAgentResult(failure("c1", "Provider timeout"));

            verify(signalResumeService, never()).advanceLoopBackEdgeForAsyncCompletedNode(
                anyString(), any(), anyString(), anyInt(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("the last attempt of a continuing node is flagged and does not cascade")
        void lastAttemptOfAContinuingNodeContinues() {
            policy(new NodePolicy(1, 0L, true));
            deliverable("c2", 2, null);

            service.onAgentResult(failure("c2", "Provider timeout"));

            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true)
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 2);
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        }
    }

    @Nested
    @DisplayName("A delivery replayed after a crash")
    class Replayed {

        @Test
        @DisplayName("REGRESSION: a failure whose next attempt is already scheduled is neither recorded again nor ended a second time")
        void replayedFailureIsIgnored() {
            policy(new NodePolicy(2, 0L, false));
            PendingAgent pending = deliverable("c1", 1, null);
            when(attemptScheduler.isScheduled(AgentAttemptScheduler.nextCorrelationId(pending))).thenReturn(true);

            boolean delivered = service.onAgentResult(failure("c1", "Provider timeout"));

            assertThat(delivered).isTrue();
            verify(attemptScheduler, never()).scheduleResend(any(), any(), anyLong());
            verify(stepCompletionOrchestrator, never()).complete(any(), anyString());
            verify(stepCompletionOrchestrator, never()).completeAttempt(any(), anyString());
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("REGRESSION: a second delivery of the same failure racing the first (a startup replay) records nothing and ends nothing")
        void racingDeliveryRecordsNothing() {
            // Both judged "retry": the loser found the kept request already taken and persisted a
            // terminal FAILED (and its SKIPPED cascade) while the winning delivery's attempt was on its way.
            policy(new NodePolicy(2, 0L, false));
            deliverable("c1", 1, null);
            when(attemptScheduler.claimRetry("c1")).thenReturn(false);

            boolean delivered = service.onAgentResult(failure("c1", "Provider timeout"));

            assertThat(delivered).isTrue();
            verify(attemptScheduler, never()).scheduleResend(any(), any(), anyLong());
            verify(stepCompletionOrchestrator, never()).complete(any(), anyString());
            verify(stepCompletionOrchestrator, never()).completeAttempt(any(), anyString());
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("REGRESSION: a send that fails on the way (Redis) gives the judgement back, so the redelivery can make it")
        void aFailedSendReleasesTheJudgement() {
            // The claim used to stay held: the recovery scan redelivered the failure within the claim's
            // life, the redelivery lost the claim, and nothing was left to send the attempt.
            policy(new NodePolicy(2, 0L, false));
            deliverable("c1", 1, null);
            when(attemptScheduler.scheduleResend(any(), any(), anyLong())).thenThrow(new IllegalStateException("redis down"));

            service.onAgentResult(failure("c1", "Provider timeout"));

            verify(attemptScheduler).releaseRetry("c1");
            verify(stepCompletionOrchestrator, never()).complete(any(), anyString());
        }

        @Test
        @DisplayName("the attempt it schedules carries the derived correlation id the replay looks for")
        void scheduledAttemptUsesTheDerivedId() {
            policy(new NodePolicy(2, 0L, false));
            PendingAgent pending = deliverable("c1", 1, null);

            service.onAgentResult(failure("c1", "Provider timeout"));

            ArgumentCaptor<PendingAgent> next = ArgumentCaptor.forClass(PendingAgent.class);
            verify(attemptScheduler).scheduleResend(eq(pending), next.capture(), anyLong());
            assertThat(next.getValue().correlationId()).isEqualTo(AgentAttemptScheduler.nextCorrelationId(pending));
        }
    }

    /**
     * The replay protection with the REAL scheduler, over an in-memory Redis with the semantics it
     * relies on (SET NX, GETDEL, EXISTS): the tests above stub isScheduled and claimRetry, which
     * cannot tell whether the id the delivery derives is the one the scheduler registered.
     */
    @Nested
    @DisplayName("A replayed delivery, with the real scheduler")
    class ReplayedWithTheRealScheduler {

        private final Map<String, String> redis = new HashMap<>();
        private final java.util.List<Runnable> timerTasks = new java.util.ArrayList<>();
        private final PendingAgentRegistry schedulerRegistry = new PendingAgentRegistry();
        private AgentAttemptScheduler scheduler;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void realScheduler() {
            org.springframework.data.redis.core.StringRedisTemplate template =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
            org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
            when(template.opsForValue()).thenReturn(values);
            org.mockito.Mockito.doAnswer(inv -> redis.put(inv.getArgument(0), inv.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
            when(values.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenAnswer(inv -> redis.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
            when(values.get(anyString())).thenAnswer(inv -> redis.get((String) inv.getArgument(0)));
            when(values.getAndDelete(anyString())).thenAnswer(inv -> redis.remove((String) inv.getArgument(0)));
            when(template.hasKey(anyString())).thenAnswer(inv -> redis.containsKey((String) inv.getArgument(0)));
            java.util.concurrent.ScheduledExecutorService timer = mock(java.util.concurrent.ScheduledExecutorService.class);
            when(timer.schedule(any(Runnable.class), anyLong(), any(java.util.concurrent.TimeUnit.class)))
                .thenAnswer(inv -> {
                    timerTasks.add(inv.getArgument(0));
                    return null;
                });
            scheduler = new AgentAttemptScheduler(template, new com.fasterxml.jackson.databind.ObjectMapper(),
                schedulerRegistry, mock(com.apimarketplace.agent.client.queue.AgentQueueProducer.class), timer);
            ReflectionTestUtils.setField(service, "attemptScheduler", scheduler);
            policy(new NodePolicy(2, 0L, false));
            Map<String, Object> payload = new HashMap<>(Map.of("prompt", "p", "executionId", "exec-1"));
            scheduler.onDispatched(plan, NODE, new com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage(
                "c1", "run-1", NODE, "tenant-1", "agent", "deepseek", "model-x", payload, "ROLE_USER",
                com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage.CURRENT_SCHEMA_VERSION));
        }

        @Test
        @DisplayName("REGRESSION: the failure replayed after its retry was scheduled finds that attempt and records nothing")
        void replayFindsTheAttemptItScheduled() {
            PendingAgent pending = deliverable("c1", 1, null);
            service.onAgentResult(failure("c1", "Provider timeout"));
            String next = AgentAttemptScheduler.nextCorrelationId(pending);
            assertThat(schedulerRegistry.peek(next)).as("the retry is registered under the derived id").isPresent();
            assertThat(timerTasks).hasSize(1);

            boolean replayed = service.replayInFlightResult(
                new RedisInFlightStore.InFlightEntry(pending, failure("c1", "Provider timeout")));

            assertThat(replayed).isTrue();
            assertThat(timerTasks).as("no second send scheduled").hasSize(1);
            verify(stepCompletionOrchestrator, org.mockito.Mockito.times(1)).completeAttempt(any(), anyString());
            verify(stepCompletionOrchestrator, never()).complete(any(), anyString());
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("REGRESSION: a replay landing after the winner took the kept request, before it registered the retry, records nothing")
        void replayBetweenTheTakeAndTheRegistrationRecordsNothing() {
            // It looked for the kept request before the claim, found it gone, and persisted attempt 1
            // as the terminal FAILED row (with its SKIPPED cascade) while attempt 2 was being sent.
            deliverable("c1", 1, null);
            redis.put(AgentAttemptScheduler.JUDGED_KEY_PREFIX + "c1", "1");
            redis.remove(AgentAttemptScheduler.REQUEST_KEY_PREFIX + "c1");

            boolean delivered = service.onAgentResult(failure("c1", "Provider timeout"));

            assertThat(delivered).isTrue();
            verify(stepCompletionOrchestrator, never()).complete(any(), anyString());
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("a claim left by a delivery that died expires, and the replay after the restart judges the failure again")
        void aClaimLeftByADeadDeliveryExpires() {
            PendingAgent pending = deliverable("c1", 1, null);
            redis.put(AgentAttemptScheduler.JUDGED_KEY_PREFIX + "c1", "1");
            service.onAgentResult(failure("c1", "Provider timeout"));
            assertThat(timerTasks).as("held by the dead delivery: nothing sent yet").isEmpty();

            redis.remove(AgentAttemptScheduler.JUDGED_KEY_PREFIX + "c1"); // its 10 s ran out during the restart
            service.replayInFlightResult(new RedisInFlightStore.InFlightEntry(pending, failure("c1", "Provider timeout")));

            assertThat(timerTasks).as("the replay sends the retry").hasSize(1);
            assertThat(schedulerRegistry.peek(AgentAttemptScheduler.nextCorrelationId(pending))).isPresent();
        }

        @Test
        @DisplayName("a delivery that loses the judgement to a racing one leaves the kept request to the winner")
        void losingDeliveryLeavesTheRequest() {
            deliverable("c1", 1, null);
            redis.put(AgentAttemptScheduler.JUDGED_KEY_PREFIX + "c1", "1");

            service.onAgentResult(failure("c1", "Provider timeout"));

            assertThat(timerTasks).isEmpty();
            assertThat(scheduler.canResend("c1")).as("the request is still there for the winner").isTrue();
            verify(stepCompletionOrchestrator, never()).completeAttempt(any(), anyString());
            verify(stepCompletionOrchestrator, never()).complete(any(), anyString());
        }
    }

    @Nested
    @DisplayName("A failure that ends the execution before its last attempt")
    class NotRetried {

        @Test
        @DisplayName("REGRESSION: a budget refusal on a continuing node is not continued: it cascades like any stop for money")
        void budgetRefusalIsNotContinued() {
            policy(new NodePolicy(0, 0L, true));
            deliverable("c1", 1, null);

            service.onAgentResult(failure("c1", "Insufficient credits (pre-flight tenant budget)"));

            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
            verify(skipPropagationService).cascadeFailureToSuccessors(
                any(), eq(agentNode), eq(0), eq(2), eq("trigger:cron"), eq(false), eq(V2SkipPropagationService.SOURCE_ASYNC));
        }

        @Test
        @DisplayName("a classify or guardrail never continues past its failure, whatever its stored policy says")
        void branchingAgentIsNotContinued() {
            policy(new NodePolicy(0, 0L, true));
            com.apimarketplace.orchestrator.domain.workflow.Agent classify =
                mock(com.apimarketplace.orchestrator.domain.workflow.Agent.class);
            when(classify.type()).thenReturn("classify");
            when(plan.findAgent(NODE)).thenReturn(Optional.of(classify));
            deliverable("c1", 1, null);

            service.onAgentResult(failure("c1", "Provider timeout"));

            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
        }

        @Test
        @DisplayName("an exhausted credit budget is not sent again and says why")
        void budgetRefusalStops() {
            policy(new NodePolicy(3, 0L, false));
            deliverable("c1", 1, null);

            service.onAgentResult(failure("c1", "Insufficient credits (pre-flight tenant budget)"));

            verify(attemptScheduler, never()).scheduleResend(any(), any(), anyLong());
            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "permanent_refusal")
                .containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true);
        }

        @Test
        @DisplayName("a workflow that reached its credit cap is not sent again: the resend would skip the gate a synchronous retry meets")
        void workflowBudgetReachedStops() {
            policy(new NodePolicy(3, 0L, false));
            deliverable("c1", 1, null);
            com.apimarketplace.orchestrator.services.credit.WorkflowBudgetState capReached =
                mock(com.apimarketplace.orchestrator.services.credit.WorkflowBudgetState.class);
            when(capReached.blocksAt(any())).thenReturn(true);
            when(runRepository.findBudgetStateByRunIdPublic("run-1")).thenReturn(Optional.of(capReached));

            service.onAgentResult(failure("c1", "Provider timeout"));

            verify(attemptScheduler, never()).scheduleResend(any(), any(), anyLong());
            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "permanent_refusal");
        }

        @Test
        @DisplayName("REGRESSION: a workflow at its credit cap is not continued past either, as a synchronous retry's budget refusal would not be")
        void workflowBudgetReachedIsNotContinued() {
            policy(new NodePolicy(3, 0L, true));
            deliverable("c1", 1, null);
            com.apimarketplace.orchestrator.services.credit.WorkflowBudgetState capReached =
                mock(com.apimarketplace.orchestrator.services.credit.WorkflowBudgetState.class);
            when(capReached.blocksAt(any())).thenReturn(true);
            when(runRepository.findBudgetStateByRunIdPublic("run-1")).thenReturn(Optional.of(capReached));

            service.onAgentResult(failure("c1", "Provider timeout"));

            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, "permanent_refusal")
                .doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
            verify(skipPropagationService).cascadeFailureToSuccessors(
                any(), eq(agentNode), eq(0), eq(2), eq("trigger:cron"), eq(false), eq(V2SkipPropagationService.SOURCE_ASYNC));
        }

        @Test
        @DisplayName("a stopped run is not sent again: the failure is persisted and nothing is traversed")
        void stoppedRunIsNotRetried() {
            policy(new NodePolicy(3, 0L, false));
            deliverable("c1", 1, null);
            when(runEntity.getStatus()).thenReturn(RunStatus.CANCELLED);

            service.onAgentResult(failure("c1", "Workflow cancelled before agent execution started"));

            verify(attemptScheduler, never()).scheduleResend(any(), any(), anyLong());
            verify(stepCompletionOrchestrator).complete(any(), eq("trigger:cron"));
            verify(skipPropagationService, never()).cascadeFailureToSuccessors(
                any(), any(), anyInt(), anyInt(), any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("a request that was not kept (dispatched before this release) ends the execution, as before")
        void notKeptRequestEnds() {
            policy(new NodePolicy(3, 0L, false));
            deliverable("c1", 1, null);
            when(attemptScheduler.canResend("c1")).thenReturn(false);

            service.onAgentResult(failure("c1", "Provider timeout"));

            verify(attemptScheduler, never()).scheduleResend(any(), any(), anyLong());
            verify(stepCompletionOrchestrator).complete(any(), eq("trigger:cron"));
        }

        @Test
        @DisplayName("if scheduling the next attempt fails, the attempt ends the execution instead of vanishing")
        void failedSchedulingEnds() {
            policy(new NodePolicy(3, 0L, false));
            deliverable("c1", 1, null);
            when(attemptScheduler.scheduleResend(any(), any(), anyLong())).thenReturn(false);

            service.onAgentResult(failure("c1", "Provider timeout"));

            verify(stepCompletionOrchestrator, never()).completeAttempt(any(), anyString());
            verify(stepCompletionOrchestrator).complete(any(), eq("trigger:cron"));
        }
    }

    @Nested
    @DisplayName("Annotations and timeouts")
    class Annotations {

        @Test
        @DisplayName("a success on attempt 2 carries policy_attempt 2 of 3 and final, like a synchronous node")
        void successAfterARetryIsAnnotated() {
            policy(new NodePolicy(2, 0L, false));
            deliverable("c2", 2, null);

            service.onAgentResult(success("c2"));

            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 2)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 3)
                .containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true)
                .containsEntry("text", "done");
        }

        @Test
        @DisplayName("a node without a policy is delivered exactly as before: no annotation, nothing kept to forget")
        void noPolicyUnchanged() {
            policy(null);
            deliverable("c1", 1, null);

            service.onAgentResult(failure("c1", "Provider timeout"));

            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().output())
                .doesNotContainKeys(ExecutionMetadataKeys.POLICY_ATTEMPT, ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT);
            verify(attemptScheduler, never()).canResend(anyString());
            verify(attemptScheduler, never()).forget(anyString());
        }

        @Test
        @DisplayName("the published timeout is a failed attempt flagged policy_timeout; its attempt is kept to bill a late answer")
        void timeoutIsAFailedAttempt() {
            policy(new NodePolicy(0, 0L, false, 30_000L, false, null));
            PendingAgent pending = deliverable("c1", 1, null);

            service.onAgentResult(new AgentResultMessage("c1", "run-1", NODE,
                new HashMap<>(AgentAttemptScheduler.timeoutFailure(30_000L)), false,
                "TIMEOUT: no answer from the agent within 30000 ms", "agent", Instant.now()));

            verify(pendingStore).storeTimedOut(pending);
            ArgumentCaptor<StepCompletionContext> persisted = ArgumentCaptor.forClass(StepCompletionContext.class);
            verify(stepCompletionOrchestrator).complete(persisted.capture(), eq("trigger:cron"));
            assertThat(persisted.getValue().result().status()).isEqualTo(NodeStatus.FAILED);
            assertThat(persisted.getValue().result().output())
                .containsEntry(ExecutionMetadataKeys.POLICY_TIMEOUT, true)
                .containsEntry(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, true);
        }

        @Test
        @DisplayName("a timeout replayed after a crash keeps its attempt for a late answer too")
        void replayedTimeoutKeepsItsAttempt() {
            policy(new NodePolicy(0, 0L, false, 30_000L, false, null));
            PendingAgent pending = new PendingAgent("c1", "run-1", NODE, "Writer", "trigger:cron", 2, 0, "0",
                "agent", "tenant-1", null, null, "conv-1", "stream-1", "exec-1", "model-x", "system", "user",
                Instant.now(), "org-1", null, 1, 30_000L);

            service.replayInFlightResult(new RedisInFlightStore.InFlightEntry(pending, new AgentResultMessage("c1", "run-1", NODE,
                new HashMap<>(AgentAttemptScheduler.timeoutFailure(30_000L)), false,
                "TIMEOUT: no answer from the agent within 30000 ms", "agent", Instant.now())));

            verify(pendingStore).storeTimedOut(pending);
        }

        @Test
        @DisplayName("a timed-out attempt with retries left is sent again")
        void timeoutIsRetried() {
            policy(new NodePolicy(1, 200L, false, 30_000L, false, null));
            deliverable("c1", 1, null);

            service.onAgentResult(new AgentResultMessage("c1", "run-1", NODE,
                new HashMap<>(AgentAttemptScheduler.timeoutFailure(30_000L)), false,
                "TIMEOUT: no answer from the agent within 30000 ms", "agent", Instant.now()));

            verify(attemptScheduler).scheduleResend(any(), any(), eq(200L));
        }
    }

    @Nested
    @DisplayName("An answer after its attempt timed out")
    class LateAnswer {

        @Test
        @DisplayName("is billed once and not delivered")
        void lateAnswerIsBilledNotDelivered() {
            ReflectionTestUtils.setField(service, "agentClient", agentClient);
            PendingAgent timedOut = new PendingAgent("c-late", "run-1", NODE, "Writer", "trigger:cron", 2, 0, "0",
                "agent", "tenant-1", null, null, null, null, "exec-1", "model-x", "system", "user",
                Instant.now(), "org-1", null, 1, 30_000L);
            when(registry.consume("c-late")).thenReturn(Optional.empty());
            when(pendingStore.claimTimedOut("c-late")).thenReturn(Optional.of(timedOut));
            when(plan.findAgent(NODE)).thenReturn(Optional.of(mock(com.apimarketplace.orchestrator.domain.workflow.Agent.class)));

            boolean delivered = service.onAgentResult(success("c-late"));

            assertThat(delivered).isFalse();
            verify(agentClient).recordObservability(any());
            verify(stepCompletionOrchestrator, never()).complete(any(), anyString());
        }

        @Test
        @DisplayName("the published timeout itself, received by another replica, is never billed")
        void publishedTimeoutIsNotBilled() {
            when(registry.consume("c1")).thenReturn(Optional.empty());

            service.onAgentResult(new AgentResultMessage("c1", "run-1", NODE,
                new HashMap<>(AgentAttemptScheduler.timeoutFailure(30_000L)), false, "TIMEOUT", "agent", Instant.now()));

            verify(pendingStore, never()).claimTimedOut(anyString());
        }
    }
}
