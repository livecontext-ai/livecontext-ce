package com.apimarketplace.datasource.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The card list shows "N rows" per table, so the paged endpoint attaches a
 * per-datasource row count. This MUST stay a single batched {@code GROUP BY}
 * over the current page's ids - never one COUNT per row (N+1) - otherwise a
 * 25-card page fires 25 extra queries against {@code data_source_items}.
 *
 * <p>If a future refactor reverts to per-id counting, the
 * {@code never()}/{@code times(1)} verifications here fail.
 */
@ExtendWith(MockitoExtension.class)
class DataSourceServicePagedRowCountsTest {

    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private EntitlementGuard entitlementGuard;

    private DataSourceService service;

    private static final String ORG = "org-1";
    private static final String USER = "user-1";

    @BeforeEach
    void setUp() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        service = new DataSourceService(dataSourceRepository, dataSourceItemRepository,
                breakdownService, new ObjectMapper(), orgAccessGuard, entitlementGuard,
                new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env)),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                org.mockito.Mockito.mock(com.apimarketplace.publication.client.PublicationClient.class));
        // Pass-through access filter: the page keeps every datasource the repo returns.
        when(orgAccessGuard.filterAccessible(anyList(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static DataSource ds(long id) {
        return new DataSource(id, "tenant", "table-" + id, "desc", DataSourceType.INLINE,
                Map.of(), null, null, null, USER, null, null, null, null, null, ORG);
    }

    @Test
    @DisplayName("Row counts are batched in ONE query for the whole page - never N+1")
    void rowCountsBatchedInSingleQuery() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L), ds(3L)));
        // id 2 has no items → absent from the batch result; the caller defaults it to 0.
        when(dataSourceItemRepository.countByDataSourceIds(List.of(1L, 2L, 3L)))
                .thenReturn(Map.of(1L, 10L, 3L, 5L));

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25);

        assertThat(page.items()).hasSize(3);
        assertThat(page.rowCounts()).containsEntry(1L, 10L).containsEntry(3L, 5L);
        assertThat(page.rowCounts()).doesNotContainKey(2L); // zero-item tables are simply absent

        // Exactly one batch call; no per-row counting on either scope path.
        verify(dataSourceItemRepository, times(1)).countByDataSourceIds(List.of(1L, 2L, 3L));
        verify(dataSourceItemRepository, never()).countByDataSourceIdAndTenantId(anyLong(), anyString());
        verify(dataSourceItemRepository, never()).countByDataSourceIdInOrgScope(anyLong(), anyString());
    }

    @Test
    @DisplayName("Only the current page's ids are counted - page 2 ids are not queried")
    void rowCountsScopedToCurrentPageOnly() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L), ds(3L)));
        when(dataSourceItemRepository.countByDataSourceIds(List.of(1L, 2L)))
                .thenReturn(Map.of(1L, 4L, 2L, 7L));

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 2);

        assertThat(page.items()).hasSize(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(dataSourceItemRepository).countByDataSourceIds(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly(1L, 2L); // id 3 lives on page 2
    }

    @Test
    @DisplayName("An empty page batches no row-count query and returns an empty map")
    void emptyPageProducesEmptyRowCounts() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER)).thenReturn(List.of());
        when(dataSourceItemRepository.countByDataSourceIds(List.of())).thenReturn(Map.of());

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25);

        assertThat(page.items()).isEmpty();
        assertThat(page.rowCounts()).isEmpty();
    }
}
