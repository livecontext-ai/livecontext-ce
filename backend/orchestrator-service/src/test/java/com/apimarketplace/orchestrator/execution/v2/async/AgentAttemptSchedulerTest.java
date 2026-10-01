package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage;
import com.apimarketplace.agent.client.queue.AgentQueueProducer;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.services.resume.RunCancellationGuard;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentAttemptScheduler}: the retry and the timeout of a queued agent node, which
 * NodePolicyRunner cannot apply because the node yields. Redis is an in-memory map with the
 * semantics the scheduler relies on (SET NX, GETDEL, publish), the timer records its tasks and
 * runs them on demand, and the registry is the real one.
 */
@DisplayName("AgentAttemptScheduler - retry and timeout of a queued agent")
class AgentAttemptSchedulerTest {

    private static final String NODE = "agent:writer";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> redisValues = new HashMap<>();
    private final List<String[]> published = new ArrayList<>();
    private final List<Runnable> timerTasks = new ArrayList<>();
    private final List<Long> timerDelays = new ArrayList<>();

    private ValueOperations<String, String> values;
    private PendingAgentRegistry registry;
    private AgentQueueProducer producer;
    private RunCancellationGuard cancellationGuard;
    private AgentAttemptScheduler scheduler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(inv -> redisValues.put(inv.getArgument(0), inv.getArgument(1)))
            .when(values).set(anyString(), anyString(), any(Duration.class));
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv ->
            redisValues.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        when(values.get(anyString())).thenAnswer(inv -> redisValues.get((String) inv.getArgument(0)));
        when(values.getAndDelete(anyString())).thenAnswer(inv -> redisValues.remove((String) inv.getArgument(0)));
        when(redis.hasKey(anyString())).thenAnswer(inv -> redisValues.containsKey((String) inv.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(inv -> redisValues.remove((String) inv.getArgument(0)) != null);
        when(redis.delete(any(Collection.class))).thenAnswer(inv -> {
            long n = 0;
            for (Object key : (Collection<?>) inv.getArgument(0)) n += redisValues.remove((String) key) != null ? 1 : 0;
            return n;
        });
        doAnswer(inv -> published.add(new String[]{inv.getArgument(0), inv.getArgument(1)}))
            .when(redis).convertAndSend(anyString(), any());

        ScheduledExecutorService timer = mock(ScheduledExecutorService.class);
        when(timer.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            timerTasks.add(inv.getArgument(0));
            timerDelays.add(inv.getArgument(1));
            return null;
        });

        registry = new PendingAgentRegistry();
        producer = mock(AgentQueueProducer.class);
        cancellationGuard = mock(RunCancellationGuard.class);
        scheduler = new AgentAttemptScheduler(redis, objectMapper, registry, producer, timer);
        scheduler.setRunCancellationGuard(cancellationGuard);
    }

    private static PendingAgent attempt(String correlationId, int attempt, long timeoutMs, Instant startedAt) {
        return new PendingAgent(correlationId, "run-1", NODE, "writer", "trigger:start", 1, 0, "item-0",
            "agent", "tenant-1", null, Map.of("prompt", "p"), "conv-1", "stream-1", "exec-" + correlationId,
            "model-x", "system", "user", startedAt, "org-1", null, attempt, timeoutMs);
    }

    private static AgentExecutionRequestMessage request(String correlationId, String executionId) {
        Map<String, Object> credentials = new HashMap<>(Map.of("__executionId__", executionId, "__orgId__", "org-1"));
        Map<String, Object> payload = new HashMap<>(Map.of("prompt", "p", "executionId", executionId,
            "credentials", credentials, "conversationId", "conv-1"));
        return new AgentExecutionRequestMessage(correlationId, "run-1", NODE, "tenant-1", "agent", "deepseek",
            "model-x", payload, "ROLE_USER", AgentExecutionRequestMessage.CURRENT_SCHEMA_VERSION);
    }

