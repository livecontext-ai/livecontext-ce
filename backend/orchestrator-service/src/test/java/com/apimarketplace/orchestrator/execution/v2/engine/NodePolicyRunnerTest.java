package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link NodePolicyRunner} - the single generic application point
 * for per-node execution policies (retry / backoff / continue-on-failure).
 *
 * <p>Backoff is verified with a RECORDING sleeper (virtual clock) - no real sleeps.
 */
@DisplayName("NodePolicyRunner - generic per-node retry/continuation wrapper")
class NodePolicyRunnerTest {

    private static final String NODE = "mcp:flaky";

    /** Virtual clock: records requested sleeps instead of sleeping. */
    private final List<Long> recordedSleeps = new ArrayList<>();
    private final NodePolicyRunner runner = new NodePolicyRunner(recordedSleeps::add);

    private static NodeExecutionResult success() {
        return NodeExecutionResult.success(NODE, Map.of("ok", true));
    }

    private static NodeExecutionResult failure(String msg) {
        return NodeExecutionResult.failure(NODE, msg);
    }

    // =====================================================================
    // Default policy = byte-identical passthrough
    // =====================================================================

    @Nested
    @DisplayName("Default policy (no nodePolicy block)")
    class DefaultPolicy {

        @Test
        @DisplayName("invokes exactly once and returns the result UNTOUCHED (no attempt annotation)")
        void defaultPolicyIsPurePassthrough() throws Exception {
            AtomicInteger invocations = new AtomicInteger();
            NodeExecutionResult original = failure("boom");

            NodeExecutionResult result = runner.run(NodePolicy.DEFAULT, NODE, () -> {
                invocations.incrementAndGet();
                return original;
            }, null);

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(result).isSameAs(original);
            assertThat(result.output()).doesNotContainKey(ExecutionMetadataKeys.POLICY_ATTEMPT);
            assertThat(result.metadata()).doesNotContainKey(ExecutionMetadataKeys.POLICY_ATTEMPT);
            assertThat(recordedSleeps).isEmpty();
        }

        @Test
        @DisplayName("null policy behaves like DEFAULT (passthrough)")
        void nullPolicyIsPassthrough() throws Exception {
            NodeExecutionResult original = success();
            NodeExecutionResult result = runner.run(null, NODE, () -> original, null);
            assertThat(result).isSameAs(original);
        }

        @Test
        @DisplayName("exceptions propagate to the caller exactly as before (engine catch handles them)")
        void defaultPolicyExceptionPropagates() {
            assertThatThrownBy(() -> runner.run(NodePolicy.DEFAULT, NODE,
                    () -> { throw new IllegalStateException("api down"); }, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("api down");
        }
    }

    // =====================================================================
    // Retry
    // =====================================================================

    @Nested
    @DisplayName("Retry on failure")
    class Retry {

        @Test
        @DisplayName("fail once then succeed: returns COMPLETED annotated attempt=2/2; failed attempt surfaced to listener")
        void retryThenSuccess() throws Exception {
            NodePolicy policy = new NodePolicy(1, 0L, false);
            AtomicInteger invocations = new AtomicInteger();
            List<NodeExecutionResult> surfacedFailures = new ArrayList<>();

            NodeExecutionResult result = runner.run(policy, NODE,
                () -> invocations.incrementAndGet() == 1 ? failure("transient") : success(),
                (annotated, attempt, max) -> surfacedFailures.add(annotated));

            assertThat(invocations.get()).isEqualTo(2);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 2)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 2)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);

