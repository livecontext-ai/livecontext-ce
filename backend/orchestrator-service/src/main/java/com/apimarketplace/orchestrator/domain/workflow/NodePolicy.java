package com.apimarketplace.orchestrator.domain.workflow;

import java.util.Map;

/**
 * Generic per-node execution policy - the optional {@code nodePolicy} block on a
 * WorkflowPlan node entry (mcps / tables / agents / cores / interfaces; triggers and
 * notes are excluded - they are entry points / annotations, not executed steps).
 *
 * <p>Applied uniformly by the execution engine ({@code NodePolicyRunner}) to EVERY
 * node type - there is no per-node-type policy code. The policy governs how a single
 * logical execution of the node behaves on failure:
 *
 * <ul>
 *   <li>{@code retryCount} - number of ADDITIONAL attempts after a failed one
 *       (0 = today's behavior, single attempt). Total attempts = retryCount + 1. An agent
 *       node on the worker queue yields instead of returning its result, so its attempts are
 *       run by {@code AgentAttemptScheduler} (the request is sent again after the wait), under
 *       the same decision, {@code NodePolicyRunner.afterFailedAttempt}.</li>
 *   <li>{@code retryBackoffMs} - delay between attempts. The backoff blocks only the
 *       executing thread (per branch / per split item), never sibling branches. When the
 *       failed attempt carries the provider's own {@code Retry-After} (a catalog tool step
 *       refused with one), the wait is the LONGER of the two.</li>
 *   <li>{@code retryOn} - which failures are worth another attempt. Absent (the default):
 *       every failure EXCEPT a definite refusal, by the provider (an HTTP 4xx other than
 *       408, 425 and 429 that carries no rate-limit signal) or by the platform (no credits
 *       left, a credit budget reached, an agent stopped by a person), because re-sending a
 *       request that was refused as invalid, unauthorized, not found or unfunded only repeats
 *       the same answer.
 *       {@code "rate_limit"}: only a rate limit (a 429, a 503, a Retry-After, or the provider's
 *       rate-limit wording on another status). Safer for a node that WRITES: it does not re-send
 *       after a timeout or most 5xx, which the provider may already have applied, although a 503
 *       can still arrive after a write was applied. Tool steps only (the builder refuses it
 *       elsewhere).</li>
 *   <li>Caps: at most {@link #MAX_RETRY_COUNT} retries and {@link #MAX_RETRY_BACKOFF_MS}
 *       of waiting before one attempt. Refused by the builder tools on write; a stored plan
 *       above them still parses and is clamped at run time.</li>
 *   <li>{@code continueOnFailure} - when ALL attempts fail, the node is still marked
 *       FAILED (same WS event + DB persistence as any failure today) but the engine
 *       continues traversal to its successors instead of cascading SKIPPED to them, on
 *       BOTH dispatch paths (AUTO traversal, and STEP_BY_STEP: execute, trigger fires, cron,
 *       the Run button, where ReadyNodeCalculator walks past the flagged FAILED node).
 *       This reuses the existing "SKIPPED-with-error" continuation semantic: the
 *       default {@code BaseNode.getNextNodes} exposes successors for any non-FAILED
 *       result and {@code ExecutionContext.isCompleted} already treats FAILED as a
 *       resolved state (merge readiness = all predecessors resolved), so successors
 *       execute exactly as they do after a terminal SKIPPED node - with the failed
 *       node resolved-but-without-output. Run-level statuses derive from the
 *       UNCHANGED existing semantics: the run ends as it would without the setting (a
 *       failed node ends it FAILED; a failed split item makes its node a partial failure).
 *       A refusal for missing credits or a budget is never continued, and neither is a
 *       classify or guardrail agent (a failed one selected no branch).</li>
 *   <li>{@code timeoutMs} - PER-ATTEMPT execution timeout (0 = disabled, the default).
 *       The node body runs under a bounded wait ({@code NodePolicyRunner.callWithTimeout});
 *       when the bound expires the attempt is converted to a FAILED result flagged
 *       {@code policy_timeout: true} (output AND metadata) with a TIMEOUT error message.
 *       A timed-out attempt is an ordinary failed attempt: it composes with
 *       {@code retryCount}/{@code retryBackoffMs} (timeout → backoff → retry) and with
 *       {@code continueOnFailure} on the final attempt.
 *       <b>Best-effort semantics (n8n-honest):</b> the abandoned node body is interrupted
 *       but may keep running on its worker thread - side effects (emails, API writes,
 *       CRUD inserts) are NOT cancelled or rolled back. The timeout bounds the NODE BODY
 *       only: a signal yield (AWAITING_SIGNAL) returns immediately and its signal wait is
 *       never subject to {@code timeoutMs}. An agent node on the worker queue is the
 *       exception that keeps the promise: its dispatch returns at once too, so
 *       {@code AgentAttemptScheduler} bounds the wait for its ANSWER instead (the agent may
 *       keep running; a late answer is billed, never delivered). Split fan-out coordination
 *       and summaries are never bounded - in a split the timeout applies PER ITEM, per
 *       attempt.</li>
 *   <li>{@code executeOnce} - in a SPLIT item context, execute the node ONLY for split
 *       item index 0 and mark every other item SKIPPED with an explicit executeOnce
 *       reason (the same per-item skip pipeline as branch-unrouted items, so counts,
 *       edge counts and downstream merge/aggregate readiness stay coherent). Outside
 *       a split context the flag is a NO-OP (single execution is already the
 *       semantic). It filters SPLIT ITEMS only - it does NOT limit loop iterations
 *       (a node inside a loop body still re-executes every iteration). If branch
 *       routing sends item 0 elsewhere, the node executes for NO item (strict,
 *       deterministic index-0 rule). Rejected at parse time on {@code split} /
 *       {@code aggregate} / {@code merge} / {@code loop} cores - see
 *       {@code WorkflowPlanParser}.</li>
 * </ul>
 *
 * <p><b>Defaults are the exact current behavior</b>: a node without a
 * {@code nodePolicy} block (or with an empty one) resolves to {@link #DEFAULT}
 * and the engine takes the byte-identical pre-policy code path.
 *
 * <p><b>Idempotency note:</b> retrying re-executes the node with the same context.
 * Side-effectful nodes (emails, API writes, CRUD inserts) WILL re-run their side
 * effects on each attempt - retry is opt-in per node precisely because only the
 * workflow author can judge idempotency.
 *
 * <p><b>Billing:</b> one logical node execution = ONE platform credit, regardless of
 * how many retry attempts it consumed. Non-final attempts are never billed (they are
 * surfaced through the attempt-aware pipeline, {@code completeAttempt}, which has no
 * billing call); the credit is charged exactly once on the TERMINAL attempt - whether
 * it succeeds or exhausts the budget ({@code StepCompletionOrchestrator.complete} bills the
 * persisted terminal row, the execution's only row since non-final attempts write none).
 * Rationale:
 * retries are the platform recovering from transient faults - charging per attempt
 * would bill users for failures they configured the policy to absorb. Agent nodes'
 * token-based LLM costs remain per-call (each attempt that reaches the LLM pays its
 * tokens) - only the flat per-node platform fee is once-per-execution.
 *
 * <p><b>Attempt visibility vs terminal state:</b> every failed attempt is WS-emitted
 * (annotated {@code policy_attempt}/{@code policy_max_attempts}); StateSnapshot counts,
 * {@code EpochState.failedNodeIds}, edge counts and {@code workflow_epochs} record ONLY
 * the terminal outcome, and so does {@code workflow_step_data}: a non-final attempt writes
 * no row (see {@code CompletionKind.persistsRow}).
 *
 * <p><b>Branching-node restriction:</b> a node that picks where the run goes never continues
 * past its own failure: it selected no port, so continuing would fan out ALL of them at once.
 * {@code continueOnFailure=true} is rejected at parse time on decision / switch / option cores;
 * on a loop core and on a classify / guardrail agent the builder tools refuse it and the run
 * ignores it ({@code NodePolicyRunner.effectivePolicy}, {@link #withoutContinueOnFailure}), so
 * a plan stored before that refusal keeps opening. {@code retryCount} stays allowed on all of
 * them. See {@code WorkflowPlanParser}.
 *
 * <p><b>Extensibility:</b> future knobs (e.g. {@code fallbackValue}) are added as new
 * record components with a widening canonical constructor plus a back-compat overload
 * of the previous shape (same pattern as {@code ExecutionContext}'s 13→15-arg
 * evolution - applied here for the 3→5 component widening that added
 * {@code timeoutMs}/{@code executeOnce}), and parsed leniently in {@link #fromMap}
 * so older plans keep resolving to the same policy.
 *
 * <p><b>Frontend mapping (later phase):</b> the workflow inspector surfaces this as a
 * generic "Execution policy" section on every node - retry count (int), backoff (ms),
 * continue-on-failure (toggle), timeout (ms), execute-once (toggle) - written verbatim
 * as the node's {@code nodePolicy} JSON block. The backend is the single validator
 * (parse-time rejection of negative values / incompatible node types).
 */
