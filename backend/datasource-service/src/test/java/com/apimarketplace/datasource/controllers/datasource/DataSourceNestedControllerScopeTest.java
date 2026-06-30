package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.datasource.domain.DataSourceNestedModels.NestedDataRequest;
import com.apimarketplace.datasource.services.DataSourceEnhancedService;
import com.apimarketplace.datasource.services.DataSourceNestedService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the tenant resolution on the nested-navigation endpoints.
 *
 * <p>Pre-fix: {@code /items/nested} and {@code /columns/nested} queried with the RAW caller
 * tenant. Items carry the parent DS owner's tenant (see {@code CrudExecutorService} + V333),
 * so an org teammate opening a jsonPath view always got 0 rows / 0 columns while the flat
 * items endpoint (which resolves the owner tenant via
 * {@link DataSourceEnhancedService#resolveAccessibleTenantId}) returned data.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceNestedController tenant scope")
class DataSourceNestedControllerScopeTest {

    private static final Long DS_ID = 38L;
    private static final String MEMBER_TENANT = "member-tenant";
    private static final String OWNER_TENANT = "owner-tenant";
    private static final String ORG_ID = "org-1";
    private static final String MEMBER_ROLE = "MEMBER";

    @Mock private DataSourceNestedService nestedService;
    @Mock private TenantIdResolver tenantIdResolver;
    @Mock private DataSourceEnhancedService enhancedService;
    @Mock private HttpServletRequest httpRequest;

    private DataSourceNestedController controller;

    @BeforeEach
    void setUp() {
        controller = new DataSourceNestedController(nestedService, tenantIdResolver, enhancedService);
        when(tenantIdResolver.resolveTenantId(httpRequest, null)).thenReturn(MEMBER_TENANT);
        when(tenantIdResolver.resolveOrgId(httpRequest)).thenReturn(ORG_ID);
        when(httpRequest.getHeader("X-Organization-Role")).thenReturn(MEMBER_ROLE);
        when(enhancedService.resolveAccessibleTenantId(DS_ID, MEMBER_TENANT, ORG_ID, MEMBER_ROLE))
                .thenReturn(OWNER_TENANT);
    }

    @Test
    @DisplayName("items/nested queries with the resolved owner tenant, not the raw caller tenant")
    void nestedItemsQueryUsesResolvedOwnerTenant() {
        controller.getNestedData(DS_ID, httpRequest, null, "payload", 1, 20, null, null);

        verify(nestedService).getNestedData(eq(DS_ID), eq(OWNER_TENANT), eq("payload"), any(NestedDataRequest.class));
        verify(nestedService, never()).getNestedData(any(), eq(MEMBER_TENANT), anyString(), any());
    }

    @Test
    @DisplayName("columns/nested queries with the resolved owner tenant, not the raw caller tenant")
    void nestedColumnsQueryUsesResolvedOwnerTenant() {
        controller.getNestedColumnDefinitions(DS_ID, httpRequest, null, "payload");

        verify(nestedService).getNestedColumnDefinitions(DS_ID, OWNER_TENANT, "payload");
        verify(nestedService, never()).getNestedColumnDefinitions(DS_ID, MEMBER_TENANT, "payload");
    }
}
