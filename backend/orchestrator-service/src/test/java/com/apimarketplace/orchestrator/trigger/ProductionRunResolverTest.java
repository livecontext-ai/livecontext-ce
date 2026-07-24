package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock
    private WorkflowPinService pinService;

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
        @DisplayName("production_run_id points at a deleted run → TRUSTED scan degradation (pin service ABSENT; Spring contexts heal via rearm)")
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
         * A workflow whose {@code production_run_id} was set before the fix may still
         * point at a showcase clone. The resolver must detect that and never yield the
         * clone. pinService is ABSENT in this nested class, so the corrupt-FK branch
         * degrades to the showcase-excluding scan (unit-construction path); in a real
         * Spring context the same state heals via rearm (showcaseFkHealsViaRearm).
         */
        @Test
        @DisplayName("Showcase-clone FK degrades to the showcase-excluding scan when the pin service is ABSENT")
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

        /**
         * Regression 2026-07-20. Closing the editor-adoption hazard made editor/agent
         * fires MINT a separate run at the pinned version; with a reusable trigger it
         * parks in WAITING_TRIGGER, sharing workflow_id + plan_version + status with
         * production but with a NEWER started_at. The scan orders by started_at DESC,
         * so the schedule would have fired on the editor run - executing its state,
         * including any __mockMode__, while production stopped accumulating epochs.
         * The LATEST_* policies now consult the production_run_id FK first.
         */
        @Test
        @DisplayName("LATEST_WAITING_TRIGGER prefers the production_run_id FK over a newer editor run at the same version")
        void latestWaitingTriggerPrefersFkOverNewerEditorRun() {
            UUID productionRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(productionRunId);

            WorkflowRunEntity productionRun = run(RunStatus.WAITING_TRIGGER);
            productionRun.setRunIdPublic("run-production");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(productionRunId)).thenReturn(Optional.of(productionRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-production");
            // The started_at scan - which the newer editor run would have won - is
            // never reached.
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                any(), any(), any());
        }

        @Test
        @DisplayName("LATEST_TRUSTED prefers the production_run_id FK over a newer editor run at the same version")
        void latestTrustedPrefersFkOverNewerEditorRun() {
            UUID productionRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(productionRunId);

            WorkflowRunEntity productionRun = run(RunStatus.COMPLETED);
            productionRun.setRunIdPublic("run-production-trusted");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(productionRunId)).thenReturn(Optional.of(productionRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-production-trusted");
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                any(), any(), any());
        }

        /**
         * Regression 2026-07-21 (inverts the previous version of this test, which
         * asserted the scan wins here - exactly the hijack shape). When the FK run is
         * VALID (live, version-true) but its status fails the policy, the answer is
         * "production is not fireable this tick", never "hand the tick to whichever
         * same-version run the scan finds" - post-adoption-fix those rivals are
         * editor/agent iteration runs.
         */
        @Test
        @DisplayName("LATEST_WAITING_TRIGGER returns EMPTY when the valid FK run's status fails the policy - the scan is never consulted")
        void latestWaitingTriggerReturnsEmptyWhenFkRunIsNotWaiting() {
            UUID productionRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(productionRunId);

            WorkflowRunEntity runningProduction = run(RunStatus.RUNNING);
            runningProduction.setRunIdPublic("run-production-mid-epoch");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(productionRunId)).thenReturn(Optional.of(runningProduction));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            // The rival-prone scan is never consulted when the FK identity is valid.
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                any(), any(), any());
        }

        @Test
        @DisplayName("STALE-plan_version FK degrades to the version scan when the pin service is ABSENT (unit-construction path; Spring contexts heal via rearm)")
        void latestPoliciesSelfHealOnStaleFkVersion() {
            // pinService is not wired in this nested class, so the corrupt-FK branch
            // takes healCorruptFk's pinService-null degradation: the bare scan. In a
            // real Spring context the bean is always present and the same state heals
            // via rearm (see TerminalFkSelfHealTests + ByProductionRunIdUnificationTests).
            UUID productionRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(productionRunId);

            WorkflowRunEntity staleVersionRun = run(RunStatus.WAITING_TRIGGER);
            staleVersionRun.setRunIdPublic("run-stale-version");
            staleVersionRun.setPlanVersion(PINNED_VERSION - 1);

            WorkflowRunEntity scanned = run(RunStatus.WAITING_TRIGGER);
            scanned.setRunIdPublic("run-scanned-current-version");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(productionRunId)).thenReturn(Optional.of(staleVersionRun));
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                WORKFLOW_ID, PINNED_VERSION, RunStatus.WAITING_TRIGGER))
                .thenReturn(Optional.of(scanned));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-scanned-current-version");
        }

        @Test
        @DisplayName("LATEST_* still uses the scan when no production_run_id FK is set (unpinned bootstrap / rearm cleared it)")
        void latestPoliciesFallBackWhenFkIsNull() {
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(null);

            WorkflowRunEntity scanned = run(RunStatus.WAITING_TRIGGER);
            scanned.setRunIdPublic("run-scanned");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                WORKFLOW_ID, PINNED_VERSION, RunStatus.WAITING_TRIGGER))
                .thenReturn(Optional.of(scanned));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-scanned");
            verify(runRepository, never()).findById(any());
        }

        @Test
        @DisplayName("Showcase detection works via runIdPublic prefix even if source field is null (pin service ABSENT → scan degradation)")
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
    // Terminal-FK self-heal - a FAILED/CANCELLED/TIMEOUT production
    // run behind the FK means the one-shot AFTER_COMMIT rearm was
    // lost (cross-pod failover, swallowed rearm failure). The
    // resolver performs the MISSED rearm instead of dead-ending
    // every production lane forever - and instead of the raw scan,
    // whose winner would fire as production while the FK still
    // pointed at the dead run (so MockRunGate / plan-edit guard /
    // NotificationEmitter would all misclassify it).
    // ============================================================

    @Nested
    @DisplayName("Terminal-FK self-heal (missed rearm)")
    class TerminalFkSelfHealTests {

        private final UUID deadRunId = UUID.randomUUID();
        private final UUID electedRunId = UUID.randomUUID();

        @BeforeEach
        void wirePinService() {
            // @InjectMocks uses constructor injection only; the @Autowired(required=false)
            // pinService field stays null - wire it through the test seam.
            resolver.setPinService(pinService);
        }

        private WorkflowEntity workflowWithFk(UUID fk) {
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(fk);
            return wf;
        }

        @Test
        @DisplayName("FAILED FK run → missed rearm is performed and the HEALED FK answers the policy (scan never consulted)")
        void deadFkTriggersMissedRearmAndResolvesTheHealedFk() {
            WorkflowRunEntity deadRun = run(RunStatus.FAILED);
            deadRun.setRunIdPublic("run-dead-production");
            WorkflowRunEntity electedRun = run(RunStatus.WAITING_TRIGGER);
            electedRun.setRunIdPublic("run-elected-by-rearm");

            // First read sees the dead FK, the re-read after rearm sees the healed FK.
            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.of(workflowWithFk(electedRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));
            when(runRepository.findById(electedRunId)).thenReturn(Optional.of(electedRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-elected-by-rearm");
            verify(pinService).rearm(WORKFLOW_ID);
            // The healed run is served via the FK, never the rival-prone scan.
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                any(), any(), any());
        }

        @Test
        @DisplayName("Rearm clears the FK (no trusted survivor) → bootstrap scan takes over")
        void deadFkRearmClearsFkFallsToBootstrapScan() {
            WorkflowRunEntity deadRun = run(RunStatus.CANCELLED);
            WorkflowRunEntity scanned = run(RunStatus.WAITING_TRIGGER);
            scanned.setRunIdPublic("run-scanned-after-clear");

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.of(workflowWithFk(null)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                WORKFLOW_ID, PINNED_VERSION, RunStatus.WAITING_TRIGGER))
                .thenReturn(Optional.of(scanned));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-scanned-after-clear");
            verify(pinService).rearm(WORKFLOW_ID);
        }

        @Test
        @DisplayName("FK unchanged after rearm (race/failure) → skip this tick, never fire a rival")
        void deadFkUnchangedAfterRearmSkipsTheTick() {
            WorkflowRunEntity deadRun = run(RunStatus.TIMEOUT);

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            verify(pinService).rearm(WORKFLOW_ID);
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                any(), any(), any());
        }

        @Test
        @DisplayName("Rearm throws → skip this tick (empty), scan never consulted")
        void rearmFailureSkipsTheTick() {
            WorkflowRunEntity deadRun = run(RunStatus.FAILED);

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));
            when(pinService.rearm(WORKFLOW_ID)).thenThrow(new RuntimeException("advisory lock timeout"));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                any(), any(), any());
        }

        @Test
        @DisplayName("COMPLETED FK run = deliberate stop → EMPTY, rearm is NEVER attempted")
        void completedFkNeverRearms() {
            WorkflowRunEntity completedProduction = run(RunStatus.COMPLETED);

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(completedProduction));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            verifyNoInteractions(pinService);
        }

        @Test
        @DisplayName("Heal electing a LIVE but policy-ineligible run (RUNNING under the strict schedule policy) → EMPTY, not the scan")
        void healElectingIneligibleLiveRunYieldsEmpty() {
            // The dominant real-world heal outcome post-LIVE-preference: rearm elects
            // a RUNNING run; the strict LATEST_WAITING_TRIGGER filter then refuses it
            // for THIS tick (mid-epoch), and the tick is skipped - never handed to a
            // same-version rival via the scan.
            WorkflowRunEntity deadRun = run(RunStatus.FAILED);
            WorkflowRunEntity electedRunning = run(RunStatus.RUNNING);
            electedRunning.setRunIdPublic("run-elected-running");

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.of(workflowWithFk(electedRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));
            when(runRepository.findById(electedRunId)).thenReturn(Optional.of(electedRunning));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            verify(pinService).rearm(WORKFLOW_ID);
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                any(), any(), any());
        }

        @Test
        @DisplayName("Purged FK row (run deleted) heals via rearm too - not the bare scan")
        void purgedFkHealsViaRearm() {
            WorkflowRunEntity electedRun = run(RunStatus.WAITING_TRIGGER);
            electedRun.setRunIdPublic("run-elected-after-purge");

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.of(workflowWithFk(electedRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.empty());
            when(runRepository.findById(electedRunId)).thenReturn(Optional.of(electedRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-elected-after-purge");
            verify(pinService).rearm(WORKFLOW_ID);
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                any(), any(), any());
        }

        @Test
        @DisplayName("Showcase-clone FK heals via rearm too - the FK gets repaired instead of re-scanning forever")
        void showcaseFkHealsViaRearm() {
            WorkflowRunEntity showcase = new WorkflowRunEntity();
            showcase.setRunIdPublic("showcase_frozen");
            showcase.setSource("showcase");
            showcase.setStatus(RunStatus.WAITING_TRIGGER);
            showcase.setPlanVersion(PINNED_VERSION);
            WorkflowRunEntity electedRun = run(RunStatus.WAITING_TRIGGER);
            electedRun.setRunIdPublic("run-elected-after-showcase");

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.of(workflowWithFk(electedRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(showcase));
            when(runRepository.findById(electedRunId)).thenReturn(Optional.of(electedRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-elected-after-showcase");
            verify(pinService).rearm(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Pin moved while the heal ran (concurrent re-pin) → skip this tick, stale closures never fire")
        void pinMovedMidHealSkipsTheTick() {
            WorkflowRunEntity deadRun = run(RunStatus.FAILED);
            WorkflowEntity repinned = new WorkflowEntity();
            repinned.setId(WORKFLOW_ID);
            repinned.setName("test-wf");
            repinned.setPinnedVersion(PINNED_VERSION + 1);
            repinned.setProductionRunId(electedRunId);

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.of(repinned));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            // This resolve's status filter + scan closures were built for the OLD pin -
            // continuing could fire an old-plan-version run as production.
            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            verify(runRepository, never()).findById(electedRunId);
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                any(), any(), any());
        }

        @Test
        @DisplayName("Workflow row vanished while the heal ran → skip this tick (defensive)")
        void workflowVanishedMidHealSkipsTheTick() {
            WorkflowRunEntity deadRun = run(RunStatus.FAILED);

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.empty());
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            verify(pinService).rearm(WORKFLOW_ID);
        }

        @Test
        @DisplayName("SBS lane: a dead (FAILED) FK heals via rearm and the elected SBS run takes the tick")
        void sbsLaneHealsDeadFkViaRearm() {
            WorkflowRunEntity deadRun = run(RunStatus.FAILED);
            WorkflowRunEntity electedSbs = run(RunStatus.PAUSED);
            electedSbs.setRunIdPublic("run-elected-sbs");
            electedSbs.setExecutionMode(com.apimarketplace.orchestrator.domain.workflow.ExecutionMode.STEP_BY_STEP);

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)))
                .thenReturn(Optional.of(workflowWithFk(electedRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));
            when(runRepository.findById(electedRunId)).thenReturn(Optional.of(electedSbs));

            assertThat(resolver.resolveStepByStepRun(WORKFLOW_ID)).contains(electedSbs);
            verify(pinService).rearm(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Without a wired pin service (partial contexts) a dead FK degrades to the version scan")
        void withoutPinServiceDeadFkFallsBackToScan() {
            ProductionRunResolver bare = new ProductionRunResolver(workflowRepository, runRepository);
            WorkflowRunEntity deadRun = run(RunStatus.FAILED);
            WorkflowRunEntity scanned = run(RunStatus.WAITING_TRIGGER);
            scanned.setRunIdPublic("run-scanned-no-pinservice");

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(workflowWithFk(deadRunId)));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));
            when(runRepository.findFirstProductionRunByWorkflowIdAndPlanVersionAndStatus(
                WORKFLOW_ID, PINNED_VERSION, RunStatus.WAITING_TRIGGER))
                .thenReturn(Optional.of(scanned));

            ProductionRunResolver.Resolution res = bare.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.LATEST_WAITING_TRIGGER);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-scanned-no-pinservice");
        }
    }

    // ============================================================
    // BY_PRODUCTION_RUN_ID unification - one production-identity rule.
    // ============================================================

    @Nested
    @DisplayName("BY_PRODUCTION_RUN_ID routes through the single FK-first rule")
    class ByProductionRunIdUnificationTests {

        @BeforeEach
        void wirePinService() {
            resolver.setPinService(pinService);
        }

        /**
         * Kills the round-3 mutation survivor: neutralising the stale-plan_version
         * check ("else if (false)") would yield the wrong-version FK run directly -
         * this test then fails because the stale run, not the healed election, comes
         * back.
         */
        @Test
        @DisplayName("BY_PRODUCTION_RUN_ID heals a STALE-plan_version FK via the missed rearm")
        void byProductionRunIdSelfHealsOnStaleFkVersion() {
            UUID prodRunId = UUID.randomUUID();
            UUID electedRunId = UUID.randomUUID();
            WorkflowEntity stale = pinnedWorkflow();
            stale.setProductionRunId(prodRunId);
            WorkflowEntity healed = pinnedWorkflow();
            healed.setProductionRunId(electedRunId);

            WorkflowRunEntity staleVersionRun = run(RunStatus.WAITING_TRIGGER);
            staleVersionRun.setRunIdPublic("run-stale-version");
            staleVersionRun.setPlanVersion(PINNED_VERSION - 1);

            WorkflowRunEntity electedRun = run(RunStatus.WAITING_TRIGGER);
            electedRun.setRunIdPublic("run-elected-current-version");

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(healed));
            when(runRepository.findById(prodRunId)).thenReturn(Optional.of(staleVersionRun));
            when(runRepository.findById(electedRunId)).thenReturn(Optional.of(electedRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-elected-current-version");
            verify(pinService).rearm(WORKFLOW_ID);
        }

        @Test
        @DisplayName("BY_PRODUCTION_RUN_ID returns the FK run in ANY live status (callers apply their own eligibility)")
        void byProductionRunIdReturnsFkRunInAnyLiveStatus() {
            UUID prodRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(prodRunId);

            WorkflowRunEntity awaiting = run(RunStatus.AWAITING_SIGNAL);
            awaiting.setRunIdPublic("run-production-awaiting");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(prodRunId)).thenReturn(Optional.of(awaiting));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.outcome()).isEqualTo(ProductionRunResolver.Outcome.FOUND);
            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-production-awaiting");
            verify(runRepository, never()).findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(
                any(), any(), any(Collection.class));
        }

        @Test
        @DisplayName("BY_PRODUCTION_RUN_ID also heals a dead (FAILED) FK through the missed rearm")
        void byProductionRunIdHealsDeadFk() {
            UUID deadRunId = UUID.randomUUID();
            UUID electedRunId = UUID.randomUUID();
            WorkflowEntity stale = pinnedWorkflow();
            stale.setProductionRunId(deadRunId);
            WorkflowEntity healed = pinnedWorkflow();
            healed.setProductionRunId(electedRunId);

            WorkflowRunEntity deadRun = run(RunStatus.FAILED);
            WorkflowRunEntity electedRun = run(RunStatus.COMPLETED);
            electedRun.setRunIdPublic("run-elected");

            when(workflowRepository.findById(WORKFLOW_ID))
                .thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(healed));
            when(runRepository.findById(deadRunId)).thenReturn(Optional.of(deadRun));
            when(runRepository.findById(electedRunId)).thenReturn(Optional.of(electedRun));

            ProductionRunResolver.Resolution res = resolver.resolve(WORKFLOW_ID,
                ProductionRunResolver.RunSelectionPolicy.BY_PRODUCTION_RUN_ID);

            assertThat(res.run().get().getRunIdPublic()).isEqualTo("run-elected");
            verify(pinService).rearm(WORKFLOW_ID);
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

        /**
         * Regression 2026-07-21: ScheduleExecutorService FIRES the run this method
         * returns (fresh epoch, bypassing the terminal-status guard), and the scan
         * orders by started_at DESC - so an editor SBS run minted at the pinned
         * version (adoption is refused since 2026-07-20) would have been EXECUTED as
         * production, mock overrides included. The FK now decides, like the LATEST_*
         * policies.
         */
        @Test
        @DisplayName("Regression 2026-07-21: FK-first - an editor SBS run never takes the schedule tick when the FK run is not SBS")
        void editorSbsRunNeverTakesTheTickWhenProductionIsAutomatic() {
            UUID productionRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(productionRunId);

            // Production is a healthy AUTOMATIC run (isStepByStepMode false).
            WorkflowRunEntity automaticProduction = run(RunStatus.RUNNING);
            automaticProduction.setRunIdPublic("run-production-automatic");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(productionRunId)).thenReturn(Optional.of(automaticProduction));

            // Even though a rival editor SBS run would win the scan, the scan is
            // never consulted: the FK identity is valid, just not SBS.
            assertThat(resolver.resolveStepByStepRun(WORKFLOW_ID)).isEmpty();
            verify(runRepository, org.mockito.Mockito.never())
                .findFirstProductionRunByWorkflowIdAndPlanVersionAndStatusIn(any(), any(), any(Collection.class));
        }

        @Test
        @DisplayName("FK-first - a promoted SBS production run parked mid-debug IS returned via the FK")
        void promotedSbsProductionRunReturnedViaFk() {
            UUID productionRunId = UUID.randomUUID();
            WorkflowEntity wf = pinnedWorkflow();
            wf.setProductionRunId(productionRunId);

            WorkflowRunEntity sbsProduction = sbsRun(RunStatus.PAUSED);
            sbsProduction.setRunIdPublic("run-production-sbs");

            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));
            when(runRepository.findById(productionRunId)).thenReturn(Optional.of(sbsProduction));

            assertThat(resolver.resolveStepByStepRun(WORKFLOW_ID)).contains(sbsProduction);
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
