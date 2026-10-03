package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.domain.WhereCondition;
import com.apimarketplace.datasource.crud.dto.CreateRowRequest;
import com.apimarketplace.datasource.crud.dto.DeleteRowRequest;
import com.apimarketplace.datasource.crud.dto.ReadRowRequest;
import com.apimarketplace.datasource.crud.dto.SimilarityQueryDto;
import com.apimarketplace.datasource.crud.dto.UpdateRowRequest;
import com.apimarketplace.datasource.crud.dto.WhereConditionDto;
import com.apimarketplace.datasource.crud.repository.CrudRepository;
import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.persistence.DataSourceColumnRepository;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.services.VectorFeatureGate;
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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reading a {@code file} / {@code image} column gives back the FileRef OBJECT, not the JSON text
 * the JSONB write flattened it into.
 *
 * <p>The write path normalises a media value to the canonical asset map, and then
 * {@code CrudRepository.serializeIfComplex} stringifies it because the JDBC driver cannot bind a
 * Map to {@code jsonb_build_object}. So every reader used to get a String back and had to re-parse
 * it, which is why workflows stored a hand-written FileRef in a text column instead of using the
 * column type built for it. These tests pin the read side of that contract, and pin just as hard
 * everything it must NOT touch.
 *
 * <p>Uses the REAL {@link ColumnValueCoercer}: the whole point is the shape it produces, so mocking
 * it would assert nothing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CrudExecutorService - media columns read back as FileRef objects")
class CrudExecutorServiceAssetHydrationTest {

    private static final String TENANT = "tenant-1";
    private static final String FILE_ID = "c7963596-ab99-46af-9cb5-fccb64461702";

    /** What a workflow FileRef looks like once stringified into the JSONB cell. */
    private static final String STORED_WORKFLOW_REF =
        "{\"_type\":\"file\",\"path\":\"1/wf/run/core:final_cut/clip.mp4\","
            + "\"name\":\"clip.mp4\",\"mimeType\":\"video/mp4\",\"size\":24438642}";

    /**
     * What the media cell wrote before the asset contract existed, and still the commonest value in
     * production: no discriminator, no path, the storage id recoverable only from the URL.
     */
    private static final String STORED_LEGACY_CELL =
        "{\"url\":\"/api/proxy/files/by-id/" + FILE_ID + "/raw?disposition=inline\","
            + "\"name\":\"clip.mp4\",\"mimeType\":\"video/mp4\",\"size\":19939355}";

    @Mock private CrudRepository crudRepository;
    @Mock private VectorRepository vectorRepository;
    @Mock private DataSourceService dataSourceService;
    @Mock private com.apimarketplace.common.storage.service.StorageBreakdownService breakdownService;
    @Mock private DataSourceColumnRepository dataSourceColumnRepository;
    @Mock private SqlSanitizer sqlSanitizer;
    @Mock private com.apimarketplace.datasource.events.DatasourceRowEventPublisher rowEventPublisher;

