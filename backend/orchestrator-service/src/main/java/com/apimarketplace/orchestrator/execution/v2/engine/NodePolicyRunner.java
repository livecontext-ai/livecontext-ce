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
 *       {@code onFailedAttempt} so the call site emits it as a step event, annotated
 *       with {@code policy_attempt}/{@code policy_max_attempts} (in output AND
 *       metadata); only the final result is persisted as the node's row, carrying the
 *       same annotation.</li>
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
 *       every item including successful ones;</li>
 *   <li>a definite refusal by the provider ({@code http_status} 4xx other than 408,
 *       425, 429, with no rate-limit signal): the same request gets the same answer.
 *       Under {@code retryOn=rate_limit}, anything that is not a rate limit;</li>
 *   <li>a refusal the platform itself made, which the same request meets again: a credit
 *       budget that ran out, an agent a person stopped (no HTTP status needed);</li>
 *   <li>a failure whose provider asked to wait ({@code metadata.retryAfterSeconds})
 *       longer than both the node's backoff and {@link NodePolicy#MAX_PROVIDER_WAIT_MS}.</li>
 * </ul>
 * An early stop is stamped {@code policy_retry_stopped}; every result that ends the
 * execution is stamped {@code policy_final_attempt}. That result is the execution's only
 * step row (non-final attempts write none), which is what bills it exactly once.
 *
 * <p><b>Wait before a retry</b>: the longer of {@code retryBackoffMs} (clamped to
 * {@link NodePolicy#MAX_RETRY_BACKOFF_MS}) and the provider's own Retry-After, which is
 * honoured when it is at most the longer of that backoff and
 * {@link NodePolicy#MAX_PROVIDER_WAIT_MS}.</p>
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
 *       call, never the subsequent (potentially days-long) signal wait. A queued agent's
 *       ANSWER is bounded, and its failed attempts retried, by {@code AgentAttemptScheduler}
 *       with {@link #afterFailedAttempt}, since this runner only sees it yield.
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

    /**
     * An exception the runner must propagate unchanged instead of retrying: it stands for work that
     * already applied the policy to each of its own parts. A split fan-out that fails as a whole is
     * the case: its items retried themselves, some may have succeeded with side effects, and
     * re-running the whole fan-out would repeat them.
     */
    public interface NotRetryable {
    }


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
        return effectivePolicy(plan, nodeId);
    }

    /**
     * The policy a node runs under: the plan's, except that a classify or guardrail agent, or a
     * loop, never continues past its own failure. Such a node picks where the run goes next; a
     * failed one picked nothing, so continuing would go EVERY way at once (a failed guardrail
     * feeding its pass branch, a failed loop starting its body and its exit). The builder refuses
     * the flag there; a plan stored before the refusal keeps opening and simply fails as without
     * it. Shared by the synchronous path and the async agent delivery, so both read one rule.
     */
    public static NodePolicy effectivePolicy(WorkflowPlan plan, String nodeId) {
        if (plan == null || nodeId == null) return NodePolicy.DEFAULT;
        NodePolicy policy = plan.getNodePolicy(nodeId);
        if (policy == null) return NodePolicy.DEFAULT;
        if (policy.continueOnFailure() && (isBranchingAgent(plan, nodeId) || isLoopCore(plan, nodeId))) {
            return policy.withoutContinueOnFailure();
        }
        return policy;
    }

    private static boolean isLoopCore(WorkflowPlan plan, String nodeId) {
        if (!nodeId.startsWith("core:")) return false;
        String key = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(nodeId);
        return plan.getCores().stream().anyMatch(core -> core.getNormalizedKey().equals(key)
            && com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser.isContinueIgnoredCoreType(core.type()));
    }

    private static boolean isBranchingAgent(WorkflowPlan plan, String nodeId) {
        if (!nodeId.startsWith("agent:")) return false;
        return plan.findAgent(nodeId)
            .map(agent -> com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser.isBranchingAgentType(agent.type()))
            .orElse(false);
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
                if (e instanceof NotRetryable) {
                    throw e; // a failure that already covers its own attempts (a split fan-out)
                }
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
                return finalResult(result, attempt, maxAttempts, /*continueOnFailureFinal=*/ false, null);
            }

            NextAttempt next = afterFailedAttempt(policy, nodeId, attempt, result);
            if (!next.retry()) {
                // A refusal for missing credits or a budget is never continued, like the engine's own
                // credit gate: continuing would run the paid nodes below with no money to run them.
                return finalResult(result, attempt, maxAttempts,
                        policy.continueOnFailure() && !isBudgetRefusal(result), next.stopReason());
            }
            long waitMs = next.waitMs();

            // Non-final failed attempt: surface it (a step event, annotated; the node's row is
            // written once, by the final result) then back off and retry.
            NodeExecutionResult annotatedFailure = annotate(result, attempt, maxAttempts, false);
            logger.info("🔁 Node attempt {}/{} failed, retrying after {}ms: nodeId={}, error={}",
                    attempt, maxAttempts, waitMs, nodeId,
                    result.errorMessage().orElse("unknown"));
            if (onFailedAttempt != null) {
                onFailedAttempt.onFailedAttempt(annotatedFailure, attempt, maxAttempts);
            }
            if (waitMs > 0) {
                try {
                    sleeper.sleep(waitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warn("🛑 Retry backoff interrupted - aborting retries: nodeId={}, attempt={}/{}",
                            nodeId, attempt, maxAttempts);
                    return finalResult(result, attempt, maxAttempts, policy.continueOnFailure(), null);
                }
            }
        }
    }

    /**
     * What follows a FAILED attempt: another attempt after {@code waitMs}, or the end of the
     * execution ({@code stopReason} set when it ends before the last planned attempt).
     */
    public record NextAttempt(boolean retry, long waitMs, String stopReason) {
        static NextAttempt end(String stopReason) {
            return new NextAttempt(false, 0L, stopReason);
        }
    }

    /**
     * The retry decision for a failed attempt, shared by {@link #run} (which sleeps and calls the
     * node again) and the async agent path (which cannot sleep: it schedules the request again,
     * see {@code AgentAttemptScheduler}), so a node reads its policy the same way on both.
     */
    public static NextAttempt afterFailedAttempt(NodePolicy policy, String nodeId, int attempt,
                                                 NodeExecutionResult failure) {
        int maxAttempts = policy.maxAttempts();
        if (attempt >= maxAttempts) {
            // Final failure - the caller flags continuation if the policy asks for it.
            logger.warn("⛔ Node failed after {} attempt(s): nodeId={}, continueOnFailure={}",
                    attempt, nodeId, policy.continueOnFailure());
            return NextAttempt.end(null);
        }

        // Not every failure is worth another attempt: a request the provider refused as
        // invalid comes back refused, and under retryOn=rate_limit only a rate limit is
        // retried. This attempt is then the final one.
        String stopReason = stopReason(policy, failure);
        if (stopReason != null) {
            logger.info("⛔ Node attempt {}/{} failed with HTTP {}, not retried ({}): nodeId={}",
                    attempt, maxAttempts, httpStatusOf(failure), stopReason, nodeId);
            return NextAttempt.end(stopReason);
        }

        // The wait is the longer of the node's own backoff and the delay the provider asked
        // for. The provider's wait is honoured up to the longer of the author's backoff and
        // MAX_PROVIDER_WAIT_MS (both 60 s today, so effectively 60 s). Beyond that the node
        // stops: re-sending sooner than the provider asked only earns another refusal, and
        // waiting longer would hold a worker thread (on STEP_BY_STEP, possibly one of the 4
        // signal-resume threads) for a window a schedule should cover.
        long backoffMs = Math.min(policy.retryBackoffMs(), NodePolicy.MAX_RETRY_BACKOFF_MS);
        Long retryAfterMs = retryAfterMsOf(failure);
        long longestAcceptedWaitMs = Math.max(backoffMs, NodePolicy.MAX_PROVIDER_WAIT_MS);
        if (retryAfterMs != null && retryAfterMs > longestAcceptedWaitMs) {
            logger.info("⛔ Node attempt {}/{} failed and the provider asked to wait {}ms, longer than "
                            + "the {}ms this node may wait: not retried, nodeId={}",
                    attempt, maxAttempts, retryAfterMs, longestAcceptedWaitMs, nodeId);
            return NextAttempt.end(STOP_PROVIDER_WAIT_TOO_LONG);
        }
        return new NextAttempt(true, retryAfterMs == null ? backoffMs : Math.max(backoffMs, retryAfterMs), null);
    }

    public static final String STOP_PERMANENT_REFUSAL = "permanent_refusal";
    public static final String STOP_NOT_RATE_LIMITED = "not_rate_limited";
    public static final String STOP_PROVIDER_WAIT_TOO_LONG = "provider_wait_too_long";

    /** The shared rate-limit wording: see {@link com.apimarketplace.common.web.RateLimitSignals#WORDING}. */
    static final java.util.regex.Pattern RATE_LIMIT_TEXT =
            com.apimarketplace.common.web.RateLimitSignals.WORDING;

    /**
     * Why this failed attempt must not be retried, or {@code null} when it may be.
     * A rate limit is recognised by status (429, 503), or by a 4xx that carries a provider
     * Retry-After or the provider's own rate-limit wording, because several providers answer a
     * rate limit with a 403 or a 409 (Google's rateLimitExceeded, GitHub's secondary limit). The
     * signals never reclassify a 5xx: "try again later" is common in a 500 body, and re-sending
     * after an ambiguous 5xx is exactly what {@code retryOn='rate_limit'} promises not to do.
     */
    static String stopReason(NodePolicy policy, NodeExecutionResult result) {
        if (isRefusalNoRetryChanges(result)) {
            return policy.retriesOnlyRateLimits() ? STOP_NOT_RATE_LIMITED : STOP_PERMANENT_REFUSAL;
        }
        Integer status = httpStatusOf(result);
        boolean clientError = status != null && status >= 400 && status < 500;
        boolean rateLimited = (status != null && (status == 429 || status == 503))
                || (clientError && hasRateLimitSignal(result));
        if (policy.retriesOnlyRateLimits()) {
            return rateLimited ? null : STOP_NOT_RATE_LIMITED;
        }
        boolean definiteRefusal = status != null && status >= 400 && status < 500
                && status != 408 && status != 425 && status != 429 && !rateLimited;
        return definiteRefusal ? STOP_PERMANENT_REFUSAL : null;
    }

    /**
     * The platform's own refusals, which carry no HTTP status and which the same request meets
     * again a moment later: a credit budget that ran out (the tenant's, the agent's, the
     * workflow's) and an agent a person stopped. Read from the agent's stop reason when the
     * worker reports one, else from the refusal's own token at the start of the error. Prod,
     * 30 days to 2026-09-29: 28 of the 82 agent-node failures were "Insufficient credits".
     */
    static final java.util.regex.Pattern NO_RETRY_CHANGES_TEXT = java.util.regex.Pattern.compile(
            "^(insufficient credits|budget_exhausted|workflow_budget_reached)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Agent stop reasons (shared/contracts/agent-stop-reason.json) that end the same way on every attempt. */
    static final java.util.Set<String> NO_RETRY_CHANGES_STOP_REASONS =
            java.util.Set.of("BUDGET_EXHAUSTED", "STOPPED_BY_USER", "NO_TOOLS");

    /**
     * The agent worker's own tenant-budget guard, whose refusal a classify or guardrail result
     * carries as text only (those results have no stop reason): "tenant balance is 0", "tenant
     * balance exhausted (...)", "tenant balance X would be exceeded (...)".
     */
    static final java.util.regex.Pattern TENANT_BALANCE_TEXT = java.util.regex.Pattern.compile(
            "tenant balance (is |exhausted|\\S+ would be exceeded)", java.util.regex.Pattern.CASE_INSENSITIVE);

    static boolean isRefusalNoRetryChanges(NodeExecutionResult result) {
        return isBudgetRefusal(result) || catalogRefusal(result) != null
            || hasStopReason(result, java.util.Set.of("STOPPED_BY_USER", "NO_TOOLS"));
    }

    /** The code of a refusal the catalog answered itself, before any provider call, or {@code null}. */
    private static String catalogRefusal(NodeExecutionResult result) {
        Map<String, Object> output = result != null ? result.output() : null;
        return output != null && output.get(ExecutionMetadataKeys.CATALOG_REFUSAL) instanceof String code
            && !code.isBlank() ? code : null;
    }

    /**
     * A refusal for missing credits or a budget: the platform's pre-flight credit check, an agent's
     * or a workflow's budget, the worker's tenant-budget guard. Never retried (the same request is
     * refused again) and never continued (the paid nodes below would run with no money for them).
     */
    public static boolean isBudgetRefusal(NodeExecutionResult result) {
        if (result == null) return false;
        if (hasStopReason(result, java.util.Set.of("BUDGET_EXHAUSTED"))
                || ExecutionMetadataKeys.CATALOG_REFUSAL_NO_CREDITS.equals(catalogRefusal(result))) {
            return true;
        }
        Map<String, Object> output = result.output();
        Object error = output != null ? output.get("error") : null;
        String errorText = error instanceof String s ? s.trim() : "";
        String message = result.errorMessage() != null ? result.errorMessage().orElse("").trim() : "";
        return NO_RETRY_CHANGES_TEXT.matcher(errorText).find() || NO_RETRY_CHANGES_TEXT.matcher(message).find()
                || TENANT_BALANCE_TEXT.matcher(errorText).find() || TENANT_BALANCE_TEXT.matcher(message).find();
    }

    private static boolean hasStopReason(NodeExecutionResult result, java.util.Set<String> reasons) {
        Map<String, Object> output = result.output();
        if (output == null) return false;
        Object stopReason = output.get("stopReason");
        if (stopReason == null) stopReason = output.get("stop_reason");
        return stopReason != null && reasons.contains(stopReason.toString().trim().toUpperCase());
    }

    /** A provider Retry-After, or rate-limit wording in the failure's error text. */
    static boolean hasRateLimitSignal(NodeExecutionResult result) {
        if (retryAfterMsOf(result) != null) return true;
        Map<String, Object> output = result.output();
        Object error = output != null ? output.get("error") : null;
        String text = (error instanceof String s ? s : "") + " "
                + (result.errorMessage() != null ? result.errorMessage().orElse("") : "");
        return RATE_LIMIT_TEXT.matcher(text).find();
    }

    /**
     * The provider's HTTP status on a failed attempt: {@code http_status} on the output, else
     * {@code httpStatus.code}. Only a catalog tool step fails with one; 0 (no answer) is null.
     */
    static Integer httpStatusOf(NodeExecutionResult result) {
        Map<String, Object> output = result.output();
        if (output == null) return null;
        Integer status = positiveInt(output.get("http_status"));
        if (status == null && output.get("httpStatus") instanceof Map<?, ?> hs) {
            status = positiveInt(hs.get("code"));
        }
        return status;
    }

    /** The provider's Retry-After on a failed catalog step ({@code metadata.retryAfterSeconds}), in ms. */
    static Long retryAfterMsOf(NodeExecutionResult result) {
        Map<String, Object> output = result.output();
        if (output == null || !(output.get("metadata") instanceof Map<?, ?> md)) return null;
        Object raw = md.get("retryAfterSeconds");
        if (!(raw instanceof Number n) || n.longValue() < 0) return null;
        // Clamped so a hostile header cannot overflow; anything this large is refused anyway.
        return Math.min(n.longValue(), 86_400L) * 1000L;
    }

    private static Integer positiveInt(Object raw) {
        if (raw instanceof Number n && n.intValue() > 0) return n.intValue();
        if (raw instanceof String s) {
            try {
                int v = Integer.parseInt(s.trim());
                return v > 0 ? v : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
     * {@link #annotate} for the result that ENDS the execution: also stamps
     * {@code policy_final_attempt} and, for an early stop, {@code policy_retry_stopped}.
     */
    static NodeExecutionResult finalResult(NodeExecutionResult result, int attempt, int maxAttempts,
                                           boolean continueOnFailureFinal, String stopReason) {
        NodeExecutionResult annotated = annotate(result, attempt, maxAttempts, continueOnFailureFinal);
        if (annotated == null) return null;
        annotated.output().put(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, Boolean.TRUE);
        annotated.metadata().put(ExecutionMetadataKeys.POLICY_FINAL_ATTEMPT, Boolean.TRUE);
        if (stopReason != null) {
            annotated.output().put(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, stopReason);
            annotated.metadata().put(ExecutionMetadataKeys.POLICY_RETRY_STOPPED, stopReason);
        }
        return annotated;
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
