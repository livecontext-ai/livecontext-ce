package com.apimarketplace.orchestrator.services.state.patch;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Plan v4 §3 - RunCoalescingService")
class RunCoalescingServiceTest {

    SimpleMeterRegistry meterRegistry;
    RunCoalescingService serviceEnabled;
    RunCoalescingService serviceDisabled;

    private com.apimarketplace.orchestrator.repository.WorkflowRunRepository mockRepo;
    private JsonbPatchExecutor mockExecutor;

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        serviceEnabled = new RunCoalescingService(meterRegistry, true);
        serviceDisabled = new RunCoalescingService(meterRegistry, false);

        // Wire optional flusher deps via reflection - kept @Autowired(required=false)
        // so existing lifecycle/POISON tests still pass without these.
        mockRepo = org.mockito.Mockito.mock(com.apimarketplace.orchestrator.repository.WorkflowRunRepository.class);
        mockExecutor = org.mockito.Mockito.mock(JsonbPatchExecutor.class);
        setField(serviceEnabled, "runRepository", mockRepo);
        setField(serviceEnabled, "patchExecutor", mockExecutor);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = RunCoalescingService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Nested
    @DisplayName("Feature flag - disabled means coalescer is a pass-through")
    class FeatureFlagDisabled {

        @Test
        @DisplayName("openCoalescing returns null when flag OFF - caller falls through to per-patch CAS")
        void openReturnsNullWhenDisabled() {
            assertThat(serviceDisabled.openCoalescing("run-1")).isNull();
        }

        @Test
        @DisplayName("isCoalescing always false when flag OFF")
        void isCoalescingFalseWhenDisabled() {
            assertThat(serviceDisabled.isCoalescing("run-1")).isFalse();
        }

        @Test
        @DisplayName("openCoalescing returns null when flag OFF → no session, direct CAS path unchanged")
        void noSessionOpenedWhenDisabled() {
            assertThat(serviceDisabled.openCoalescing("run-1")).isNull();
            assertThat(serviceDisabled.isCoalescing("run-1")).isFalse();
            assertThat(serviceDisabled.getCacheSize()).isZero();
        }
    }

    @Nested
    @DisplayName("Session lifecycle - open/close + reference count")
    class SessionLifecycle {

        @Test
        @DisplayName("openCoalescing creates a session and isCoalescing returns true")
        void openCreatesSession() {
            var session = serviceEnabled.openCoalescing("run-1");

            assertThat(session).isNotNull();
            assertThat(session.getRunId()).isEqualTo("run-1");
            assertThat(session.getState()).isEqualTo(RunCoalescingService.SessionState.ACTIVE);
            assertThat(serviceEnabled.isCoalescing("run-1")).isTrue();
        }

        @Test
        @DisplayName("closeCoalescing evicts the session when refcount drops to 0")
        void closeEvictsAtRefcountZero() {
            serviceEnabled.openCoalescing("run-1");
            serviceEnabled.closeCoalescing("run-1");

            assertThat(serviceEnabled.isCoalescing("run-1")).isFalse();
            assertThat(serviceEnabled.activePermitCount()).isZero();
        }

        @Test
        @DisplayName("Two openCoalescing on the same run share a session - second open increments refcount")
        void sharedSessionRefcount() {
            var s1 = serviceEnabled.openCoalescing("run-1");
            var s2 = serviceEnabled.openCoalescing("run-1");

            assertThat(s1).isSameAs(s2);
            assertThat(serviceEnabled.activePermitCount()).isEqualTo(1);  // only 1 semaphore permit consumed

            serviceEnabled.closeCoalescing("run-1");
            assertThat(serviceEnabled.isCoalescing("run-1")).isTrue();  // still ACTIVE - refcount=1

            serviceEnabled.closeCoalescing("run-1");
            assertThat(serviceEnabled.isCoalescing("run-1")).isFalse();
            assertThat(serviceEnabled.activePermitCount()).isZero();
        }

        @Test
        @DisplayName("closeCoalescing is idempotent - closing a non-existent run is a no-op")
        void closeIsIdempotent() {
            serviceEnabled.closeCoalescing("never-opened");  // no-op, no throw
            serviceEnabled.openCoalescing("run-1");
            serviceEnabled.closeCoalescing("run-1");
            serviceEnabled.closeCoalescing("run-1");  // second close - no-op
            assertThat(serviceEnabled.isCoalescing("run-1")).isFalse();
        }
    }

