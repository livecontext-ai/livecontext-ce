package com.apimarketplace.datasource.services;

import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.crud.service.ColumnValueCoercer;
import com.apimarketplace.datasource.crud.service.MediaCellHydrator;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.DataSourceItemRow;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.events.DatasourceRowEventPublisher;
import com.apimarketplace.datasource.persistence.DataSourceEnhancedRepositories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The grid's own writes publish the SAME row events as the CRUD path, so they must publish the same
 * cell shape. Without this, {@code trigger:<label>.output.row.<media column>} would be an object or
 * a string depending on WHO wrote the row - an agent through the CRUD tools, or a person editing a
 * cell in the table - and a workflow reading it could not be written to work in both cases.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DataSourceEnhancedService - media cells in the events it publishes")
class DataSourceEnhancedServiceMediaEventTest {

    private static final Long DATA_SOURCE_ID = 42L;
    private static final String TENANT = "tenant-1";
    /** What the CRUD write path leaves in the cell: the reference, stringified. */
    private static final String STORED_REF =
        "{\"_type\":\"file\",\"path\":\"tenant-1/wf/run/clip.mp4\",\"name\":\"clip.mp4\"}";

    /**
     * What the GRID write path leaves in the cell: a real nested object - and deliberately NOT one
     * already in canonical form. A fixture that is already {@code {_type, path, name}} is a fixed
     * point of the hydrator, so the test would pass whether or not hydration is wired into this
     * service at all. These alias keys only become {@code _type}/{@code path}/{@code name} if it
     * actually ran.
     */
    private static final Map<String, Object> OBJECT_ENCODED_REF = Map.of(
        "storageKey", "tenant-1/wf/run/clip.mp4", "fileName", "clip.mp4");

    @Mock private DataSourceEnhancedRepositories repositories;
    @Mock private VectorRepository vectorRepository;
    @Mock private DataSourceService dataSourceService;
    @Mock private DatasourceRowEventPublisher rowEventPublisher;

    private DataSourceEnhancedService service;

    @BeforeEach
    void setUp() {
        service = new DataSourceEnhancedService(repositories, vectorRepository, dataSourceService,
            rowEventPublisher, new MediaCellHydrator(new ColumnValueCoercer()), ceVectorGate(),
            mock(ApplicationEventPublisher.class));
    }