    private static WorkflowPlan planWith(NodePolicy policy) {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getNodePolicy(NODE)).thenReturn(policy);
        return plan;
    }

    private Map<String, Object> stored(String correlationId) throws Exception {
        String json = redisValues.get(AgentAttemptScheduler.REQUEST_KEY_PREFIX + correlationId);
        return json == null ? null : objectMapper.readValue(json, new TypeReference<>() {});
    }

    @Nested
    @DisplayName("Dispatch")
    class Dispatch {

        @Test
        @DisplayName("a node that may retry keeps its request, marked as already sent")
        void keepsTheRequestWhenTheNodeMayRetry() throws Exception {
            scheduler.onDispatched(planWith(new NodePolicy(2, 500L, false)), NODE, request("c1", "e1"));

            assertThat(stored("c1")).containsEntry("sendAtMs", 0);
            assertThat(scheduler.canResend("c1")).isTrue();
            assertThat(timerTasks).isEmpty();
        }

        @Test
        @DisplayName("a node without retries keeps nothing; a node with a timeout gets a timer of that length")
        void timeoutOnlyKeepsNothingAndArmsTheTimer() {
            scheduler.onDispatched(planWith(new NodePolicy(0, 0L, false, 45_000L, false, null)), NODE, request("c1", "e1"));

            assertThat(redisValues).isEmpty();
            assertThat(timerDelays).containsExactly(45_000L);
        }

        @Test
        @DisplayName("a node without a policy is untouched")
        void noPolicyNoWork() {
            scheduler.onDispatched(planWith(null), NODE, request("c1", "e1"));

            assertThat(redisValues).isEmpty();
            assertThat(timerTasks).isEmpty();
        }
    }

    @Nested
    @DisplayName("Retry")
    class Retry {

        @Test
        @DisplayName("the next attempt is registered at once and sent after the wait, with its own correlation and execution ids")
        void resendsTheSameRequestAfterTheWait() throws Exception {
            scheduler.onDispatched(planWith(new NodePolicy(2, 500L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            PendingAgent next = failed.nextAttempt("c2", "e2", "stream-2", Instant.now().plusMillis(500));

            assertThat(scheduler.scheduleResend(failed, next, 500L)).isTrue();

            assertThat(registry.peek("c2")).contains(next);
            assertThat(stored("c1")).as("the failed attempt's request is taken").isNull();
            assertThat(timerDelays).containsExactly(500L);
            verify(producer, never()).enqueue(any());

            timerTasks.get(0).run();

            ArgumentCaptor<AgentExecutionRequestMessage> sent = ArgumentCaptor.forClass(AgentExecutionRequestMessage.class);
            verify(producer).enqueue(sent.capture());
            assertThat(sent.getValue().correlationId()).isEqualTo("c2");
            assertThat(sent.getValue().requestPayload()).containsEntry("executionId", "e2").containsEntry("prompt", "p");
            assertThat((Map<String, Object>) sent.getValue().requestPayload().get("credentials"))
                .containsEntry("__executionId__", "e2").containsEntry("__orgId__", "org-1");
            assertThat(sent.getValue().userRoles()).isEqualTo("ROLE_USER");
            assertThat(stored("c2")).as("kept for a further retry, marked sent").containsEntry("sendAtMs", 0);
            assertThat(registry.peek("c2").orElseThrow().startedAt())
                .as("the attempt's clocks start when it leaves").isAfterOrEqualTo(next.startedAt().minusMillis(500));
        }

        @Test
        @DisplayName("without a kept request nothing is scheduled and the caller ends the execution")
        void noKeptRequestNoRetry() {
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());

            assertThat(scheduler.scheduleResend(failed, failed.nextAttempt("c2", null, null, Instant.now()), 100L)).isFalse();

            assertThat(registry.peek("c2")).isEmpty();
            assertThat(timerTasks).isEmpty();
        }

        @Test
        @DisplayName("the timer and the recovery scan both trying sends the attempt once")
        void sentOnce() {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 0L);

            timerTasks.get(0).run();
            scheduler.sendIfDue("c2");

            verify(producer, times(1)).enqueue(any());
        }

        @Test
        @DisplayName("an attempt whose run was stopped during the wait is dropped, not sent")
        void stoppedRunDropsTheAttempt() throws Exception {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 0L);
            when(cancellationGuard.isRunStoppedOrTerminal("run-1")).thenReturn(true);

            timerTasks.get(0).run();

            verify(producer, never()).enqueue(any());
            assertThat(registry.peek("c2")).isEmpty();
            assertThat(stored("c2")).isNull();
        }

        @Test
        @DisplayName("an attempt removed from the registry (run cancelled elsewhere) is not sent")
        void removedAttemptIsNotSent() {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 0L);
            registry.removeByRunId("run-1");

            timerTasks.get(0).run();

            verify(producer, never()).enqueue(any());
        }

        @Test
        @DisplayName("a failed send releases its claim, so the recovery scan sends it later")
        void failedSendIsRetriedByRecovery() {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 0L);
            doThrow(new RuntimeException("redis down")).doNothing().when(producer).enqueue(any());

            timerTasks.get(0).run();
            // A scan pass after the grace: the attempt is still due and unclaimed.
            redisValues.computeIfPresent(AgentAttemptScheduler.REQUEST_KEY_PREFIX + "c2",
                (k, v) -> v.replaceAll("\"sendAtMs\":\\d+", "\"sendAtMs\":1"));
            scheduler.recover(registry.peek("c2").orElseThrow());

            verify(producer, times(2)).enqueue(any());
            assertThat(registry.peek("c2")).as("still pending until its answer").isPresent();
        }

        @Test
        @DisplayName("a timer that refuses the task (instance shutting down) does not fail the retry: the recovery scan sends it")
        void refusedTimerIsALostTimer() {
            ScheduledExecutorService refusing = mock(ScheduledExecutorService.class);
            when(refusing.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("shutting down"));
            StringRedisTemplate redis = (StringRedisTemplate) org.springframework.test.util.ReflectionTestUtils
                .getField(scheduler, "redisTemplate");
            AgentAttemptScheduler stopping = new AgentAttemptScheduler(redis, objectMapper, registry, producer, refusing);
            stopping.setRunCancellationGuard(cancellationGuard);
            stopping.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());

            assertThat(stopping.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 0L)).isTrue();
            assertThat(registry.peek("c2")).as("registered: a live instance's scan will find it").isPresent();

            redisValues.computeIfPresent(AgentAttemptScheduler.REQUEST_KEY_PREFIX + "c2",
                (k, v) -> v.replaceAll("\"sendAtMs\":\\d+", "\"sendAtMs\":1"));
            stopping.recover(registry.peek("c2").orElseThrow());
            verify(producer, times(1)).enqueue(any());
        }

        @Test
        @DisplayName("an attempt another instance already claimed is not sent here")
        void claimedElsewhereIsNotSent() {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 0L);
            redisValues.put(AgentAttemptScheduler.SENT_KEY_PREFIX + "c2", "1");

            timerTasks.get(0).run();

            verify(producer, never()).enqueue(any());
        }

        @Test
        @DisplayName("the attempt is marked sent BEFORE it leaves: an answer that comes back at once finds no stale schedule")
        void markedSentBeforeItLeaves() {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 0L);
            java.util.concurrent.atomic.AtomicReference<String> keptWhenSent = new java.util.concurrent.atomic.AtomicReference<>();
            doAnswer(inv -> {
                keptWhenSent.set(redisValues.get(AgentAttemptScheduler.REQUEST_KEY_PREFIX + "c2"));
                return null;
            }).when(producer).enqueue(any());

            timerTasks.get(0).run();

            assertThat(keptWhenSent.get()).contains("\"sendAtMs\":0");
        }

        @Test
        @DisplayName("the next correlation id is derived from the failed one, so a replayed failure finds its scheduled attempt")
        void nextCorrelationIdIsDeterministic() {
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());

            String next = AgentAttemptScheduler.nextCorrelationId(failed);

            assertThat(next).isEqualTo(AgentAttemptScheduler.nextCorrelationId(attempt("c1", 1, 0L, Instant.now())));
            assertThat(next).isNotEqualTo(AgentAttemptScheduler.nextCorrelationId(attempt("c1", 2, 0L, Instant.now())));
            assertThat(next).isNotEqualTo(AgentAttemptScheduler.nextCorrelationId(attempt("c9", 1, 0L, Instant.now())));
            assertThat(scheduler.isScheduled(next)).isFalse();

            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            scheduler.scheduleResend(failed, failed.nextAttempt(next, "e2", null, Instant.now()), 60_000L);

            assertThat(scheduler.isScheduled(next)).isTrue();
        }

        @Test
        @DisplayName("REGRESSION: a kept request alone is not a scheduled attempt: one never registered is never sent")
        void keptRequestAloneIsNotScheduled() {
            // A crash between keeping the next attempt's request and registering it used to make a
            // replayed delivery of the failure believe that attempt was on its way: nothing sent it,
            // and the node stayed RUNNING until the run was failed as a zombie.
            redisValues.put(AgentAttemptScheduler.REQUEST_KEY_PREFIX + "c2", "{\"message\":{},\"sendAtMs\":1}");
            assertThat(scheduler.isScheduled("c2")).isFalse();

            redisValues.put(RedisPendingAgentStore.KEY_PREFIX + "c2", "{}");
            assertThat(scheduler.isScheduled("c2")).as("pending in Redis, registered by another instance").isTrue();
        }

        @Test
        @DisplayName("one delivery of a failed attempt judges it: a second delivery of the same failure is refused, another failure is not")
        void theRetryOfAFailureIsJudgedOnce() {
            assertThat(scheduler.claimRetry("c1")).isTrue();
            assertThat(scheduler.claimRetry("c1")).isFalse();
            assertThat(scheduler.claimRetry("c9")).isTrue();
            verify(values, times(2)).setIfAbsent(AgentAttemptScheduler.JUDGED_KEY_PREFIX + "c1", "1", Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("a released judgement can be claimed again: the redelivery of a send that failed on the way judges it")
        void aReleasedJudgementCanBeClaimedAgain() {
            assertThat(scheduler.claimRetry("c1")).isTrue();

            scheduler.releaseRetry("c1");

            assertThat(scheduler.claimRetry("c1")).isTrue();
        }

        @Test
        @DisplayName("the send claim is short: a crash between the claim and the send leaves the attempt to the recovery scan a minute later, not for hours")
        void theSendClaimIsShort() {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 0L);

            timerTasks.get(0).run();

            verify(values).setIfAbsent(AgentAttemptScheduler.SENT_KEY_PREFIX + "c2", "1", Duration.ofSeconds(60));
            verify(producer).enqueue(any());
        }

        @Test
        @DisplayName("forget drops what was kept for an attempt that ended")
        void forgetDropsTheKeptRequest() throws Exception {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));

            scheduler.forget("c1");

            assertThat(stored("c1")).isNull();
            assertThat(scheduler.canResend("c1")).isFalse();
        }
    }

    @Nested
    @DisplayName("Timeout")
    class Timeout {

        @Test
        @DisplayName("an attempt still waiting at its timeout ends as a failure flagged policy_timeout, published like a worker answer")
        void publishesTheTimeout() throws Exception {
            registry.register(attempt("c1", 1, 30_000L, Instant.now()));

            scheduler.expireIfWaiting("c1");

            String json = redisValues.get(AgentAttemptScheduler.RESULT_KEY_PREFIX + "c1");
            Map<String, Object> answer = objectMapper.readValue(json, new TypeReference<>() {});
            assertThat(answer).containsEntry("success", false).containsEntry(ExecutionMetadataKeys.POLICY_TIMEOUT, true);
            assertThat((String) answer.get("error")).startsWith("TIMEOUT: no answer from the agent within 30000 ms");
            assertThat(AgentAttemptScheduler.isPolicyTimeout(answer)).isTrue();
            assertThat(published).hasSize(1);
            assertThat(published.get(0)[0]).isEqualTo(AgentAttemptScheduler.RESULT_CHANNEL_PREFIX + "c1");
        }

        @Test
        @DisplayName("an attempt already answered is left alone")
        void answeredAttemptIsLeftAlone() {
            scheduler.expireIfWaiting("c-answered");

            assertThat(published).isEmpty();
        }

        @Test
        @DisplayName("an answer already written by the worker wins over the timeout")
        void workerAnswerWins() {
            registry.register(attempt("c1", 1, 30_000L, Instant.now()));
            redisValues.put(AgentAttemptScheduler.RESULT_KEY_PREFIX + "c1", "{\"success\":true}");

            scheduler.expireIfWaiting("c1");

            assertThat(published).isEmpty();
            assertThat(redisValues.get(AgentAttemptScheduler.RESULT_KEY_PREFIX + "c1")).isEqualTo("{\"success\":true}");
        }

        @Test
        @DisplayName("a worker failure is not mistaken for the published timeout")
        void workerFailureIsNotATimeout() {
            assertThat(AgentAttemptScheduler.isPolicyTimeout(Map.of("success", false, "error", "boom"))).isFalse();
            assertThat(AgentAttemptScheduler.isPolicyTimeout(Map.of("synthetic", true, "error", "Hard timeout"))).isFalse();
        }
    }

    @Nested
    @DisplayName("Recovery (the timers were lost with their instance)")
    class Recovery {

        @Test
        @DisplayName("an overdue unsent attempt is sent; one not yet due is not")
        void sendsOnlyOverdueAttempts() {
            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 0L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now()), 60_000L);

            scheduler.recover(registry.peek("c2").orElseThrow());
            verify(producer, never()).enqueue(any());

            redisValues.computeIfPresent(AgentAttemptScheduler.REQUEST_KEY_PREFIX + "c2",
                (k, v) -> v.replaceAll("\"sendAtMs\":\\d+", "\"sendAtMs\":1"));
            scheduler.recover(registry.peek("c2").orElseThrow());
            verify(producer, times(1)).enqueue(any());
        }

        @Test
        @DisplayName("a sent attempt past its timeout is ended; one within it is not; an unsent one never is")
        void expiresOnlyOverdueSentAttempts() {
            scheduler.recover(attempt("c-in-time", 1, 60_000L, Instant.now()));
            registry.register(attempt("c-late", 1, 1_000L, Instant.now().minusSeconds(60)));
            scheduler.recover(registry.peek("c-late").orElseThrow());

            assertThat(published).extracting(p -> p[0])
                .containsExactly(AgentAttemptScheduler.RESULT_CHANNEL_PREFIX + "c-late");

            scheduler.onDispatched(planWith(new NodePolicy(2, 0L, false)), NODE, request("c1", "e1"));
            PendingAgent failed = attempt("c1", 1, 1_000L, Instant.now());
            scheduler.scheduleResend(failed, failed.nextAttempt("c2", "e2", null, Instant.now().minusSeconds(60)), 60_000L);
            scheduler.recover(registry.peek("c2").orElseThrow());
            assertThat(published).as("not sent yet: no timeout can run").hasSize(1);
        }
    }

    @Test
    @DisplayName("forAttempt changes only the correlation and execution ids")
    void forAttemptChangesOnlyTheIds() {
        AgentExecutionRequestMessage original = request("c1", "e1");
        PendingAgent next = attempt("c1", 1, 0L, Instant.now()).nextAttempt("c2", "e2", null, Instant.now());

        AgentExecutionRequestMessage resent = AgentAttemptScheduler.forAttempt(original, next);

        assertThat(resent.correlationId()).isEqualTo("c2");
        assertThat(resent.requestPayload()).containsEntry("executionId", "e2").containsEntry("conversationId", "conv-1");
        assertThat(original.requestPayload()).as("the kept request is not mutated").containsEntry("executionId", "e1");
        assertThat(resent).usingRecursiveComparison().ignoringFields("correlationId", "requestPayload").isEqualTo(original);
    }
}
