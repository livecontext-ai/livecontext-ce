package com.apimarketplace.orchestrator.domain.workflow;

import java.util.regex.Pattern;

/**
 * Per-column caps for the identifier-string fields on
 * {@code workflow_step_data}. Sister utility to {@link ErrorMessageLimits}
 * - the latter covers the large diagnostic blob columns
 * ({@code error_message} TEXT, {@code metadata.statusMessage} JSONB,
 * cap = 16 384 chars); this one covers the short identifier columns that
 * map to VARCHAR(N) and feed indexes.
 *
 * <p><b>Why per-column constants instead of one global cap?</b> Some of
 * these columns participate in the unique index
 * {@code idx_workflow_step_data_unique_v6} (covers {@code step_alias} +
 * {@code trigger_id}) or in the aggregate index from V155
 * ({@code normalized_key}). Postgres B-tree caps an index entry at
 * ~2 712 bytes; a UTF-8 multibyte worst case at 2 000 chars × 4 B/char =
 * 8 KB already exceeds that for a SINGLE column. So indexed columns
 * need both a tighter cap AND a collision-safe hash suffix (two distinct
 * long values truncated at the same prefix would collide on the unique
 * index, silently dropping the second insert via
 * {@code ON CONFLICT DO NOTHING}).
 *
 * <p><b>Caps today</b> match the current schema (VARCHAR(255)), minus
 * room for the suffix. F1 bundle 2 will widen the DB columns to
 * VARCHAR(2000) and bump these caps to 500 (the B-tree-safe value
 * derived in the round-3 schema audit). For now we live within
 * VARCHAR(255) by capping at 220 (plain) / 200 (hash-suffix - extra
 * room for the 32-char hash tail).
 *
 * <p>Hot path is zero-allocation when the input is {@code null} or
 * within budget. Slow path uses a single pre-sized StringBuilder.
 */
public final class DiagnosticFieldLimits {

    // Indexed columns (participate in v6 unique index or V155 aggregate
    // index). V189 widened the DB to VARCHAR(2000) and added a CHECK ≤ 500
    // constraint on each; caps bumped from 200 to 500 to match. Math (see
    // V189 header): worst-case UTF-8 4 B/char on v6 unique index keeps
    // 2 256 B < 2 704 B Postgres B-tree limit.
    public static final int STEP_ALIAS_MAX     = 500;
    public static final int TRIGGER_ID_MAX     = 500;
    public static final int NORMALIZED_KEY_MAX = 500;

    // Non-indexed identifier columns. V189 widened to VARCHAR(2000); no
    // CHECK constraint (no B-tree pressure). Caps generous at 1 000 - well
    // under the schema ceiling but bounded so a runaway caller cannot bloat
    // a row to multi-KB. Plain truncation marker (no collision risk).
    public static final int TOOL_ID_MAX            = 1_000;
    public static final int LOOP_ID_MAX            = 1_000;
    public static final int SKIP_SOURCE_NODE_MAX   = 1_000;

    // run_id is system-generated (`run_<ts>_<8hex>` ≈ 24 chars) but
    // participates in idx_wsd_run_step alongside step_alias. V189 added
    // CHECK (length(run_id) <= 200) as a forward-compat invariant pin -
    // any future caller that builds a longer run id is caught at the DB
    // boundary. Constant exposed here for read-side cap symmetry helpers
    // (callers of WorkflowStepDataRepository.findByRunIdAnd*) - the entity
    // setter is intentionally NOT capped (system-generated values never
    // overflow in practice, and the @PrePersist hook would re-truncate a
    // sentinel value).
    public static final int RUN_ID_MAX = 200;

    /**
     * Sentinel value preserved unchanged by {@link #capWithCollisionHash}.
     * V164 made {@code trigger_id NOT NULL DEFAULT 'trigger:default'} -
     * hashing that sentinel would mutate the unique-index partition key
     * for the entire "no specific trigger" cohort, which is precisely the
     * opposite of what the collision-safe cap is trying to achieve.
     */
    private static final String DEFAULT_TRIGGER_SENTINEL = "trigger:default";

