package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.datasource.services.DataSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The card list paints a mini-table preview from a {@code sampleRows} map that the
 * service computes and the controller must forward verbatim in the {@code /paged}
 * body - alongside the existing {@code rowCounts}. This pins the controller wiring
 * offline (the live JSON shape is additionally covered by the CE e2e spec).
 */
@DisplayName("DataSourceCrudController GET /paged - sampleRows passthrough")
class DataSourceCrudControllerPagedSampleRowsTest {

    @Test
    @DisplayName("paged body forwards the service's sampleRows map (and still carries rowCounts)")
    void pagedBodyIncludesSampleRows() {
        DataSourceService service = mock(DataSourceService.class);
        TenantIdResolver tenantIdResolver = mock(TenantIdResolver.class);
        OrgAccessGuard orgAccessGuard = mock(OrgAccessGuard.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        DataSourceCrudController controller =
                new DataSourceCrudController(service, tenantIdResolver, new ObjectMapper(), orgAccessGuard);

        when(tenantIdResolver.resolveTenantId(request, null)).thenReturn("tenant-A");

        Map<Long, List<Map<String, Object>>> sample = Map.of(
                10L, List.of(Map.of("title", "Alice"), Map.of("title", "Bob")));
        Map<Long, Long> counts = Map.of(10L, 2L);
        Map<String, Map<String, String>> publicationStatuses = Map.of(
                "10", Map.of("status", "ACTIVE"));
        DataSourceService.DataSourcePage page = new DataSourceService.DataSourcePage(
                List.of(), 0, 0, 25, counts, sample, publicationStatuses);
        when(service.getDataSourcesPaged(eq("tenant-A"), isNull(), isNull(), isNull(), eq(0), eq(25),
                isNull(), isNull()))
                .thenReturn(page);

        var response = controller.getAllDataSourcesPaged(request, null, null, null, null, 0, 25, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        // The preview sample is forwarded verbatim…
        assertThat(body.get("sampleRows")).isEqualTo(sample);
        // …and the sibling rowCounts map is untouched (no regression).
        assertThat(body.get("rowCounts")).isEqualTo(counts);
        // …and the new per-page publication badge map is forwarded verbatim.
        assertThat(body.get("publicationStatuses")).isEqualTo(publicationStatuses);
    }
}
