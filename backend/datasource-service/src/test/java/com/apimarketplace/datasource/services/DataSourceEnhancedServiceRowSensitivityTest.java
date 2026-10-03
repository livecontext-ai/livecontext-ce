package com.apimarketplace.datasource.services;

import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.crud.service.ColumnValueCoercer;
import com.apimarketplace.datasource.crud.service.MediaCellHydrator;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.BulkOperationRequest;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.BulkOperationType;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.DataSourceItemRow;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.JsonPatchOperation;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.PatchOperation;
import com.apimarketplace.datasource.events.DatasourceRowEventPublisher;
import com.apimarketplace.datasource.persistence.DataSourceEnhancedRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066, re-audit 2026-09-29: a row a Gmail-derived workflow stored is RESTRICTED, and the
 * run a table trigger fires on it must be restricted too. The CRUD tools already carried the row's
 * classification with their row events, but the table grid (patch a cell, delete a row, bulk
 * patch/delete) published the same events with none, so editing that row by hand fired the
 * table-triggered workflow as an ordinary run: its step outputs kept for the normal retention and
 * readable by everyone the run is shared with.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DataSourceEnhancedService - grid row events carry the row's sensitivity (LC-066)")
class DataSourceEnhancedServiceRowSensitivityTest {

    private static final Long DS = 42L;
    private static final String TENANT = "tenant-1";
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    @Mock private DataSourceEnhancedRepositories repositories;
    @Mock private VectorRepository vectorRepository;
    @Mock private DataSourceService dataSourceService;
    @Mock private DatasourceRowEventPublisher rowEventPublisher;

    private DataSourceEnhancedService service;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "ce");
        service = new DataSourceEnhancedService(repositories, vectorRepository, dataSourceService,
            rowEventPublisher, new MediaCellHydrator(new ColumnValueCoercer()),
            new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env), null),
            mock(ApplicationEventPublisher.class));
        when(dataSourceService.getDataSource(DS)).thenReturn(Optional.empty());
        when(repositories.dataSourceExists(eq(DS), any())).thenReturn(true);
        when(repositories.itemExists(eq(DS), any(), any())).thenReturn(true);
    }

    private static DataSourceItemRow row(long id) {
        return new DataSourceItemRow(id, DS, TENANT, Map.of("subject", "Invoice " + id), 1, AT, null);
    }

    private static List<JsonPatchOperation> renameSubject() {
        return List.of(new JsonPatchOperation(PatchOperation.REPLACE, "/subject", "renamed", null));
    }

    @Test
    @DisplayName("regression: patching a RESTRICTED row in the grid publishes row_updated as RESTRICTED")
    void gridPatchCarriesTheRowsSensitivity() {
        when(repositories.findByIds(eq(DS), any(), any())).thenReturn(List.of(row(11L)));
        when(repositories.sensitivityByIds(DS, TENANT, List.of(11L))).thenReturn(Map.of(11L, "RESTRICTED"));
        when(repositories.applyJsonPatch(eq(DS), any(), eq(11L), any())).thenReturn(row(11L));

        service.applyJsonPatch(DS, TENANT, 11L, renameSubject());

        verify(rowEventPublisher).publishUpdated(eq(DS), eq(11L), eq(TENANT), any(), any(), any(),
            eq("RESTRICTED"));
    }

    @Test
    @DisplayName("regression: deleting a RESTRICTED row publishes row_deleted as RESTRICTED, read before the row is gone")
    void gridDeleteReadsTheSensitivityBeforeDeleting() {
        when(repositories.findByIds(eq(DS), any(), any())).thenReturn(List.of(row(9L)));
        when(repositories.sensitivityByIds(DS, TENANT, List.of(9L))).thenReturn(Map.of(9L, "RESTRICTED"));

        service.deleteItem(DS, TENANT, 9L);

        InOrder order = inOrder(repositories, rowEventPublisher);
        order.verify(repositories).sensitivityByIds(DS, TENANT, List.of(9L));
        order.verify(repositories).deleteItem(DS, TENANT, 9L);
        order.verify(rowEventPublisher).publishDeleted(eq(DS), eq(9L), eq(TENANT), any(), any(),
            eq("RESTRICTED"));
    }

    @Test
    @DisplayName("a bulk patch tags each row with its OWN sensitivity: a NORMAL row next to a RESTRICTED one stays untagged")
    void bulkPatchTagsEachRowWithItsOwnSensitivity() {
        when(repositories.findByIds(eq(DS), any(), anyList())).thenReturn(List.of(row(1L), row(2L)));
        when(repositories.sensitivityByIds(DS, TENANT, List.of(1L, 2L))).thenReturn(Map.of(1L, "RESTRICTED"));

        service.executeBulkOperation(DS, TENANT,
            new BulkOperationRequest(BulkOperationType.PATCH, List.of(1L, 2L), renameSubject()));

        verify(rowEventPublisher).publishUpdated(eq(DS), eq(1L), eq(TENANT), any(), any(), any(), eq("RESTRICTED"));
        verify(rowEventPublisher).publishUpdated(eq(DS), eq(2L), eq(TENANT), any(), any(), any(), isNull());
    }

    @Test
    @DisplayName("regression: a bulk delete of a RESTRICTED row publishes row_deleted as RESTRICTED")
    void bulkDeleteCarriesTheSensitivity() {
        when(repositories.findByIds(eq(DS), any(), anyList())).thenReturn(List.of(row(5L)));
        when(repositories.sensitivityByIds(DS, TENANT, List.of(5L))).thenReturn(Map.of(5L, "RESTRICTED"));

        service.executeBulkOperation(DS, TENANT,
            new BulkOperationRequest(BulkOperationType.DELETE, List.of(5L), null));

        verify(rowEventPublisher).publishDeleted(eq(DS), eq(5L), eq(TENANT), any(), any(), eq("RESTRICTED"));
    }

    @Test
    @DisplayName("a failed sensitivity read never fails the user's edit: the event goes out untagged")
    void failedSensitivityReadDoesNotFailTheEdit() {
        when(repositories.findByIds(eq(DS), any(), any())).thenReturn(List.of(row(11L)));
        when(repositories.sensitivityByIds(any(), any(), any())).thenThrow(new RuntimeException("db down"));
        when(repositories.applyJsonPatch(eq(DS), any(), eq(11L), any())).thenReturn(row(11L));

        assertThatCode(() -> service.applyJsonPatch(DS, TENANT, 11L, renameSubject())).doesNotThrowAnyException();
        assertThatCode(() -> service.executeBulkOperation(DS, TENANT,
            new BulkOperationRequest(BulkOperationType.DELETE, List.of(11L), null))).doesNotThrowAnyException();

        verify(rowEventPublisher).publishUpdated(eq(DS), eq(11L), eq(TENANT), any(), any(), any(), isNull());
        verify(rowEventPublisher).publishDeleted(eq(DS), eq(11L), eq(TENANT), any(), any(), isNull());
    }
}