    /**
     * Matches the markers emitted by both {@link #cap} and
     * {@link #capWithCollisionHash}. Used to make truncation idempotent:
     * passing an already-truncated value back through either method
     * returns it unchanged, no double-suffix stacking.
     */
    private static final Pattern TRUNCATION_MARKER =
            Pattern.compile("…\\[truncated,was=\\d+(?:,hash=[0-9a-f]{8})?\\]$");

    private DiagnosticFieldLimits() {}

    /**
     * Read-side cap helper. Callers querying {@code WorkflowStepDataRepository}
     * by stepAlias / triggerId / normalizedKey from a runtime source (dynamic
     * label, raw trigger config, derived label) MUST wrap the query parameter
     * with {@code DiagnosticFieldLimits.cap(value, max)} or
     * {@code capWithCollisionHash(value, max)} - same cap as the writer side -
     * so that a long input matches its capped persisted form.
     *
     * <p>Without this symmetry, a 300-char alias gets capped to ~200 chars at
     * write time but the caller queries with the 300-char raw form → no match
     * → row appears missing. Silent zero-rows on reads is the read-side mirror
     * of the F1 silent-drop on writes - same UX failure mode, different
     * vector.
     *
     * <p>Bundle 2 will add a thin {@code CappedWorkflowStepDataRepository}
     * fragment to enforce this at every finder method automatically; this
     * static helper is the bridge layer until that ships. Apply at hot call
     * sites first (StepCompletionOrchestrator.recordSplitAggregateIfMissing,
     * ItemIndex lookups by selectedBranch).
     */

    /**
     * Plain truncation. Marker shape:
     * {@code …[truncated,was=N]}. Used for identifier columns that are
     * NOT in a unique index - collision-after-truncation cannot drop
     * rows here, so the cheaper non-hashed suffix is sufficient.
     */
    public static String cap(String value, int max) {
        if (value == null) return null;
        int len = value.length();
        if (len <= max) return value;
        if (TRUNCATION_MARKER.matcher(value).find()) return value; // idempotent
        String suffix = "…[truncated,was=" + len + "]";
        int prefix = Math.max(0, max - suffix.length());
        return new StringBuilder(max).append(value, 0, prefix).append(suffix).toString();
    }

    /**
     * Truncation with a deterministic hash suffix. Marker shape:
     * {@code …[truncated,was=N,hash=hhhhhhhh]} where {@code hhhhhhhh} is
     * the 8-char hex of {@code value.hashCode()} (JLS-specified, stable
     * across JVMs since Java 1.2).
     *
     * <p>Used for the three columns in the unique index
     * {@code idx_workflow_step_data_unique_v6} ({@code step_alias},
     * {@code trigger_id}) and the V155 aggregate index
     * ({@code normalized_key}). The hash differentiates two distinct
     * long values that share their first N chars - without it, both
     * would truncate to the same prefix and the second INSERT would be
     * silently dropped by {@code ON CONFLICT DO NOTHING}, recreating
     * the F1 silent-drop family from a different vector.
     *
     * <p>Sentinel-aware: {@code "trigger:default"} (the V164 NOT NULL
     * default) passes through unchanged.
     */
    public static String capWithCollisionHash(String value, int max) {
        if (value == null) return null;
        if (DEFAULT_TRIGGER_SENTINEL.equals(value)) return value;
        int len = value.length();
        if (len <= max) return value;
        if (TRUNCATION_MARKER.matcher(value).find()) return value; // idempotent
        String hash = String.format("%08x", value.hashCode());
        String suffix = "…[truncated,was=" + len + ",hash=" + hash + "]";
        int prefix = Math.max(0, max - suffix.length());
        return new StringBuilder(max).append(value, 0, prefix).append(suffix).toString();
    }
}
