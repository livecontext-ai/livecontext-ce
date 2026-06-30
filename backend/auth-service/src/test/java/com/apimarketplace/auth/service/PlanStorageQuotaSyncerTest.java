package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.common.storage.service.QuotaService;
import com.apimarketplace.storage.client.StorageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@link PlanStorageQuotaSyncer} - the single
 * choke-point that enforces "subscription.plan.included_storage_bytes ⇒
 * tenant_storage_quota.max_bytes". This class is the V198 bug-class
 * guard: every caller now routes through here, so any contract drift
 * lands in a test failure rather than a silent regression across four
 * separate write sites.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlanStorageQuotaSyncer Tests")
class PlanStorageQuotaSyncerTest {

    @Mock private QuotaService quotaService;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private StorageClient storageClient;

    private Organization orgWithId(UUID id) {
        Organization o = new Organization();
        o.setId(id);
        return o;
    }

    @AfterEach
    void clearTxScope() {
        // Defensive: a failing test could leave the synchronization scope
        // open and pollute subsequent tests. Always clear.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Plan plan(String code, Long includedStorageBytes) {
        Plan p = new Plan(code, code + " Plan", "desc");
        p.setIncludedStorageBytes(includedStorageBytes);
        return p;
    }

    @Nested
    @DisplayName("syncAfterCommit() - happy path")
    class HappyPath {

