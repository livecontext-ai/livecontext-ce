package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the per-attempt execution timeout ({@code nodePolicy.timeoutMs},
 * enforced by {@link NodePolicyRunner#callWithTimeout}).
 *
 * <p>All blocking bodies are LATCH-controlled (released in {@code @AfterEach}) and
 * timeouts are tiny - no sleep-based assertions, no real backoff (recording sleeper).
 */
@DisplayName("NodePolicyRunner - per-attempt timeout (callWithTimeout)")
@Timeout(30)
class NodePolicyRunnerTimeoutTest {

    private static final String NODE = "mcp:slow";
    private static final long TINY_TIMEOUT_MS = 150L;
    private static final long GENEROUS_TIMEOUT_MS = 30_000L;

    private final List<Long> recordedSleeps = new ArrayList<>();
    private final NodePolicyRunner runner = new NodePolicyRunner(recordedSleeps::add);

    /** Latch the blocking bodies wait on; released after each test so abandoned workers exit. */
    private final CountDownLatch blockForever = new CountDownLatch(1);

    @AfterEach
    void releaseAbandonedWorkers() {
        blockForever.countDown();
    }

    private static NodePolicy timeoutPolicy(long timeoutMs) {
        return new NodePolicy(0, 0L, false, timeoutMs, false);
    }

    private static NodeExecutionResult success() {
        return NodeExecutionResult.success(NODE, Map.of("ok", true));
    }

    // =====================================================================
    // Passthrough - timeoutMs=0 is byte-identical to a direct call
    // =====================================================================

    @Nested
    @DisplayName("Disabled timeout (timeoutMs=0) = byte-identical passthrough")
    class Passthrough {

        @Test
        @DisplayName("REGRESSION: runs on the CALLING thread and returns the result instance UNTOUCHED")
        void noTimeoutRunsOnCallingThread() throws Exception {
            NodeExecutionResult original = success();
            AtomicReference<Thread> bodyThread = new AtomicReference<>();

            NodeExecutionResult result = runner.callWithTimeout(NodePolicy.DEFAULT, NODE, () -> {
                bodyThread.set(Thread.currentThread());
                return original;
            });

            assertThat(result).isSameAs(original);
            assertThat(bodyThread.get()).isSameAs(Thread.currentThread());
        }

        @Test
        @DisplayName("REGRESSION: null policy is a passthrough too")
        void nullPolicyPassthrough() throws Exception {
            NodeExecutionResult original = success();
            assertThat(runner.callWithTimeout(null, NODE, () -> original)).isSameAs(original);
        }

        @Test
        @DisplayName("REGRESSION: body exceptions propagate untouched without a timeout")
        void noTimeoutExceptionPropagates() {
            assertThatThrownBy(() -> runner.callWithTimeout(NodePolicy.DEFAULT, NODE,
                    () -> { throw new IllegalStateException("api down"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("api down");
        }
    }

    // =====================================================================
    // Bounded wait
    // =====================================================================

    @Nested
    @DisplayName("Bounded wait (timeoutMs > 0)")
    class BoundedWait {

        @Test
        @DisplayName("a body completing within the bound is returned untouched (no failure conversion)")
        void completesWithinBound() throws Exception {
            NodeExecutionResult original = success();

            NodeExecutionResult result = runner.callWithTimeout(
                timeoutPolicy(GENEROUS_TIMEOUT_MS), NODE, () -> original);

            assertThat(result).isSameAs(original);
            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("an AWAITING_SIGNAL yield within the bound is NEVER converted - the signal wait is not subject to timeoutMs")
        void yieldNeverTimedOut() throws Exception {
            NodeExecutionResult yield = NodeExecutionResult.awaitingSignal(
                NODE, SignalType.USER_APPROVAL, Map.of());

            NodeExecutionResult result = runner.callWithTimeout(
                timeoutPolicy(TINY_TIMEOUT_MS), NODE, () -> yield);

            assertThat(result.isAwaitingSignal()).isTrue();
            assertThat(result.isFailure()).isFalse();
            assertThat(result.metadata()).doesNotContainKey(ExecutionMetadataKeys.POLICY_TIMEOUT);
        }

        @Test
        @DisplayName("on expiry: FAILED result flagged policy_timeout (output AND metadata) with a TIMEOUT-flavored, best-effort-honest message")
        void timeoutProducesFlaggedFailure() throws Exception {
            NodeExecutionResult result = runner.callWithTimeout(
                timeoutPolicy(TINY_TIMEOUT_MS), NODE, () -> {
                    blockForever.await();
                    return success();
                });

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(result.output()).containsEntry(ExecutionMetadataKeys.POLICY_TIMEOUT, Boolean.TRUE);
            assertThat(ExecutionMetadataKeys.isPolicyTimeout(result.metadata())).isTrue();
            assertThat(result.errorMessage().orElse(""))
                .contains("TIMEOUT")
                .contains(String.valueOf(TINY_TIMEOUT_MS))
                .contains("side effects are not cancelled");
        }

        @Test
        @DisplayName("a body exception under an active timeout is rethrown as the ORIGINAL exception on the calling thread")
        void bodyExceptionRethrownOriginal() {
            assertThatThrownBy(() -> runner.callWithTimeout(
                    timeoutPolicy(GENEROUS_TIMEOUT_MS), NODE,
                    () -> { throw new IllegalStateException("api down"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("api down");
        }

        @Test
        @DisplayName("the caller's org scope is re-bound on the timeout worker (V261 NOT NULL guard)")
        void orgScopePropagatedToWorker() throws Exception {
            AtomicReference<String> seenOrg = new AtomicReference<>();
            AtomicReference<Thread> bodyThread = new AtomicReference<>();
            AtomicReference<Exception> failure = new AtomicReference<>();

            TenantResolver.runWithOrgScope("org-42", () -> {
                try {
                    runner.callWithTimeout(timeoutPolicy(GENEROUS_TIMEOUT_MS), NODE, () -> {
                        bodyThread.set(Thread.currentThread());
                        seenOrg.set(TenantResolver.currentRequestOrganizationId());
                        return success();
                    });
                } catch (Exception e) {
                    failure.set(e);
                }
            });

            assertThat(failure.get()).isNull();
            assertThat(bodyThread.get()).as("body must hop to the timeout worker").isNotSameAs(Thread.currentThread());
            assertThat(seenOrg.get()).isEqualTo("org-42");
        }
    }

    // =====================================================================
    // Composition with the retry loop (run + callWithTimeout-wrapped invoker)
    // =====================================================================

    @Nested
    @DisplayName("Composition with retry / continueOnFailure (run() around a callWithTimeout invoker)")
    class RetryComposition {

        @Test
        @DisplayName("timeout → retry → success: the timed-out attempt is an ordinary failed attempt (surfaced, flagged) and the retry completes")
        void timeoutThenRetrySucceeds() throws Exception {
            NodePolicy policy = new NodePolicy(1, 0L, false, TINY_TIMEOUT_MS, false);
            AtomicInteger invocations = new AtomicInteger();
            List<NodeExecutionResult> surfacedFailures = new ArrayList<>();

            NodeExecutionResult result = runner.run(policy, NODE,
                () -> runner.callWithTimeout(policy, NODE, () -> {
                    if (invocations.incrementAndGet() == 1) {
                        blockForever.await(); // first attempt hangs → bounded wait expires
                    }
                    return success();
                }),
                (annotated, attempt, max) -> surfacedFailures.add(annotated));

            assertThat(result.status()).isEqualTo(NodeStatus.COMPLETED);
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 2)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);

            assertThat(surfacedFailures).hasSize(1);
            NodeExecutionResult timedOutAttempt = surfacedFailures.get(0);
            assertThat(timedOutAttempt.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(ExecutionMetadataKeys.isPolicyTimeout(timedOutAttempt.metadata())).isTrue();
            assertThat(timedOutAttempt.metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 1)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);
        }

        @Test
        @DisplayName("timeout exhausts every attempt → terminal FAILED keeps the policy_timeout flag and the attempt annotation")
        void timeoutExhaustsAttempts() throws Exception {
            NodePolicy policy = new NodePolicy(1, 0L, false, TINY_TIMEOUT_MS, false);
            AtomicInteger invocations = new AtomicInteger();

            NodeExecutionResult result = runner.run(policy, NODE,
                () -> runner.callWithTimeout(policy, NODE, () -> {
                    invocations.incrementAndGet();
                    blockForever.await(); // every attempt hangs
                    return success();
                }), null);

            assertThat(invocations.get()).isEqualTo(2);
            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(ExecutionMetadataKeys.isPolicyTimeout(result.metadata())).isTrue();
            assertThat(result.metadata())
                .containsEntry(ExecutionMetadataKeys.POLICY_ATTEMPT, 2)
                .containsEntry(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, 2);
        }

        @Test
        @DisplayName("the final timed-out attempt honors continueOnFailure (continuation flag alongside policy_timeout)")
        void timeoutHonorsContinueOnFailure() throws Exception {
            NodePolicy policy = new NodePolicy(0, 0L, true, TINY_TIMEOUT_MS, false);

            NodeExecutionResult result = runner.run(policy, NODE,
                () -> runner.callWithTimeout(policy, NODE, () -> {
                    blockForever.await();
                    return success();
                }), null);

            assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
            assertThat(ExecutionMetadataKeys.isPolicyTimeout(result.metadata())).isTrue();
            assertThat(ExecutionMetadataKeys.isContinueOnFailure(result.metadata())).isTrue();
        }

        @Test
        @DisplayName("WorkflowStoppedException thrown by the body still propagates through run() with a timeout configured (StopOnError wins)")
        void workflowStoppedPropagatesThroughTimeout() {
            NodePolicy policy = new NodePolicy(3, 0L, true, GENEROUS_TIMEOUT_MS, false);
            AtomicInteger invocations = new AtomicInteger();

            assertThatThrownBy(() -> runner.run(policy, NODE,
                () -> runner.callWithTimeout(policy, NODE, () -> {
                    invocations.incrementAndGet();
                    throw new WorkflowStoppedException(NODE, "hard stop", "E42",
                        NodeExecutionResult.failure(NODE, "hard stop"));
                }), null))
                .isInstanceOf(WorkflowStoppedException.class);

            assertThat(invocations.get()).isEqualTo(1);
        }
    }
}