public record NodePolicy(
        int retryCount,
        long retryBackoffMs,
        boolean continueOnFailure,
        long timeoutMs,
        boolean executeOnce,
        /** {@code null} (default classification) or {@link #RETRY_ON_RATE_LIMIT}. */
        String retryOn
) {

    /** No policy = exact current behavior: single attempt, no backoff, no timeout, failure cascades SKIPPED. */
    public static final NodePolicy DEFAULT = new NodePolicy(0, 0L, false, 0L, false, null);

    /** {@code retryOn} value: retry only a rate limit (429, 503, or a 4xx with a Retry-After or rate-limit wording). */
    public static final String RETRY_ON_RATE_LIMIT = "rate_limit";

    /** Most retries a node may ask for. */
    public static final int MAX_RETRY_COUNT = 10;

    /**
     * Longest wait before one attempt, in ms (60 seconds): the wait holds the executing thread. On the
     * STEP_BY_STEP path that thread can come from a small pool (the signal-resume executor has 4) and sit
     * inside the per-run async-completion lock, so a long wait blocks unrelated runs. Prod peak: 1000 ms.
     */
    public static final long MAX_RETRY_BACKOFF_MS = 60_000L;

    /**
     * Longest wait a PROVIDER may impose through Retry-After (1 minute) beyond the node's own
     * backoff: a Retry-After is honoured up to the longer of the two, and a longer one ends the
     * retries. A provider window measured in hours must not hold a worker thread, it belongs to
     * a schedule.
     */
    public static final long MAX_PROVIDER_WAIT_MS = 60_000L;

    /** JSON key of the policy block on a plan node entry. */
    public static final String JSON_KEY = "nodePolicy";

    public NodePolicy {
        if (retryCount < 0) {
            throw new IllegalArgumentException("nodePolicy.retryCount must be >= 0 (got " + retryCount + ")");
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("nodePolicy.retryBackoffMs must be >= 0 (got " + retryBackoffMs + ")");
        }
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("nodePolicy.timeoutMs must be >= 0 (got " + timeoutMs + ")");
        }
        if (retryOn != null && !RETRY_ON_RATE_LIMIT.equals(retryOn)) {
            throw new IllegalArgumentException("nodePolicy.retryOn must be '" + RETRY_ON_RATE_LIMIT
                    + "' or absent (got '" + retryOn + "')");
        }
    }

    /**
     * Back-compat overload of the shape before {@code retryOn}: the default failure
     * classification, exactly what those callers had.
     */
    public NodePolicy(int retryCount, long retryBackoffMs, boolean continueOnFailure,
                      long timeoutMs, boolean executeOnce) {
        this(retryCount, retryBackoffMs, continueOnFailure, timeoutMs, executeOnce, null);
    }

    /**
     * Back-compat overload of the pre-timeout/executeOnce shape (retry / backoff /
     * continue-on-failure). Resolves to {@code timeoutMs=0, executeOnce=false} -
     * the exact semantics those callers had before the widening.
     */
    public NodePolicy(int retryCount, long retryBackoffMs, boolean continueOnFailure) {
        this(retryCount, retryBackoffMs, continueOnFailure, 0L, false, null);
    }

    /** True when a per-attempt timeout is configured (timeoutMs > 0). */
    public boolean hasTimeout() {
        return timeoutMs > 0;
    }

    /**
     * Total attempt budget: the initial attempt plus {@link #retryCount} retries, the retries
     * clamped to {@link #MAX_RETRY_COUNT} (a stored plan written before the cap still runs, bounded).
     */
    public int maxAttempts() {
        return Math.min(retryCount, MAX_RETRY_COUNT) + 1;
    }

    /** True when only a rate limit (429, 503, or a 4xx rate-limit signal) is retried. */
    public boolean retriesOnlyRateLimits() {
        return RETRY_ON_RATE_LIMIT.equals(retryOn);
    }

    /**
     * The builder-side refusal of a policy no tool may WRITE, as an agent-facing message, or
     * {@code null} when it is acceptable: a value above a cap, or a {@code retryOn} with nothing
     * to retry. Not applied by the parser, so a stored plan keeps opening (and is clamped when
     * it runs).
     */
    public String writeViolation(String nodeKey) {
        if (retryOn != null && retryCount == 0) {
            return "Invalid nodePolicy for node '" + nodeKey + "': retryOn only applies when "
                    + "retryCount > 0. Set retryCount, or leave retryOn out.";
        }
        if (retryCount > MAX_RETRY_COUNT) {
            return "Invalid nodePolicy for node '" + nodeKey + "': retryCount is at most "
                    + MAX_RETRY_COUNT + " (got " + retryCount + ").";
        }
        if (retryBackoffMs > MAX_RETRY_BACKOFF_MS) {
            return "Invalid nodePolicy for node '" + nodeKey + "': retryBackoffMs is at most "
                    + MAX_RETRY_BACKOFF_MS + " (60 seconds, got " + retryBackoffMs + ").";
        }
        return null;
    }

    /** This policy without {@code continueOnFailure}: every other field unchanged. */
    public NodePolicy withoutContinueOnFailure() {
        return continueOnFailure
            ? new NodePolicy(retryCount, retryBackoffMs, false, timeoutMs, executeOnce, retryOn)
            : this;
    }

    /** True when this policy is behaviorally identical to having no policy at all. */
    public boolean isDefault() {
        return this.equals(DEFAULT);
    }

    /**
     * Parses a raw {@code nodePolicy} JSON block.
     *
     * <p>Lenient on shape (absent keys default; unknown keys ignored for forward
     * compatibility) but STRICT on values: negative or non-numeric values are
     * rejected with a clear error naming the offending node, so a bad plan fails
     * at parse time instead of surprising at execution time.
     *
     * @param raw     the raw value under the {@code nodePolicy} key (expected Map; null → DEFAULT)
     * @param nodeKey normalized node key for error messages (e.g. {@code mcp:send_email})
     * @return the parsed policy, or {@link #DEFAULT} when the block is absent/empty
     * @throws IllegalArgumentException on negative or non-coercible values
     */
    public static NodePolicy fromMap(Object raw, String nodeKey) {
        if (raw == null) {
            return DEFAULT;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "Invalid nodePolicy for node '" + nodeKey + "': expected an object, got "
                            + raw.getClass().getSimpleName());
        }
        int retryCount = requireNonNegativeInt(map.get("retryCount"), "retryCount", 0, nodeKey);
        long retryBackoffMs = requireNonNegativeLong(map.get("retryBackoffMs"), "retryBackoffMs", 0L, nodeKey);
        boolean continueOnFailure = coerceBoolean(map.get("continueOnFailure"), "continueOnFailure", nodeKey);
        long timeoutMs = requireNonNegativeLong(map.get("timeoutMs"), "timeoutMs", 0L, nodeKey);
        boolean executeOnce = coerceBoolean(map.get("executeOnce"), "executeOnce", nodeKey);
        String retryOn = parseRetryOn(map.get("retryOn"), nodeKey);
        // A stored plan may still carry "providerRetryMaxWaitSec", a knob of the provider retry the
        // platform no longer performs: like any unknown key it is ignored, so those plans keep parsing.
        return new NodePolicy(retryCount, retryBackoffMs, continueOnFailure, timeoutMs, executeOnce, retryOn);
    }

    private static String parseRetryOn(Object value, String nodeKey) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            return null;
        }
        if (value instanceof String s && RETRY_ON_RATE_LIMIT.equals(s.trim())) {
            return RETRY_ON_RATE_LIMIT;
        }
        throw new IllegalArgumentException("Invalid nodePolicy.retryOn for node '" + nodeKey
                + "': the only value is '" + RETRY_ON_RATE_LIMIT + "' (retry only when the provider "
                + "signals a rate limit: 429, 503, a Retry-After, or a rate-limit message); leave it out "
                + "to retry every failure except a permanent refusal (got '" + value + "')");
    }

    private static int requireNonNegativeInt(Object value, String field, int defaultValue, String nodeKey) {
        long parsed = requireNonNegativeLong(value, field, defaultValue, nodeKey);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Invalid nodePolicy." + field + " for node '" + nodeKey + "': value too large (" + parsed + ")");
        }
        return (int) parsed;
    }

    private static long requireNonNegativeLong(Object value, String field, long defaultValue, String nodeKey) {
        if (value == null) {
            return defaultValue;
        }
        long parsed;
        if (value instanceof Number n) {
            parsed = n.longValue();
        } else if (value instanceof String s && !s.isBlank()) {
            try {
                parsed = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid nodePolicy." + field + " for node '" + nodeKey + "': not a number ('" + s + "')");
            }
        } else {
            throw new IllegalArgumentException(
                    "Invalid nodePolicy." + field + " for node '" + nodeKey + "': expected a non-negative number");
        }
        if (parsed < 0) {
            throw new IllegalArgumentException(
                    "Invalid nodePolicy." + field + " for node '" + nodeKey + "': must be >= 0 (got " + parsed + ")");
        }
        return parsed;
    }

    private static boolean coerceBoolean(Object value, String field, String nodeKey) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) return true;
            if ("false".equalsIgnoreCase(s.trim())) return false;
        }
        throw new IllegalArgumentException(
                "Invalid nodePolicy." + field + " for node '" + nodeKey + "': expected a boolean");
    }
}
