package com.apimarketplace.datasource.controllers.internal;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.crud.service.ColumnValueCoercer;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.crud.service.MediaCellHydrator;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceItem;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.services.VectorFeatureGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066: a table trigger that LOADS a table (manual or scheduled fire, not a row event)
 * loaded Gmail-derived rows into its run untagged. The items read now says, in a response header,
 * when a returned row is RESTRICTED, so the orchestrator restricts the run that loads it. A
 * header, so the rows' JSON, which the publication snapshot copies and compares, is unchanged.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalDataSourceController - the items read flags RESTRICTED rows (LC-066)")
class InternalDataSourceControllerSensitivityHeaderTest {

    private static final Long DS_ID = 42L;
    private static final String TENANT = "tenant-1";

    @Mock private DataSourceService dataSourceService;
    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private CrudExecutorService crudExecutorService;

    private InternalDataSourceController controller;
    private List<DataSourceItem> rows;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "ce");
        controller = new InternalDataSourceController(dataSourceService, dataSourceRepository,
            dataSourceItemRepository, crudExecutorService,
            new MediaCellHydrator(new ColumnValueCoercer()), new ObjectMapper(),
            new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env), null),
            mock(ApplicationEventPublisher.class));
        rows = List.of(new DataSourceItem(7L, DS_ID, TENANT, Map.of("subject", "Wire approved"), 1,
            Instant.parse("2026-01-01T00:00:00Z")));
        when(dataSourceService.getDataSourceItemsByTenantAndDataSourcePaginated(
                anyInt(), anyString(), any(), anyInt(), anyInt()))
            .thenReturn(rows);
    }

    @Test
    @DisplayName("regression: a page holding a RESTRICTED row carries the RESTRICTED header, rows unchanged")
    void restrictedPageCarriesTheHeader() {
        when(dataSourceService.containsRestrictedItems(eq(DS_ID), eq(rows))).thenReturn(true);

        ResponseEntity<List<DataSourceItem>> response = controller.getItems(DS_ID, TENANT, null, 0, 50, false, false, null, null);

        assertThat(response.getHeaders().getFirst(DataSourceClient.DATA_SENSITIVITY_HEADER)).isEqualTo("RESTRICTED");
        assertThat(response.getBody()).isEqualTo(rows);
    }

    @Test
    @DisplayName("an ordinary page carries no header")
    void ordinaryPageCarriesNoHeader() {
        when(dataSourceService.containsRestrictedItems(eq(DS_ID), eq(rows))).thenReturn(false);

        ResponseEntity<List<DataSourceItem>> response = controller.getItems(DS_ID, TENANT, null, 0, 50, false, false, null, null);

        assertThat(response.getHeaders().containsKey(DataSourceClient.DATA_SENSITIVITY_HEADER)).isFalse();
    }

    @Test
    @DisplayName("regression: a publication copy (excludeRestricted) serves the rows with the RESTRICTED ones left out")
    void publicationCopyLeavesRestrictedRowsOut() {
        when(dataSourceService.getDataSourceItemsForCopy(DS_ID.intValue(), TENANT, "org-1", 0, 500, null))
                .thenReturn(rows);

        ResponseEntity<List<DataSourceItem>> response =
                controller.getItems(DS_ID, TENANT, "org-1", 0, 500, false, true, null, null);

        assertThat(response.getBody()).isEqualTo(rows);
        // The copy read: the rows are left out by the query, before the page is cut, so a page
        // holding a RESTRICTED row is not served short.
        verify(dataSourceService).getDataSourceItemsForCopy(DS_ID.intValue(), TENANT, "org-1", 0, 500, null);
        verify(dataSourceService, never()).getDataSourceItemsByTenantAndDataSourcePaginated(
                anyInt(), anyString(), any(), anyInt(), anyInt());
        // The two confirmations without which the publication copy takes no rows.
        assertThat(response.getHeaders().getFirst(DataSourceClient.RESTRICTED_EXCLUDED_HEADER)).isEqualTo("true");
        assertThat(response.getHeaders().getFirst(DataSourceClient.COPY_KEYSET_HEADER)).isEqualTo("true");
    }

    @Test
    @DisplayName("a copy page skips the sensitivity lookup: the query already left every RESTRICTED row out")
    void copyPageDoesNotLookUpSensitivity() {
        when(dataSourceService.getDataSourceItemsForCopy(anyInt(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(rows);

        ResponseEntity<List<DataSourceItem>> response =
                controller.getItems(DS_ID, TENANT, null, 0, 500, false, true, null, null);

        verify(dataSourceService, never()).containsRestrictedItems(any(), any());
        assertThat(response.getHeaders().containsKey(DataSourceClient.DATA_SENSITIVITY_HEADER)).isFalse();
    }

    @Test
    @DisplayName("regression (copy paging): afterPriority + afterId resume the copy by keyset after that row")
    void copyCursorIsPassedThrough() {
        when(dataSourceService.getDataSourceItemsForCopy(anyInt(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(List.of());

        controller.getItems(DS_ID, TENANT, "org-1", 500, 500, false, true, 3, 77L);

        verify(dataSourceService).getDataSourceItemsForCopy(DS_ID.intValue(), TENANT, "org-1", 500, 500,
                new DataSourceItemRepository.CopyCursor(3, 77L));
    }

    @Test
    @DisplayName("half a cursor is refused (400), never guessed: guessing would skip or repeat rows")
    void halfACursorIsRefused() {
        ResponseEntity<List<DataSourceItem>> onlyPriority =
                controller.getItems(DS_ID, TENANT, null, 0, 500, false, true, 3, null);
        ResponseEntity<List<DataSourceItem>> onlyId =
                controller.getItems(DS_ID, TENANT, null, 0, 500, false, true, null, 77L);

        assertThat(onlyPriority.getStatusCode().value()).isEqualTo(400);
        assertThat(onlyId.getStatusCode().value()).isEqualTo(400);
        assertThat(onlyPriority.getHeaders().containsKey(DataSourceClient.RESTRICTED_EXCLUDED_HEADER)).isFalse();
        verify(dataSourceService, never()).getDataSourceItemsForCopy(anyInt(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    @DisplayName("regression (fail closed): a copy query that fails propagates its error, so no rows and no confirmation are served")
    void failingCopyQueryServesNothing() {
        when(dataSourceService.getDataSourceItemsForCopy(anyInt(), any(), any(), anyInt(), anyInt(), any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("sensitivity unreadable"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> controller.getItems(DS_ID, TENANT, null, 0, 500, false, true, null, null))
                .isInstanceOf(org.springframework.dao.QueryTimeoutException.class);
    }

    @Test
    @DisplayName("an ordinary read (no excludeRestricted) never filters: a run still loads every row it is scoped for")
    void ordinaryReadDoesNotFilter() {
        ResponseEntity<List<DataSourceItem>> response =
                controller.getItems(DS_ID, TENANT, null, 0, 50, false, false, 3, 77L);

        assertThat(response.getBody()).isEqualTo(rows);
        // A cursor on an ordinary read is ignored: other callers keep their offset / limit read.
        verify(dataSourceService).getDataSourceItemsByTenantAndDataSourcePaginated(DS_ID.intValue(), TENANT, null, 0, 50);
        verify(dataSourceService, never()).getDataSourceItemsForCopy(anyInt(), any(), any(), anyInt(), anyInt(), any());
        assertThat(response.getHeaders().containsKey(DataSourceClient.RESTRICTED_EXCLUDED_HEADER)).isFalse();
        assertThat(response.getHeaders().containsKey(DataSourceClient.COPY_KEYSET_HEADER)).isFalse();
    }
}