    @Nested
    @DisplayName("Capacity cap - 50-session semaphore")
    class CapacityCap {

        @Test
        @DisplayName("Beyond 50 distinct runs, openCoalescing returns null + increments capacity_reject_count")
        void capacityRejectAt50() {
            for (int i = 0; i < 50; i++) {
                var s = serviceEnabled.openCoalescing("run-" + i);
                assertThat(s).as("run-%d should have a session", i).isNotNull();
            }
            var rejected = serviceEnabled.openCoalescing("run-overflow");
            assertThat(rejected).isNull();
            assertThat(meterRegistry.counter("orchestrator.coalesce.capacity_reject_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Releasing a session frees a permit - next open succeeds")
        void permitReleasedOnClose() {
            for (int i = 0; i < 50; i++) {
                serviceEnabled.openCoalescing("run-" + i);
            }
            serviceEnabled.closeCoalescing("run-0");
            var freed = serviceEnabled.openCoalescing("run-overflow");
            assertThat(freed).isNotNull();
        }

        @Test
        @DisplayName("Audit M1 fix - reaper-then-late-close does NOT double-release. "
                + "Permit accounting stays balanced even when eviction paths race.")
        void reaperThenLateCloseDoesNotLeakOrDoubleRelease() {
            // Open 50 sessions to consume all permits.
            for (int i = 0; i < 50; i++) {
                serviceEnabled.openCoalescing("run-" + i);
            }
            assertThat(serviceEnabled.activePermitCount()).isEqualTo(50);

            // Simulate reaper firing on session 0 (we manually invoke cleanup
            // which uses the same releasePermitOnce path; reaper is harder to
            // unit-test deterministically because it depends on @Scheduled).
            serviceEnabled.cleanupRun("run-0");
            assertThat(serviceEnabled.activePermitCount()).isEqualTo(49);

            // Late close on the same session - caller didn't know reaper
            // already evicted. With the M1 fix, this is a no-op on permits.
            serviceEnabled.closeCoalescing("run-0");
            assertThat(serviceEnabled.activePermitCount())
                    .as("permit accounting unchanged by late closeCoalescing - no double-release")
                    .isEqualTo(49);

            // Permit is freed, so we can open one more session.
            var freed = serviceEnabled.openCoalescing("run-overflow");
            assertThat(freed).isNotNull();
            assertThat(serviceEnabled.activePermitCount()).isEqualTo(50);

            // Another late close - still no double-release.
            serviceEnabled.closeCoalescing("run-0");
            assertThat(serviceEnabled.activePermitCount()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("POISON state - audit C M2")
    class PoisonState {

        @Test
        @DisplayName("poison marks state POISONED and increments counter")
        void poisonMarksState() {
            var session = serviceEnabled.openCoalescing("run-1");

            serviceEnabled.poison("run-1", new RuntimeException("CAS conflict final"));

            assertThat(session.getState()).isEqualTo(RunCoalescingService.SessionState.POISONED);
            assertThat(meterRegistry.counter("orchestrator.coalesce.session_poisoned_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("isCoalescing returns false for POISONED sessions - callers fall through to per-patch CAS")
        void isCoalescingFalseWhenPoisoned() {
            serviceEnabled.openCoalescing("run-1");
            serviceEnabled.poison("run-1", new RuntimeException("boom"));

            assertThat(serviceEnabled.isCoalescing("run-1")).isFalse();
        }

        @Test
        @DisplayName("poison is idempotent - second call does not double-increment counter")
        void poisonIsIdempotent() {
            serviceEnabled.openCoalescing("run-1");
            serviceEnabled.poison("run-1", new RuntimeException("first"));
            serviceEnabled.poison("run-1", new RuntimeException("second"));

            assertThat(meterRegistry.counter("orchestrator.coalesce.session_poisoned_count").count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Collision detection - DELTA+DELTA merge, DELTA+ASSIGN force-flush")
    class CollisionDetection {

        @Test
        @DisplayName("DELTA+DELTA same-path → MERGE")
        void deltaDeltaSamePathMerges() {
            var future = new CompletableFuture<Void>();
            var existing = new RunCoalescingService.EnqueuedPatch(
                    new JsonbPatch(new String[]{"nodes", "foo", "completed"}, "1"),
                    PatchClass.OpKind.COMMUTATIVE_DELTA, future);
            var newPatch = new JsonbPatch(new String[]{"nodes", "foo", "completed"}, "1");

            var decision = serviceEnabled.detectCollision(List.of(existing), newPatch,
                    PatchClass.OpKind.COMMUTATIVE_DELTA);

            assertThat(decision).isEqualTo(RunCoalescingService.CollisionDecision.MERGE);
            assertThat(meterRegistry.counter("orchestrator.coalesce.collision_merge_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("DELTA+ASSIGN same-path → FORCE_FLUSH (jsonb_set replace-not-merge)")
        void deltaAssignSamePathForceFlush() {
            var future = new CompletableFuture<Void>();
            var existing = new RunCoalescingService.EnqueuedPatch(
                    new JsonbPatch(new String[]{"nodes", "foo", "completed"}, "1"),
                    PatchClass.OpKind.COMMUTATIVE_DELTA, future);
            var newPatch = new JsonbPatch(new String[]{"nodes", "foo", "completed"}, "\"timestamp\"");

            var decision = serviceEnabled.detectCollision(List.of(existing), newPatch,
                    PatchClass.OpKind.ASSIGN);

            assertThat(decision).isEqualTo(RunCoalescingService.CollisionDecision.FORCE_FLUSH);
            assertThat(meterRegistry.counter("orchestrator.coalesce.collision_force_flush_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("ASSIGN+ASSIGN same-path → FORCE_FLUSH")
        void assignAssignSamePathForceFlush() {
            var future = new CompletableFuture<Void>();
            var existing = new RunCoalescingService.EnqueuedPatch(
                    new JsonbPatch(new String[]{"nodes", "foo", "status"}, "\"running\""),
                    PatchClass.OpKind.ASSIGN, future);
            var newPatch = new JsonbPatch(new String[]{"nodes", "foo", "status"}, "\"completed\"");

            var decision = serviceEnabled.detectCollision(List.of(existing), newPatch,
                    PatchClass.OpKind.ASSIGN);

            assertThat(decision).isEqualTo(RunCoalescingService.CollisionDecision.FORCE_FLUSH);
        }

        @Test
        @DisplayName("Different path → APPEND (no collision)")
        void differentPathAppends() {
            var future = new CompletableFuture<Void>();
            var existing = new RunCoalescingService.EnqueuedPatch(
                    new JsonbPatch(new String[]{"nodes", "foo", "completed"}, "1"),
                    PatchClass.OpKind.COMMUTATIVE_DELTA, future);
            var newPatch = new JsonbPatch(new String[]{"nodes", "bar", "completed"}, "1");

            var decision = serviceEnabled.detectCollision(List.of(existing), newPatch,
                    PatchClass.OpKind.COMMUTATIVE_DELTA);

            assertThat(decision).isEqualTo(RunCoalescingService.CollisionDecision.APPEND);
            assertThat(meterRegistry.counter("orchestrator.coalesce.collision_merge_count").count())
                    .isZero();
            assertThat(meterRegistry.counter("orchestrator.coalesce.collision_force_flush_count").count())
                    .isZero();
        }

        @Test
        @DisplayName("Empty queue → APPEND")
        void emptyQueueAppends() {
            var newPatch = new JsonbPatch(new String[]{"nodes", "foo", "completed"}, "1");

            var decision = serviceEnabled.detectCollision(List.of(), newPatch,
                    PatchClass.OpKind.COMMUTATIVE_DELTA);

            assertThat(decision).isEqualTo(RunCoalescingService.CollisionDecision.APPEND);
        }
    }

    @Nested
    @DisplayName("awaitFlush - ManagedBlocker wraps future.join")
    class AwaitFlush {

        @Test
        @DisplayName("Completed future returns true immediately")
        void completedFutureReturnsTrue() {
            var future = CompletableFuture.<Void>completedFuture(null);

            boolean ok = serviceEnabled.awaitFlush(future);

            assertThat(ok).isTrue();
            assertThat(meterRegistry.counter("orchestrator.coalesce.managed_block_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Exceptionally-completed future returns true (caller routes via POISON)")
        void exceptionallyCompletedReturnsTrue() {
            var future = CompletableFuture.<Void>failedFuture(new RuntimeException("flush rollback"));

            boolean ok = serviceEnabled.awaitFlush(future);

            assertThat(ok).isTrue();  // block() completed; the failure is observable via future.get inside the blocker
        }
    }

    @Nested
    @DisplayName("Plan v4 §3 phase 2c - flusher implementation (enqueuePatch + flush via applyPatchesCas)")
    class FlusherTests {

        @Test
        @DisplayName("enqueuePatch without active session → future completes exceptionally")
        void enqueueWithoutSessionFailsFast() {
            JsonbPatch patch = new JsonbPatch(new String[]{"seq"}, "1");

            CompletableFuture<Void> f = serviceEnabled.enqueuePatch("run-none", patch,
                    PatchClass.OpKind.ASSIGN);

            assertThat(f).isCompletedExceptionally();
        }

        @Test
        @DisplayName("enqueuePatch with feature flag OFF → future completes exceptionally")
        void enqueueDisabledFailsFast() {
            JsonbPatch patch = new JsonbPatch(new String[]{"seq"}, "1");

            CompletableFuture<Void> f = serviceDisabled.enqueuePatch("run-1", patch,
                    PatchClass.OpKind.ASSIGN);

            assertThat(f).isCompletedExceptionally();
        }

        @Test
        @DisplayName("Single patch enqueue + closeCoalescing → flushes via applyPatchesCas + future completes normally")
        void singlePatchFlushOnClose() {
            org.mockito.Mockito.when(mockRepo.findStateSnapshotSeqByRunIdPublic("run-1"))
                    .thenReturn(java.util.Optional.of(10L));
            org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                    org.mockito.ArgumentMatchers.eq("run-1"),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.eq(10L),
                    org.mockito.ArgumentMatchers.eq(11L))).thenReturn(1);

            serviceEnabled.openCoalescing("run-1");
            CompletableFuture<Void> f = serviceEnabled.enqueuePatch("run-1",
                    new JsonbPatch(new String[]{"seq"}, "11"), PatchClass.OpKind.ASSIGN);

            // closeCoalescing triggers flush since refcount→0 with patches queued.
            serviceEnabled.closeCoalescing("run-1");

            assertThat(f).isCompletedWithValue(null);
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_batch_size").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("32-patch cap → automatic flush before reaching close")
        void batchSizeCapTriggersFlush() {
            org.mockito.Mockito.when(mockRepo.findStateSnapshotSeqByRunIdPublic("run-1"))
                    .thenReturn(java.util.Optional.of(0L));
            org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                    org.mockito.ArgumentMatchers.eq("run-1"),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.eq(0L),
                    org.mockito.ArgumentMatchers.eq(1L))).thenReturn(1);

            serviceEnabled.openCoalescing("run-1");
            for (int i = 0; i < RunCoalescingService.MAX_PATCHES_PER_BATCH; i++) {
                serviceEnabled.enqueuePatch("run-1",
                        new JsonbPatch(new String[]{"nodes", "n" + i, "completed"}, "1"),
                        PatchClass.OpKind.ASSIGN);
            }

            // Flush happens INSIDE the 32nd enqueue - before closeCoalescing.
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_batch_size").count())
                    .isEqualTo(RunCoalescingService.MAX_PATCHES_PER_BATCH);
            serviceEnabled.closeCoalescing("run-1");  // no second flush - queue empty
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("CAS conflict (rowCount=0) on all 3 attempts → POISON + retry_exhausted + futures fail")
        void casConflictExhaustsBudgetPoisons() {
            org.mockito.Mockito.when(mockRepo.findStateSnapshotSeqByRunIdPublic("run-1"))
                    .thenReturn(java.util.Optional.of(10L));
            // Always return 0 → CAS conflict on every attempt
            org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(0);

            var session = serviceEnabled.openCoalescing("run-1");
            CompletableFuture<Void> f = serviceEnabled.enqueuePatch("run-1",
                    new JsonbPatch(new String[]{"seq"}, "11"), PatchClass.OpKind.ASSIGN);
            serviceEnabled.closeCoalescing("run-1");

            assertThat(f).isCompletedExceptionally();
            assertThat(meterRegistry.counter("orchestrator.coalesce.cas_conflict_count").count())
                    .isEqualTo(RunCoalescingService.CAS_RETRY_BACKOFF_MS.length);
            assertThat(meterRegistry.counter("orchestrator.coalesce.cas_retry_exhausted_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.coalesce.session_poisoned_count").count())
                    .isEqualTo(1.0);
            assertThat(session.getState()).isEqualTo(RunCoalescingService.SessionState.POISONED);
        }

        @Test
        @DisplayName("Repo throws (DB-down / trigger violation) → no retry, POISON immediately")
        void runtimeExceptionFailsFast() {
            org.mockito.Mockito.when(mockRepo.findStateSnapshotSeqByRunIdPublic("run-1"))
                    .thenReturn(java.util.Optional.of(0L));
            org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong()))
                    .thenThrow(new RuntimeException("V181 trigger fired"));

            serviceEnabled.openCoalescing("run-1");
            CompletableFuture<Void> f = serviceEnabled.enqueuePatch("run-1",
                    new JsonbPatch(new String[]{"seq"}, "1"), PatchClass.OpKind.ASSIGN);
            serviceEnabled.closeCoalescing("run-1");

            assertThat(f).isCompletedExceptionally();
            // Only 1 CAS attempt - trigger violation is fatal, no retry
            org.mockito.Mockito.verify(mockExecutor, org.mockito.Mockito.times(1))
                    .applyPatchesCas(org.mockito.ArgumentMatchers.anyString(),
                            org.mockito.ArgumentMatchers.anyList(),
                            org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyLong());
            assertThat(meterRegistry.counter("orchestrator.coalesce.cas_retry_exhausted_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("ASSIGN+ASSIGN same-path collision → force-flush old before enqueue new")
        void samePathAssignForceFlush() {
            org.mockito.Mockito.when(mockRepo.findStateSnapshotSeqByRunIdPublic("run-1"))
                    .thenReturn(java.util.Optional.of(0L)).thenReturn(java.util.Optional.of(1L));
            org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.anyLong(),
                    org.mockito.ArgumentMatchers.anyLong())).thenReturn(1);

            serviceEnabled.openCoalescing("run-1");
            String[] samePath = {"nodes", "foo", "status"};
            CompletableFuture<Void> f1 = serviceEnabled.enqueuePatch("run-1",
                    new JsonbPatch(samePath, "\"running\""), PatchClass.OpKind.ASSIGN);
            // Second same-path patch → force-flush first batch before adding
            CompletableFuture<Void> f2 = serviceEnabled.enqueuePatch("run-1",
                    new JsonbPatch(samePath, "\"completed\""), PatchClass.OpKind.ASSIGN);
            serviceEnabled.closeCoalescing("run-1");

            assertThat(f1).isCompletedWithValue(null);
            assertThat(f2).isCompletedWithValue(null);
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count())
                    .isEqualTo(2.0);
            assertThat(meterRegistry.counter("orchestrator.coalesce.collision_force_flush_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Plan §2b - 3 same-path DELTA enqueues merge into single +6 patch; all 3 futures complete on flush")
        void deltaMergeAggregatesIntoSinglePatch() {
            org.mockito.Mockito.when(mockRepo.findStateSnapshotSeqByRunIdPublic("run-1"))
                    .thenReturn(java.util.Optional.of(0L));
            org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                    org.mockito.ArgumentMatchers.eq("run-1"),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.eq(0L),
                    org.mockito.ArgumentMatchers.eq(1L))).thenReturn(1);

            serviceEnabled.openCoalescing("run-1");
            String[] path = {"nodes", "X", "completed"};
            CompletableFuture<Void> f1 = serviceEnabled.enqueuePatch("run-1",
                    JsonbPatch.commutativeDelta(path, 1), PatchClass.OpKind.COMMUTATIVE_DELTA);
            CompletableFuture<Void> f2 = serviceEnabled.enqueuePatch("run-1",
                    JsonbPatch.commutativeDelta(path, 2), PatchClass.OpKind.COMMUTATIVE_DELTA);
            CompletableFuture<Void> f3 = serviceEnabled.enqueuePatch("run-1",
                    JsonbPatch.commutativeDelta(path, 3), PatchClass.OpKind.COMMUTATIVE_DELTA);
            serviceEnabled.closeCoalescing("run-1");

            // All 3 futures complete normally on the single flush
            assertThat(f1).isCompletedWithValue(null);
            assertThat(f2).isCompletedWithValue(null);
            assertThat(f3).isCompletedWithValue(null);

            // 2 merges (f2 into f1's slot, f3 into the merged slot) + 1 flush
            assertThat(meterRegistry.counter("orchestrator.coalesce.collision_merge_count").count())
                    .isEqualTo(2.0);
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_batch_size").count())
                    .as("merged patch counts as 1, not 3").isEqualTo(1.0);

            // Verify the merged patch jsonValue was +6 (1+2+3) - captured via the
            // ArgumentCaptor on applyPatchesCas patches arg.
            @SuppressWarnings("unchecked")
            org.mockito.ArgumentCaptor<java.util.List<JsonbPatch>> captor =
                    org.mockito.ArgumentCaptor.forClass(java.util.List.class);
            org.mockito.Mockito.verify(mockExecutor).applyPatchesCas(
                    org.mockito.ArgumentMatchers.eq("run-1"), captor.capture(),
                    org.mockito.ArgumentMatchers.eq(0L),
                    org.mockito.ArgumentMatchers.eq(1L));
            assertThat(captor.getValue()).hasSize(1);
            JsonbPatch merged = captor.getValue().get(0);
            assertThat(merged.opKind()).isEqualTo(JsonbPatch.OpKind.COMMUTATIVE_DELTA);
            assertThat(merged.jsonValue()).isEqualTo("6");
        }

        @Test
        @DisplayName("Audit S4 - CAS conflict on attempt 1 then success on attempt 2: retry loop commits, future completes normally")
        void casConflictRetrySuccess() {
            org.mockito.Mockito.when(mockRepo.findStateSnapshotSeqByRunIdPublic("run-1"))
                    .thenReturn(java.util.Optional.of(10L))
                    .thenReturn(java.util.Optional.of(11L));  // peer bumped between attempts
            org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                    org.mockito.ArgumentMatchers.eq("run-1"),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.eq(10L),
                    org.mockito.ArgumentMatchers.eq(11L))).thenReturn(0);   // conflict
            org.mockito.Mockito.when(mockExecutor.applyPatchesCas(
                    org.mockito.ArgumentMatchers.eq("run-1"),
                    org.mockito.ArgumentMatchers.anyList(),
                    org.mockito.ArgumentMatchers.eq(11L),
                    org.mockito.ArgumentMatchers.eq(12L))).thenReturn(1);   // success

            serviceEnabled.openCoalescing("run-1");
            CompletableFuture<Void> f = serviceEnabled.enqueuePatch("run-1",
                    new JsonbPatch(new String[]{"seq"}, "12"), PatchClass.OpKind.ASSIGN);
            serviceEnabled.closeCoalescing("run-1");

            assertThat(f).isCompletedWithValue(null);
            assertThat(meterRegistry.counter("orchestrator.coalesce.cas_conflict_count").count())
                    .as("1 retry consumed before success").isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.coalesce.flush_ok_count").count())
                    .as("eventual flush committed").isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.coalesce.cas_retry_exhausted_count").count())
                    .as("budget not exhausted").isZero();
        }

        @Test
        @DisplayName("Wiring incomplete (no executor) → POISON + future fails - no NPE")
        void wiringIncompletePoisons() throws Exception {
            RunCoalescingService unwired = new RunCoalescingService(meterRegistry, true);
            // Skip wiring patchExecutor + runRepository
            unwired.openCoalescing("run-1");
            CompletableFuture<Void> f = unwired.enqueuePatch("run-1",
                    new JsonbPatch(new String[]{"seq"}, "1"), PatchClass.OpKind.ASSIGN);
            unwired.closeCoalescing("run-1");

            assertThat(f).isCompletedExceptionally();
            assertThat(meterRegistry.counter("orchestrator.coalesce.session_poisoned_count").count())
                    .isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("RunScopedCache contract - RunCacheRegistry auto-wire")
    class RunScopedCache {

        @Test
        @DisplayName("cleanupRun removes the session and releases the semaphore permit")
        void cleanupRunReleasesPermit() {
            serviceEnabled.openCoalescing("run-1");
            assertThat(serviceEnabled.activePermitCount()).isEqualTo(1);

            serviceEnabled.cleanupRun("run-1");

            assertThat(serviceEnabled.activePermitCount()).isZero();
            assertThat(serviceEnabled.isCoalescing("run-1")).isFalse();
        }

        @Test
        @DisplayName("Metadata accessors")
        void metadataAccessors() {
            assertThat(serviceEnabled.getCacheName()).isEqualTo("RunCoalescingService.sessions");
            assertThat(serviceEnabled.getDomain())
                    .isEqualTo(com.apimarketplace.orchestrator.services.cache.RunScopedCache.CacheDomain.PERSISTENCE);
            assertThat(serviceEnabled.getCacheSize()).isZero();

            serviceEnabled.openCoalescing("run-1");
            assertThat(serviceEnabled.getCacheSize()).isEqualTo(1);
        }
    }
}
