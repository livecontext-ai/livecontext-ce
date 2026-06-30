package com.apimarketplace.orchestrator.services.storage;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.StorageUsageDto;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("StorageReconciliationService Unit Tests")
@ExtendWith(MockitoExtension.class)
class StorageReconciliationServiceTest {

    private static final String TENANT_ID = "tenant-001";
    private static final String ORG_ID = "org-42";

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
                "SKILLS", Map.of("usedBytes", bytes, "itemCount", count)
        ));

        // Remote: interface-service
        when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                Map.of("usedBytes", bytes, "itemCount", count));

        // Remote: conversation-service
        when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                new StorageUsageDto(bytes, count));

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
                    "SKILLS", Map.of("usedBytes", 0, "itemCount", 0)
            ));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                    new StorageUsageDto(0, 0));
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
                    "SKILLS", Map.of("usedBytes", 0, "itemCount", 0)
            ));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                    new StorageUsageDto(0, 0));
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
                    new StorageUsageDto(200, 5));
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
            String stepOutputsSql = sqls.stream()
                    .filter(s -> s.contains("s.storage_type = 'JSON'") && s.contains(":oid"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("STEP_OUTPUTS org SQL not captured"));
            String filesSql = sqls.stream()
                    .filter(s -> s.contains("s.storage_type IN ('BINARY','TEXT')") && s.contains(":oid"))
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

            assertThat(stepOutputsSql)
                    .as("regression: JSON rows without file source_type are STEP_OUTPUTS, matching StorageService.saveJsonWithContext")
                    .contains("s.storage_type = 'JSON'")
                    .contains("s.source_type IS NULL")
                    .contains("s.source_type NOT IN ('S3_FILE','CHAT_ATTACHMENT')");
            assertThat(filesSql)
                    .as("regression: saveBinary/saveText rows have BINARY/TEXT storage_type and may have null source_type, but are still FILES")
                    .contains("s.source_type IN ('S3_FILE','CHAT_ATTACHMENT')")
                    .contains("s.storage_type IN ('BINARY','TEXT')");

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
        @DisplayName("refreshOrgBreakdown throttle skips SQL but still refreshes the org quota gauge")
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
            when(entityManager.createNativeQuery("SELECT DISTINCT tenant_id FROM storage.tenant_storage_breakdown"))
                    .thenReturn(tenantQuery);
            when(tenantQuery.getResultList()).thenReturn(Arrays.asList("tenant-A", "tenant-B", "tenant-C"));

            // Stub local category queries
            Query mockQuery = mock(Query.class);
            when(mockQuery.setParameter(eq("tid"), anyString())).thenReturn(mockQuery);
            when(mockQuery.getSingleResult()).thenReturn(
                    new Object[]{BigInteger.valueOf(1000L), BigInteger.valueOf(5)});
            when(entityManager.createNativeQuery(argThat(sql ->
                    sql != null && !sql.equals("SELECT DISTINCT tenant_id FROM storage.tenant_storage_breakdown"))
            )).thenReturn(mockQuery);

            // Stub remote clients for all tenants
            when(agentClient.getAgentStorageUsage(anyString())).thenReturn(Map.of(
                    "AGENTS", Map.of("usedBytes", 1000, "itemCount", 5),
                    "SKILLS", Map.of("usedBytes", 500, "itemCount", 2)
            ));
            when(interfaceClient.getInterfaceStorageUsage(anyString())).thenReturn(
                    Map.of("usedBytes", 1000, "itemCount", 5));
            when(conversationStorageClient.getStorageUsage(anyString())).thenReturn(
                    new StorageUsageDto(1000, 5));
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
        @DisplayName("should handle empty tenant list gracefully")
        void shouldHandleEmptyTenantList() {
            Query tenantQuery = mock(Query.class);
            when(entityManager.createNativeQuery("SELECT DISTINCT tenant_id FROM storage.tenant_storage_breakdown"))
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
            when(entityManager.createNativeQuery("SELECT DISTINCT tenant_id FROM storage.tenant_storage_breakdown"))
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

        @Test
        @DisplayName("STEP_OUTPUTS query should mirror saveJson categorization")
        void shouldFilterStepOutputSourceTypes() {
            assertThat(StorageReconciliationQueries.STEP_OUTPUTS)
                    .contains("s.storage_type = 'JSON'")
                    .contains("s.source_type IS NULL")
                    .contains("s.source_type NOT IN ('S3_FILE','CHAT_ATTACHMENT')")
                    .contains("status = 'ACTIVE'")
                    .contains("storage.storage");
        }

        @Test
        @DisplayName("FILES query should mirror saveBinary/saveText and file source-type categorization")
        void shouldFilterFileSourceTypes() {
            assertThat(StorageReconciliationQueries.FILES)
                    .contains("S3_FILE", "CHAT_ATTACHMENT")
                    .contains("BINARY", "TEXT")
                    .contains("status = 'ACTIVE'")
                    .contains("storage.storage");
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
                    "SKILLS", Map.of("usedBytes", 0, "itemCount", 0)
            ));
            when(interfaceClient.getInterfaceStorageUsage(TENANT_ID)).thenReturn(
                    Map.of("usedBytes", 0, "itemCount", 0));
            when(conversationStorageClient.getStorageUsage(TENANT_ID)).thenReturn(
                    new StorageUsageDto(0, 0));
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
                    "SKILLS", Map.of("usedBytes", 100, "itemCount", 1)
            ));
            when(interfaceClient.getInterfaceStorageUsage(specialTenantId)).thenReturn(
                    Map.of("usedBytes", 500, "itemCount", 2));
            when(conversationStorageClient.getStorageUsage(specialTenantId)).thenReturn(
                    new StorageUsageDto(500, 2));
            when(dataSourceClient.getDataSourceStorageUsage(specialTenantId)).thenReturn(
                    Map.of("usedBytes", 500, "itemCount", 2));
            when(publicationClient.getPublicationStorageUsage(specialTenantId)).thenReturn(
                    Map.of("usedBytes", 500, "itemCount", 2));

            service.reconcileTenant(specialTenantId);

            verify(breakdownService, times(9)).setUsage(eq(specialTenantId), anyString(), anyLong(), anyInt());
            verify(quotaService).updateUsage(specialTenantId);
        }
    }
}
