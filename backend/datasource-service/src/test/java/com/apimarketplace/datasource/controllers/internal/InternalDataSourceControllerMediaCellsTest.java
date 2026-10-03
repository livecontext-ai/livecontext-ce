package com.apimarketplace.datasource.controllers.internal;

import com.apimarketplace.datasource.crud.service.ColumnValueCoercer;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.crud.service.MediaCellHydrator;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The raw items endpoint answers in TWO shapes, and which one you get is the caller's choice.
 *
 * <p>A caller that is about to RUN on the rows - a table trigger's {@code data[]}, the
 * interface-render path - asks for media cells as file objects, so they match the rows read
 * anywhere else. A caller that is COPYING the table - the publication snapshot, and the live side
 * of the moderation diff that is compared against an older stored snapshot - must get exactly what
 * is stored, or an unchanged table reads as changed on every media cell. The default is the stored
 * form, so a caller that has not thought about it cannot silently corrupt a comparison.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalDataSourceController - media cells on the raw items endpoint")
class InternalDataSourceControllerMediaCellsTest {

    private static final Long DS_ID = 42L;
    private static final String TENANT = "tenant-1";
    private static final String STORED_REF =
        "{\"_type\":\"file\",\"path\":\"tenant-1/wf/run/clip.mp4\",\"name\":\"clip.mp4\"}";

    @Mock private DataSourceService dataSourceService;
    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private CrudExecutorService crudExecutorService;

    private InternalDataSourceController controller;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "ce");
        controller = new InternalDataSourceController(dataSourceService, dataSourceRepository,
            dataSourceItemRepository, crudExecutorService,
            new MediaCellHydrator(new ColumnValueCoercer()), new ObjectMapper(),
            new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env), null),
            mock(ApplicationEventPublisher.class));
    }

    private DataSource tableWithMediaColumn() {
        return new DataSource(DS_ID, TENANT, "queue", null, null, null, null, null, null, null, null,
            Map.of("video", new ColumnMappingSpec("data.video", ColumnType.FILE, null, null, null)),
            null, null, null, null);
    }

    private void givenOneRow(Map<String, Object> cells) {
        when(dataSourceService.getDataSourceItemsByTenantAndDataSourcePaginated(
                anyInt(), anyString(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(new DataSourceItem(7L, DS_ID, TENANT, cells, 1,
                Instant.parse("2026-01-01T00:00:00Z"))));
    }

    private Map<String, Object> firstRowData(ResponseEntity<List<DataSourceItem>> response) {
        return response.getBody().get(0).data();
    }

    @Test
    @DisplayName("A caller that asks gets the media cell as the file object")
    void hydrateMediaTrueReturnsTheFileObject() {
        when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(tableWithMediaColumn()));
        givenOneRow(Map.of("video", STORED_REF));

        ResponseEntity<List<DataSourceItem>> response =
            controller.getItems(DS_ID, TENANT, null, 0, 50, true, false, null, null);

        Object cell = firstRowData(response).get("video");
        assertThat(cell).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) cell;
        assertThat(ref).containsEntry("path", "tenant-1/wf/run/clip.mp4");
    }

    /**
     * The default protects the copiers. If it ever flips, an unchanged table starts reading as
     * changed on every media cell in the moderation diff, and a re-published snapshot stops
     * matching the one before it.
     */
    @Test
    @DisplayName("A caller that does NOT ask gets exactly what is stored")
    void defaultReturnsTheStoredForm() {
        when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(tableWithMediaColumn()));
        givenOneRow(Map.of("video", STORED_REF));

        ResponseEntity<List<DataSourceItem>> response =
            controller.getItems(DS_ID, TENANT, null, 0, 50, false, false, null, null);

        assertThat(firstRowData(response).get("video")).isEqualTo(STORED_REF);
        verify(dataSourceService, never()).getDataSource(any());
    }

    @Test
    @DisplayName("A table with no media column is returned without a column-spec query")
    void noMediaColumnMeansNoSpecQueryAndNoChange() {
        DataSource plain = new DataSource(DS_ID, TENANT, "notes", null, null, null, null, null, null,
            null, null,
            Map.of("body", new ColumnMappingSpec("data.body", ColumnType.TEXT, null, null, null)),
            null, null, null, null);
        when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(plain));
        givenOneRow(Map.of("body", STORED_REF));

        ResponseEntity<List<DataSourceItem>> response =
            controller.getItems(DS_ID, TENANT, null, 0, 50, true, false, null, null);

        assertThat(firstRowData(response).get("body")).isEqualTo(STORED_REF);
    }

    @Test
    @DisplayName("A row carrying no data at all is passed through, never dereferenced")
    void rowWithNullDataIsTolerated() {
        when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(tableWithMediaColumn()));
        when(dataSourceService.getDataSourceItemsByTenantAndDataSourcePaginated(
                anyInt(), anyString(), any(), anyInt(), anyInt()))
            .thenReturn(List.of(new DataSourceItem(7L, DS_ID, TENANT, null, 1,
                Instant.parse("2026-01-01T00:00:00Z"))));

        ResponseEntity<List<DataSourceItem>> response =
            controller.getItems(DS_ID, TENANT, null, 0, 50, true, false, null, null);

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).data()).isNull();
    }

    @Test
    @DisplayName("The row map the repository handed over is not mutated")
    void repositoryOwnedRowIsNotMutated() {
        when(dataSourceService.getDataSource(DS_ID)).thenReturn(Optional.of(tableWithMediaColumn()));
        Map<String, Object> stored = new HashMap<>();
        stored.put("video", STORED_REF);
        givenOneRow(stored);

        controller.getItems(DS_ID, TENANT, null, 0, 50, true, false, null, null);

        assertThat(stored.get("video")).isEqualTo(STORED_REF);
    }

    @Test
    @DisplayName("When the column spec cannot be read the rows still come back, in the stored form")
    void specLookupFailureStillReturnsRows() {
        when(dataSourceService.getDataSource(DS_ID))
            .thenThrow(new IllegalStateException("datasource lookup down"));
        givenOneRow(Map.of("video", STORED_REF));

        ResponseEntity<List<DataSourceItem>> response =
            controller.getItems(DS_ID, TENANT, null, 0, 50, true, false, null, null);

        assertThat(firstRowData(response).get("video")).isEqualTo(STORED_REF);
    }
}
