package com.apimarketplace.orchestrator.services.state.patch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Plan v4 §1.4 builder classification marker - every concrete
 * {@link JsonbPatchBuilder} subclass MUST be annotated with this so the CAS
 * path knows which composition rules apply.
 *
 * <p>{@code opKind} is the union of the kinds of operations the builder emits
 * across all its {@code @PatchTarget} methods. Combined with the per-method
 * {@link PatchTarget} annotation, the {@link RunCoalescingService} can decide
 * whether two same-path patches can be merged (DELTA+DELTA on COMMUTATIVE_DELTA)
 * or must force-flush (DELTA+ASSIGN, ASSIGN+ASSIGN - plan §3 force-flush rule).
 *
 * <p>ArchUnit invariant (plan §1.4): builders annotated {@code @PatchClass} MUST NOT
 * inject {@code RestTemplate}, {@code RedisTemplate}, {@code JpaRepository},
 * {@code StateSnapshotService}, or {@code WorkflowRunRepository}. They are pure
 * functions of {@code (snapshot, params)}. Enforced by
 * {@code PatchBuilderPurityArchUnitTest} (shipping with the callsite-flip phase
 * of #1).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface PatchClass {

    /** The composition kind(s) of operations this builder emits. */
    OpKind[] opKind();

    /**
     * Composition kinds:
     * <ul>
     *   <li>{@code COMMUTATIVE_DELTA} - integer counter increment via
     *       {@code (state_snapshot->'X'->>'Y')::int + N} read-modify-write inside
     *       the SQL statement. Two same-path patches can MERGE into {@code +N+M}
     *       without correctness loss because Postgres serializes the
     *       row-level UPDATE.</li>
     *   <li>{@code ASSIGN} - literal value replacement (timestamp, status
     *       string, array assignment). Two same-path patches CANNOT merge -
     *       force-flush the earlier patch first.</li>
     * </ul>
     */
    enum OpKind { COMMUTATIVE_DELTA, ASSIGN }
}
