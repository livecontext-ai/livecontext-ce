package com.apimarketplace.datasource.services;

import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.ColumnDefinition;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.DataSourceItemRow;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.PaginationRequest;
import com.apimarketplace.datasource.domain.DataSourceModels;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceStatus;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.events.DatasourceRowEventPublisher;
import com.apimarketplace.datasource.persistence.DataSourceEnhancedRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceEnhancedService organization scope")
class DataSourceEnhancedServiceOrgScopeTest {

    private static final Long DATA_SOURCE_ID = 42L;
    private static final String OWNER_TENANT = "owner-tenant";
    private static final String MEMBER_TENANT = "member-tenant";
    private static final String ORG_ID = "org-1";
    private static final String MEMBER_ROLE = "MEMBER";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private DataSourceEnhancedRepositories repositories;
    @Mock private VectorRepository vectorRepository;
    @Mock private DataSourceService dataSourceService;
    @Mock private DatasourceRowEventPublisher rowEventPublisher;

    private DataSourceEnhancedService service;

    @BeforeEach
    void setUp() {
        service = new DataSourceEnhancedService(repositories, vectorRepository, dataSourceService, rowEventPublisher, ceVectorGate(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("Accepted org member reads owner table items using owner tenant")
    void acceptedOrgMemberReadsOwnerTableItemsUsingOwnerTenant() {
        PaginationRequest request = new PaginationRequest(0, 10, 10, null, List.of(), Map.of(), null);
        DataSourceItemRow row = row(100L, Map.of("name", "Shared row"));
        givenMemberCanAccessSharedDatasource();
        when(repositories.countItemsWithFilters(DATA_SOURCE_ID, OWNER_TENANT, request)).thenReturn(1);
        when(repositories.findItemsWithOffset(DATA_SOURCE_ID, OWNER_TENANT, request, 0)).thenReturn(List.of(row));

        var response = service.getItemsWithPagination(
            DATA_SOURCE_ID, MEMBER_TENANT, ORG_ID, MEMBER_ROLE, request);

        assertThat(response.rowData()).containsExactly(row);
        assertThat(response.rowCount()).isEqualTo(1);
        verify(repositories).findItemsWithOffset(DATA_SOURCE_ID, OWNER_TENANT, request, 0);
        verify(repositories, never()).countItemsWithFilters(DATA_SOURCE_ID, MEMBER_TENANT, request);
    }

    @Test
    @DisplayName("Accepted org member adds rows to owner table tenant scope")
    void acceptedOrgMemberAddsRowsToOwnerTableTenantScope() {
        Map<String, Object> data = Map.of("name", "Created by member");
        DataSourceItemRow row = row(101L, data);
        givenMemberCanAccessSharedDatasource();
        when(repositories.addItem(DATA_SOURCE_ID, OWNER_TENANT, data, 1)).thenReturn(row);

        DataSourceItemRow created = service.addItem(
            DATA_SOURCE_ID, MEMBER_TENANT, ORG_ID, MEMBER_ROLE, data, 1);

        assertThat(created).isEqualTo(row);
        verify(repositories).addItem(DATA_SOURCE_ID, OWNER_TENANT, data, 1);
        verify(rowEventPublisher).publishCreated(
            eq(DATA_SOURCE_ID),
            eq(101L),
            eq(OWNER_TENANT),
            // Phase 5 - orgId carried through to the dispatch DTO
            any(),
            anyMap());
    }

    @Test
    @DisplayName("Accepted org member adds columns to owner table tenant scope")
    void acceptedOrgMemberAddsColumnsToOwnerTableTenantScope() {
        givenMemberCanAccessSharedDatasource();
        when(repositories.getColumnDefinitions(DATA_SOURCE_ID, OWNER_TENANT)).thenReturn(List.of());
        when(repositories.addColumn(DATA_SOURCE_ID, OWNER_TENANT, "status", "text", "scalar", Map.of(), "todo"))
            .thenReturn(true);

        boolean added = service.addColumn(
            DATA_SOURCE_ID,
            MEMBER_TENANT,
            ORG_ID,
            MEMBER_ROLE,
            "status",
            "text",
            "scalar",
            Map.of(),
            "todo");

        assertThat(added).isTrue();
        verify(repositories).addColumn(DATA_SOURCE_ID, OWNER_TENANT, "status", "text", "scalar", Map.of(), "todo");
        verify(repositories, never()).addColumn(DATA_SOURCE_ID, MEMBER_TENANT, "status", "text", "scalar", Map.of(), "todo");
    }

    @Test
    @DisplayName("Read-only org member reads owner table rows but cannot add rows")
    void readOnlyOrgMemberReadsRowsButCannotWriteRows() {
        PaginationRequest request = new PaginationRequest(0, 10, 10, null, List.of(), Map.of(), null);
        DataSourceItemRow row = row(102L, Map.of("name", "Readable row"));
        when(repositories.dataSourceExists(DATA_SOURCE_ID, MEMBER_TENANT)).thenReturn(false);
        when(dataSourceService.getDataSource(DATA_SOURCE_ID)).thenReturn(Optional.of(sharedDatasource()));
        when(dataSourceService.canAccessViaOrg(ORG_ID, MEMBER_TENANT, String.valueOf(DATA_SOURCE_ID), MEMBER_ROLE))
            .thenReturn(true);
        when(dataSourceService.canWriteViaOrg(ORG_ID, MEMBER_TENANT, String.valueOf(DATA_SOURCE_ID), MEMBER_ROLE))
            .thenReturn(false);
        when(repositories.dataSourceExists(DATA_SOURCE_ID, OWNER_TENANT)).thenReturn(true);
        when(repositories.countItemsWithFilters(DATA_SOURCE_ID, OWNER_TENANT, request)).thenReturn(1);
        when(repositories.findItemsWithOffset(DATA_SOURCE_ID, OWNER_TENANT, request, 0)).thenReturn(List.of(row));

        var response = service.getItemsWithPagination(
            DATA_SOURCE_ID, MEMBER_TENANT, ORG_ID, MEMBER_ROLE, request);

        assertThat(response.rowData()).containsExactly(row);
        assertThatThrownBy(() -> service.addItem(
            DATA_SOURCE_ID, MEMBER_TENANT, ORG_ID, MEMBER_ROLE, Map.of("name", "Blocked"), 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("access denied");
        verify(repositories, never()).addItem(eq(DATA_SOURCE_ID), any(), anyMap(), any());
    }

    @Test
    @DisplayName("Mismatched org claim keeps strict caller tenant and denies table access")
    void mismatchedOrgClaimKeepsStrictCallerTenantAndDeniesTableAccess() {
        when(repositories.dataSourceExists(DATA_SOURCE_ID, MEMBER_TENANT)).thenReturn(false);
        when(dataSourceService.getDataSource(DATA_SOURCE_ID)).thenReturn(Optional.of(sharedDatasource()));

        assertThatThrownBy(() -> service.getColumnDefinitions(
            DATA_SOURCE_ID, MEMBER_TENANT, "org-2", MEMBER_ROLE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("access denied");

        verify(dataSourceService, never()).canAccessViaOrg(ORG_ID, MEMBER_TENANT, String.valueOf(DATA_SOURCE_ID), MEMBER_ROLE);
        verify(repositories, never()).getColumnDefinitions(DATA_SOURCE_ID, OWNER_TENANT);
    }

    private void givenMemberCanAccessSharedDatasource() {
        org.mockito.Mockito.lenient()
            .when(repositories.dataSourceExists(DATA_SOURCE_ID, MEMBER_TENANT))
            .thenReturn(false);
        when(dataSourceService.getDataSource(DATA_SOURCE_ID)).thenReturn(Optional.of(sharedDatasource()));
        org.mockito.Mockito.lenient()
            .when(dataSourceService.canAccessViaOrg(ORG_ID, MEMBER_TENANT, String.valueOf(DATA_SOURCE_ID), MEMBER_ROLE))
            .thenReturn(true);
        org.mockito.Mockito.lenient()
            .when(dataSourceService.canWriteViaOrg(ORG_ID, MEMBER_TENANT, String.valueOf(DATA_SOURCE_ID), MEMBER_ROLE))
            .thenReturn(true);
        org.mockito.Mockito.lenient()
            .when(repositories.dataSourceExists(DATA_SOURCE_ID, OWNER_TENANT))
            .thenReturn(true);
    }

    private DataSourceModels.DataSource sharedDatasource() {
        return new DataSourceModels.DataSource(
            DATA_SOURCE_ID,
            OWNER_TENANT,
            "Org Table",
            "Shared table",
            DataSourceType.INLINE,
            Map.of(),
            DataSourceStatus.ACTIVE,
            NOW,
            NOW,
            OWNER_TENANT,
            List.of(),
            Map.of("name", new DataSourceModels.ColumnMappingSpec(
                "name", ColumnType.TEXT, ColumnStructure.SCALAR, Map.of(), Map.of())),
            null,
            null,
            null,
            ORG_ID);
    }

    private DataSourceItemRow row(Long id, Map<String, Object> data) {
        return new DataSourceItemRow(id, DATA_SOURCE_ID, OWNER_TENANT, data, 1, NOW, NOW);
    }

    private static com.apimarketplace.datasource.services.VectorFeatureGate ceVectorGate() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        return new com.apimarketplace.datasource.services.VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env));
    }
}
