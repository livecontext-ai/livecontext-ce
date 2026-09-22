package com.apimarketplace.orchestrator.services.storage;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.StorageUsageDto;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.StorageRowCategories;
import com.apimarketplace.common.storage.service.QuotaService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.config.ConversationStorageClient;
import com.apimarketplace.publication.client.PublicationClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StorageReconciliationService Unit Tests")
@ExtendWith(MockitoExtension.class)
class StorageReconciliationServiceTest {

    private static final String TENANT_ID = "tenant-001";
    private static final String ORG_ID = "org-42";

    static java.util.stream.Stream<Object> invalidConfigurationMeasurements() {
        return java.util.stream.Stream.of(
                null, "not-a-map", Map.of("itemCount", 1),
                Map.of("usedBytes", "12"), Map.of("usedBytes", -1L),
                Map.of("usedBytes", 1.5), Map.of("usedBytes", Double.NaN),
                Map.of("usedBytes", Double.POSITIVE_INFINITY),
                Map.of("usedBytes", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
    }

    @Mock private EntityManager entityManager;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private QuotaService quotaService;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private PublicationClient publicationClient;
    @Mock private ConversationStorageClient conversationStorageClient;

    private StorageReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new StorageReconciliationService(
                entityManager, breakdownService, quotaService,
                agentClient, interfaceClient, dataSourceClient,
                publicationClient, conversationStorageClient);
    }

    /**
     * Stubs all local SQL queries (STEP_OUTPUTS, FILES, EXECUTION_DATA, CONFIGURATION_WORKFLOWS)
     * and all remote HTTP clients to return the given values.
     */
    private void stubAllCategories(long bytes, int count) {
        // Local SQL queries (4 total: 3 LOCAL_QUERIES + CONFIGURATION_WORKFLOWS)
        Query mockQuery = mock(Query.class);
        when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(
                new Object[]{BigInteger.valueOf(bytes), BigInteger.valueOf(count)});
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

        // Remote: agent-service (AGENTS + SKILLS)
        when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                "AGENTS", Map.of("usedBytes", bytes, "itemCount", count),
                "SKILLS", Map.of("usedBytes", bytes, "itemCount", count),
                "MEMORIES", Map.of("usedBytes", bytes, "itemCount", count)
        ));

