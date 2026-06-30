package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.datasource.exception.ResourceNotFoundException;
import com.apimarketplace.datasource.services.DataSourceExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DataSourceExportController")
class DataSourceExportControllerTest {

    @Test
    @DisplayName("returns 404 when export service cannot resolve the datasource in caller scope")
    void exportDataReturns404ForScopedNotFound() throws Exception {
        DataSourceExportService exportService = mock(DataSourceExportService.class);
        TenantIdResolver tenantIdResolver = mock(TenantIdResolver.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DataSourceExportController controller = new DataSourceExportController(exportService, tenantIdResolver);

        when(tenantIdResolver.resolveTenantId(request, null)).thenReturn("tenant-B");
        when(tenantIdResolver.resolveOrgId(request)).thenReturn("org-B");
        when(request.getHeader("X-Organization-Role")).thenReturn("MEMBER");
        when(exportService.exportData(
                eq(42L),
                eq("tenant-B"),
                eq("org-B"),
                eq("MEMBER"),
                eq(DataSourceExportService.ExportFormat.JSON),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        )).thenThrow(new ResourceNotFoundException("DataSource", 42L));

        var response = controller.exportData(42L, request, null, "json", null, null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> body = (Map<String, String>) response.getBody();
        assertThat(body).containsEntry("error", "DataSource not found: 42");
    }
}
