package com.apimarketplace.orchestrator.archunit;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan v4 §1.4 callsite invariant - enforces the JSONB-write contract on
 * {@code workflow_runs}.
 *
 * <p>The plan-v4 ideal rule is: every UPDATE on {@code workflow_runs.state_snapshot}
 * (or {@code state_snapshot_seq}) flows through one of:
 * <ul>
 *   <li>{@code services.state.patch.JsonbPatchExecutor.applyPatches} (pessimistic
 *       + advisory-lock carve-outs, {@code @Transactional(propagation = MANDATORY)})</li>
 *   <li>{@code services.state.patch.JsonbPatchExecutor.applyPatchesCas} (CAS path,
 *       shipping in #1+#3+#14 bundle)</li>
 *   <li>{@code services.state.StateSnapshotService.saveSnapshotFullRewrite} (full
 *       rewrite for non-patchable mutations)</li>
 * </ul>
 *
 * <p>Generic ArchUnit cannot parse SQL literals; the rule we CAN enforce
 * reliably is: every {@code @Modifying @Query} on {@link WorkflowRunRepository}
 * must be in a declared allow-list. New mutating queries fail this test until
 * either (a) the developer adds a justified entry here with code review or
 * (b) the work is reshaped to go through the patch executor.
 *
 * <p>The {@code upsertLastEventSeqSingle} entry is the sole pre-plan-v4
 * carve-out: it updates {@code last_event_seq} only (not state_snapshot), is
 * idempotent, monotonic, and pre-exists this plan.
 *
 * <p>This test ships ALONE in plan v4 §17 - the other four ArchUnit rules
 * (builder purity, factory ban, advisory-lock no-HTTP, try-finally) ship with
 * the #1+#3 bundle once the {@code @PatchClass}, {@code @AdvisoryLockHolding}
 * marker annotations exist. Stubs in this package, @Disabled until then.
 */
@DisplayName("plan v4 §1.4 - WorkflowRunRepository @Modifying allow-list")
class JsonbWritesCallsiteInvariantTest {

    /**
     * Allow-listed {@code @Modifying} method names on
     * {@link WorkflowRunRepository}. Adding a new entry here requires:
     * <ol>
     *   <li>The method does NOT update {@code state_snapshot} or
     *       {@code state_snapshot_seq} - those must flow through the patch
     *       executor.</li>
     *   <li>The query is idempotent or monotonic (so retries don't corrupt).</li>
     *   <li>The audit step at the next plan milestone reviews the entry.</li>
     * </ol>
     */
    private static final Set<String> ALLOWED_MODIFYING_METHODS = Set.of(
            "upsertLastEventSeqSingle",       // pre-plan-v4 carve-out: last_event_seq only, idempotent + monotonic
            "cancelAllActiveRuns",            // workflow-lifecycle bulk cancel: SET status/endedAt/updatedAt - never touches state_snapshot
            "cancelStaleRuns",                // same: bulk cancel WAITING_TRIGGER + PAUSED by workflow_id
            "cancelWaitingTriggerRuns",       // same: bulk cancel WAITING_TRIGGER by workflow_id
            "upsertUserPlanMetadata",         // plan v4 E2E4 carve-out: metadata/userPlan jsonb_set only - never touches state_snapshot
            "updateSnapshotAndSeq",           // plan v4 E2E5 carve-out: saveSnapshotFullRewrite native UPDATE (snapshot + seq atomic)
            "incrementRunCost"                // run-budget carve-out: cost_credits + cost_by_epoch monotonic increment - never touches state_snapshot; idempotent-safe (pure add), fed by agent-cost notifications
    );

    @Test
    @DisplayName("Every @Modifying @Query on WorkflowRunRepository must be in the allow-list "
            + "- new mutations route through JsonbPatchExecutor or are explicitly justified here")
    void everyModifyingQueryIsAllowListed() {
        Set<String> declared = Arrays.stream(WorkflowRunRepository.class.getMethods())
                .filter(m -> m.isAnnotationPresent(Modifying.class))
                .filter(m -> m.isAnnotationPresent(Query.class))
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());

        Set<String> rogue = declared.stream()
                .filter(name -> !ALLOWED_MODIFYING_METHODS.contains(name))
                .collect(Collectors.toUnmodifiableSet());

        assertThat(rogue)
                .as("Unauthorized @Modifying @Query methods on WorkflowRunRepository. "
                        + "Plan v4 §1.4: every UPDATE on workflow_runs (especially state_snapshot/"
                        + "state_snapshot_seq) must flow through JsonbPatchExecutor.applyPatches, "
                        + "applyPatchesCas, or StateSnapshotService.saveSnapshotFullRewrite. "
                        + "If a new mutation truly does NOT touch state_snapshot and is "
                        + "idempotent/monotonic, justify and add to ALLOWED_MODIFYING_METHODS.")
                .isEmpty();
    }

    @Test
    @DisplayName("Allow-list entries reference real methods (catch typos when an entry rots after rename)")
    void allowListEntriesReferenceExistingMethods() {
        Set<String> declared = Arrays.stream(WorkflowRunRepository.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toUnmodifiableSet());

        Set<String> stale = ALLOWED_MODIFYING_METHODS.stream()
                .filter(name -> !declared.contains(name))
                .collect(Collectors.toUnmodifiableSet());

        assertThat(stale)
                .as("Allow-list entries that no longer match a WorkflowRunRepository method - "
                        + "drop these from ALLOWED_MODIFYING_METHODS or fix the rename")
                .isEmpty();
    }
}
