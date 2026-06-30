package com.apimarketplace.datasource.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceItem;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: the paginated fetch + count helpers must delegate to the SQL-bounded repository
 * methods, NOT to the legacy {@code findByDataSourceIdAndTenantId} (full-table load) followed by
 * a Java {@code subList}/{@code size}. The pre-fix implementation made every call do a
 * full-table scan in datasource-service, hiding the unbounded heap risk that the orchestrator
 * cap was supposed to remove.
 *
 * <p>If a future refactor reverts to {@code findByDataSourceIdAndTenantId(...).subList(...)},
 * {@link #paginatedFetchHitsSqlLimitOffsetAndNotFullTableLoad} fails because we explicitly
 * {@code verify(repo, never()).findByDataSourceIdAndTenantId(...)}.
 */
@ExtendWith(MockitoExtension.class)
class DataSourceServicePaginationDelegationTest {

    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private EntitlementGuard entitlementGuard;

    private DataSourceService service;

    @BeforeEach
    void setUp() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        service = new DataSourceService(dataSourceRepository, dataSourceItemRepository,
                breakdownService, new ObjectMapper(), orgAccessGuard, entitlementGuard,
                new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env)),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                org.mockito.Mockito.mock(com.apimarketplace.publication.client.PublicationClient.class));
    }

    @Test
    @DisplayName("Paginated fetch pushes LIMIT/OFFSET to SQL - no full-table load + subList")
    void paginatedFetchHitsSqlLimitOffsetAndNotFullTableLoad() {
        when(dataSourceItemRepository.findByDataSourceIdAndTenantIdPaginated(eq(42L), eq("tenantA"), eq(100), eq(50)))
                .thenReturn(List.of());

        List<DataSourceItem> result = service.getDataSourceItemsByTenantAndDataSourcePaginated(
                42, "tenantA", 100, 50);

        assertThat(result).isEmpty();
        verify(dataSourceItemRepository).findByDataSourceIdAndTenantIdPaginated(42L, "tenantA", 100, 50);
        verify(dataSourceItemRepository, never()).findByDataSourceIdAndTenantId(anyLong(), anyString());
    }

    @Test
    @DisplayName("Count uses SELECT COUNT(*) - no full-table load + size()")
    void countHitsAggregateAndNotFullTableLoad() {
        when(dataSourceItemRepository.countByDataSourceIdAndTenantId(42L, "tenantA")).thenReturn(12_345L);

        int count = service.getDataSourceItemsCount(42, "tenantA");

        assertThat(count).isEqualTo(12_345);
        verify(dataSourceItemRepository).countByDataSourceIdAndTenantId(42L, "tenantA");
        verify(dataSourceItemRepository, never()).findByDataSourceIdAndTenantId(anyLong(), anyString());
    }

    @Test
    @DisplayName("Count clamps long → int at Integer.MAX_VALUE so the int-typed signature stays safe")
    void countClampsLongOverflowToIntMax() {
        when(dataSourceItemRepository.countByDataSourceIdAndTenantId(anyLong(), anyString()))
                .thenReturn((long) Integer.MAX_VALUE + 7L);

        int count = service.getDataSourceItemsCount(42, "tenantA");

        assertThat(count).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("Invalid offset/limit short-circuits without hitting the repository")
    void negativeOrZeroBoundsShortCircuit() {
        assertThat(service.getDataSourceItemsByTenantAndDataSourcePaginated(42, "tenantA", -1, 10)).isEmpty();
        assertThat(service.getDataSourceItemsByTenantAndDataSourcePaginated(42, "tenantA", 0, 0)).isEmpty();
        verify(dataSourceItemRepository, never()).findByDataSourceIdAndTenantIdPaginated(
                anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Null dataSourceId or tenantId returns empty without hitting the repository")
    void nullArgsShortCircuit() {
        assertThat(service.getDataSourceItemsByTenantAndDataSourcePaginated(null, "tenantA", 0, 10)).isEmpty();
        assertThat(service.getDataSourceItemsByTenantAndDataSourcePaginated(42, null, 0, 10)).isEmpty();
        assertThat(service.getDataSourceItemsCount(null, "tenantA")).isZero();
        assertThat(service.getDataSourceItemsCount(42, null)).isZero();
        verify(dataSourceItemRepository, never()).findByDataSourceIdAndTenantIdPaginated(
                anyLong(), anyString(), anyInt(), anyInt());
        verify(dataSourceItemRepository, never()).countByDataSourceIdAndTenantId(anyLong(), anyString());
    }

    // ===== Org-strict scope =====

    @Test
    @DisplayName("Org-strict fetch routes to the JOIN-on-parent path (teammates of the org see items even with different tenant_id)")
    void orgStrictFetchUsesJoinPathNotTenantPath() {
        when(dataSourceItemRepository.findByDataSourceIdInOrgScopePaginated(
                eq(42L), eq("orgX"), eq(0), eq(50))).thenReturn(List.of());

        service.getDataSourceItemsByTenantAndDataSourcePaginated(42, "tenantA", "orgX", 0, 50);

        verify(dataSourceItemRepository).findByDataSourceIdInOrgScopePaginated(42L, "orgX", 0, 50);
        verify(dataSourceItemRepository, never()).findByDataSourceIdAndTenantIdPaginated(
                anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Org-strict count routes to COUNT(*) JOIN path, not the tenant-only aggregate")
    void orgStrictCountUsesJoinPathNotTenantPath() {
        when(dataSourceItemRepository.countByDataSourceIdInOrgScope(42L, "orgX")).thenReturn(7L);

        int count = service.getDataSourceItemsCount(42, "tenantA", "orgX");

        assertThat(count).isEqualTo(7);
        verify(dataSourceItemRepository).countByDataSourceIdInOrgScope(42L, "orgX");
        verify(dataSourceItemRepository, never()).countByDataSourceIdAndTenantId(anyLong(), anyString());
    }

    @Test
    @DisplayName("Blank organizationId falls back to tenant scope (treated as missing)")
    void blankOrgIdFallsBackToTenantScope() {
        when(dataSourceItemRepository.findByDataSourceIdAndTenantIdPaginated(
                eq(42L), eq("tenantA"), eq(0), eq(10))).thenReturn(List.of());

        service.getDataSourceItemsByTenantAndDataSourcePaginated(42, "tenantA", "  ", 0, 10);

        verify(dataSourceItemRepository).findByDataSourceIdAndTenantIdPaginated(42L, "tenantA", 0, 10);
        verify(dataSourceItemRepository, never()).findByDataSourceIdInOrgScopePaginated(
                anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("Blank organizationId on count also falls back to tenant scope (symmetry with fetch path)")
    void blankOrgIdOnCountFallsBackToTenantScope() {
        when(dataSourceItemRepository.countByDataSourceIdAndTenantId(42L, "tenantA")).thenReturn(7L);

        service.getDataSourceItemsCount(42, "tenantA", "  ");

        verify(dataSourceItemRepository).countByDataSourceIdAndTenantId(42L, "tenantA");
        verify(dataSourceItemRepository, never()).countByDataSourceIdInOrgScope(anyLong(), anyString());
    }

    @Test
    @DisplayName("Org-strict fetch with null tenant works - orgId alone is enough to scope")
    void orgStrictFetchWithoutTenantStillWorks() {
        when(dataSourceItemRepository.findByDataSourceIdInOrgScopePaginated(
                eq(42L), eq("orgX"), eq(0), eq(10))).thenReturn(List.of());

        service.getDataSourceItemsByTenantAndDataSourcePaginated(42, null, "orgX", 0, 10);

        verify(dataSourceItemRepository).findByDataSourceIdInOrgScopePaginated(42L, "orgX", 0, 10);
    }

    @Test
    @DisplayName("Backward-compat 4-arg overload delegates to the 5-arg with orgId=null")
    void backwardCompatOverloadPreservesTenantBehaviour() {
        when(dataSourceItemRepository.findByDataSourceIdAndTenantIdPaginated(
                eq(42L), eq("tenantA"), eq(0), eq(10))).thenReturn(List.of());

        service.getDataSourceItemsByTenantAndDataSourcePaginated(42, "tenantA", 0, 10);

        verify(dataSourceItemRepository).findByDataSourceIdAndTenantIdPaginated(42L, "tenantA", 0, 10);
        verify(dataSourceItemRepository, never()).findByDataSourceIdInOrgScopePaginated(
                anyLong(), anyString(), anyInt(), anyInt());
    }
}
