package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * THE single generic application point for per-node execution policies
 * ({@link NodePolicy}: retry / backoff / continue-on-failure).
 *
 * <p>Engine-agnostic by design: the caller hands an {@link AttemptInvoker} (one
 * logical execution of the node with the same context) and an optional
 * {@link FailedAttemptListener} (how THIS call site emits a failed attempt through
 * its normal failure pipeline). The runner owns only the policy mechanics:
 *
 * <ul>
 *   <li><b>Default policy = byte-identical legacy behavior.</b> A single
 *       {@code invoker.invoke()}; results AND exceptions pass through untouched
 *       (no annotation, no catch) - the caller's existing error handling fires
 *       exactly as before.</li>
 *   <li><b>Retry.</b> On a FAILED result (or a thrown exception, converted to a
 *       FAILED result exactly like the engine's own catch block does), re-invoke
 *       up to {@code retryCount} additional times, sleeping
 *       {@code retryBackoffMs} between attempts. The backoff blocks ONLY the
 *       calling thread - under fork parallelism / split fan-out each branch/item
 *       retries on its own worker, siblings are never delayed.</li>
 *   <li><b>No silent attempts.</b> Every NON-final failed attempt is handed to
 *       {@code onFailedAttempt} so the call site emits the SAME WS event + DB
 *       persistence as any failure today, annotated with
 *       {@code policy_attempt}/{@code policy_max_attempts} (in output AND
 *       metadata). The final result carries the same annotation.</li>
 *   <li><b>Continue-on-failure.</b> When all attempts fail and the policy says
 *       {@code continueOnFailure}, the FINAL failed result is flagged with
 *       {@link ExecutionMetadataKeys#POLICY_CONTINUE_ON_FAILURE}; the engine then
 *       suppresses the SKIPPED cascade and traverses successors (reusing the
 *       SKIPPED-with-error continuation semantic - see {@link NodePolicy}).</li>
 * </ul>
 *
 * <p><b>What is never retried:</b>
 * <ul>
 *   <li>non-FAILED results (COMPLETED / SKIPPED / AWAITING_SIGNAL / async-RUNNING
 *       / COLLECTING) - only hard failures retry;</li>
 *   <li>{@link WorkflowStoppedException} - StopOnError is an intentional hard stop
 *       of the whole workflow and always wins over any policy;</li>
 *   <li>split fan-out SUMMARY failures (metadata
 *       {@code split_already_persisted}) - per-item executions inside the fan-out
 *       already applied the policy per item; retrying the summary would re-run
 *       every item including successful ones.</li>
 * </ul>
 *
 * <p><b>Per-attempt timeout ({@code timeoutMs}) - enforced by {@link #callWithTimeout},
 * NOT inside {@link #run}.</b> Call sites wrap the ACTUAL node-body invocation
 * ({@code node.execute(ctx)}) in {@code callWithTimeout}; {@code run} then composes
 * retries around whatever the invoker returns, so a timed-out attempt is an ordinary
 * FAILED attempt (flagged {@code policy_timeout}) that backs off, retries and honors
 * {@code continueOnFailure} like any other failure. Keeping the bound at the LEAF
 * invocation (rather than around {@code run}'s invoker generically) is what guarantees
 * the interaction guards:
 * <ul>
 *   <li><b>split fan-out summaries are never bounded</b> - the engine-level invoker
 *       covers the whole N-item fan-out + successor traversals (legitimately ≫
 *       timeoutMs); only the per-item {@code node.execute} calls inside
 *       {@code SplitAwareNodeExecutor} are wrapped, so the timeout applies PER ITEM,
 *       per attempt. This extends the existing {@code split_already_persisted}
 *       retry guard: the summary is neither retried NOR timed;</li>
 *   <li><b>signal yields are never killed</b> - AWAITING_SIGNAL (and async-RUNNING)
 *       nodes return their yield immediately; the bound covers only that quick body
 *       call, never the subsequent (potentially days-long) signal wait or async work.
 *       A result that completes within the bound is returned untouched whatever its
 *       status.</li>
 * </ul>
 * <b>Best-effort honesty (same as n8n):</b> on expiry the worker thread is interrupted
 * and abandoned - the node body may keep running and its side effects are NOT
 * cancelled. Billing is unchanged: a timed-out attempt follows the existing attempt
 * pipeline (non-final attempts unbilled; one platform credit on the terminal attempt).
 *
 * <p><b>Idempotency:</b> a retry re-executes the node with the same context;
 * side-effectful nodes re-run their side effects each attempt (documented on
 * {@link NodePolicy} - retry is opt-in per node for exactly this reason).
 */
@Service
public class NodePolicyRunner {

    private static final Logger logger = LoggerFactory.getLogger(NodePolicyRunner.class);

    /** One logical execution of the node (same context every attempt). */
    @FunctionalInterface
    public interface AttemptInvoker {
        NodeExecutionResult invoke() throws Exception;
    }

    /**
     * Invoked for each NON-final failed attempt with the annotated failure, so the
     * call site can emit it through its normal failure pipeline (WS + DB).
     */
    @FunctionalInterface
    public interface FailedAttemptListener {
        void onFailedAttempt(NodeExecutionResult annotatedFailure, int attempt, int maxAttempts);
    }

    /**
     * Clock seam for the inter-attempt backoff - injectable so tests use a virtual
     * clock (recording sleeper) instead of real sleeps.
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * Dedicated executor for timeout-bounded node-body invocations. Shared across
     * runner instances (the engine/split executor construct default instances) so
     * configuring a timeout never proliferates pools. Cached: threads are created
     * on demand only - a deployment with zero {@code timeoutMs} policies never
     * spawns one. Daemon threads: an abandoned (timed-out) node body must never
     * block JVM shutdown.
     */
    private static final AtomicInteger TIMEOUT_THREAD_SEQ = new AtomicInteger();
    private static final ExecutorService TIMEOUT_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "node-policy-timeout-" + TIMEOUT_THREAD_SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    private final Sleeper sleeper;

    public NodePolicyRunner() {
        this(Thread::sleep);
    }

    /** Test constructor - inject a virtual-clock sleeper. */
    public NodePolicyRunner(Sleeper sleeper) {
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
    }

    /**
     * Resolves the policy for a node from the plan carried by the execution context.
     * Null-safe (mocked/absent plan → {@link NodePolicy#DEFAULT}).
     */
    public NodePolicy resolve(WorkflowPlan plan, String nodeId) {
        if (plan == null || nodeId == null) return NodePolicy.DEFAULT;
        NodePolicy policy = plan.getNodePolicy(nodeId);
        return policy != null ? policy : NodePolicy.DEFAULT;
    }

    /**
     * Executes one node under its policy. See class javadoc for semantics.
     *
     * @throws Exception only on the default-policy passthrough path (preserving the
     *         caller's legacy exception handling) or for {@link WorkflowStoppedException}
     */
    public NodeExecutionResult run(
            NodePolicy policy,
            String nodeId,
            AttemptInvoker invoker,
            FailedAttemptListener onFailedAttempt) throws Exception {

        if (policy == null || policy.isDefault()) {
            // No policy = exact current behavior, including exception propagation.
            return invoker.invoke();
        }

        int maxAttempts = policy.maxAttempts();
        for (int attempt = 1; ; attempt++) {
            NodeExecutionResult result;
            long attemptStartMs = System.currentTimeMillis();
            try {
                result = invoker.invoke();
            } catch (WorkflowStoppedException e) {
                throw e; // StopOnError hard stop - never retried, never swallowed
            } catch (Exception e) {
                // Same conversion the engine's own catch performs for unpoliced nodes.
                long attemptDurationMs = System.currentTimeMillis() - attemptStartMs;
                logger.error("❌ Node attempt threw: nodeId={}, attempt={}/{}, errorClass={}, error={}",
                        nodeId, attempt, maxAttempts, e.getClass().getSimpleName(), e.getMessage(), e);
                result = NodeExecutionResult.failure(nodeId, e.getMessage(), attemptDurationMs);
            }

            boolean retryEligible = result != null
                    && result.isFailure()
                    && !ExecutionMetadataKeys.isSplitAlreadyPersisted(result.metadata());

            if (!retryEligible) {
                // Success / yield / skip - annotate attempt info only when a retry
                // actually happened (first-try results under maxAttempts>1 are also
                // annotated so the frontend can show "attempt 1/3 succeeded").
                return annotate(result, attempt, maxAttempts, /*continueOnFailureFinal=*/ false);
            }

            if (attempt >= maxAttempts) {
                // Final failure - flag continuation if the policy asks for it.
                logger.warn("⛔ Node failed after {} attempt(s): nodeId={}, continueOnFailure={}",
                        attempt, nodeId, policy.continueOnFailure());
                return annotate(result, attempt, maxAttempts, policy.continueOnFailure());
            }

            // Non-final failed attempt: surface it (same WS+DB pipeline as today's
            // failures, annotated) then back off and retry.
            NodeExecutionResult annotatedFailure = annotate(result, attempt, maxAttempts, false);
            logger.info("🔁 Node attempt {}/{} failed, retrying after {}ms: nodeId={}, error={}",
                    attempt, maxAttempts, policy.retryBackoffMs(), nodeId,
                    result.errorMessage().orElse("unknown"));
            if (onFailedAttempt != null) {
                onFailedAttempt.onFailedAttempt(annotatedFailure, attempt, maxAttempts);
            }
            if (policy.retryBackoffMs() > 0) {
                try {
                    sleeper.sleep(policy.retryBackoffMs());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warn("🛑 Retry backoff interrupted - aborting retries: nodeId={}, attempt={}/{}",
                            nodeId, attempt, maxAttempts);
                    return annotate(result, attempt, maxAttempts, policy.continueOnFailure());
                }
            }
        }
    }

    /**
     * Runs ONE node-body invocation under the policy's per-attempt timeout
     * ({@code timeoutMs}). This is the SINGLE timeout enforcement point - call sites
     * wrap the actual {@code node.execute(ctx)} call (never a fan-out summary, never
     * a coordination block) and {@link #run} composes retries around it.
     *
     * <ul>
     *   <li>{@code timeoutMs == 0} (or null policy) → pure same-thread passthrough,
     *       byte-identical to calling {@code body.invoke()} directly (results AND
     *       exceptions untouched).</li>
     *   <li>{@code timeoutMs > 0} → the body runs on a dedicated daemon worker with
     *       the caller's org scope re-bound ({@code TenantResolver.runWithOrgScope}
     *       - without it, V261 NOT NULL org-scoped inserts fail off-thread) while
     *       the calling thread waits at most {@code timeoutMs}.
     *       <ul>
     *         <li>completes in time → result returned untouched (a quick
     *             AWAITING_SIGNAL / async-RUNNING yield is NEVER converted - the
     *             bound covers only the body call, not the signal wait);</li>
     *         <li>body throws → the original exception is rethrown on the calling
     *             thread (legacy error handling fires exactly as before, including
     *             {@link WorkflowStoppedException} propagation through {@link #run});</li>
     *         <li>bound expires → the worker is interrupted (best effort - the body
     *             may keep running; side effects are NOT cancelled) and a FAILED
     *             result flagged {@code policy_timeout: true} (output AND metadata)
     *             is returned, which {@link #run} treats as an ordinary failed
     *             attempt (retry / backoff / continueOnFailure compose naturally).</li>
     *       </ul></li>
     * </ul>
     */
    public NodeExecutionResult callWithTimeout(NodePolicy policy, String nodeId, AttemptInvoker body)
            throws Exception {
        if (policy == null || !policy.hasTimeout()) {
            // No timeout configured = exact current behavior (same thread, no wrapping).
            return body.invoke();
        }
        long timeoutMs = policy.timeoutMs();

        // Capture the caller's org scope (request header OR async thread-local) and
        // re-bind it on the timeout worker - same pattern as the split fan-out's
        // ForkJoinPool hop (V261 NOT NULL on org-scoped inserts).
        final String orgId = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId();
        final String orgRole = com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole();

        Future<NodeExecutionResult> future = TIMEOUT_EXECUTOR.submit(() -> {
            final Object[] holder = new Object[1];
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgId, orgRole, () -> {
                try {
                    holder[0] = body.invoke();
                } catch (Exception e) {
                    holder[0] = e;
                }
            });
            if (holder[0] instanceof Exception e) {
                throw e;
            }
            return (NodeExecutionResult) holder[0];
        });

        long startMs = System.currentTimeMillis();
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true); // best-effort interrupt; the body may keep running
            long elapsedMs = System.currentTimeMillis() - startMs;
            logger.warn("⏱️ TIMEOUT: node attempt exceeded nodePolicy.timeoutMs: nodeId={}, timeoutMs={}, elapsedMs={} - "
                    + "best effort: the node body was interrupted but may still be running; side effects are NOT cancelled",
                nodeId, timeoutMs, elapsedMs);
            return timeoutFailure(nodeId, timeoutMs, elapsedMs);
        } catch (ExecutionException ee) {
            // Body threw - surface the ORIGINAL exception on the calling thread so the
            // caller's legacy error handling (engine catch, per-item catch, StopOnError
            // propagation) fires exactly as without a timeout.
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw ee;
        } catch (InterruptedException ie) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw ie;
        }
    }

    /** TIMEOUT-flavored FAILED result, flagged {@code policy_timeout} in output AND metadata. */
    private static NodeExecutionResult timeoutFailure(String nodeId, long timeoutMs, long elapsedMs) {
        String message = "TIMEOUT: node execution exceeded the configured nodePolicy.timeoutMs ("
            + timeoutMs + " ms). Best effort: the node body may still be running - side effects are not cancelled.";
        Map<String, Object> output = new HashMap<>();
        output.put(ExecutionMetadataKeys.POLICY_TIMEOUT, Boolean.TRUE);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ExecutionMetadataKeys.POLICY_TIMEOUT, Boolean.TRUE);
        return new NodeExecutionResult(
            nodeId,
            NodeStatus.FAILED,
            output,
            Optional.of(message),
            metadata,
            elapsedMs
        );
    }

    /**
     * Stamps attempt metadata into BOTH output and metadata (dual-write pattern of
     * the split partial-failure markers: output is queryable/persisted, metadata
     * signals the engine). Status, error and duration are untouched.
     */
    static NodeExecutionResult annotate(
            NodeExecutionResult result, int attempt, int maxAttempts, boolean continueOnFailureFinal) {
        if (result == null) return null;

        Map<String, Object> output = result.output() != null
                ? new HashMap<>(result.output())
                : new HashMap<>();
        output.put(ExecutionMetadataKeys.POLICY_ATTEMPT, attempt);
        output.put(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, maxAttempts);

        Map<String, Object> metadata = result.metadata() != null
                ? new HashMap<>(result.metadata())
                : new HashMap<>();
        metadata.put(ExecutionMetadataKeys.POLICY_ATTEMPT, attempt);
        metadata.put(ExecutionMetadataKeys.POLICY_MAX_ATTEMPTS, maxAttempts);
        if (continueOnFailureFinal && result.status() == NodeStatus.FAILED) {
            output.put(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, Boolean.TRUE);
            metadata.put(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, Boolean.TRUE);
        }

        return new NodeExecutionResult(
                result.nodeId(),
                result.status(),
                output,
                result.errorMessage() != null ? result.errorMessage() : Optional.empty(),
                metadata,
                result.durationMs()
        );
    }
}