            // No silent attempts: the intermediate failure was surfaced, annotated
            assertThat(surfacedFailures).hasSize(1);
            assertThat(surfacedFailures.get(0).status()).isEqualTo(NodeStatus.FAILED);
            assertThat(surfacedFailures.get(0).metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 1)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);
        }

        @Test
        @DisplayName("all attempts fail: returns FAILED annotated attempt=3/3 with each non-final attempt surfaced")
        void allAttemptsFail() throws Exception {
            NodePolicy policy = new NodePolicy(2, 0L, false);
            AtomicInteger invocations = new AtomicInteger();
            List<Integer> surfacedAttempts = new ArrayList<>();

            NodeExecutionResult result = runner.run(policy, NODE,
                () -> { invocations.incrementAndGet(); return failure("always down"); },
                (annotated, attempt, max) -> surfacedAttempts.add(attempt));

            assertThat(invocations.get()).isEqualTo(3);
            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.errorMessage()).contains("always down");
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 3)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 3);
            assertThat(surfacedAttempts).containsExactly(1, 2);
            // continueOnFailure=false → no continuation flag
            assertThat(result.metadata()).doesNotContainKey(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE);
        }

        @Test
        @DisplayName("a thrown exception is converted to a FAILED attempt and retried (same conversion as the engine catch)")
        void thrownExceptionIsRetried() throws Exception {
            NodePolicy policy = new NodePolicy(1, 0L, false);
            AtomicInteger invocations = new AtomicInteger();

            NodeExecutionResult result = runner.run(policy, NODE,
                () -> {
                    if (invocations.incrementAndGet() == 1) throw new RuntimeException("socket reset");
                    return success();
                }, null);

            assertThat(invocations.get()).isEqualTo(2);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("first-try success under a retry policy is annotated attempt=1/N (frontend can render it)")
        void firstTrySuccessAnnotated() throws Exception {
            NodePolicy policy = new NodePolicy(2, 0L, false);

            NodeExecutionResult result = runner.run(policy, NODE, NodePolicyRunnerTest::success, null);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.output())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 1)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 3);
        }
    }

    // =====================================================================
    // Backoff (virtual clock)
    // =====================================================================

    @Nested
    @DisplayName("Backoff between attempts")
    class Backoff {

        @Test
        @DisplayName("sleeps retryBackoffMs BETWEEN attempts only - never after the final one")
        void backoffAppliedBetweenAttemptsOnly() throws Exception {
            NodePolicy policy = new NodePolicy(2, 250L, false);

            runner.run(policy, NODE, () -> failure("down"), null);

            // 3 attempts → 2 inter-attempt backoffs, none after the final failure
            assertThat(recordedSleeps).containsExactly(250L, 250L);
        }

        @Test
        @DisplayName("zero backoff never calls the sleeper")
        void zeroBackoffNeverSleeps() throws Exception {
            NodePolicy policy = new NodePolicy(2, 0L, false);
            runner.run(policy, NODE, () -> failure("down"), null);
            assertThat(recordedSleeps).isEmpty();
        }

        @Test
        @DisplayName("interrupt during backoff aborts the retry loop, restores the interrupt flag, returns the last failure")
        void interruptDuringBackoffAborts() throws Exception {
            NodePolicyRunner interruptingRunner = new NodePolicyRunner(ms -> { throw new InterruptedException(); });
            NodePolicy policy = new NodePolicy(5, 100L, false);
            AtomicInteger invocations = new AtomicInteger();

            NodeExecutionResult result;
            try {
                result = interruptingRunner.run(policy, NODE,
                    () -> { invocations.incrementAndGet(); return failure("down"); }, null);
                assertThat(Thread.interrupted()).as("interrupt flag must be restored").isTrue();
            } finally {
                Thread.interrupted(); // clear for other tests in any case
            }

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        }
    }

    // =====================================================================
    // Continue-on-failure flag
    // =====================================================================

    @Nested
    @DisplayName("continueOnFailure")
    class ContinueOnFailure {

        @Test
        @DisplayName("final failure carries the continuation flag (output AND metadata) when policy asks for it")
        void continuationFlagSetOnFinalFailure() throws Exception {
            NodePolicy policy = new NodePolicy(1, 0L, true);

            NodeExecutionResult result = runner.run(policy, NODE, () -> failure("down"), null);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(ExecutionMetadataKeys.isContinueOnFailure(result.metadata())).isTrue();
            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, Boolean.TRUE);
        }

        @Test
        @DisplayName("continueOnFailure without retries (retryCount=0) still flags the single failed attempt")
        void continuationWithoutRetries() throws Exception {
            NodePolicy policy = new NodePolicy(0, 0L, true);
            AtomicInteger invocations = new AtomicInteger();

            NodeExecutionResult result = runner.run(policy, NODE,
                () -> { invocations.incrementAndGet(); return failure("down"); }, null);

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(ExecutionMetadataKeys.isContinueOnFailure(result.metadata())).isTrue();
        }

        @Test
        @DisplayName("a SUCCESSFUL retry never carries the continuation flag")
        void successNeverFlagged() throws Exception {
            NodePolicy policy = new NodePolicy(1, 0L, true);
            AtomicInteger invocations = new AtomicInteger();

            NodeExecutionResult result = runner.run(policy, NODE,
                () -> invocations.incrementAndGet() == 1 ? failure("transient") : success(), null);

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(ExecutionMetadataKeys.isContinueOnFailure(result.metadata())).isFalse();
        }
    }

    // =====================================================================
    // What is never retried
    // =====================================================================

    @Nested
    @DisplayName("Never-retried outcomes")
    class NeverRetried {

        @Test
        @DisplayName("AWAITING_SIGNAL yields are not failures - single invocation, no retry")
        void awaitingSignalNotRetried() throws Exception {
            NodePolicy policy = new NodePolicy(3, 0L, false);
            AtomicInteger invocations = new AtomicInteger();

            NodeExecutionResult result = runner.run(policy, NODE, () -> {
                invocations.incrementAndGet();
                return NodeExecutionResult.awaitingSignal(NODE, SignalType.USER_APPROVAL, Map.of());
            }, null);

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(result.isAwaitingSignal()).isTrue();
        }

        @Test
        @DisplayName("SKIPPED results are not failures - single invocation, no retry")
        void skippedNotRetried() throws Exception {
            NodePolicy policy = new NodePolicy(3, 0L, false);
            AtomicInteger invocations = new AtomicInteger();

            NodeExecutionResult result = runner.run(policy, NODE, () -> {
                invocations.incrementAndGet();
                return NodeExecutionResult.skipped(NODE, "branch not taken");
            }, null);

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(result.isSkipped()).isTrue();
        }

        @Test
        @DisplayName("a split fan-out SUMMARY failure (split_already_persisted) is never retried - items already retried per item")
        void splitSummaryFailureNotRetried() throws Exception {
            NodePolicy policy = new NodePolicy(3, 0L, false);
            AtomicInteger invocations = new AtomicInteger();
            NodeExecutionResult summaryFailure = new NodeExecutionResult(
                NODE, NodeStatus.FAILED, Map.of(),
                Optional.of("All items failed"),
                Map.of(ExecutionMetadataKeys.SPLIT_ALREADY_PERSISTED, true), 0);

            NodeExecutionResult result = runner.run(policy, NODE, () -> {
                invocations.incrementAndGet();
                return summaryFailure;
            }, null);

            assertThat(invocations.get()).isEqualTo(1);
            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("WorkflowStoppedException (StopOnError) always propagates - never retried, never swallowed")
        void workflowStoppedExceptionPropagates() {
            NodePolicy policy = new NodePolicy(3, 0L, true);
            AtomicInteger invocations = new AtomicInteger();

            assertThatThrownBy(() -> runner.run(policy, NODE, () -> {
                invocations.incrementAndGet();
                throw new WorkflowStoppedException(NODE, "hard stop", "E42",
                    NodeExecutionResult.failure(NODE, "hard stop"));
            }, null)).isInstanceOf(WorkflowStoppedException.class);

            assertThat(invocations.get()).isEqualTo(1);
        }
    }

    // =====================================================================
    // Policy resolution
    // =====================================================================

    @Nested
    @DisplayName("resolve()")
    class Resolve {

        @Test
        @DisplayName("null plan or nodeId resolves to DEFAULT (mock-safe)")
        void nullSafeResolve() {
            assertThat(runner.resolve(null, NODE)).isEqualTo(NodePolicy.DEFAULT);
            assertThat(runner.resolve(mock(WorkflowPlan.class), null)).isEqualTo(NodePolicy.DEFAULT);
        }

        @Test
        @DisplayName("a mocked plan returning null resolves to DEFAULT")
        void mockedPlanNullPolicyResolvesToDefault() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getNodePolicy(NODE)).thenReturn(null);
            assertThat(runner.resolve(plan, NODE)).isEqualTo(NodePolicy.DEFAULT);
        }

        @Test
        @DisplayName("plan-declared policy is returned as-is")
        void declaredPolicyReturned() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            NodePolicy declared = new NodePolicy(2, 500L, true);
            when(plan.getNodePolicy(NODE)).thenReturn(declared);
            assertThat(runner.resolve(plan, NODE)).isEqualTo(declared);
        }
    }
}