    private CrudExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = new CrudExecutorService(
            crudRepository, vectorRepository, dataSourceService, breakdownService,
            new ColumnValueCoercer(), new MediaCellHydrator(new ColumnValueCoercer()),
            dataSourceColumnRepository, sqlSanitizer, rowEventPublisher,
            ceVectorGate(), mock(ApplicationEventPublisher.class));
    }

    // ── Arrange helpers ──────────────────────────────────────────────────

    private static VectorFeatureGate ceVectorGate() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "ce");
        return new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env), null);
    }

    private static ColumnMappingSpec spec(String column, ColumnType type) {
        return new ColumnMappingSpec("data." + column, type, null, null, null);
    }

    private DataSource tableWithColumns(Map<String, ColumnMappingSpec> mappingSpec) {
        return new DataSource(1L, TENANT, "queue", null, null, null, null, null, null, null, null,
            mappingSpec, null, null, null, null);
    }

    /** One stored row, exactly as the repository hands it over (a JSONB {@code data} map). */
    private static Map<String, Object> storedRow(Map<String, Object> cells) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 4549);
        row.put("data", cells);
        return row;
    }

    /** A read of one row whose stored cells are exactly {@code cells}. */
    private CrudResult readOneRow(DataSource dataSource, Map<String, Object> cells) {
        when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(dataSource));
        when(crudRepository.readRows(eq(1L), eq(TENANT), isNull(), eq(21), eq(0)))
            .thenReturn(List.of(storedRow(cells)));
        return executorService.execute(readRequest(), TENANT);
    }

    /** A REAL request, not a mock: a mocked DTO would only agree with itself about its getters. */
    private static ReadRowRequest readRequest() {
        ReadRowRequest request = new ReadRowRequest();
        request.setDataSourceId(1L);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cell(CrudResult result, String column) {
        return (Map<String, Object>) result.data().rows().get(0).get(column);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    // ── The contract ─────────────────────────────────────────────────────

    @Test
    @DisplayName("A file column returns the FileRef object, keeping the storage path public_link needs")
    void fileColumnReturnsAFileRefObjectWithItsPath() {
        DataSource table = tableWithColumns(Map.of("video", spec("video", ColumnType.FILE)));

        CrudResult result = readOneRow(table, Map.of("video", STORED_WORKFLOW_REF));

        assertThat(result.data().rows().get(0).get("video")).isInstanceOf(Map.class);
        assertThat(cell(result, "video"))
            .containsEntry("_type", "file")
            .containsEntry("path", "1/wf/run/core:final_cut/clip.mp4")
            .containsEntry("name", "clip.mp4")
            .containsEntry("mimeType", "video/mp4");
    }

    @Test
    @DisplayName("An image column is hydrated exactly like a file column (one value contract)")
    void imageColumnIsHydratedLikeAFileColumn() {
        DataSource table = tableWithColumns(Map.of("cover", spec("cover", ColumnType.IMAGE)));

        CrudResult result = readOneRow(table, Map.of("cover", STORED_LEGACY_CELL));

        assertThat(cell(result, "cover")).containsEntry("_type", "file");
    }

    /**
     * The help promises {@code path} only when the file HAS a storage path. A cell known by id
     * alone is exactly the case that has none, so asserting its ABSENCE is what keeps the three
     * help texts from over-promising the one field {@code public_link} requires.
     */
    @Test
    @DisplayName("A cell known only by id gains the discriminator and its id, but no storage path")
    void legacyCellIsNormalisedButGainsNoPath() {
        DataSource table = tableWithColumns(Map.of("clip", spec("clip", ColumnType.FILE)));

        CrudResult result = readOneRow(table, Map.of("clip", STORED_LEGACY_CELL));

        assertThat(cell(result, "clip"))
            .containsEntry("_type", "file")
            .containsEntry("id", FILE_ID)
            .doesNotContainKey("path");
    }

    @Test
    @DisplayName("A media column declared with the data. prefix is hydrated like a bare one")
    void mediaColumnDeclaredWithTheDataPrefixIsStillHydrated() {
        // mapping_spec is keyed by the bare name, but a caller may have written "data.video";
        // matching that key raw against a row key would silently skip hydration for that spelling.
        DataSource table = tableWithColumns(Map.of("data.video", spec("video", ColumnType.FILE)));

        CrudResult result = readOneRow(table, Map.of("video", STORED_WORKFLOW_REF));

        assertThat(cell(result, "video")).containsEntry("_type", "file");
    }

    @Test
    @DisplayName("A similarity (RAG) read hydrates its media cells too, not only a plain read")
    void similaritySearchHydratesMediaCellsToo() {
        Map<String, ColumnMappingSpec> columns = new LinkedHashMap<>();
        columns.put("video", spec("video", ColumnType.FILE));
        columns.put("embedding", new ColumnMappingSpec("data.embedding", ColumnType.VECTOR, null, null,
            Map.of("dimension", 3, "metric", "cosine")));
        DataSource table = tableWithColumns(columns);
        when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(table));
        when(vectorRepository.similaritySearch(eq(1L), eq(TENANT), eq("embedding"), any(),
            anyInt(), anyString(), anyInt(), isNull(), isNull(), isNull()))
            .thenReturn(List.of(storedRow(Map.of("video", STORED_WORKFLOW_REF))));

        SimilarityQueryDto similarity = new SimilarityQueryDto();
        similarity.setColumn("embedding");
        similarity.setQueryVector(new float[]{0.1f, 0.2f, 0.3f});
        ReadRowRequest request = readRequest();
        request.setSimilarity(similarity);

        CrudResult result = executorService.execute(request, TENANT);

        assertThat(cell(result, "video")).containsEntry("_type", "file");
    }

    /**
     * The row a table trigger fires with must look like the row a workflow reads. If it kept the
     * stored text, the same cell would be an object after find_rows and a string after a trigger,
     * and the file-taking parameter the docs point at would fail on one of the two paths.
     *
     * <p>This covers the CRUD writer only. The grid is the OTHER publisher of the same events and
     * is covered by {@code DataSourceEnhancedServiceMediaEventTest} - both must agree, or the shape
     * would depend on who wrote the row rather than on the column type.
     */
    @Test
    @DisplayName("A CRUD write publishes its trigger row with the same object shape as a read")
    void crudWriteTriggerSnapshotCarriesTheSameShapeAsARead() {
        DataSource table = tableWithColumns(Map.of("video", spec("video", ColumnType.FILE)));
        when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(table));
        when(dataSourceColumnRepository.loadMappingSpec(1L, TENANT)).thenReturn(table.mappingSpec());
        when(sqlSanitizer.sanitizeColumnName(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(crudRepository.findIdsMatching(eq(1L), eq(TENANT), any(WhereCondition.class)))
            .thenReturn(List.of(4549L));
        when(crudRepository.findRowsByIds(eq(1L), eq(TENANT), any()))
            .thenReturn(List.of(storedRow(Map.of("video", STORED_WORKFLOW_REF))));
        when(crudRepository.updateRows(eq(1L), eq(TENANT), any(), any(), anyBoolean())).thenReturn(1);

        UpdateRowRequest request = new UpdateRowRequest();
        request.setDataSourceId(1L);
        request.setWhere(new WhereConditionDto("id", "=", "4549"));
        Map<String, Object> set = new HashMap<>();
        set.put("title", "renamed");
        request.setSet(set);

        executorService.execute(request, TENANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> after = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> before = ArgumentCaptor.forClass(Map.class);
        verify(rowEventPublisher).publishUpdated(eq(1L), eq(4549L), eq(TENANT), any(),
            after.capture(), before.capture(), anyString());
        assertThat(asMap(after.getValue().get("video")))
            .containsEntry("_type", "file")
            .containsEntry("path", "1/wf/run/core:final_cut/clip.mp4");
        // previous_row is the same snapshot mechanism and reaches the same expressions, so a
        // workflow comparing before and after must not get two different shapes.
        assertThat(asMap(before.getValue().get("video")))
            .containsEntry("_type", "file")
            .containsEntry("path", "1/wf/run/core:final_cut/clip.mp4");
    }

    @Test
    @DisplayName("A CRUD insert publishes its trigger row with the media cell as the file object")
    void crudInsertPublishesTheMediaCellAsAnObject() {
        DataSource table = tableWithColumns(Map.of("video", spec("video", ColumnType.FILE)));
        when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(table));
        when(dataSourceColumnRepository.loadMappingSpec(1L, TENANT)).thenReturn(table.mappingSpec());
        when(sqlSanitizer.sanitizeColumnName(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(crudRepository.createRows(eq(1L), eq(TENANT), any(), any())).thenReturn(List.of(4549L));
        when(crudRepository.findRowsByIds(eq(1L), eq(TENANT), any()))
            .thenReturn(List.of(storedRow(Map.of("video", STORED_WORKFLOW_REF))));

        // Mutable on purpose: the create path rewrites row keys in place, so an immutable
        // fixture would fail with an unrelated error before ever reaching the publisher.
        Map<String, Object> columns = new HashMap<>();
        columns.put("video", STORED_WORKFLOW_REF);
        List<CreateRowRequest.RowData> rows = new java.util.ArrayList<>();
        rows.add(new CreateRowRequest.RowData(null, columns));
        CreateRowRequest request = new CreateRowRequest();
        request.setDataSourceId(1L);
        request.setRows(rows);

        executorService.execute(request, TENANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> published = ArgumentCaptor.forClass(Map.class);
        verify(rowEventPublisher).publishCreated(eq(1L), eq(4549L), eq(TENANT), any(),
            published.capture(), anyString());
        assertThat(asMap(published.getValue().get("video")))
            .containsEntry("_type", "file")
            .containsEntry("path", "1/wf/run/core:final_cut/clip.mp4");
    }

    @Test
    @DisplayName("A CRUD delete publishes the deleted row with the media cell as the file object")
    void crudDeletePublishesTheMediaCellAsAnObject() {
        DataSource table = tableWithColumns(Map.of("video", spec("video", ColumnType.FILE)));
        when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(table));
        when(sqlSanitizer.sanitizeColumnName(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(crudRepository.findIdsMatching(eq(1L), eq(TENANT), any(WhereCondition.class)))
            .thenReturn(List.of(4549L));
        when(crudRepository.findRowsByIds(eq(1L), eq(TENANT), any()))
            .thenReturn(List.of(storedRow(Map.of("video", STORED_WORKFLOW_REF))));
        when(crudRepository.deleteRows(eq(1L), eq(TENANT), any())).thenReturn(1);

        DeleteRowRequest request = new DeleteRowRequest();
        request.setDataSourceId(1L);
        request.setWhere(new WhereConditionDto("id", "=", "4549"));

        executorService.execute(request, TENANT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> published = ArgumentCaptor.forClass(Map.class);
        verify(rowEventPublisher).publishDeleted(eq(1L), eq(4549L), eq(TENANT), any(),
            published.capture(), anyString());
        assertThat(asMap(published.getValue().get("video"))).containsEntry("_type", "file");
    }

    // ── What it must NOT touch ───────────────────────────────────────────

    /**
     * The normaliser answers with a map even when it recognised nothing, so accepting its output
     * unconditionally would replace a cell's real content with a bare {@code {_type:'file'}} while
     * the database still holds the original.
     */
    @Test
    @DisplayName("A JSON object that is not a file reference is returned exactly as stored")
    void nonFileJsonObjectInAMediaColumnIsKeptAsStored() {
        // Carries "key", one of the generic aliases the normaliser reads. A fixture without one
        // would pass whatever the guard did, because nothing in it could be mistaken for a file.
        String notAFile = "{\"key\":\"abc\",\"value\":42}";
        DataSource table = tableWithColumns(Map.of("cover", spec("cover", ColumnType.IMAGE)));

        CrudResult result = readOneRow(table, Map.of("cover", notAFile));

        assertThat(result.data().rows().get(0).get("cover")).isEqualTo(notAFile);
    }

    @Test
    @DisplayName("A text column holding a hand-written FileRef is returned untouched, as a string")
    void textColumnHoldingJsonIsNeverHydrated() {
        DataSource table = tableWithColumns(Map.of("video_ref_json", spec("video_ref_json", ColumnType.TEXT)));

        CrudResult result = readOneRow(table, Map.of("video_ref_json", STORED_WORKFLOW_REF));

        assertThat(result.data().rows().get(0).get("video_ref_json")).isEqualTo(STORED_WORKFLOW_REF);
    }

    @Test
    @DisplayName("A bare URL in a media column stays the string it was, never reinterpreted as an object")
    void bareUrlInAMediaColumnStaysAString() {
        DataSource table = tableWithColumns(Map.of("photo", spec("photo", ColumnType.FILE)));

        CrudResult result = readOneRow(table, Map.of("photo", "https://example.com/a.png"));

        assertThat(result.data().rows().get(0).get("photo")).isEqualTo("https://example.com/a.png");
    }

    @Test
    @DisplayName("A JSON array in a media column keeps its stored text - one cell holds one reference")
    void jsonArrayInAMediaColumnKeepsItsStoredText() {
        String array = "[" + STORED_WORKFLOW_REF + "]";
        DataSource table = tableWithColumns(Map.of("clips", spec("clips", ColumnType.FILE)));

        CrudResult result = readOneRow(table, Map.of("clips", array));

        assertThat(result.data().rows().get(0).get("clips")).isEqualTo(array);
    }

    @Test
    @DisplayName("An empty media cell stays empty rather than becoming null")
    void emptyMediaCellStaysEmpty() {
        DataSource table = tableWithColumns(Map.of("photo", spec("photo", ColumnType.FILE)));

        CrudResult result = readOneRow(table, Map.of("photo", ""));

        assertThat(result.data().rows().get(0).get("photo")).isEqualTo("");
    }

    @Test
    @DisplayName("A null media cell stays null")
    void nullMediaCellStaysNull() {
        DataSource table = tableWithColumns(Map.of("photo", spec("photo", ColumnType.FILE)));
        Map<String, Object> cells = new HashMap<>();
        cells.put("photo", null);

        CrudResult result = readOneRow(table, cells);

        assertThat(result.data().rows().get(0)).containsKey("photo");
        assertThat(result.data().rows().get(0).get("photo")).isNull();
    }

    @Test
    @DisplayName("A row that never carried the media column does not gain a null one")
    void rowWithoutTheMediaColumnDoesNotGainOne() {
        DataSource table = tableWithColumns(Map.of("photo", spec("photo", ColumnType.FILE)));

        CrudResult result = readOneRow(table, Map.of("title", "no photo here"));

        assertThat(result.data().rows().get(0)).doesNotContainKey("photo");
    }

    @Test
    @DisplayName("A table with no media column returns its rows exactly as before")
    void tableWithoutMediaColumnsIsUnaffected() {
        DataSource table = tableWithColumns(Map.of("title", spec("title", ColumnType.TEXT)));

        CrudResult result = readOneRow(table, Map.of("title", "Set: miniature city"));

        assertThat(result.data().rows().get(0)).containsEntry("title", "Set: miniature city");
    }

    /**
     * Documented limit: the value handed back is the canonical asset and nothing else. Pinned so
     * the loss is a decision someone made, not something a future reader discovers in production.
     */
    @Test
    @DisplayName("Keys outside the asset contract are dropped, so the shape is the canonical one")
    void keysOutsideTheAssetContractAreDropped() {
        String withExtras = "{\"_type\":\"file\",\"path\":\"1/wf/clip.mp4\",\"name\":\"clip.mp4\","
            + "\"caption\":\"kept in the database, not in the read\"}";
        DataSource table = tableWithColumns(Map.of("video", spec("video", ColumnType.FILE)));

        CrudResult result = readOneRow(table, Map.of("video", withExtras));

        assertThat(cell(result, "video"))
            .containsEntry("path", "1/wf/clip.mp4")
            .doesNotContainKey("caption");
    }

    @Test
    @DisplayName("Hydration reads the column spec the datasource already carries, never a second query")
    void hydrationNeverQueriesTheColumnSpec() {
        DataSource table = tableWithColumns(Map.of("video", spec("video", ColumnType.FILE)));

        readOneRow(table, Map.of("video", STORED_WORKFLOW_REF));

        verify(dataSourceColumnRepository, never()).loadMappingSpec(anyLong(), anyString());
    }

    @Test
    @DisplayName("A table that declares no columns at all reads back without a spec query")
    void tableWithNoDeclaredColumnsNeedsNoSpecQuery() {
        DataSource table = tableWithColumns(Map.of());

        CrudResult result = readOneRow(table, Map.of("video", STORED_WORKFLOW_REF));

        assertThat(result.data().rows().get(0).get("video")).isEqualTo(STORED_WORKFLOW_REF);
        verify(dataSourceColumnRepository, never()).loadMappingSpec(anyLong(), anyString());
    }

    @Test
    @DisplayName("The rows the repository handed over are not mutated by hydration")
    void repositoryOwnedRowsAreNotMutated() {
        DataSource table = tableWithColumns(Map.of("video", spec("video", ColumnType.FILE)));
        Map<String, Object> cells = new HashMap<>();
        cells.put("video", STORED_WORKFLOW_REF);
        when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(table));
        when(crudRepository.readRows(eq(1L), eq(TENANT), isNull(), eq(21), eq(0)))
            .thenReturn(List.of(storedRow(cells)));

        executorService.execute(readRequest(), TENANT);

        assertThat(cells.get("video")).isEqualTo(STORED_WORKFLOW_REF);
    }
}
