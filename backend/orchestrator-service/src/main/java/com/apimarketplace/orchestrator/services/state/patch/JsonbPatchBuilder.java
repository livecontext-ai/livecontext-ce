package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Per-mutator translator: given a pre-mutation {@link StateSnapshot} {@code before}
 * and the post-mutation {@code after} that the regular Java mutator path produced,
 * build the minimal list of {@link JsonbPatch} operations that bring the DB JSONB
 * column from {@code before} to {@code after}.
 *
 * <h2>Three return states</h2>
 *
 * <ul>
 *   <li>{@link Result#NO_OP} - the mutation made no observable change to the
 *       fields this builder cares about (e.g. {@code markNodeAwaitingSignal}
 *       on a node already in the awaiting set). The caller skips the DB write
 *       entirely. Only valid for set-pure mutators where idempotence is
 *       semantically equivalent to no-op; a builder for a counts-bumping
 *       mutator (e.g. {@code markNodeCompleted}) MUST NOT return NO_OP.</li>
 *   <li>{@link Result#fallback()} - the builder cannot express the mutation
 *       as a list of patches (unknown shape, missing path prerequisite,
 *       schema-evolution mismatch, etc.). The caller falls back to the full
 *       Jackson rewrite path. Detected via {@code result.fallback == true}.</li>
 *   <li>{@link Result#patch(List)} - the mutation is expressible as the given
 *       list of patches. The caller emits one composed {@code UPDATE … SET
 *       state_snapshot = jsonb_set(jsonb_set(…, …), …)} statement.</li>
 * </ul>
 *
 * <h2>Tenant elide flag (P2.3)</h2>
 *
 * Builders MUST consult {@link com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver}
 * for {@code runningNodeIds} paths. When elide is ON for the tenant, the
 * builder MUST omit any {@code …runningNodeIds} patch - emitting one would
 * resurrect the field in the JSONB and silently regress the P2.3 default-ON
 * shipping (commit {@code 72eab813c}).
 *
 * <p>Important: builders serialize individual {@link java.util.Set} fields
 * (e.g. {@code completedNodeIds}) as standalone JSON arrays via the shared
 * {@code stateSnapshotMapper} - NOT the full {@link com.apimarketplace.orchestrator.domain.execution.EpochState}.
 * The {@code EpochStateRunningElideSerializer} is registered at the EpochState
 * level and is consequently NOT triggered by per-set serialization here. The
 * elide decision is taken explicitly by the builder (via the resolver) and
 * mirrored by omitting the {@code runningNodeIds} patch - keeping JSONB shape
 * symmetric with the full-rewrite path. A future contributor adding
 * {@code mapper.writeValueAsString(epochState)} would re-trigger the
 * serializer and break the symmetry - don't.
 *
 * <h2>Set ordering</h2>
 *
 * Sets are backed by {@link java.util.HashSet} (unordered). When the builder
 * serializes a set as a JSON array, the element order is non-deterministic.
 * Two patches emitting "same content, different order" will produce
 * cosmetically distinct JSONB byte strings - semantically equivalent for any
 * Set-typed reader, but a future shadow validator comparing patch vs
 * full-rewrite output byte-for-byte will see false-positive divergences.
 * Mitigation when shadow validation lands: switch to {@link java.util.LinkedHashSet}
 * or sort before serialization. Documented here, not blocking for round 1
 * (no shadow validator yet).
 */
public interface JsonbPatchBuilder {

    /**
     * Builder result discriminating PATCH / NO_OP / FALLBACK.
     *
     * <p>Hand-written instead of sealed interface to keep the test surface
     * small (no Switch on patterns required).
     */
    final class Result {
        private static final Result FALLBACK = new Result(null, false, true);
        private static final Result NO_OP = new Result(List.of(), true, false);

        private final List<JsonbPatch> patches;
        private final boolean noOp;
        private final boolean fallback;

        private Result(List<JsonbPatch> patches, boolean noOp, boolean fallback) {
            this.patches = patches;
            this.noOp = noOp;
            this.fallback = fallback;
        }

        public static Result patch(List<JsonbPatch> patches) {
            if (patches == null || patches.isEmpty()) {
                throw new IllegalArgumentException("patch list must not be null or empty; use NO_OP for no-op");
            }
            return new Result(List.copyOf(patches), false, false);
        }

        public static Result noOp() { return NO_OP; }
        public static Result fallback() { return FALLBACK; }

        public boolean isNoOp() { return noOp; }
        public boolean isFallback() { return fallback; }
        public boolean isPatch() { return !noOp && !fallback; }

        /** Only valid when {@link #isPatch()} is true. */
        public Optional<List<JsonbPatch>> patches() {
            return isPatch() ? Optional.of(patches) : Optional.empty();
        }
    }
}
