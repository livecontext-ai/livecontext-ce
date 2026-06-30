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
 *       (0 = today's behavior, single attempt). Total attempts = retryCount + 1.</li>
 *   <li>{@code retryBackoffMs} - delay between attempts. The backoff blocks only the
 *       executing thread (per branch / per split item), never sibling branches.</li>
 *   <li>{@code continueOnFailure} - when ALL attempts fail, the node is still marked
 *       FAILED (same WS event + DB persistence as any failure today) but the engine
 *       continues traversal to its successors instead of cascading SKIPPED to them.
 *       This reuses the existing "SKIPPED-with-error" continuation semantic: the
 *       default {@code BaseNode.getNextNodes} exposes successors for any non-FAILED
 *       result and {@code ExecutionContext.isCompleted} already treats FAILED as a
 *       resolved state (merge readiness = all predecessors resolved), so successors
 *       execute exactly as they do after a terminal SKIPPED node - with the failed
 *       node resolved-but-without-output. Run-level statuses derive from the
 *       UNCHANGED existing semantics (a failed step still counts as failed →
 *       FAILED / PARTIAL_SUCCESS as today's finalizers dictate).</li>
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
 *       only: signal yields (AWAITING_SIGNAL) and async dispatches return immediately, so
 *       the subsequent signal wait / async work is never subject to {@code timeoutMs};
 *       split fan-out coordination and summaries are never bounded either - in a split
 *       the timeout applies PER ITEM, per attempt.</li>
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
 * it succeeds or exhausts the budget ({@code StepCompletionOrchestrator.complete}
 * bills persisted terminal rows, plus the deduped-terminal-failure branch for retried
 * nodes whose FAILED slot was already claimed by the first attempt's row). Rationale:
 * retries are the platform recovering from transient faults - charging per attempt
 * would bill users for failures they configured the policy to absorb. Agent nodes'
 * token-based LLM costs remain per-call (each attempt that reaches the LLM pays its
 * tokens) - only the flat per-node platform fee is once-per-execution.
 *
 * <p><b>Attempt visibility vs terminal state:</b> every failed attempt is WS-emitted
 * (annotated {@code policy_attempt}/{@code policy_max_attempts}); StateSnapshot counts,
 * {@code EpochState.failedNodeIds}, edge counts and {@code workflow_epochs} record ONLY
 * the terminal outcome. Attempt step_data rows persist in non-loop contexts only - see
 * {@code NodeCompletionService.emitNodeFailedAttempt} for the loop/non-loop asymmetry.
 *
 * <p><b>Branching-node restriction:</b> {@code continueOnFailure=true} is rejected at
 * parse time on single-port branching cores (decision / switch / option): a failed
 * branching node selected no port, so continuing would fan out ALL ports at once.
 * {@code retryCount} stays allowed there. See {@code WorkflowPlanParser}.
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
        boolean executeOnce
) {

    /** No policy = exact current behavior: single attempt, no backoff, no timeout, failure cascades SKIPPED. */
    public static final NodePolicy DEFAULT = new NodePolicy(0, 0L, false, 0L, false);

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
    }

    /**
     * Back-compat overload of the pre-timeout/executeOnce shape (retry / backoff /
     * continue-on-failure). Resolves to {@code timeoutMs=0, executeOnce=false} -
     * the exact semantics those callers had before the widening.
     */
    public NodePolicy(int retryCount, long retryBackoffMs, boolean continueOnFailure) {
        this(retryCount, retryBackoffMs, continueOnFailure, 0L, false);
    }

    /** True when a per-attempt timeout is configured (timeoutMs > 0). */
    public boolean hasTimeout() {
        return timeoutMs > 0;
    }

    /** Total attempt budget: the initial attempt plus {@link #retryCount} retries. */
    public int maxAttempts() {
        return retryCount + 1;
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
        return new NodePolicy(retryCount, retryBackoffMs, continueOnFailure, timeoutMs, executeOnce);
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
