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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Each table card paints a mini-table preview, so the paged endpoint attaches a
 * small first-N row sample per datasource. Exactly like the row counts next to
 * it, this MUST be ONE batched window query over the current page's ids - never
 * one items fetch per card (N+1) - otherwise a 25-card page fires 25 extra
 * queries against {@code data_source_items}.
 *
 * <p>Companion of {@link DataSourceServicePagedRowCountsTest}. If a future
 * refactor reverts to per-card sampling, the {@code never()}/{@code times(1)}
 * verifications here fail.
 */
@ExtendWith(MockitoExtension.class)
class DataSourceServicePagedSampleRowsTest {

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
        // Row counts are not under test here; keep them out of the way.
        when(dataSourceItemRepository.countByDataSourceIds(anyList())).thenReturn(Map.of());
    }

    private static DataSource ds(long id) {
        return new DataSource(id, "tenant", "table-" + id, "desc", DataSourceType.INLINE,
                Map.of(), null, null, null, USER, null, null, null, null, null, ORG);
    }

    @Test
    @DisplayName("Sample rows are batched in ONE window query for the whole page - never N+1")
    void sampleRowsBatchedInSingleQuery() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L), ds(3L)));
        // id 2 has no rows → absent from the batch result; the caller defaults it to an empty list.
        Map<Long, List<Map<String, Object>>> sample = Map.of(
                1L, List.of(Map.of("title", "Alice"), Map.of("title", "Bob")),
                3L, List.of(Map.of("title", "Carol")));
        when(dataSourceItemRepository.sampleRowsByDataSourceIds(List.of(1L, 2L, 3L), 3)).thenReturn(sample);

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25);

        assertThat(page.sampleRows()).containsEntry(1L, List.of(Map.of("title", "Alice"), Map.of("title", "Bob")));
        assertThat(page.sampleRows()).containsEntry(3L, List.of(Map.of("title", "Carol")));
        assertThat(page.sampleRows()).doesNotContainKey(2L); // zero-row tables are simply absent

        // Exactly one batch call; no per-card items fetch on any scope path.
        verify(dataSourceItemRepository, times(1)).sampleRowsByDataSourceIds(List.of(1L, 2L, 3L), 3);
        verify(dataSourceItemRepository, never()).findByDataSourceId(anyLong());
        verify(dataSourceItemRepository, never())
                .findByDataSourceIdInOrgScopePaginated(anyLong(), anyString(), anyInt(), anyInt());
        verify(dataSourceItemRepository, never())
                .findByDataSourceIdAndTenantIdPaginated(anyLong(), anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("The page asks for exactly 3 sample rows per table")
    void sampleRowsRequestsThreePerTable() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER)).thenReturn(List.of(ds(1L)));
        when(dataSourceItemRepository.sampleRowsByDataSourceIds(anyList(), anyInt())).thenReturn(Map.of());

        service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25);

        ArgumentCaptor<Integer> perTable = ArgumentCaptor.forClass(Integer.class);
        verify(dataSourceItemRepository).sampleRowsByDataSourceIds(anyList(), perTable.capture());
        assertThat(perTable.getValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("Only the current page's ids are sampled - page 2 ids are not queried")
    void sampleRowsScopedToCurrentPageOnly() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L), ds(3L)));
        when(dataSourceItemRepository.sampleRowsByDataSourceIds(eq(List.of(1L, 2L)), anyInt()))
                .thenReturn(Map.of(1L, List.of(Map.of("k", "v"))));

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 2);

        assertThat(page.items()).hasSize(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(dataSourceItemRepository).sampleRowsByDataSourceIds(idsCaptor.capture(), anyInt());
        assertThat(idsCaptor.getValue()).containsExactly(1L, 2L); // id 3 lives on page 2
    }

    @Test
    @DisplayName("An empty page still batches a single (empty) sample query and surfaces an empty map")
    void emptyPageProducesEmptySampleRows() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER)).thenReturn(List.of());
        when(dataSourceItemRepository.sampleRowsByDataSourceIds(List.of(), 3)).thenReturn(Map.of());

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25);

        assertThat(page.items()).isEmpty();
        assertThat(page.sampleRows()).isEmpty();
    }
}
