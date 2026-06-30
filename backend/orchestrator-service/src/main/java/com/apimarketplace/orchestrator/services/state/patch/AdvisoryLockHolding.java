package com.apimarketplace.orchestrator.services.state.patch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Plan v4 §1.6 marker - methods that take the per-run
 * {@code pg_advisory_xact_lock(0x0100…L | hashtextextended(runId,0))} at the
 * top of their {@code @Transactional} body.
 *
 * <p>Used by ArchUnit (plan §1.6) to enforce the "no HTTP under advisory lock"
 * contract: a method tagged with this marker MUST NOT call client beans that
 * issue HTTP / WebClient / RestTemplate operations within its body. Holding a
 * Postgres advisory lock during a network round-trip would extend the lock
 * tail beyond Postgres-internal time and create CAS retry-storm risk on
 * concurrent writers (audit A H2).
 *
 * <p>Listed advisory-lock carve-out methods (plan v4 §1.6, verified file:line
 * against the working tree on 2026-05-11 phase 2e annotation pass):
 *
 * <ul>
 *   <li>{@code WorkflowResumeService.cancelWorkflow / reactivateWorkflow / stopWorkflow}
 *       - the 3 @Transactional methods that take findByRunIdPublicForUpdate (lines 465, 679, 967).</li>
 *   <li>{@code SignalResumeService.resumeAfterSignal} (line 192) - signal resume after dedup.</li>
 *   <li>{@code TriggerEpochManager.incrementEpoch} (2 overloads at 333, 378).</li>
 *   <li>{@code ReusableTriggerService.resetForNextCycle} (2 overloads at 1253, 1278) +
 *       {@code ReusableTriggerService.executeTriggerInternal} (line 334).</li>
 * </ul>
 *
 * <p>The lock namespace is the top byte {@code 0x01} (plan §26) - reserved for
 * state-snapshot coordination locks. Future advisory-lock features pick a
 * different top byte to avoid collisions.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AdvisoryLockHolding {

    /**
     * The advisory-lock namespace byte (top byte of the 64-bit lock key).
     * Default {@code 0x01} matches plan §26 state-snapshot coordination.
     */
    byte namespace() default (byte) 0x01;
}
