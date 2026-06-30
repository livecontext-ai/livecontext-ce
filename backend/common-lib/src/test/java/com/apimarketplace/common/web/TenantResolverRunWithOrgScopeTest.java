package com.apimarketplace.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 6 MIGRATION_ORG_ID_NOT_NULL (C-7, 2026-05-19) - pins the
 * {@link TenantResolver#runWithOrgScope(String, Runnable)} ThreadLocal contract.
 *
 * <p>This helper is the only bridge that lets async/daemon paths
 * (Quartz {@code @Scheduled}, raw {@code Executors}, {@code CompletableFuture}
 * workers) thread the producer-thread orgId into the consumer thread so
 * {@link com.apimarketplace.common.scope.OrgScopedEntityListener} can stamp
 * the V261 NOT NULL column. Any regression here silently breaks dozens of
 * sites at runtime, so the contract is pinned with regression tests rather
 * than left to ad-hoc verification.
 */
@DisplayName("TenantResolver.runWithOrgScope - ThreadLocal contract")
class TenantResolverRunWithOrgScopeTest {

    @AfterEach
    void clearThreadLocalBetweenTests() {
        // Defensive: a buggy test that throws *before* runWithOrgScope's
        // finally block runs (impossible today but cheap insurance) could
        // leak the binding to the next test. Force-clear by binding-then-
        // clearing via a no-op task.
        TenantResolver.runWithOrgScope(null, () -> { });
    }

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    @DisplayName("Binds orgId during lambda, clears after (single call)")
    void bindsAndClearsOnHappyPath() {
        assertThat(TenantResolver.currentRequestOrganizationId())
                .as("ThreadLocal must start unbound")
                .isNull();

        AtomicReference<String> seenInside = new AtomicReference<>();
        TenantResolver.runWithOrgScope("org-happy-1", () ->
                seenInside.set(TenantResolver.currentRequestOrganizationId()));

        assertThat(seenInside.get()).isEqualTo("org-happy-1");
        assertThat(TenantResolver.currentRequestOrganizationId())
                .as("ThreadLocal must be cleared after runWithOrgScope returns")
                .isNull();
    }

    // =========================================================================
    // Nesting
    // =========================================================================

    @Test
    @DisplayName("Nested calls: inner overrides, outer is restored after inner completes")
    void nestedCallsRestoreOuterBinding() {
        AtomicReference<String> seenInnermost = new AtomicReference<>();
        AtomicReference<String> seenAfterInner = new AtomicReference<>();

        TenantResolver.runWithOrgScope("org-outer", () -> {
            assertThat(TenantResolver.currentRequestOrganizationId()).isEqualTo("org-outer");

            TenantResolver.runWithOrgScope("org-inner", () ->
                    seenInnermost.set(TenantResolver.currentRequestOrganizationId()));

            // After the inner runWithOrgScope returns, the outer's binding must
            // be back in place - otherwise concurrent dispatch helpers (a
            // daemon calling another daemon-aware service) would silently lose
            // the outer scope and persist with a different org.
            seenAfterInner.set(TenantResolver.currentRequestOrganizationId());
        });

        assertThat(seenInnermost.get()).isEqualTo("org-inner");
        assertThat(seenAfterInner.get()).isEqualTo("org-outer");
        assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
    }

    // =========================================================================
    // Exception propagation + ThreadLocal cleanup
    // =========================================================================

    @Test
    @DisplayName("Exception in lambda: ThreadLocal still cleared, exception propagates unchanged")
    void exceptionInLambdaStillClearsThreadLocal() {
        RuntimeException boom = new RuntimeException("downstream blew up");

        assertThatThrownBy(() ->
                TenantResolver.runWithOrgScope("org-failing", () -> { throw boom; }))
                .isSameAs(boom);

        // CRITICAL: the finally block in runWithOrgScope MUST clear the
        // ThreadLocal so the next task on the same pool worker thread does
        // not inherit a stale "org-failing" binding.
        assertThat(TenantResolver.currentRequestOrganizationId())
                .as("ThreadLocal must be cleared even when lambda throws")
                .isNull();
    }

    @Test
    @DisplayName("Exception in nested inner: outer binding is restored, exception propagates")
    void exceptionInInnerRestoresOuter() {
        RuntimeException boom = new RuntimeException("inner blew up");
        AtomicReference<String> afterInner = new AtomicReference<>();

        TenantResolver.runWithOrgScope("org-outer-2", () -> {
            try {
                TenantResolver.runWithOrgScope("org-inner-bad", () -> { throw boom; });
            } catch (RuntimeException caught) {
                assertThat(caught).isSameAs(boom);
            }
            // Even though the inner throws, the outer binding must be back.
            afterInner.set(TenantResolver.currentRequestOrganizationId());
        });

        assertThat(afterInner.get()).isEqualTo("org-outer-2");
        assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
    }

    // =========================================================================
    // Null / blank orgId - no-scope pass-through
    // =========================================================================

    @Test
    @DisplayName("Null orgId: binding cleared inside lambda (no-op pass-through)")
    void nullOrgIdClearsBinding() {
        AtomicReference<String> seen = new AtomicReference<>();
        TenantResolver.runWithOrgScope(null, () ->
                seen.set(TenantResolver.currentRequestOrganizationId()));

        assertThat(seen.get())
                .as("Null orgId must NOT bind - currentRequestOrganizationId must return null")
                .isNull();
    }

    @Test
    @DisplayName("Blank orgId: binding cleared inside lambda (no-op pass-through)")
    void blankOrgIdClearsBinding() {
        AtomicReference<String> seen = new AtomicReference<>();
        TenantResolver.runWithOrgScope("   ", () ->
                seen.set(TenantResolver.currentRequestOrganizationId()));

        assertThat(seen.get())
                .as("Blank orgId must NOT bind - daemons that have no org context must "
                        + "be treated as unscoped, not as an invalid scope")
                .isNull();
    }

    @Test
    @DisplayName("Previous binding restored on exception when called inside an outer scope")
    void previousBindingRestoredOnExceptionInsideOuter() {
        AtomicReference<String> afterFailedInner = new AtomicReference<>();

        TenantResolver.runWithOrgScope("org-prev", () -> {
            try {
                TenantResolver.runWithOrgScope("org-inner", () -> {
                    throw new IllegalStateException("inner crash");
                });
            } catch (IllegalStateException expected) {
                // swallow
            }
            afterFailedInner.set(TenantResolver.currentRequestOrganizationId());
        });

        assertThat(afterFailedInner.get())
                .as("Outer binding must survive an inner exception verbatim")
                .isEqualTo("org-prev");
        assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
    }

    // =========================================================================
    // currentRequestOrganizationId resolution
    // =========================================================================

    @Test
    @DisplayName("currentRequestOrganizationId returns ThreadLocal value when no RequestContextHolder is bound")
    void currentRequestResolvesThreadLocalWhenNoServletRequest() {
        // Pre-condition: no servlet request bound on this thread (we never
        // call RequestContextHolder.setRequestAttributes in this test).
        // runWithOrgScope binds the ThreadLocal - currentRequestOrganizationId
        // must return that value because it's the only available source.
        AtomicReference<String> seen = new AtomicReference<>();
        TenantResolver.runWithOrgScope("org-thread-local-only", () ->
                seen.set(TenantResolver.currentRequestOrganizationId()));

        assertThat(seen.get()).isEqualTo("org-thread-local-only");
    }
}
