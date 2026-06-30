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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The project Tables tab renders the SAME mini-table card as {@code /app/tables}, so the
 * {@code /by-project/{id}/details} internal endpoint must return the project's datasources WITH
 * the {@code rowCounts} + {@code sampleRows} preview maps (computed by the two batch queries over
 * the project's ids). This pins the wiring + the org-vs-tenant scope routing offline.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalDataSourceController GET /by-project/{id}/details")
class InternalDataSourceControllerProjectPreviewTest {

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
                new com.apimarketplace.datasource.services.VectorFeatureGate(
                        new com.apimarketplace.common.web.AppEditionProvider(env)));
    }

    private DataSource ds(long id) {
        return new DataSource(id, "tenant-1", "Table " + id, null, DataSourceType.INLINE,
                Map.of(), DataSourceStatus.ACTIVE, null, null, "tenant-1",
                List.of(), null, null, null, null, "org-A");
    }

    @Test
    @DisplayName("with X-Organization-ID: org-scoped items + forwards rowCounts & sampleRows from the batch queries")
    void detailsWithOrgReturnsItemsAndPreview() {
        UUID projectId = UUID.randomUUID();
        List<DataSource> items = List.of(ds(10L), ds(11L));
        when(dataSourceRepository.findByProjectIdAndOrganizationId(projectId, "org-A")).thenReturn(items);
        Map<Long, Long> counts = Map.of(10L, 2L, 11L, 0L);
        Map<Long, List<Map<String, Object>>> sample =
                Map.of(10L, List.of(Map.of("title", "Alice"), Map.of("title", "Bob")));
        when(dataSourceItemRepository.countByDataSourceIds(List.of(10L, 11L))).thenReturn(counts);
        when(dataSourceItemRepository.sampleRowsByDataSourceIds(eq(List.of(10L, 11L)), anyInt())).thenReturn(sample);

        ResponseEntity<Map<String, Object>> response =
                controller.findByProjectWithPreview(projectId, "tenant-1", "org-A");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("items")).isEqualTo(items);
        assertThat(body.get("rowCounts")).isEqualTo(counts);
        assertThat(body.get("sampleRows")).isEqualTo(sample);
        // org present → tenant-scoped finder must NOT be used (no cross-org bleed).
        verify(dataSourceRepository, never()).findByProjectIdAndTenantId(projectId, "tenant-1");
    }

    @Test
    @DisplayName("without orgId: falls back to the tenant-scoped finder (legacy back-compat)")
    void detailsWithoutOrgFallsBackToTenant() {
        UUID projectId = UUID.randomUUID();
        when(dataSourceRepository.findByProjectIdAndTenantId(projectId, "tenant-1")).thenReturn(List.of());
        when(dataSourceItemRepository.countByDataSourceIds(List.of())).thenReturn(Map.of());
        when(dataSourceItemRepository.sampleRowsByDataSourceIds(eq(List.of()), anyInt())).thenReturn(Map.of());

        ResponseEntity<Map<String, Object>> response =
                controller.findByProjectWithPreview(projectId, "tenant-1", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(dataSourceRepository).findByProjectIdAndTenantId(projectId, "tenant-1");
        verify(dataSourceRepository, never()).findByProjectIdAndOrganizationId(projectId, null);
    }
}