        // Remote: interface-service
        when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                Map.of("usedBytes", bytes, "itemCount", count));

        // Remote: conversation-service
        when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                Optional.of(new StorageUsageDto(bytes, count)));

        // Remote: datasource-service
        when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(
                Map.of("usedBytes", bytes, "itemCount", count));

        // Remote: publication-service
        when(publicationClient.getPublicationStorageUsage(TENANT_ID)).thenReturn(
                Map.of("usedBytes", bytes, "itemCount", count));
    }

    // ========================================================================
    // reconcileTenant()
    // ========================================================================

    @Nested
    @DisplayName("reconcileTenant()")
    class ReconcileTenantTests {

        @Test
        @DisplayName("should reconcile all 9 categories and call setUsage for each")
        void shouldReconcileAllCategories() {
            stubAllCategories(1000L, 10);

            service.reconcileTenant(TENANT_ID);

            // 9 categories total: STEP_OUTPUTS, FILES, EXECUTION_DATA, CONFIGURATION,
            // AGENTS, INTERFACES, CONVERSATIONS, DATATABLES, PUBLICATIONS
            verify(breakdownService, times(9)).setUsage(eq(TENANT_ID), anyString(), anyLong(), anyInt());
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should call local SQL for STEP_OUTPUTS, FILES, EXECUTION_DATA")
        void shouldRunLocalQueries() {
            stubAllCategories(5000L, 42);

            service.reconcileTenant(TENANT_ID);

            // 4 native queries: STEP_OUTPUTS + FILES + EXECUTION_DATA + CONFIGURATION_WORKFLOWS
            verify(entityManager, times(4)).createNativeQuery(anyString());
        }

        @Test
        @DisplayName("should call remote clients for AGENTS, INTERFACES, CONVERSATIONS, DATATABLES, PUBLICATIONS")
        void shouldCallRemoteClients() {
            stubAllCategories(1000L, 5);

            service.reconcileTenant(TENANT_ID);

            // agentClient called twice: once for CONFIGURATION (skills), once for AGENTS
            verify(agentClient, times(2)).getAgentStorageUsage(TENANT_ID);
            verify(interfaceClient).getInterfaceStorageUsage(TENANT_ID);
            verify(conversationStorageClient).getStorageUsage(TENANT_ID);
            verify(dataSourceClient).getDataSourceStorageUsage(TENANT_ID);
            verify(publicationClient).getPublicationStorageUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should clamp negative values to zero for local queries")
        void shouldClampNegativeValues() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(-500L), BigInteger.valueOf(-3)});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            // Stub remote to avoid NPE
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 0, "itemCount", 0),
                    "SKILLS", Map.of("usedBytes", 0, "itemCount", 0),
                    "MEMORIES", Map.of("usedBytes", 0L, "itemCount", 0)
            ));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                    Optional.of(new StorageUsageDto(0, 0)));
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(publicationClient.getPublicationStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));

            service.reconcileTenant(TENANT_ID);

            // Local categories should use Math.max(0, ...)
            verify(breakdownService, atLeastOnce()).setUsage(eq(TENANT_ID), anyString(), eq(0L), eq(0));
        }

        @Test
        @DisplayName("should handle null query results gracefully via toBigInteger()")
        void shouldHandleNullResults() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(new Object[]{null, null});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 0, "itemCount", 0),
                    "SKILLS", Map.of("usedBytes", 0, "itemCount", 0),
                    "MEMORIES", Map.of("usedBytes", 0L, "itemCount", 0)
            ));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                    Optional.of(new StorageUsageDto(0, 0)));
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(publicationClient.getPublicationStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, times(9)).setUsage(eq(TENANT_ID), anyString(), eq(0L), eq(0));
        }

        @Test
        @DisplayName("should continue when a remote client fails")
        void shouldContinueOnRemoteClientFailure() {
            // Local queries succeed
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(100L), BigInteger.ONE});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            // Some remote clients fail
            when(agentClient.getAgentStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("Agent service down"));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("Interface service down"));
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                    Optional.of(new StorageUsageDto(200, 5)));
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 300, "itemCount", 3));
            when(publicationClient.getPublicationStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 400, "itemCount", 4));

            service.reconcileTenant(TENANT_ID);

            // Should still call updateUsage
            verify(quotaService).updateUsage(TENANT_ID);
            // At least local categories + successful remotes should be set
            verify(breakdownService, atLeast(5)).setUsage(eq(TENANT_ID), anyString(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("should still call updateUsage even when all queries and clients fail")
        void shouldCallUpdateUsageEvenOnAllFailures() {
            when(entityManager.createNativeQuery(anyString())).thenThrow(new RuntimeException("DB down"));
            when(agentClient.getAgentStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(conversationStorageClient.getStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(publicationClient.getPublicationStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(anyString(), anyString(), anyLong(), anyInt());
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should handle zero usage (empty tenant)")
        void shouldHandleZeroUsage() {
            stubAllCategories(0L, 0);

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, times(9)).setUsage(eq(TENANT_ID), anyString(), eq(0L), eq(0));
            verify(quotaService).updateUsage(TENANT_ID);
        }
    }

    // ========================================================================
    // reconcileOrganization() - Issue #149
    // ========================================================================

    @Nested
    @DisplayName("reconcileOrganization() - Issue #149")
    class ReconcileOrganizationTests {

        @Test
        @DisplayName("overwrites the org rollup from current org-owned data - 4 setOrgUsage calls")
        void overwritesOrgRollupFromOwningRows() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("oid"), eq(ORG_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(7000L), BigInteger.valueOf(12)});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            service.reconcileOrganization(ORG_ID);

            // 4 org-scoped categories: STEP_OUTPUTS, FILES, EXECUTION_DATA, CONFIGURATION
            verify(breakdownService, times(4)).setOrgUsage(eq(ORG_ID), anyString(), anyLong(), anyInt());
            // Tenant rollups untouched on the org reconcile path.
            verify(breakdownService, never()).setUsage(anyString(), anyString(), anyLong(), anyInt());
            // Org reconcile never bumps the tenant quota service either.
            verify(quotaService, never()).updateUsage(anyString());
        }

        @Test
        @DisplayName("clamps negative org rollup values to zero")
        void clampsNegativeValuesToZero() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("oid"), eq(ORG_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(-500L), BigInteger.valueOf(-3)});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            service.reconcileOrganization(ORG_ID);

            verify(breakdownService, atLeastOnce())
                    .setOrgUsage(eq(ORG_ID), anyString(), eq(0L), eq(0));
        }

        @Test
        @DisplayName("blank organizationId is a no-op (no SQL, no setOrgUsage)")
        void blankOrgIdIsNoOp() {
            service.reconcileOrganization("");

            verifyNoInteractions(entityManager);
            verify(breakdownService, never()).setOrgUsage(anyString(), anyString(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("null organizationId is a no-op (no SQL, no setOrgUsage)")
        void nullOrgIdIsNoOp() {
            service.reconcileOrganization(null);

            verifyNoInteractions(entityManager);
            verify(breakdownService, never()).setOrgUsage(anyString(), anyString(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("refreshes the org gauge from the rows it just wrote, like reconcileTenant does")
        void reconcileOrganizationRefreshesTheGauge() {
            // Without this the nightly pass left the org gauge on yesterday's total until somebody
            // opened the page, so the quota gate that decides whether a WRITE is allowed read a
            // stale number in between. The tenant path has always ended this way.
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("oid"), eq(ORG_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(21_000_000_000L), BigInteger.valueOf(168_022)});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            service.reconcileOrganization(ORG_ID);

            verify(quotaService).updateOrganizationUsage(ORG_ID);
        }

        @Test
        @DisplayName("STEP_OUTPUTS_BY_ORG and FILES_BY_ORG filter by s.organization_id directly and mirror StorageService categorization")
        void orgScopedStorageQueriesFilterByOrganizationIdNotWorkflowIdJoin() {
            // The bug: the previous shape JOINed `storage` to `workflows` via
            //   `JOIN orchestrator.workflows w ON w.id::text = s.workflow_id`
            // but the `storage.storage.workflow_id` column does NOT hold the
            // workflows PK (it carries a per-step UUID assigned at save time).
            // On prod tenant 1 the JOIN matched 0 of 140 799 active rows, so
            // `org_storage_breakdown` persisted STEP_OUTPUTS=0 and FILES=0
            // while the gauge (which sums `s.organization_id` directly via
            // `StorageRepository.calculateOrganizationUsage`) showed ~425 MB.
            //
            // The fix: aggregate by `s.organization_id` - the SAME column the
            // gauge uses. By construction the per-category sum can no longer
            // exceed the gauge, so the stacked bar and the gauge agree
            // byte-for-byte (modulo the remote-service categories that come
            // from `reconcileAgents/Interfaces/...`).
            //
            // A run-id-based JOIN would also have worked in theory but would
            // silently drop rows that the gauge counts (NULL run_id, deleted
            // workflow_runs via FK CASCADE) - leaving gauge and breakdown
            // drifted again. The direct filter avoids that whole class of
            // drift.
            org.mockito.ArgumentCaptor<String> sqlCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("oid"), eq(ORG_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(1L), BigInteger.valueOf(1)});
            when(entityManager.createNativeQuery(sqlCaptor.capture())).thenReturn(mockQuery);

            service.reconcileOrganization(ORG_ID);

            java.util.List<String> sqls = sqlCaptor.getAllValues();
            String stepOutputsPredicate = StorageRowCategories.stepOutputsSqlPredicate("s");
            String filesPredicate = StorageRowCategories.filesSqlPredicate("s");
            String stepOutputsSql = sqls.stream()
                    .filter(s -> s.contains(stepOutputsPredicate) && s.contains(":oid"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("STEP_OUTPUTS org SQL not captured"));
            String filesSql = sqls.stream()
                    .filter(s -> s.contains(filesPredicate) && !s.contains(stepOutputsPredicate) && s.contains(":oid"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("FILES org SQL not captured"));

            // Both queries MUST filter on s.organization_id (same column the
            // gauge sums). This is what makes gauge and breakdown agree.
            assertThat(stepOutputsSql)
                    .as("STEP_OUTPUTS_BY_ORG must filter by s.organization_id directly")
                    .contains("s.organization_id = :oid")
                    .contains("s.status = 'ACTIVE'");
            assertThat(filesSql)
                    .as("FILES_BY_ORG must filter by s.organization_id directly")
                    .contains("s.organization_id = :oid")
                    .contains("s.status = 'ACTIVE'");

            // The org queries must classify with the SAME rule as the tenant queries and as the
            // save/delete paths, which is what {@link StorageRowCategories} is for. Spelling the
            // predicate out here again is what let the tenant and org copies drift: the org one
            // was fixed in 2026-05 while the tenant one kept losing every S3-backed file.
            assertThat(stepOutputsSql)
                    .as("STEP_OUTPUTS_BY_ORG must use the shared classifier's predicate")
                    .contains(stepOutputsPredicate);
            assertThat(filesSql)
                    .as("FILES_BY_ORG must use the shared classifier's predicate")
                    .contains(filesPredicate);
            assertThat(filesSql)
                    .as("regression: an S3-backed row is a FILE whatever produced it - the literal "
                            + "whose absence hid 16 GB of one production tenant")
                    .contains("COALESCE(s.storage_type, '') IN ('S3_FILE', 'BINARY', 'TEXT')");

            // The broken direct-PK JOIN MUST NOT come back. On prod this matched
            // 0 / 140 799 rows because storage.storage.workflow_id does not
            // store the workflow PK despite the column name.
            assertThat(stepOutputsSql)
                    .as("STEP_OUTPUTS_BY_ORG must NOT join workflows directly on s.workflow_id")
                    .doesNotContain("w.id::text = s.workflow_id");
            assertThat(filesSql)
                    .as("FILES_BY_ORG must NOT join workflows directly on s.workflow_id")
                    .doesNotContain("w.id::text = s.workflow_id");

            // Defense-in-depth: also reject any future revert that routes
            // through workflows / workflow_runs - anything other than a
            // direct `s.organization_id` filter risks drifting from the gauge.
            assertThat(stepOutputsSql)
                    .as("STEP_OUTPUTS_BY_ORG must NOT JOIN orchestrator.workflows (drifts from gauge)")
                    .doesNotContain("orchestrator.workflows");
            assertThat(filesSql)
                    .as("FILES_BY_ORG must NOT JOIN orchestrator.workflows (drifts from gauge)")
                    .doesNotContain("orchestrator.workflows");
        }
    }

    @Nested
    @DisplayName("refreshOrgBreakdown()")
    class RefreshOrgBreakdownTests {

        @Test
        @DisplayName("regression: refreshOrgBreakdown recomputes org local categories before settings storage reads")
        void refreshOrgBreakdownRecomputesOrgLocalCategories() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("oid"), eq(ORG_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(435_000_000L), BigInteger.valueOf(140_799)});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            service.refreshOrgBreakdown(ORG_ID);

            verify(entityManager, times(3)).createNativeQuery(anyString());
            verify(breakdownService, times(3))
                    .setOrgUsage(eq(ORG_ID), anyString(), eq(435_000_000L), eq(140_799));
            verify(quotaService).updateOrganizationUsage(ORG_ID);
        }

        @Test
        @DisplayName("refreshOrgBreakdown throttle hit skips the SQL but still refreshes the org gauge")
        void refreshOrgBreakdownThrottleStillUpdatesOrgQuotaGauge() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("oid"), eq(ORG_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(1L), BigInteger.ONE});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            service.refreshOrgBreakdown(ORG_ID);
            clearInvocations(entityManager, breakdownService, quotaService);

            service.refreshOrgBreakdown(ORG_ID);

            verifyNoInteractions(entityManager);
            verify(breakdownService, never()).setOrgUsage(anyString(), anyString(), anyLong(), anyInt());
            // The org gauge is a direct SUM over storage.storage, so it moves whenever a file is
            // written even though the categories are throttled. The tenant path differs on
            // purpose: its gauge is the sum of these rows, and every writer refreshes it already.
            verify(quotaService).updateOrganizationUsage(ORG_ID);
        }

        @Test
        @DisplayName("refreshOrgBreakdown with blank organizationId is a no-op")
        void refreshOrgBreakdownBlankOrgIsNoOp() {
            service.refreshOrgBreakdown(" ");

            verifyNoInteractions(entityManager);
            verify(breakdownService, never()).setOrgUsage(anyString(), anyString(), anyLong(), anyInt());
            verify(quotaService, never()).updateOrganizationUsage(anyString());
        }
    }

    @Nested
    @DisplayName("which scopes the daily pass visits")
    class ScopeEnumerationTests {

        /**
         * Enumerating from the breakdown table alone could only ever find scopes that had already
         * been reconciled, so a scope that had never been visited never would be, and its gauge
         * answered zero for whatever it held. Production on 2026-09-18: 31 organizations and 30
         * tenants held ACTIVE rows with no breakdown row at all.
         */
        @Test
        @DisplayName("tenants are taken from the breakdown rows UNION the storage rows")
        void tenantEnumerationIncludesScopesWithNoBreakdownRow() {
            assertThat(StorageReconciliationQueries.TENANTS_TO_RECONCILE)
                    .contains("storage.tenant_storage_breakdown")
                    .containsIgnoringCase("union")
                    .contains("FROM storage.storage")
                    .contains("status = 'ACTIVE'");
        }

        @Test
        @DisplayName("organizations are taken the same way, and never a NULL organization_id")
        void orgEnumerationIncludesScopesWithNoBreakdownRow() {
            assertThat(StorageReconciliationQueries.ORGS_TO_RECONCILE)
                    .contains("storage.org_storage_breakdown")
                    .containsIgnoringCase("union")
                    .contains("FROM storage.storage")
                    .contains("organization_id IS NOT NULL");
        }

        @Test
        @DisplayName("the daily pass runs those two queries, not a breakdown-only enumeration")
        void dailyReconciliationUsesTheUnionQueries() {
            Query tenantQuery = mock(Query.class);
            when(tenantQuery.getResultList()).thenReturn(java.util.List.of());
            org.mockito.ArgumentCaptor<String> sqlCaptor =
                    org.mockito.ArgumentCaptor.forClass(String.class);
            when(entityManager.createNativeQuery(sqlCaptor.capture())).thenReturn(tenantQuery);

            service.dailyReconciliation();

            assertThat(sqlCaptor.getAllValues())
                    .contains(StorageReconciliationQueries.TENANTS_TO_RECONCILE,
                            StorageReconciliationQueries.ORGS_TO_RECONCILE);
        }
    }

    @Nested
    @DisplayName("refreshTenantBreakdown()")
    class RefreshTenantBreakdownTests {

        /**
         * The tenant scope used to refresh EXECUTION_DATA only, while the org scope refreshed all
         * three local categories. The same workspace's storage page therefore showed numbers up to
         * a day apart depending on which scope it was read in, and a corrected classification
         * would have taken until the 02:00 cron to appear anywhere. How fresh a figure is must not
         * depend on the scope it is read in.
         */
        @Test
        @DisplayName("refreshes the same three local categories as the org scope")
        void refreshesAllThreeLocalCategories() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(21_000_000_000L), BigInteger.valueOf(168_022)});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            service.refreshTenantBreakdown(TENANT_ID);

            verify(entityManager, times(3)).createNativeQuery(anyString());
            verify(breakdownService).setUsage(TENANT_ID, "STEP_OUTPUTS", 21_000_000_000L, 168_022);
            verify(breakdownService).setUsage(TENANT_ID, "FILES", 21_000_000_000L, 168_022);
            verify(breakdownService).setUsage(TENANT_ID, "EXECUTION_DATA", 21_000_000_000L, 168_022);
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("a throttle hit costs a map lookup and nothing else")
        void throttleHitDoesNothing() {
            // The tenant gauge is the sum of these rows and every writer that changes them
            // refreshes it on the way out, so a throttled read has nothing to correct. Refreshing
            // here would re-write an identical value and evict the tenantQuota cache on every GET,
            // leaving that cache unable to serve. The org path is the opposite case, see
            // refreshOrgBreakdownThrottleStillUpdatesOrgQuotaGauge.
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.ONE, BigInteger.ONE});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            service.refreshTenantBreakdown(TENANT_ID);
            clearInvocations(entityManager, breakdownService, quotaService);

            service.refreshTenantBreakdown(TENANT_ID);

            verifyNoInteractions(entityManager);
            verifyNoInteractions(quotaService);
            verify(breakdownService, never()).setUsage(anyString(), anyString(), anyLong(), anyInt());
        }

        @Test
        @DisplayName("a failing query does not arm the throttle, so the next caller may retry")
        void failedQueryLeavesThrottleOpen() {
            // doThrow, not when().thenThrow(): the latter CALLS the mock to build the stub, and
            // the already-armed throw would escape the test itself.
            doThrow(new RuntimeException("lock wait timeout"))
                    .when(entityManager).createNativeQuery(anyString());

            service.refreshTenantBreakdown(TENANT_ID);
            clearInvocations(entityManager);

            service.refreshTenantBreakdown(TENANT_ID);

            // Three categories attempted again: a transient DB failure must not cost the tenant a
            // 30s window of staleness it cannot retry out of.
            verify(entityManager, times(3)).createNativeQuery(anyString());
        }

        @Test
        @DisplayName("a blank tenantId is a no-op")
        void blankTenantIsNoOp() {
            service.refreshTenantBreakdown(" ");

            verifyNoInteractions(entityManager);
            verify(quotaService, never()).updateUsage(anyString());
        }
    }

    // ========================================================================
    // dailyReconciliation()
    // ========================================================================

    @Nested
    @DisplayName("dailyReconciliation()")
    class DailyReconciliationTests {

        @Test
        @DisplayName("should reconcile all tenants returned by distinct query")
        void shouldReconcileAllTenants() {
            Query tenantQuery = mock(Query.class);
            when(entityManager.createNativeQuery(StorageReconciliationQueries.TENANTS_TO_RECONCILE))
                    .thenReturn(tenantQuery);
            when(tenantQuery.getResultList()).thenReturn(Arrays.asList("tenant-A", "tenant-B", "tenant-C"));

            // Stub local category queries
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), anyString())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(1000L), BigInteger.valueOf(5)});
            when(entityManager.createNativeQuery(argThat(sql ->
                    sql != null && !sql.equals(StorageReconciliationQueries.TENANTS_TO_RECONCILE)
                            && !sql.equals(StorageReconciliationQueries.ORGS_TO_RECONCILE))
            )).thenReturn(mockQuery);

            // Stub remote clients for all tenants
            when(agentClient.getAgentStorageUsage(anyString())).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 1000, "itemCount", 5),
                    "SKILLS", Map.of("usedBytes", 500, "itemCount", 2),
                    "MEMORIES", Map.of("usedBytes", 0L, "itemCount", 0)
            ));
            when(interfaceClient.getInterfaceStorageUsage(anyString())).thenReturn(
                    Map.of("usedBytes", 1000, "itemCount", 5));
            when(conversationStorageClient.getStorageUsage(anyString())).thenReturn(
                    Optional.of(new StorageUsageDto(1000, 5)));
            when(dataSourceClient.getDataSourceStorageUsage(anyString())).thenReturn(
                    Map.of("usedBytes", 1000, "itemCount", 5));
            when(publicationClient.getPublicationStorageUsage(anyString())).thenReturn(
                    Map.of("usedBytes", 1000, "itemCount", 5));

            service.dailyReconciliation();

            // 3 tenants x 9 categories = 27 setUsage calls
            verify(breakdownService, times(27)).setUsage(anyString(), anyString(), anyLong(), anyInt());
            verify(quotaService, times(3)).updateUsage(anyString());
        }

        @Test
        @DisplayName("each organization in the list is reconciled, and a failure does not stop the rest")
        void shouldReconcileEveryOrgAndSurviveOne() {
            Query tenantQuery = mock(Query.class);
            when(tenantQuery.getResultList()).thenReturn(Collections.emptyList());
            Query orgQuery = mock(Query.class);
            when(orgQuery.getResultList()).thenReturn(java.util.List.of("org-A", "org-B"));

            Query categoryQuery = mock(Query.class);
            when(categoryQuery.setParameter(eq("oid"), anyString())).thenReturn(categoryQuery);
            when(categoryQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(100L), BigInteger.valueOf(2)});

            when(entityManager.createNativeQuery(anyString())).thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                if (StorageReconciliationQueries.TENANTS_TO_RECONCILE.equals(sql)) return tenantQuery;
                if (StorageReconciliationQueries.ORGS_TO_RECONCILE.equals(sql)) return orgQuery;
                return categoryQuery;
            });
            // org-A's gauge refresh blows up. The whole night must not go with it.
            doThrow(new RuntimeException("duplicate key"))
                    .when(quotaService).updateOrganizationUsage("org-A");

            service.dailyReconciliation();

            verify(breakdownService, atLeastOnce()).setOrgUsage(eq("org-A"), anyString(), anyLong(), anyInt());
            verify(breakdownService, atLeastOnce()).setOrgUsage(eq("org-B"), anyString(), anyLong(), anyInt());
            verify(quotaService).updateOrganizationUsage("org-B");
        }

        @Test
        @DisplayName("should handle empty tenant list gracefully")
        void shouldHandleEmptyTenantList() {
            Query tenantQuery = mock(Query.class);
            when(entityManager.createNativeQuery(StorageReconciliationQueries.TENANTS_TO_RECONCILE))
                    .thenReturn(tenantQuery);
            when(tenantQuery.getResultList()).thenReturn(Collections.emptyList());

            service.dailyReconciliation();

            verify(breakdownService, never()).setUsage(anyString(), anyString(), anyLong(), anyInt());
            verify(quotaService, never()).updateUsage(anyString());
        }

        @Test
        @DisplayName("should not throw when tenant list query itself fails")
        void shouldNotThrowWhenTenantQueryFails() {
            Query tenantQuery = mock(Query.class);
            when(entityManager.createNativeQuery(StorageReconciliationQueries.TENANTS_TO_RECONCILE))
                    .thenReturn(tenantQuery);
            when(tenantQuery.getResultList()).thenThrow(new RuntimeException("DB connection lost"));

            assertThatCode(() -> service.dailyReconciliation()).doesNotThrowAnyException();
        }
    }

    // ========================================================================
    // Query SQL Validation
    // ========================================================================

    @Nested
    @DisplayName("Query SQL Validation")
    class QueryValidationTests {

        /**
         * These two used to spell the predicate out again, and that is precisely how the bug
         * survived: the old assertions described what the query DID
         * ({@code source_type NOT IN ('S3_FILE','CHAT_ATTACHMENT')}) rather than what it had to
         * ACHIEVE, so they stayed green while 16 GB of one tenant's files were counted in no
         * category at all. They now pin the one property a string test can prove - that both
         * queries take their predicate from the single classifier the save and delete paths also
         * use. That the predicates really partition the rows is proved by executing them, in
         * {@code StorageBreakdownPartitionPostgresIT}.
         */
        @Test
        @DisplayName("STEP_OUTPUTS query uses the shared classifier's predicate, not its own copy")
        void shouldFilterStepOutputSourceTypes() {
            assertThat(StorageReconciliationQueries.STEP_OUTPUTS)
                    .contains(StorageRowCategories.stepOutputsSqlPredicate("s"))
                    .contains("status = 'ACTIVE'")
                    .contains("storage.storage");
        }

        @Test
        @DisplayName("FILES query uses the shared classifier's predicate, not its own copy")
        void shouldFilterFileSourceTypes() {
            assertThat(StorageReconciliationQueries.FILES)
                    .contains(StorageRowCategories.filesSqlPredicate("s"))
                    .contains("status = 'ACTIVE'")
                    .contains("storage.storage");
        }

        @Test
        @DisplayName("REGRESSION GUARD: the FILES predicate matches the S3_FILE storage type")
        void filesPredicateCoversS3BackedRows() {
            // The single literal whose absence cost 16 GB of visibility: S3_FILE was tested as a
            // SOURCE type only, so every file a workflow step or an interface node produced
            // (storage_type = 'S3_FILE', source_type = 'STEP_OUTPUT' / 'INTERFACE_VIDEO' / ...)
            // matched neither category.
            assertThat(StorageReconciliationQueries.FILES)
                    .contains("COALESCE(s.storage_type, '') IN ('S3_FILE', 'BINARY', 'TEXT')");
            assertThat(StorageReconciliationQueries.FILES_BY_ORG)
                    .contains("COALESCE(s.storage_type, '') IN ('S3_FILE', 'BINARY', 'TEXT')");
        }

        @Test
        @DisplayName("EXECUTION_DATA query should use pg_column_size for JSONB")
        void shouldUsePgColumnSizeForJsonb() {
            assertThat(StorageReconciliationQueries.EXECUTION_DATA)
                    .contains("pg_column_size(wr.state_snapshot)")
                    .contains("pg_column_size(wr.plan)")
                    .contains("pg_column_size(wr.trigger_payload)")
                    .contains("pg_column_size(wr.metadata)")
                    .contains("orchestrator.workflow_runs");
        }

        @Test
        @DisplayName("CONFIGURATION_WORKFLOWS query should include workflows and plan versions (no skills)")
        void shouldIncludeConfigurationWorkflowEntities() {
            assertThat(StorageReconciliationQueries.CONFIGURATION_WORKFLOWS)
                    .contains("orchestrator.workflows")
                    .contains("orchestrator.workflow_plan_versions")
                    .contains("data_inputs")
                    .doesNotContain("agent.skills");
        }

        @Test
        @DisplayName("all local queries should use :tid parameter")
        void allQueriesShouldUseTidParam() {
            assertThat(StorageReconciliationQueries.STEP_OUTPUTS).contains(":tid");
            assertThat(StorageReconciliationQueries.FILES).contains(":tid");
            assertThat(StorageReconciliationQueries.EXECUTION_DATA).contains(":tid");
            assertThat(StorageReconciliationQueries.CONFIGURATION_WORKFLOWS).contains(":tid");
        }

        @Test
        @DisplayName("all local queries should use COALESCE for null safety")
        void allQueriesShouldUseCoalesce() {
            assertThat(StorageReconciliationQueries.STEP_OUTPUTS).containsIgnoringCase("coalesce");
            assertThat(StorageReconciliationQueries.FILES).containsIgnoringCase("coalesce");
            assertThat(StorageReconciliationQueries.EXECUTION_DATA).containsIgnoringCase("coalesce");
            assertThat(StorageReconciliationQueries.CONFIGURATION_WORKFLOWS).containsIgnoringCase("coalesce");
        }
    }

    // ========================================================================
    // toBigInteger() (private, tested indirectly)
    // ========================================================================

    @Nested
    @DisplayName("toBigInteger() via reconcileTenant()")
    class ToBigIntegerTests {

        @Test
        @DisplayName("should handle BigInteger values")
        void shouldHandleBigInteger() {
            stubAllCategories(Long.MAX_VALUE, Integer.MAX_VALUE);

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, atLeastOnce()).setUsage(eq(TENANT_ID), anyString(),
                    eq(Long.MAX_VALUE), eq(Integer.MAX_VALUE));
        }

        @Test
        @DisplayName("should handle Long results from query (not BigInteger)")
        void shouldHandleLongResults() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(new Object[]{99999L, 55L});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            // Stub remote clients
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 0, "itemCount", 0),
                    "SKILLS", Map.of("usedBytes", 0, "itemCount", 0),
                    "MEMORIES", Map.of("usedBytes", 0L, "itemCount", 0)
            ));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                    Optional.of(new StorageUsageDto(0, 0)));
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(publicationClient.getPublicationStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));

            service.reconcileTenant(TENANT_ID);

            // Local categories should be set with 99999L, 55
            verify(breakdownService, atLeastOnce()).setUsage(eq(TENANT_ID), anyString(), eq(99999L), eq(55));
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle ClassCastException from unexpected result type")
        void shouldHandleClassCastException() {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn("unexpected_string");
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            // Remote clients fail too
            when(agentClient.getAgentStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(conversationStorageClient.getStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(publicationClient.getPublicationStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));

            assertThatCode(() -> service.reconcileTenant(TENANT_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle entityManager.createNativeQuery throwing exception")
        void shouldHandleQueryCreationFailure() {
            when(entityManager.createNativeQuery(anyString())).thenThrow(new RuntimeException("SQL syntax error"));

            when(agentClient.getAgentStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(conversationStorageClient.getStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));
            when(publicationClient.getPublicationStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("down"));

            assertThatCode(() -> service.reconcileTenant(TENANT_ID)).doesNotThrowAnyException();
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("should handle special characters in tenant ID")
        void shouldHandleSpecialTenantId() {
            String specialTenantId = "google-oauth2|123456789";

            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(specialTenantId))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(500L), BigInteger.valueOf(2)});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);

            when(agentClient.getAgentStorageUsage(specialTenantId)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 500, "itemCount", 2),
                    "SKILLS", Map.of("usedBytes", 100, "itemCount", 1),
                    "MEMORIES", Map.of("usedBytes", 0L, "itemCount", 0)
            ));
            when(interfaceClient.getInterfaceStorageUsage(specialTenantId)).thenReturn(
                    Map.of("usedBytes", 500, "itemCount", 2));
            when(conversationStorageClient.getStorageUsage(specialTenantId)).thenReturn(
                    Optional.of(new StorageUsageDto(500, 2)));
            when(dataSourceClient.getDataSourceStorageUsage(specialTenantId)).thenReturn(
                    Map.of("usedBytes", 500, "itemCount", 2));
            when(publicationClient.getPublicationStorageUsage(specialTenantId)).thenReturn(
                    Map.of("usedBytes", 500, "itemCount", 2));

            service.reconcileTenant(specialTenantId);

            verify(breakdownService, times(9)).setUsage(eq(specialTenantId), anyString(), anyLong(), anyInt());
            verify(quotaService).updateUsage(specialTenantId);
        }
    }
    // ========================================================================
    // CONFIGURATION: long-term memory rides on the agent-service usage call
    // ========================================================================

    @Nested
    @DisplayName("CONFIGURATION - long-term memory bytes")
    class ConfigurationMemoryTests {

        /** Stubs everything except the agent-service response, which each test shapes itself. */
        private void stubEverythingExceptAgentUsage(long bytes, int count) {
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(bytes), BigInteger.valueOf(count)});
            when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", bytes, "itemCount", count));
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                    Optional.of(new StorageUsageDto(bytes, count)));
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", bytes, "itemCount", count));
            when(publicationClient.getPublicationStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", bytes, "itemCount", count));
        }

        @Test
        @DisplayName("counts memory text toward the CONFIGURATION total, on top of workflows and skills")
        void memoryBytesAreAddedToConfiguration() {
            stubEverythingExceptAgentUsage(1000L, 3);
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 1000L, "itemCount", 3),
                    "SKILLS", Map.of("usedBytes", 20L, "itemCount", 1),
                    "MEMORIES", Map.of("usedBytes", 7L, "itemCount", 2)
            ));

            service.reconcileTenant(TENANT_ID);

            // 1000 workflow bytes + 20 skills + 7 memories. The three distinct values
            // matter: with equal ones, a total that dropped memory or double-counted
            // skills would still land on a plausible number.
            verify(breakdownService).setUsage(TENANT_ID, "CONFIGURATION", 1027L, 3);
        }

        @Test
        @DisplayName("preserves CONFIGURATION when an older or failing agent omits memory")
        void missingMemoryKeyPreservesConfiguration() {
            // Absence can mean an older service or a failed query. Neither proves zero.
            // Keep the last complete total until both measurements are available.
            stubEverythingExceptAgentUsage(1000L, 3);
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 1000L, "itemCount", 3),
                    "SKILLS", Map.of("usedBytes", 20L, "itemCount", 1)
            ));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONFIGURATION"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("preserves CONFIGURATION when a remote memory entry is malformed")
        void malformedMemoryEntryPreservesConfiguration() {
            // The value arrives as untyped JSON. An invalid entry cannot prove zero;
            // preserve the last complete sum while other categories keep reconciling.
            stubEverythingExceptAgentUsage(1000L, 3);
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 1000L, "itemCount", 3),
                    "SKILLS", Map.of("usedBytes", 20L, "itemCount", 1),
                    "MEMORIES", "not-a-map"
            ));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONFIGURATION"), anyLong(), anyInt());
        }
    }

    // ========================================================================
    // A remote category with NO measurement must not be written as zero
    // ========================================================================

    @Nested
    @DisplayName("remote category with no measurement")
    class MissingRemoteMeasurementTests {

        /** Every local query returns a usable row so only the remote branch is under test. */
        @BeforeEach
        void localQueriesSucceed() {
            Query mockQuery = mock(Query.class);
            lenient().when(mockQuery.setParameter(eq("tid"), eq(TENANT_ID))).thenReturn(mockQuery);
            lenient().when(mockQuery.getSingleResult())
                    .thenReturn(new Object[]{BigInteger.valueOf(100L), BigInteger.ONE});
            lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
            lenient().when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 1L, "itemCount", 1),
                    "SKILLS", Map.of("usedBytes", 1L, "itemCount", 1),
                    "MEMORIES", Map.of("usedBytes", 1L, "itemCount", 1)));
            lenient().when(interfaceClient.getInterfaceStorageUsage(TENANT_ID))
                    .thenReturn(Map.of("usedBytes", 1L, "itemCount", 1));
            lenient().when(conversationStorageClient.getStorageUsage(TENANT_ID))
                    .thenReturn(Optional.of(new StorageUsageDto(1L, 1)));
            lenient().when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID))
                    .thenReturn(Map.of("usedBytes", 1L, "itemCount", 1));
            lenient().when(publicationClient.getPublicationStorageUsage(TENANT_ID))
                    .thenReturn(Map.of("usedBytes", 1L, "itemCount", 1));
        }

        // Every one of these clients degrades to an EMPTY result rather than throwing, so
        // "the service is unreachable" and "this tenant stores nothing" reach the
        // reconciler identically. setUsage is an ABSOLUTE set, so writing the empty case
        // erases the last good figure. That is what turned the DATATABLES/PUBLICATIONS
        // column-count bug into 86 tenants zeroed every night instead of a stale number.

        @Test
        @DisplayName("DATATABLES: an empty client result leaves the stored value alone")
        void emptyDatatablesResultIsNotWrittenAsZero() {
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(Map.of());

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("DATATABLES"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("PUBLICATIONS: an empty client result leaves the stored value alone")
        void emptyPublicationsResultIsNotWrittenAsZero() {
            when(publicationClient.getPublicationStorageUsage(TENANT_ID)).thenReturn(Map.of());

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("PUBLICATIONS"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("INTERFACES: an empty client result leaves the stored value alone")
        void emptyInterfacesResultIsNotWrittenAsZero() {
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(Map.of());

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("INTERFACES"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("CONVERSATIONS: an absent measurement leaves the stored value alone")
        void absentConversationsMeasurementIsNotWrittenAsZero() {
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(Optional.empty());

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONVERSATIONS"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("a measured zero IS written, so the skip cannot hide a tenant emptying a table")
        void measuredZeroIsStillWritten() {
            // The other half of the contract. If the skip keyed on the VALUE rather than
            // on the measurement being present, a tenant deleting every table would keep
            // its old usage forever and could never get back under quota.
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID))
                    .thenReturn(Map.of("usedBytes", 0L, "itemCount", 0));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService).setUsage(TENANT_ID, "DATATABLES", 0L, 0);
        }

        @Test
        @DisplayName("a PARTIAL payload is not a measurement either")
        void partialPayloadIsNotAMeasurement() {
            // A non-empty check would accept this and write usedBytes as 0 - the same
            // wrong-zero arriving through a slightly different door.
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID))
                    .thenReturn(Map.of("itemCount", 3));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("DATATABLES"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("CONFIGURATION is skipped when the skills/memory half cannot be measured")
        void unmeasuredSkillsSkipsConfiguration() {
            // CONFIGURATION SUMS a local and a remote half into one absolute set, so
            // treating an unreachable agent-service as "zero skills, zero memories" does
            // not report a smaller number, it erases those bytes from the stored total.
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of());

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONFIGURATION"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("CONFIGURATION is skipped when the agent call THROWS, not only when it is empty")
        void throwingAgentClientAlsoSkipsConfiguration() {
            // Without this, a compiling mutation survives: restore the skillsBytes/memoryBytes
            // zero-initialisers and delete only the `return` in the catch, and the erasing
            // behaviour is back. The pre-existing "continues when a remote client fails" test
            // asserts atLeast(5) setUsage calls, so the count merely drops and it stays green.
            when(agentClient.getAgentStorageUsage(TENANT_ID))
                    .thenThrow(new RuntimeException("agent-service down"));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONFIGURATION"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("a failed skills query preserves CONFIGURATION while other categories update")
        void omittedSkillsPreservesConfiguration() {
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 1L, "itemCount", 1),
                    "MEMORIES", Map.of("usedBytes", 5L, "itemCount", 1)));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONFIGURATION"), anyLong(), anyInt());
            verify(breakdownService).setUsage(TENANT_ID, "AGENTS", 1L, 1);
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @ParameterizedTest
        @MethodSource("com.apimarketplace.orchestrator.services.storage.StorageReconciliationServiceTest#invalidConfigurationMeasurements")
        @DisplayName("an invalid skills or memory measurement never replaces CONFIGURATION")
        void invalidMeasurementPreservesConfiguration(Object invalid) {
            for (String category : java.util.List.of("SKILLS", "MEMORIES")) {
                Map<String, Object> usage = new java.util.HashMap<>();
                usage.put("AGENTS", Map.of("usedBytes", 1L, "itemCount", 1));
                usage.put("SKILLS", Map.of("usedBytes", 2L, "itemCount", 1));
                usage.put("MEMORIES", Map.of("usedBytes", 3L, "itemCount", 1));
                usage.put(category, invalid);
                when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(usage);

                service.reconcileTenant(TENANT_ID);
            }

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONFIGURATION"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("explicitly measured zeros still clear the remote contribution")
        void measuredSkillsAndMemoryZerosAreWritten() {
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "SKILLS", Map.of("usedBytes", 0, "itemCount", 0),
                    "MEMORIES", Map.of("usedBytes", 0L, "itemCount", 0)));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService).setUsage(TENANT_ID, "CONFIGURATION", 100L, 1);
            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("AGENTS"), anyLong(), anyInt());
        }

        @Test
        @DisplayName("an overflowing complete sum preserves CONFIGURATION instead of wrapping negative")
        void overflowingTotalPreservesConfiguration() {
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "SKILLS", Map.of("usedBytes", Long.MAX_VALUE, "itemCount", 1),
                    "MEMORIES", Map.of("usedBytes", 0L, "itemCount", 0)));

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONFIGURATION"), anyLong(), anyInt());
            verify(quotaService).updateUsage(TENANT_ID);
        }

        @Test
        @DisplayName("CONFIGURATION resumes after an incomplete measurement without losing the remote bytes")
        void completeMeasurementAfterFailureRestoresTotal() {
            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "MEMORIES", Map.of("usedBytes", 5L, "itemCount", 1)));
            service.reconcileTenant(TENANT_ID);
            verify(breakdownService, never()).setUsage(eq(TENANT_ID), eq("CONFIGURATION"), anyLong(), anyInt());

            when(agentClient.getAgentStorageUsage(TENANT_ID)).thenReturn(Map.of(
                    "SKILLS", Map.of("usedBytes", 20L, "itemCount", 2),
                    "MEMORIES", Map.of("usedBytes", 5L, "itemCount", 1)));
            service.reconcileTenant(TENANT_ID);

            verify(breakdownService).setUsage(TENANT_ID, "CONFIGURATION", 125L, 1);
        }

        @Test
        @DisplayName("the CONVERSATIONS skip leaves a trace too")
        void theConversationsSkipIsLogged() {
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(StorageReconciliationService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(Optional.empty());

                service.reconcileTenant(TENANT_ID);

                assertThat(appender.list).anySatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                    assertThat(e.getFormattedMessage())
                            .contains("No CONVERSATIONS measurement")
                            .contains(TENANT_ID);
                });
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("the skip leaves a trace, because a frozen figure is otherwise silent")
        void theSkipIsLogged() {
            // After the skip the ONLY evidence a category stopped updating is this line.
            // Delete it and a stale number looks exactly like a correct one.
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(StorageReconciliationService.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(Map.of());

                service.reconcileTenant(TENANT_ID);

                assertThat(appender.list)
                        .anySatisfy(e -> {
                            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
                            assertThat(e.getFormattedMessage())
                                    .contains("No DATATABLES measurement")
                                    .contains(TENANT_ID);
                        });
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
        }

        @Test
        @DisplayName("one unmeasured category does not stop the others from reconciling")
        void oneMissingCategoryDoesNotBlockTheRest() {
            when(dataSourceClient.getDataSourceStorageUsage(TENANT_ID)).thenReturn(Map.of());

            service.reconcileTenant(TENANT_ID);

            verify(breakdownService).setUsage(eq(TENANT_ID), eq("PUBLICATIONS"), anyLong(), anyInt());
            verify(breakdownService).setUsage(eq(TENANT_ID), eq("INTERFACES"), anyLong(), anyInt());
            verify(quotaService).updateUsage(TENANT_ID);
        }
    }
}
