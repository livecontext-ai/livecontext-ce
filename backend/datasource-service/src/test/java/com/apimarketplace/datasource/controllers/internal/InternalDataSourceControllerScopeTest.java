package com.apimarketplace.datasource.controllers.internal;

import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.datasource.services.DataSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the org-scope tightening on the inter-service internal endpoints
 * exposed by {@link InternalDataSourceController}. Each test names the bug shape ({@code rejects*},
 * {@code accepts*}, {@code prefers*}) and exercises one branch of the org/tenant routing.
 *
 * <p>Pre-fix: {@code bulkFind}, {@code findByWorkflow}, {@code deleteByWorkflow},
 * {@code bulkInsertItems}, and {@code createFromSnapshot} either had NO scope check at all or
 * read the org from the request body. A caller could enumerate IDs / workflow UUIDs and pull
 * (or destroy) rows across orgs.
 */
@ExtendWith(MockitoExtension.class)
class InternalDataSourceControllerScopeTest {

    @Mock private DataSourceService dataSourceService;
    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private CrudExecutorService crudExecutorService;

    private InternalDataSourceController controller;

    @BeforeEach
    void setUp() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        controller = new InternalDataSourceController(dataSourceService, dataSourceRepository,
                dataSourceItemRepository, crudExecutorService, new ObjectMapper(),
                new com.apimarketplace.datasource.services.VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env)));
    }

    // ===== bulkFind =====

    @Test
    @DisplayName("bulk-find with X-Organization-ID routes to the org-strict finder")
    void bulkFindWithOrgIdHitsOrgStrictFinder() {
        List<Long> ids = List.of(1L, 2L, 3L);
        when(dataSourceRepository.findAllByIdsAndOrganizationId(ids, "org-A")).thenReturn(List.of());

        controller.bulkFind(ids, "tenant-1", "org-A");

        verify(dataSourceRepository).findAllByIdsAndOrganizationId(ids, "org-A");
        verify(dataSourceRepository, never()).findAllByIds(any());
        verify(dataSourceRepository, never()).findAllByIdsAndTenantId(any(), anyString());
    }

    @Test
    @DisplayName("bulk-find without orgId falls back to tenant-scoped finder (legacy back-compat + WARN)")
    void bulkFindWithoutOrgIdFallsBackToTenantScope() {
        List<Long> ids = List.of(7L);
        when(dataSourceRepository.findAllByIdsAndTenantId(ids, "tenant-1")).thenReturn(List.of());

        controller.bulkFind(ids, "tenant-1", null);

        verify(dataSourceRepository).findAllByIdsAndTenantId(ids, "tenant-1");
        verify(dataSourceRepository, never()).findAllByIds(any());
        verify(dataSourceRepository, never()).findAllByIdsAndOrganizationId(any(), anyString());
    }

    @Test
    @DisplayName("bulk-find without tenantId AND without orgId returns empty without hitting the repo")
    void bulkFindNoCallerContextReturnsEmpty() {
        ResponseEntity<List<DataSource>> response = controller.bulkFind(List.of(1L), null, null);

        assertThat(response.getBody()).isEmpty();
        verify(dataSourceRepository, never()).findAllByIds(any());
        verify(dataSourceRepository, never()).findAllByIdsAndOrganizationId(any(), anyString());
        verify(dataSourceRepository, never()).findAllByIdsAndTenantId(any(), anyString());
    }

    // ===== findByWorkflow =====

    @Test
    @DisplayName("findByWorkflow with orgId routes to org-strict finder")
    void findByWorkflowWithOrgIdHitsOrgStrictFinder() {
        UUID workflowId = UUID.randomUUID();
        when(dataSourceRepository.findBySourceWorkflowIdAndOrganizationId(workflowId, "org-A"))
                .thenReturn(List.of());

        controller.findByWorkflow(workflowId, "tenant-1", "org-A");

        verify(dataSourceRepository).findBySourceWorkflowIdAndOrganizationId(workflowId, "org-A");
        verify(dataSourceRepository, never()).findBySourceWorkflowId(any());
    }

    @Test
    @DisplayName("findByWorkflow without orgId falls back to tenant scope")
    void findByWorkflowWithoutOrgIdFallsBackToTenant() {
        UUID workflowId = UUID.randomUUID();
        when(dataSourceRepository.findBySourceWorkflowIdAndTenantId(workflowId, "tenant-1"))
                .thenReturn(List.of());

        controller.findByWorkflow(workflowId, "tenant-1", null);

        verify(dataSourceRepository).findBySourceWorkflowIdAndTenantId(workflowId, "tenant-1");
        verify(dataSourceRepository, never()).findBySourceWorkflowId(any());
    }

    // ===== deleteByWorkflow (MUTATING) =====

    @Test
    @DisplayName("delete by-workflow with orgId only deletes rows in that org - cross-org request becomes no-op")
    void deleteByWorkflowWithOrgIdRoutesToOrgStrictCascade() {
        UUID workflowId = UUID.randomUUID();
        when(dataSourceRepository.deleteBySourceWorkflowIdAndOrganizationId(workflowId, "org-A"))
                .thenReturn(0); // cross-org workflow id → no rows deleted

        controller.deleteByWorkflow(workflowId, "tenant-1", "org-A");

        verify(dataSourceRepository).deleteBySourceWorkflowIdAndOrganizationId(workflowId, "org-A");
        verify(dataSourceRepository, never()).deleteBySourceWorkflowId(any());
        verify(dataSourceRepository, never()).deleteBySourceWorkflowIdAndTenantId(any(), anyString());
    }

    @Test
    @DisplayName("delete by-workflow without orgId falls back to tenant cascade")
    void deleteByWorkflowWithoutOrgIdFallsBackToTenantCascade() {
        UUID workflowId = UUID.randomUUID();
        when(dataSourceRepository.deleteBySourceWorkflowIdAndTenantId(workflowId, "tenant-1"))
                .thenReturn(2);

        controller.deleteByWorkflow(workflowId, "tenant-1", null);

        verify(dataSourceRepository).deleteBySourceWorkflowIdAndTenantId(workflowId, "tenant-1");
        verify(dataSourceRepository, never()).deleteBySourceWorkflowId(any());
    }

    // ===== bulkInsertItems (MUTATING) =====

    @Test
    @DisplayName("bulk-insert with orgId rejects when target DS is not in caller's workspace (404, no writes)")
    void bulkInsertRejectsCrossOrgDatasource() {
        long dsId = 42L;
        when(dataSourceService.findByIdAndOrganizationIdStrict((int) dsId, "org-X"))
                .thenReturn(Optional.empty());

        ResponseEntity<Integer> response = controller.bulkInsertItems(dsId,
                List.of(Map.of("data", Map.of("x", 1))), "tenant-1", "org-X");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(dataSourceItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("bulk-insert with orgId accepts when target DS belongs to caller's workspace")
    void bulkInsertAcceptsWhenDatasourceIsInScope() {
        long dsId = 42L;
        DataSource ds = new DataSource(dsId, "tenant-1", "X", null, DataSourceType.INLINE,
                Map.of(), DataSourceStatus.ACTIVE, null, null, "tenant-1",
                List.of(), null, null, null, null, "org-A");
        when(dataSourceService.findByIdAndOrganizationIdStrict((int) dsId, "org-A"))
                .thenReturn(Optional.of(ds));

        ResponseEntity<Integer> response = controller.bulkInsertItems(dsId,
                List.of(Map.of("data", Map.of("x", 1)), Map.of("data", Map.of("y", 2))),
                "tenant-1", "org-A");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(2);
        verify(dataSourceItemRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    @DisplayName("bulk-insert without orgId still requires tenant ownership of the target DS (no-org path is NOT a privilege escalation)")
    void bulkInsertWithoutOrgIdRequiresTenantOwnership() {
        long dsId = 42L;
        // Tenant does NOT own the DS → reject; rejects the privilege-inversion where
        // dropping the header would grant write access to a foreign DS.
        when(dataSourceService.findByIdAndTenantId((int) dsId, "tenant-1"))
                .thenReturn(Optional.empty());

        ResponseEntity<Integer> response = controller.bulkInsertItems(dsId,
                List.of(Map.of("data", Map.of("x", 1))), "tenant-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(dataSourceService, never()).findByIdAndOrganizationIdStrict(any(Integer.class), anyString());
        verify(dataSourceItemRepository, never()).save(any());
    }

    @Test
    @DisplayName("bulk-insert without orgId accepts when caller's tenant owns the target DS")
    void bulkInsertWithoutOrgIdAcceptsWhenTenantOwnsDs() {
        long dsId = 42L;
        DataSource ds = new DataSource(dsId, "tenant-1", "X", null, DataSourceType.INLINE,
                Map.of(), DataSourceStatus.ACTIVE, null, null, "tenant-1",
                List.of(), null, null, null, null, "org-A");
        when(dataSourceService.findByIdAndTenantId((int) dsId, "tenant-1"))
                .thenReturn(Optional.of(ds));

        ResponseEntity<Integer> response = controller.bulkInsertItems(dsId,
                List.of(Map.of("data", Map.of("x", 1))), "tenant-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dataSourceItemRepository).save(any());
    }

    @Test
    @DisplayName("bulk-insert stamps rows with the DS OWNER's tenant, not the caller's (empty-grid regression)")
    void bulkInsertStampsOwnerTenantNotCallerTenant() {
        // Regression for prod datasource 38 "processed_emails": rows bulk-inserted by an
        // org teammate were stamped with the CALLER's tenant; every owner-tenant-scoped
        // read (UI grid, export, column ops) then returned 0 rows. Items must carry the
        // parent DS owner's tenant (same invariant as CrudExecutorService + V333).
        long dsId = 38L;
        DataSource ds = new DataSource(dsId, "owner-tenant", "processed_emails", null, DataSourceType.INLINE,
                Map.of(), DataSourceStatus.ACTIVE, null, null, "owner-tenant",
                List.of(), null, null, null, null, "org-A");
        when(dataSourceService.findByIdAndOrganizationIdStrict((int) dsId, "org-A"))
                .thenReturn(Optional.of(ds));

        controller.bulkInsertItems(dsId,
                List.of(Map.of("data", Map.of("label", "Finance"))), "caller-tenant", "org-A");

        ArgumentCaptor<com.apimarketplace.datasource.domain.DataSourceModels.DataSourceItem> captor =
                ArgumentCaptor.forClass(com.apimarketplace.datasource.domain.DataSourceModels.DataSourceItem.class);
        verify(dataSourceItemRepository).save(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("owner-tenant");
    }

    // ===== createFromSnapshot - header wins over body =====

    @Test
    @DisplayName("create-from-snapshot prefers X-Organization-ID header over snapshot body organizationId (forged body neutered)")
    void createFromSnapshotHeaderWinsOverBody() {
        Map<String, Object> snapshot = Map.of(
                "name", "X",
                "sourceType", "INLINE",
                "organizationId", "org-FORGED");
        when(dataSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.createFromSnapshot(snapshot, "tenant-1", "org-AUTH");

        ArgumentCaptor<DataSource> captor = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceRepository).save(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo("org-AUTH");
    }

    @Test
    @DisplayName("create-from-snapshot falls back to snapshot body organizationId when no header is present")
    void createFromSnapshotFallsBackToBodyOrgIdWhenNoHeader() {
        Map<String, Object> snapshot = Map.of(
                "name", "X",
                "sourceType", "INLINE",
                "organizationId", "org-FROM-BODY");
        when(dataSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        controller.createFromSnapshot(snapshot, "tenant-1", null);

        ArgumentCaptor<DataSource> captor = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceRepository).save(captor.capture());
        assertThat(captor.getValue().organizationId()).isEqualTo("org-FROM-BODY");
    }

    @Test
    @DisplayName("create-from-snapshot rejects with 400 when neither header nor body carries an organizationId (post-V263 NOT NULL fail-fast)")
    void createFromSnapshotRejectsWhenNoOrgAnywhere() {
        Map<String, Object> snapshot = Map.of("name", "X", "sourceType", "INLINE");

        ResponseEntity<DataSource> response = controller.createFromSnapshot(snapshot, "tenant-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(dataSourceRepository, never()).save(any());
    }
}