    private static VectorFeatureGate ceVectorGate() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "ce");
        return new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private DataSource tableWithMediaColumn() {
        return new DataSource(DATA_SOURCE_ID, TENANT, "queue", null, null, null, null, null, null,
            null, null,
            Map.of("video", new ColumnMappingSpec("data.video", ColumnType.FILE, null, null, null)),
            null, null, null, null);
    }

    /**
     * The grid serialises the whole row map at once, so ITS media cell is stored as a real nested
     * JSONB object, not the JSON text the CRUD write path produces. Feeding this test a string
     * would exercise the other service's encoding and leave the grid's own branch untested.
     */
    @Test
    @DisplayName("A row added through the grid publishes its object-encoded media cell as a file object")
    void gridWritePublishesTheMediaCellAsAnObject() {
        DataSource table = tableWithMediaColumn();
        when(dataSourceService.getDataSource(DATA_SOURCE_ID)).thenReturn(Optional.of(table));
        when(repositories.dataSourceExists(eq(DATA_SOURCE_ID), any())).thenReturn(true);
        when(repositories.addItem(eq(DATA_SOURCE_ID), any(), anyMap(), any()))
            .thenReturn(new DataSourceItemRow(7L, DATA_SOURCE_ID, TENANT,
                Map.of("video", OBJECT_ENCODED_REF), 1, Instant.parse("2026-01-01T00:00:00Z"), null));

        service.addItem(DATA_SOURCE_ID, TENANT, Map.of("video", OBJECT_ENCODED_REF), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> published = ArgumentCaptor.forClass(Map.class);
        verify(rowEventPublisher).publishCreated(eq(DATA_SOURCE_ID), eq(7L), any(), any(),
            published.capture());
        assertThat(asMap(published.getValue().get("video")))
            .as("the grid's event must carry the same object a read of the row would give")
            .containsEntry("_type", "file")
            .containsEntry("path", "tenant-1/wf/run/clip.mp4")
            .containsEntry("name", "clip.mp4")
            .doesNotContainKey("storageKey");
    }

    @Test
    @DisplayName("A row patched through the grid publishes BOTH row and previous_row as file objects")
    void gridPatchPublishesBothSnapshotsAsObjects() {
        DataSource table = tableWithMediaColumn();
        when(dataSourceService.getDataSource(DATA_SOURCE_ID)).thenReturn(Optional.of(table));
        when(repositories.itemExists(eq(DATA_SOURCE_ID), any(), eq(11L))).thenReturn(true);
        when(repositories.findByIds(eq(DATA_SOURCE_ID), any(), any()))
            .thenReturn(java.util.List.of(new DataSourceItemRow(11L, DATA_SOURCE_ID, TENANT,
                Map.of("video", STORED_REF), 1, Instant.parse("2026-01-01T00:00:00Z"), null)));
        when(repositories.applyJsonPatch(eq(DATA_SOURCE_ID), any(), eq(11L), any()))
            .thenReturn(new DataSourceItemRow(11L, DATA_SOURCE_ID, TENANT,
                Map.of("video", STORED_REF), 1, Instant.parse("2026-01-01T00:00:00Z"), null));

        service.applyJsonPatch(DATA_SOURCE_ID, TENANT, 11L, java.util.List.of(
            new com.apimarketplace.datasource.domain.DataSourceEnhancedModels.JsonPatchOperation(
                com.apimarketplace.datasource.domain.DataSourceEnhancedModels.PatchOperation.REPLACE,
                "/title", "renamed", null)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        verify(rowEventPublisher).publishUpdated(eq(DATA_SOURCE_ID), eq(11L), any(), any(),
            after.capture(), before.capture(), any());
        assertThat(asMap(after.getValue().get("video"))).containsEntry("_type", "file");
        assertThat(asMap(before.getValue().get("video")))
            .as("previous_row reaches the same expressions, so it must not be the other shape")
            .containsEntry("_type", "file");
    }

    @Test
    @DisplayName("A row deleted through the grid publishes its media cell as the file object")
    void gridDeletePublishesTheMediaCellAsAnObject() {
        DataSource table = tableWithMediaColumn();
        when(dataSourceService.getDataSource(DATA_SOURCE_ID)).thenReturn(Optional.of(table));
        when(repositories.itemExists(eq(DATA_SOURCE_ID), any(), eq(9L))).thenReturn(true);
        when(repositories.findByIds(eq(DATA_SOURCE_ID), any(), any()))
            .thenReturn(java.util.List.of(new DataSourceItemRow(9L, DATA_SOURCE_ID, TENANT,
                Map.of("video", STORED_REF), 1, Instant.parse("2026-01-01T00:00:00Z"), null)));

        service.deleteItem(DATA_SOURCE_ID, TENANT, 9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> published = ArgumentCaptor.forClass(Map.class);
        verify(rowEventPublisher).publishDeleted(eq(DATA_SOURCE_ID), eq(9L), any(), any(),
            published.capture(), any());
        assertThat(asMap(published.getValue().get("video")))
            .containsEntry("_type", "file")
            .containsEntry("path", "tenant-1/wf/run/clip.mp4");
    }

    /**
     * The spec lookup is best-effort: an event must still go out when it fails. What must NOT happen
     * is an exception escaping into the write that triggered it, so the row lands in the table and
     * the trigger simply sees the older text shape.
     */
    @Test
    @DisplayName("When the column spec cannot be read the event still goes out, in the stored form")
    void eventStillPublishesWhenTheColumnSpecCannotBeRead() {
        when(dataSourceService.getDataSource(DATA_SOURCE_ID))
            .thenThrow(new IllegalStateException("datasource lookup down"));
        when(repositories.dataSourceExists(eq(DATA_SOURCE_ID), any())).thenReturn(true);
        when(repositories.addItem(eq(DATA_SOURCE_ID), any(), anyMap(), any()))
            .thenReturn(new DataSourceItemRow(10L, DATA_SOURCE_ID, TENANT,
                Map.of("video", STORED_REF), 1, Instant.parse("2026-01-01T00:00:00Z"), null));

        service.addItem(DATA_SOURCE_ID, TENANT, Map.of("video", STORED_REF), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> published = ArgumentCaptor.forClass(Map.class);
        verify(rowEventPublisher).publishCreated(eq(DATA_SOURCE_ID), eq(10L), any(), any(),
            published.capture());
        assertThat(published.getValue().get("video")).isEqualTo(STORED_REF);
    }

    @Test
    @DisplayName("A table with no media column publishes its row untouched")
    void gridWriteOnATableWithoutMediaColumnsIsUnaffected() {
        DataSource table = new DataSource(DATA_SOURCE_ID, TENANT, "notes", null, null, null, null,
            null, null, null, null,
            Map.of("body", new ColumnMappingSpec("data.body", ColumnType.TEXT, null, null, null)),
            null, null, null, null);
        when(dataSourceService.getDataSource(DATA_SOURCE_ID)).thenReturn(Optional.of(table));
        when(repositories.dataSourceExists(eq(DATA_SOURCE_ID), any())).thenReturn(true);
        when(repositories.addItem(eq(DATA_SOURCE_ID), any(), anyMap(), any()))
            .thenReturn(new DataSourceItemRow(8L, DATA_SOURCE_ID, TENANT,
                Map.of("body", STORED_REF), 1, Instant.parse("2026-01-01T00:00:00Z"), null));

        service.addItem(DATA_SOURCE_ID, TENANT, Map.of("body", STORED_REF), 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> published = ArgumentCaptor.forClass(Map.class);
        verify(rowEventPublisher).publishCreated(eq(DATA_SOURCE_ID), eq(8L), any(), any(),
            published.capture());
        assertThat(published.getValue().get("body")).isEqualTo(STORED_REF);
    }
}
