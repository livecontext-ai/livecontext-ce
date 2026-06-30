package com.apimarketplace.orchestrator.archunit;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import org.hibernate.annotations.DynamicUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan v4 E2E4 - pin {@code @DynamicUpdate} on {@link WorkflowRunEntity}.
 *
 * <p>Without this annotation, Hibernate emits UPDATE statements containing
 * EVERY column of the entity, even when only one field was set by the caller.
 * In a workflow_runs row this means {@code state_snapshot}, {@code state_snapshot_seq},
 * and {@code state_snapshot} would be re-written on every {@code save()} -
 * with whatever values the L1-cached entity holds.
 *
 * <p>Under contention (k6 saturation 50 VU × 3 min on a single run reproduces
 * this in ~10s), a caller that loads the entity non-locked for a status check,
 * then later modifies an unrelated field and saves, will overwrite the live
 * {@code state_snapshot_seq=N} with its L1-cached {@code N-M} value. V181's
 * monotonicity trigger correctly rejects the write - but the rejection
 * cascades into a failed run and lost work.
 *
 * <p>With {@code @DynamicUpdate}, Hibernate tracks per-field dirty state
 * since load and emits an UPDATE containing only the columns whose values
 * changed via setters. The state_snapshot* fields, untouched by callers like
 * {@code ReusableTriggerService.setPlan + save}, are NOT in the UPDATE
 * statement and V181 doesn't trip.
 *
 * <p>Removing {@code @DynamicUpdate} is a regression. This test fails loudly
 * if a refactor (or auto-formatting, or a merge resolution) drops it.
 */
@DisplayName("Plan v4 E2E4 - @DynamicUpdate annotation on WorkflowRunEntity")
class DynamicUpdateOnWorkflowRunEntityTest {

    @Test
    @DisplayName("WorkflowRunEntity carries @DynamicUpdate so saves emit only-dirty UPDATE")
    void workflowRunEntityHasDynamicUpdate() {
        DynamicUpdate annotation = WorkflowRunEntity.class.getAnnotation(DynamicUpdate.class);
        assertThat(annotation)
                .as("WorkflowRunEntity MUST be annotated @DynamicUpdate - see plan v4 E2E4 "
                        + "(k6 saturation reproduced 21k+ state_snapshot_seq regress errors "
                        + "without it). Removing this annotation is a regression.")
                .isNotNull();
    }
}
