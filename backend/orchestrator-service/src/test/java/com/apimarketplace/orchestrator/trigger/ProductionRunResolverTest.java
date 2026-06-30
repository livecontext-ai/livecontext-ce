package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ProductionRunResolver} - the centralised production-run lookup that
 * the round-7 redesign extends with {@link ProductionRunResolver.RunSelectionPolicy}.
 *
 * <p>Covers the full matrix:
 * <ul>
 *   <li>3 policies × 4 outcomes (FOUND, NOT_PINNED, NO_PRODUCTION_RUN, WORKFLOW_MISSING)</li>
 *   <li>The legacy single-arg {@link ProductionRunResolver#resolveProductionRun(UUID)} wrapper</li>
 *   <li>{@link ProductionRunResolver#isAllowedForProduction(WorkflowRunEntity, WorkflowEntity)}
 *       defense-in-depth check</li>
 * </ul>
 *
 * <p>This is the PR1 gate: no migration ships until these tests are green.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductionRunResolver - PR1 RunSelectionPolicy + Outcome rename")
class ProductionRunResolverTest {

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final Integer PINNED_VERSION = 5;

    @Mock
    private WorkflowRepository workflowRepository;
    @Mock
    private WorkflowRunRepository runRepository;

    @InjectMocks
    private ProductionRunResolver resolver;

    private WorkflowEntity pinnedWorkflow() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setName("test-wf");
        wf.setPinnedVersion(PINNED_VERSION);
        return wf;
    }

    private WorkflowEntity unpinnedWorkflow() {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setName("test-wf");
        wf.setPinnedVersion(null);
        return wf;
    }

    private WorkflowRunEntity run(RunStatus status) {
        WorkflowRunEntity r = new WorkflowRunEntity();
        r.setRunIdPublic("run-123");
        r.setStatus(status);
        r.setPlanVersion(PINNED_VERSION);
        return r;
    }

    @BeforeEach
    void setUp() {
        // Default: workflow exists and is pinned. Tests override as needed.
        lenient().when(workflowRepository.findById(WORKFLOW_ID))
            .thenReturn(Optional.of(pinnedWorkflow()));
    }

    // ============================================================
    // Common outcomes - apply to ALL policies
    // ============================================================

    @Nested
    @DisplayName("Common outcomes (workflow lookup)")
    class CommonOutcomeTests {

        @Test
        @DisplayName("Null workflowId → WORKFLOW_MISSING (defensive null guard)")
        void nullWorkflowId() {
            ProductionRunResolver.Resolution res = resolver.resolve(null,
                ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.WORKFLOW_MISSING);
            assertThat(res.run()).isEmpty();
            assertThat(res.workflowName()).isNull();
            assertThat(res.isWorkflowMissing()).isTrue();
        }

        @Test
        @DisplayName("Workflow not found → WORKFLOW_MISSING")
        void workflowNotFound() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.WORKFLOW_MISSING);
            assertThat(res.isWorkflowMissing()).isTrue();
        }

        @Test
        @DisplayName("Workflow has no pinned version → NOT_PINNED (regardless of policy)")
        void workflowNotPinned() {
            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(unpinnedWorkflow()));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NOT_PINNED);
            assertThat(res.run()).isEmpty();
            assertThat(res.workflowName()).isEqualTo("test-wf");
            assertThat(res.isNotPinned()).isTrue();
        }

        @Test
        @DisplayName("Null policy defaults to LATEST_TRUSTED")
        void nullPolicyDefaultsToTrusted() {
            WorkflowRunEntity completedRun = run(RunStatus.COMPLETED);
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any()))
                .thenReturn(Optional.of(completedRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID, null);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run()).contains(completedRun);
        }
    }

    // ============================================================
    // Policy: LATEST_WAITING_TRIGGER (schedule's accumulation pattern)
    // ============================================================

    @Nested
    @DisplayName("Policy LATEST_WAITING_TRIGGER (schedule)")
    class WaitingTriggerPolicyTests {

        @Test
        @DisplayName("WAITING_TRIGGER run exists at pinned version → FOUND")
        void waitingTriggerRunFound() {
            WorkflowRunEntity waitingRun = run(RunStatus.WAITING_TRIGGER);
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                WORKFLOW_ID, PINNED_VERSION, RunStatus.WAITING_TRIGGER))
                .thenReturn(Optional.of(waitingRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run()).contains(waitingRun);
            // Critical: trusted-statuses query is NOT called (strict policy)
            verify(runRepository).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                WORKFLOW_ID, PINNED_VERSION, RunStatus.WAITING_TRIGGER);
            verifyNoMoreInteractions(runRepository);
        }

        @Test
        @DisplayName("Only COMPLETED run exists at pinned version → NO_PRODUCTION_RUN " +
                     "(strict WAITING_TRIGGER policy filters it out - this is the round-7 fix)")
        void completedRunRejected() {
            // The repository query for WAITING_TRIGGER returns empty - even though a COMPLETED
            // run exists at this version, the strict policy ignores it.
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                WORKFLOW_ID, PINNED_VERSION, RunStatus.WAITING_TRIGGER))
                .thenReturn(Optional.empty());

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            assertThat(res.isNoProductionRun()).isTrue();
        }

        @Test
        @DisplayName("CANCELLED run at pinned version → NO_PRODUCTION_RUN, " +
                     "and TRUSTED-set overload is NEVER called (D1 regression guard)")
        void cancelledRunDoesNotShadowWaitingTrigger() {
            // The strict query returns empty (no WAITING_TRIGGER even though CANCELLED exists)
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                WORKFLOW_ID, PINNED_VERSION, RunStatus.WAITING_TRIGGER))
                .thenReturn(Optional.empty());

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            // Pre-PR1 bug: CANCELLED would be returned via FindFirstOrderByStartedAtDesc → schedule auto-disabled.
            // PR1: returns NO_PRODUCTION_RUN, schedule skips this tick and stays armed.
            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);

            // Lock the contract: the strict policy MUST NOT fall back to either the
            // trusted-set overload or the unfiltered findFirst on miss. A future refactor
            // that did so would silently re-introduce D1.
            verify(runRepository, org.mockito.Mockito.never())
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                    any(), any(), any(java.util.Collection.class));
            verify(runRepository, org.mockito.Mockito.never())
                .findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(any(), any());
            verify(runRepository, org.mockito.Mockito.never())
                .findFirstByWorkflowIdOrderByStartedAtDesc(any());
        }
    }

    // ============================================================
    // Policy: LATEST_TRUSTED (webhook, form, chat, workflow-chain)
    // ============================================================

    @Nested
    @DisplayName("Policy LATEST_TRUSTED (webhook/form/chat)")
    class TrustedPolicyTests {

        @Test
        @DisplayName("COMPLETED run at pinned version → FOUND (trusted set includes COMPLETED)")
        void completedRunIsTrusted() {
            WorkflowRunEntity completedRun = run(RunStatus.COMPLETED);
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(completedRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run()).contains(completedRun);
        }

        @Test
        @DisplayName("Trusted query returns the right status set: " +
                     "{COMPLETED, WAITING_TRIGGER, RUNNING, PAUSED}")
        void trustedStatusSetIsCorrect() {
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(run(RunStatus.COMPLETED)));

            resolver.resolve(WORKFLOW_ID, ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);

            org.mockito.ArgumentCaptor<Collection<RunStatus>> captor =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(runRepository).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(
                RunStatus.COMPLETED, RunStatus.WAITING_TRIGGER, RunStatus.RUNNING, RunStatus.PAUSED);
            // CANCELLED, TIMEOUT, FAILED, PENDING are NOT trusted - D1 fix.
            assertThat(captor.getValue()).doesNotContain(
                RunStatus.CANCELLED, RunStatus.TIMEOUT, RunStatus.FAILED, RunStatus.PENDING);
        }

        @Test
        @DisplayName("No trusted run exists → NO_PRODUCTION_RUN")
        void noTrustedRun() {
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.empty());

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
        }
    }

    // ============================================================
    // Policy: BY_PRODUCTION_RUN_ID (PR1 placeholder - falls back to LATEST_TRUSTED)
    // ============================================================

    @Nested
    @DisplayName("Policy BY_PRODUCTION_RUN_ID (PR4: reads workflow.production_run_id FK)")
    class ByProductionRunIdPolicyTests {

        @Test
        @DisplayName("production_run_id set + run exists → FOUND via O(1) FK lookup (no TRUSTED fallback)")
        void fkFastPath() {
            UUID prodRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(prodRunId);
            WorkflowRunEntity run = run(RunStatus.WAITING_TRIGGER);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(prodRunId)).thenReturn(Optional.of(run));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run()).contains(run);
            // Lock the contract: TRUSTED-set query MUST NOT run when FK is hot.
            verify(runRepository, org.mockito.Mockito.never())
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                    any(), any(), any(Collection.class));
        }

        @Test
        @DisplayName("production_run_id NULL → fall back to LATEST_TRUSTED")
        void fkNullFallback() {
            // Default pinnedWorkflow() has productionRunId == null
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(run(RunStatus.COMPLETED)));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
        }

        @Test
        @DisplayName("production_run_id stale (run deleted) → fall back to LATEST_TRUSTED")
        void fkStaleFallback() {
            UUID prodRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(prodRunId);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(prodRunId)).thenReturn(Optional.empty());
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(run(RunStatus.WAITING_TRIGGER)));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
        }

        @Test
        @DisplayName("FK NULL + no trusted run → NO_PRODUCTION_RUN")
        void noTrustedRun() {
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.empty());

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
        }

        @Test
        @DisplayName("Workflow unpinned → NOT_PINNED (matches LATEST_TRUSTED behaviour)")
        void unpinnedWorkflowReturnsNotPinned() {
            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(ProductionRunResolverTest.this.unpinnedWorkflow()));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NOT_PINNED);
        }
    }

    // ============================================================
    // Showcase-exclusion regression - production triggers MUST NOT
    // resolve to a frozen marketplace clone.
    // ============================================================

    @Nested
    @DisplayName("Showcase clones must never become production runs")
    class ShowcaseExclusionTests {

        /**
         * Bug reproduced from prod (Gmail Auto-Labeler v25): the user pinned a
         * version, but {@code WorkflowPinService.pin()} resolved the production
         * run via the unfiltered query and picked a {@code showcase_*} clone
         * (created by {@code RunCloneService.cloneRun()}). The hourly schedule
         * then fired on the inert clone and the workflow looked silent.
         *
         * The fix: production-run lookups go through
         * {@code findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn}
         * which filters {@code source <> 'showcase'} AND
         * {@code runIdPublic NOT LIKE 'showcase_%'}. Mocking that method already
         * embeds the filter; this test pins the contract that the resolver
         * NEVER calls the unfiltered legacy method.
         */
        @Test
        @DisplayName("LATEST_TRUSTED routes through the showcase-excluding query")
        void latestTrustedUsesShowcaseExcludingQuery() {
            WorkflowRunEntity real = run(RunStatus.COMPLETED);
            real.setRunIdPublic("run-real-1");
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(real));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run()).contains(real);
        }

        @Test
        @DisplayName("LATEST_WAITING_TRIGGER routes through the showcase-excluding query")
        void latestWaitingTriggerUsesShowcaseExcludingQuery() {
            WorkflowRunEntity real = run(RunStatus.WAITING_TRIGGER);
            real.setRunIdPublic("run-real-2");
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), eq(RunStatus.WAITING_TRIGGER)))
                .thenReturn(Optional.of(real));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run()).contains(real);
        }

        /**
         * Self-healing path: a workflow whose {@code production_run_id} was set
         * before the fix may still point at a showcase clone. The resolver must
         * detect that, log it, and fall back to the showcase-excluding lookup.
         */
        @Test
        @DisplayName("BY_PRODUCTION_RUN_ID self-heals when FK points at a showcase clone")
        void fkPointingAtShowcaseFallsBackToTrustedLookup() {
            UUID showcaseRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(showcaseRunId);

            WorkflowRunEntity stale = new WorkflowRunEntity();
            stale.setRunIdPublic("showcase_abcd1234");
            stale.setSource("showcase");
            stale.setStatus(RunStatus.COMPLETED);
            stale.setPlanVersion(PINNED_VERSION);

            WorkflowRunEntity real = run(RunStatus.COMPLETED);
            real.setRunIdPublic("run-real-3");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(showcaseRunId)).thenReturn(Optional.of(stale));
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(real));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run()).contains(real);
            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-real-3");
        }

        @Test
        @DisplayName("BY_PRODUCTION_RUN_ID self-healing detects showcase via runIdPublic prefix even if source field is null")
        void fkPointingAtShowcasePrefixedRunIsDetected() {
            UUID showcaseRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(showcaseRunId);

            // Belt-and-braces: a clone where source was somehow not stamped.
            WorkflowRunEntity stale = new WorkflowRunEntity();
            stale.setRunIdPublic("showcase_xy");
            stale.setSource(null);
            stale.setStatus(RunStatus.COMPLETED);
            stale.setPlanVersion(PINNED_VERSION);

            WorkflowRunEntity real = run(RunStatus.COMPLETED);
            real.setRunIdPublic("run-real-4");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(showcaseRunId)).thenReturn(Optional.of(stale));
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(real));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-real-4");
        }
    }

    // ============================================================
    // resolveStepByStepRun - SBS schedule fallback.
    // Bug: a scheduled workflow in STEP_BY_STEP mode never got a new
    // epoch because the run rests in PAUSED/RUNNING/AWAITING_SIGNAL
    // (reconcileSbsRunStatus), never WAITING_TRIGGER mid-step, so the
    // strict LATEST_WAITING_TRIGGER schedule policy skipped it forever.
    // ============================================================

    @Nested
    @DisplayName("resolveStepByStepRun (SBS schedule fallback)")
    class ResolveStepByStepRunTests {

        private WorkflowRunEntity sbsRun(RunStatus status) {
            WorkflowRunEntity r = run(status);
            r.setExecutionMode(com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.STEP_BY_STEP);
            return r;
        }

        @Test
        @DisplayName("Pinned + SBS run PAUSED at pinned version → returned (the fix: schedulable mid-step)")
        void sbsPausedRunReturned() {
            WorkflowRunEntity sbs = sbsRun(RunStatus.PAUSED);
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(sbs));

            assertThat(resolver.resolveStepByStepRun(WORKFLOW_ID)).contains(sbs);
        }

        @Test
        @DisplayName("AUTOMATIC run PAUSED → empty (a half-done automatic run must NOT be fired by a schedule)")
        void automaticPausedRunFilteredOut() {
            // run(...) leaves executionMode unset → isStepByStepMode() == false.
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(run(RunStatus.PAUSED)));

            assertThat(resolver.resolveStepByStepRun(WORKFLOW_ID)).isEmpty();
        }

        @Test
        @DisplayName("Query asks for non-terminal SBS-active statuses {WAITING_TRIGGER, PAUSED, RUNNING, AWAITING_SIGNAL}, never COMPLETED")
        void queryUsesSbsActiveStatuses() {
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.of(sbsRun(RunStatus.PAUSED)));

            resolver.resolveStepByStepRun(WORKFLOW_ID);

            org.mockito.ArgumentCaptor<Collection<RunStatus>> captor =
                org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(runRepository).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(
                RunStatus.WAITING_TRIGGER, RunStatus.PAUSED, RunStatus.RUNNING, RunStatus.AWAITING_SIGNAL);
            // COMPLETED is intentionally excluded - a finished run must not be re-fired by a schedule.
            assertThat(captor.getValue()).doesNotContain(RunStatus.COMPLETED);
        }

        @Test
        @DisplayName("Unpinned workflow → empty, and the run table is NEVER queried")
        void unpinnedReturnsEmpty() {
            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(ProductionRunResolverTest.this.unpinnedWorkflow()));

            assertThat(resolver.resolveStepByStepRun(WORKFLOW_ID)).isEmpty();
            verify(runRepository, org.mockito.Mockito.never())
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(any(), any(), any(Collection.class));
        }

        @Test
        @DisplayName("Null workflowId → empty (defensive)")
        void nullWorkflowIdReturnsEmpty() {
            assertThat(resolver.resolveStepByStepRun(null)).isEmpty();
        }

        @Test
        @DisplayName("No active run at pinned version → empty")
        void noRunReturnsEmpty() {
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                eq(WORKFLOW_ID), eq(PINNED_VERSION), any(Collection.class)))
                .thenReturn(Optional.empty());

            assertThat(resolver.resolveStepByStepRun(WORKFLOW_ID)).isEmpty();
        }

        @Test
        @DisplayName("Workflow not found → empty, and the run table is NEVER queried (defensive)")
        void workflowMissingReturnsEmpty() {
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            assertThat(resolver.resolveStepByStepRun(WORKFLOW_ID)).isEmpty();
            verify(runRepository, org.mockito.Mockito.never())
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(any(), any(), any(Collection.class));
        }
    }

    // ============================================================
    // Defense-in-depth chokepoint
    // ============================================================

    @Nested
    @DisplayName("isAllowedForProduction (defense-in-depth chokepoint)")
    class ChokepointTests {

        @Test
        @DisplayName("Run plan version matches workflow pin → allowed")
        void matchingVersionAllowed() {
            WorkflowEntity wf = pinnedWorkflow();
            WorkflowRunEntity r = run(RunStatus.WAITING_TRIGGER);

            assertThat(resolver.isAllowedForProduction(r, wf)).isTrue();
        }

        @Test
        @DisplayName("Run plan version differs from workflow pin → refused")
        void mismatchedVersionRefused() {
            WorkflowEntity wf = pinnedWorkflow();
            WorkflowRunEntity r = run(RunStatus.WAITING_TRIGGER);
            r.setPlanVersion(PINNED_VERSION + 1);

            assertThat(resolver.isAllowedForProduction(r, wf)).isFalse();
        }

        @Test
        @DisplayName("Workflow has no pin → refused (no production trigger should reach here)")
        void unpinnedWorkflowRefused() {
            WorkflowEntity wf = unpinnedWorkflow();
            WorkflowRunEntity r = run(RunStatus.WAITING_TRIGGER);

            assertThat(resolver.isAllowedForProduction(r, wf)).isFalse();
        }

        @Test
        @DisplayName("Null run → refused (defensive)")
        void nullRunRefused() {
            assertThat(resolver.isAllowedForProduction(null, pinnedWorkflow())).isFalse();
        }

        @Test
        @DisplayName("Null workflow → refused (defensive)")
        void nullWorkflowRefused() {
            assertThat(resolver.isAllowedForProduction(run(RunStatus.WAITING_TRIGGER), null)).isFalse();
        }
    }
}