        @Test
        @DisplayName("non-tx context: runs inline (tenant_id is userId.toString(), softLimitRatio is 0.8)")
        void runsInlineWhenNoTx() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            syncer.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L));

            verify(quotaService).updateLimits("42", 1_073_741_824L, 0.8);
        }

        @Test
        @DisplayName("active tx scope: defers to afterCommit (not fired synchronously)")
        void defersWhenTxActive() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            TransactionSynchronizationManager.initSynchronization();

            syncer.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L));

            // Inside the active tx scope, the sync must NOT have run yet.
            verify(quotaService, never()).updateLimits(any(), anyLong(), anyDouble());

            // Trigger the afterCommit callbacks - what the real tx infra does on commit.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            // NOW the sync must have run.
            verify(quotaService).updateLimits("42", 1_073_741_824L, 0.8);
        }
    }

    @Nested
    @DisplayName("syncAfterCommit() - defensive guards")
    class Guards {

        @Test
        @DisplayName("null userId: skip silently")
        void nullUserIdSkips() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            syncer.syncAfterCommit(null, plan("STARTER", 1_073_741_824L));

            verifyNoInteractions(quotaService);
        }

        @Test
        @DisplayName("null plan: skip silently")
        void nullPlanSkips() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            syncer.syncAfterCommit(42L, null);

            verifyNoInteractions(quotaService);
        }

        @Test
        @DisplayName("null included_storage_bytes: skip silently (plan declines to opine)")
        void nullStorageBytesSkips() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            syncer.syncAfterCommit(42L, plan("CREDIT_PACK", null));

            verifyNoInteractions(quotaService);
        }

        @Test
        @DisplayName("zero included_storage_bytes: skip silently (CREDIT_PACK seed)")
        void zeroStorageBytesSkips() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            syncer.syncAfterCommit(42L, plan("CREDIT_PACK", 0L));

            verifyNoInteractions(quotaService);
        }

        @Test
        @DisplayName("negative included_storage_bytes: skip silently")
        void negativeStorageBytesSkips() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            syncer.syncAfterCommit(42L, plan("BROKEN", -1L));

            verifyNoInteractions(quotaService);
        }

        @Test
        @DisplayName("missing QuotaService bean (CE / module absent): syncer is a no-op")
        void missingBeanIsNoOp() {
            // Construct with null QuotaService - simulates the CE / monolith-without-storage profile.
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(null, organizationRepository);

            // Must not throw.
            assertThatCode(() -> syncer.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("guards short-circuit BEFORE afterCommit registration (no zombie sync queued)")
        void guardsRunBeforeTxRegistration() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            TransactionSynchronizationManager.initSynchronization();

            syncer.syncAfterCommit(42L, plan("CREDIT_PACK", null));

            // No synchronization should have been registered - otherwise commit
            // would fire a no-op task and the guard becomes load-bearing post-commit.
            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit))
                    .doesNotThrowAnyException();
            verifyNoInteractions(quotaService);
        }
    }

    @Nested
    @DisplayName("syncAfterCommit() - failure handling")
    class Failures {

        @Test
        @DisplayName("QuotaService.updateLimits throws → swallowed (best-effort, V198 is the safety net)")
        void quotaServiceFailureIsBestEffort() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            doThrow(new RuntimeException("storage backend down"))
                    .when(quotaService).updateLimits(any(), anyLong(), anyDouble());

            // The caller's tx must not be poisoned - sync failures cannot bubble.
            assertThatCode(() -> syncer.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L)))
                    .doesNotThrowAnyException();

            verify(quotaService).updateLimits("42", 1_073_741_824L, 0.8);
        }

        @Test
        @DisplayName("afterCommit failure does not propagate (tx already committed)")
        void afterCommitFailureDoesNotPropagate() {
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            doThrow(new RuntimeException("redis cache unavailable"))
                    .when(quotaService).updateLimits(any(), anyLong(), anyDouble());

            TransactionSynchronizationManager.initSynchronization();
            syncer.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L));

            // Manually trigger afterCommit - the failure inside must not escape.
            assertThatCode(() -> TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit))
                    .doesNotThrowAnyException();

            verify(quotaService).updateLimits("42", 1_073_741_824L, 0.8);
        }
    }

    @Nested
    @DisplayName("Org-quota sync (2026-05-14 fix)")
    class OrgQuotaSync {

        /** Helper: build an Organization stub with the given id. */
        private Organization org(UUID id) {
            Organization o = new Organization();
            o.setId(id);
            return o;
        }

        @Test
        @DisplayName("syncs each org owned by user via updateOrganizationLimits (personal + team)")
        void syncsEveryOwnedOrg() {
            UUID personalOrgId = UUID.randomUUID();
            UUID teamOrgId = UUID.randomUUID();
            when(organizationRepository.findByOwnerId(42L))
                    .thenReturn(List.of(org(personalOrgId), org(teamOrgId)));

            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            syncer.syncAfterCommit(42L, plan("TEAM", 107_374_182_400L));

            // Tenant sync (legacy path)
            verify(quotaService).updateLimits("42", 107_374_182_400L, 0.8);
            // Org sync (new path): one call per owned org
            verify(quotaService).updateOrganizationLimits(personalOrgId.toString(), 107_374_182_400L, 0.8);
            verify(quotaService).updateOrganizationLimits(teamOrgId.toString(), 107_374_182_400L, 0.8);
        }

        @Test
        @DisplayName("no orgs owned: still calls tenant sync, no org calls")
        void noOrgsOwned() {
            when(organizationRepository.findByOwnerId(42L)).thenReturn(Collections.emptyList());

            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            syncer.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L));

            verify(quotaService).updateLimits("42", 1_073_741_824L, 0.8);
            verify(quotaService, never()).updateOrganizationLimits(anyString(), anyLong(), anyDouble());
        }

        @Test
        @DisplayName("org repo throws → tenant sync still succeeded (isolated failure)")
        void orgRepoFailureIsolatedFromTenantSync() {
            when(organizationRepository.findByOwnerId(42L))
                    .thenThrow(new RuntimeException("DB down"));

            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            assertThatCode(() -> syncer.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L)))
                    .doesNotThrowAnyException();

            verify(quotaService).updateLimits("42", 1_073_741_824L, 0.8);
            verify(quotaService, never()).updateOrganizationLimits(anyString(), anyLong(), anyDouble());
        }

        @Test
        @DisplayName("updateOrganizationLimits throws → swallowed (best-effort, mirrors tenant path)")
        void orgUpdateFailureIsBestEffort() {
            UUID orgId = UUID.randomUUID();
            when(organizationRepository.findByOwnerId(42L)).thenReturn(List.of(org(orgId)));
            doThrow(new RuntimeException("storage down"))
                    .when(quotaService).updateOrganizationLimits(eq(orgId.toString()), anyLong(), anyDouble());

            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            assertThatCode(() -> syncer.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L)))
                    .doesNotThrowAnyException();

            // Tighten audit nit: ensure the call WAS attempted (a NO-OP impl would silently pass otherwise).
            verify(quotaService).updateOrganizationLimits(eq(orgId.toString()), eq(1_073_741_824L), eq(0.8));
        }

        @Test
        @DisplayName("audit nit: one org throws → REMAINING orgs still synced (per-iteration isolation)")
        void orgUpdateFailureContinuesWithRemaining() {
            UUID firstOrgId = UUID.randomUUID();
            UUID secondOrgId = UUID.randomUUID();
            when(organizationRepository.findByOwnerId(42L))
                    .thenReturn(List.of(org(firstOrgId), org(secondOrgId)));
            // First org throws; second must STILL be attempted.
            doThrow(new RuntimeException("row-level lock contention on first org"))
                    .when(quotaService).updateOrganizationLimits(eq(firstOrgId.toString()), anyLong(), anyDouble());

            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);

            assertThatCode(() -> syncer.syncAfterCommit(42L, plan("TEAM", 107_374_182_400L)))
                    .doesNotThrowAnyException();

            // Tenant sync untouched.
            verify(quotaService).updateLimits("42", 107_374_182_400L, 0.8);
            // First org WAS attempted (and threw).
            verify(quotaService).updateOrganizationLimits(eq(firstOrgId.toString()), eq(107_374_182_400L), eq(0.8));
            // CRITICAL: second org MUST have been synced despite first failure.
            verify(quotaService).updateOrganizationLimits(eq(secondOrgId.toString()), eq(107_374_182_400L), eq(0.8));
        }
    }

    @Nested
    @DisplayName("Plan-entity decoupling (afterCommit safety)")
    class EntityDecoupling {

        @Test
        @DisplayName("captures primitive maxBytes inside active session - afterCommit doesn't re-read the entity")
        void doesNotReReadPlanEntityAfterCommit() {
            // The Runnable captured by registerSynchronization must NOT depend on
            // the Plan entity post-commit (session is closed → LazyInitializationException).
            // We simulate this by mutating the plan AFTER the syncAfterCommit call but
            // BEFORE the afterCommit fires: the captured primitive should win.
            PlanStorageQuotaSyncer syncer = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            Plan p = plan("STARTER", 1_073_741_824L);

            TransactionSynchronizationManager.initSynchronization();
            syncer.syncAfterCommit(42L, p);

            // Mutate the entity after registration - emulates a stale-entity / detached-state read.
            p.setIncludedStorageBytes(999_999_999_999L);
            p.setCode("MUTATED");

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);

            // Original snapshot wins (1 GB / STARTER), not the post-mutation 999 GB / MUTATED.
            verify(quotaService).updateLimits("42", 1_073_741_824L, 0.8);
        }
    }

    @Nested
    @DisplayName("Microservice path - routes through storage-service (HTTP), not in-process")
    class MicroservicePath {

        private PlanStorageQuotaSyncer syncerWithClient() {
            PlanStorageQuotaSyncer s = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            // microservice wiring: StorageClient present (field-injected in production via @Autowired).
            ReflectionTestUtils.setField(s, "storageClient", storageClient);
            return s;
        }

        @Test
        @DisplayName("tenant + each owned org go through storage-client; in-process QuotaService is NOT touched")
        void routesThroughStorageClient() {
            UUID orgId = UUID.randomUUID();
            when(organizationRepository.findByOwnerId(42L)).thenReturn(List.of(orgWithId(orgId)));
            when(storageClient.updateTenantStorageLimits("42", 1_073_741_824L, 0.8)).thenReturn(true);
            when(storageClient.updateOrganizationStorageLimits(orgId.toString(), 1_073_741_824L, 0.8))
                    .thenReturn(true);

            syncerWithClient().syncAfterCommit(42L, plan("STARTER", 1_073_741_824L));

            verify(storageClient).updateTenantStorageLimits("42", 1_073_741_824L, 0.8);
            verify(storageClient).updateOrganizationStorageLimits(orgId.toString(), 1_073_741_824L, 0.8);
            // The whole point of the architectural fix: auth no longer writes the storage schema itself.
            verifyNoInteractions(quotaService);
        }

        @Test
        @DisplayName("storage-service non-ack is best-effort - no throw, remaining work continues")
        void httpNonAckIsBestEffort() {
            when(organizationRepository.findByOwnerId(42L)).thenReturn(Collections.emptyList());
            when(storageClient.updateTenantStorageLimits(anyString(), anyLong(), anyDouble()))
                    .thenReturn(false);

            assertThatCode(() -> syncerWithClient().syncAfterCommit(42L, plan("STARTER", 1_073_741_824L)))
                    .doesNotThrowAnyException();

            verify(storageClient).updateTenantStorageLimits("42", 1_073_741_824L, 0.8);
        }
    }

    @Nested
    @DisplayName("In-process commit safety (REQUIRES_NEW) - the afterCommit-lost-write guard")
    class InProcessCommit {

        @Test
        @DisplayName("in-process write runs inside a committed REQUIRES_NEW transaction")
        void writeRunsInRequiresNewTx() {
            when(organizationRepository.findByOwnerId(42L)).thenReturn(Collections.emptyList());
            PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
            TransactionStatus status = mock(TransactionStatus.class);
            when(txManager.getTransaction(any())).thenReturn(status);

            PlanStorageQuotaSyncer s = new PlanStorageQuotaSyncer(quotaService, organizationRepository);
            s.setTransactionManager(txManager); // builds the REQUIRES_NEW template (production monolith wiring)

            s.syncAfterCommit(42L, plan("STARTER", 1_073_741_824L));

            // The in-process write was wrapped in a NEW transaction that actually committed -
            // not joined to (and lost by) an already-committed caller transaction.
            ArgumentCaptor<TransactionDefinition> def = ArgumentCaptor.forClass(TransactionDefinition.class);
            verify(txManager).getTransaction(def.capture());
            assertThat(def.getValue().getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            verify(txManager).commit(status);
            verify(quotaService).updateLimits("42", 1_073_741_824L, 0.8);
        }
    }
}
