package com.apimarketplace.datasource.services;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.DataSourceItemRow;
import com.apimarketplace.datasource.exception.ResourceNotFoundException;
import com.apimarketplace.datasource.persistence.DataSourceEnhancedRepositories;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the org-aware export path. Pre-fix, {@code DataSourceExportService}
 * filtered by the caller's {@code tenant_id} alone - an org teammate whose tenant differs from
 * the datasource owner's got a 404 even though they have legitimate workspace access. Now the
 * service routes the caller's (orgId, orgRole) through
 * {@code DataSourceEnhancedService.resolveAccessibleTenantId} so the effective tenantId swaps
 * to the owner when the caller has org access.
 */
@ExtendWith(MockitoExtension.class)
class DataSourceExportServiceOrgScopeTest {

    @Mock private DataSourceEnhancedRepositories repositories;
    @Mock private DataSourceEnhancedService enhancedService;

    private DataSourceExportService service;

    @BeforeEach
    void setUp() {
        service = new DataSourceExportService(repositories, enhancedService, new ObjectMapper());
    }

    @Test
    @DisplayName("Org teammate exports their admin's datasource - resolveAccessibleTenantId swap delivers rows")
    void teammateGetsRowsViaTenantSwap() throws IOException {
        long dsId = 42L;
        // Caller = tenant-B (org-A member), owner = tenant-A (org-A admin). The resolver
        // swaps to the owner's tenant under org access.
        when(enhancedService.resolveAccessibleTenantId(dsId, "tenant-B", "org-A", "MEMBER"))
                .thenReturn("tenant-A");
        when(repositories.dataSourceExists(dsId, "tenant-A")).thenReturn(true);
        when(repositories.findItemsWithPagination(eq(dsId), eq("tenant-A"), any()))
                .thenReturn(List.of(itemRow(1L, Map.of("x", 1))))
                .thenReturn(List.of());

        DataSourceExportService.ExportResult result = service.exportData(
                dsId, "tenant-B", "org-A", "MEMBER",
                DataSourceExportService.ExportFormat.JSON,
                null, null, null, null, null, null);

        assertThat(result).isNotNull();
        verify(repositories).dataSourceExists(dsId, "tenant-A");
        verify(repositories, never()).dataSourceExists(eq(dsId), eq("tenant-B"));
    }

    @Test
    @DisplayName("Cross-org request resolves back to caller's tenant (no swap) and 404s if no match")
    void crossOrgRequestNoSwapReturns404() {
        long dsId = 42L;
        // Resolver returns caller tenant unchanged when there's no org access path.
        when(enhancedService.resolveAccessibleTenantId(dsId, "tenant-X", "org-WRONG", "MEMBER"))
                .thenReturn("tenant-X");
        when(repositories.dataSourceExists(dsId, "tenant-X")).thenReturn(false);

        assertThatThrownBy(() -> service.exportData(dsId, "tenant-X", "org-WRONG", "MEMBER",
                DataSourceExportService.ExportFormat.CSV,
                null, null, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repositories, never()).findItemsWithPagination(anyLong(), anyString(), any());
        verify(repositories, never()).findByIds(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("Backward-compat 9-arg overload delegates to 11-arg with null org context")
    void backwardCompat9ArgOverloadDelegatesWithNullOrg() throws IOException {
        long dsId = 42L;
        when(enhancedService.resolveAccessibleTenantId(dsId, "tenant-A", null, null))
                .thenReturn("tenant-A");
        when(repositories.dataSourceExists(dsId, "tenant-A")).thenReturn(true);
        when(repositories.findItemsWithPagination(eq(dsId), eq("tenant-A"), any()))
                .thenReturn(List.of());

        service.exportData(dsId, "tenant-A",
                DataSourceExportService.ExportFormat.JSON,
                null, null, null, null, null, null);

        // Delegated through the 11-arg path → resolver invoked with null org context.
        verify(enhancedService).resolveAccessibleTenantId(dsId, "tenant-A", null, null);
    }

    private static DataSourceItemRow itemRow(long id, Map<String, Object> data) {
        return new DataSourceItemRow(id, 42L, "tenant", data, 0, Instant.EPOCH, Instant.EPOCH);
    }
}
