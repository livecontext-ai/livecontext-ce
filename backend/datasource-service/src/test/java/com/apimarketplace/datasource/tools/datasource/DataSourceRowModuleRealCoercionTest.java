package com.apimarketplace.datasource.tools.datasource;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.crud.repository.CrudRepository;
import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.crud.service.ColumnValueCoercer;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.crud.service.MediaCellHydrator;
import com.apimarketplace.datasource.crud.service.SqlSanitizer;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.persistence.DataSourceColumnRepository;
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
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The write path end to end, with the REAL coercer: value in, warning out, warning visible.
 *
 * <p>The sibling test mocks {@link CrudExecutorService} and hands the module a warning it wrote
 * itself, so it proves the module passes a list along. It cannot see the bug this exists for, which
 * lived in the JOIN between the three: the coercer produced the sentence, the service carried it,
 * and the tool answered "Successfully inserted 1 rows" and dropped it. Reverting the service to the
 * warning-less {@code forCreate} overload leaves that whole suite green; it turns this one red.
 *
 * <p>Only the repository is mocked - nothing below the write is under test here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DataSourceRowModule - warnings the real coercer produces reach the caller")
class DataSourceRowModuleRealCoercionTest {

    private static final String TENANT = "tenant-1";
    private static final Long TABLE = 3L;

    @Mock private CrudRepository crudRepository;
    @Mock private VectorRepository vectorRepository;
    @Mock private DataSourceService dataSourceService;
    @Mock private com.apimarketplace.common.storage.service.StorageBreakdownService breakdownService;
    @Mock private DataSourceColumnRepository columnRepository;
    @Mock private com.apimarketplace.datasource.events.DatasourceRowEventPublisher rowEventPublisher;

    private DataSourceRowModule module;

    @BeforeEach
    void setUp() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", "ce");
        ColumnValueCoercer coercer = new ColumnValueCoercer();
        CrudExecutorService executor = new CrudExecutorService(
            crudRepository, vectorRepository, dataSourceService, breakdownService, coercer,
            new MediaCellHydrator(coercer), columnRepository, new SqlSanitizer(), rowEventPublisher,
            new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env), null),
            mock(ApplicationEventPublisher.class));
        module = new DataSourceRowModule(executor, dataSourceService, new ObjectMapper());

        DataSource table = new DataSource(TABLE, TENANT, "queue", null, null, null, null, null, null,
            null, null,
            Map.of("video", new ColumnMappingSpec("data.video", ColumnType.FILE, null, null, null),
                   "due", new ColumnMappingSpec("data.due", ColumnType.DATE, null, null, null)),
            null, null, null, null);
        when(dataSourceService.getDataSource(TABLE)).thenReturn(Optional.of(table));
        when(columnRepository.loadMappingSpec(eq(TABLE), anyString())).thenReturn(table.mappingSpec());
        when(crudRepository.createRows(eq(TABLE), anyString(), any())).thenReturn(List.of(7L));
    }

    private static ToolExecutionContext ctx() {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), Set.of(), null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolExecutionResult result) {
        return (Map<String, Object>) result.data();
    }

    /**
     * The exact shape of the incident: a file reference rebuilt by hand from a storage path, with
     * no id and no url. It is stored, the table shows it as unavailable, and the only place that
     * ever said so was a sentence this tool used to throw away.
     */
    @Test
    @DisplayName("A file reference with no id and no url comes back with the coercer's own sentence")
    void undisplayableFileRefSurfacesItsWarning() {
        Optional<ToolExecutionResult> res = module.execute("insert_rows",
            Map.of("table_id", TABLE, "rows", List.of(Map.of(
                "video", Map.of("_type", "file", "path", "1/wf/run/clip.mp4", "name", "clip.mp4")))),
            TENANT, ctx());

        assertThat(res).isPresent();
        assertThat(res.get().success()).as("the write itself succeeds - this is not a failure").isTrue();

        Map<String, Object> data = dataOf(res.get());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) data.get("warnings");
        assertThat(warnings).as("the coercer's diagnosis must reach the caller").isNotNull();
        assertThat(warnings).anySatisfy(w -> assertThat(w)
            .contains("video")
            .contains("cannot be displayed"));
        assertThat((String) data.get("message")).contains("read 'warnings'");
    }

    @Test
    @DisplayName("A normalisation the coercer performs is reported without being called a problem")
    void benignNormalisationIsReportedNeutrally() {
        Optional<ToolExecutionResult> res = module.execute("insert_rows",
            Map.of("table_id", TABLE, "rows", List.of(Map.of("due", "15/01/2024"))),
            TENANT, ctx());

        Map<String, Object> data = dataOf(res.get());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) data.get("warnings");
        assertThat(warnings).as("the coercer does report its date conversion").isNotNull();
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("ISO"));
        // The wording must survive an ordinary import: an instruction to go and rewrite these
        // sends the caller round a loop rewriting values the coercer normalises again.
        String message = (String) data.get("message");
        assertThat(message).doesNotContain("fix");
        assertThat(message).doesNotContain("not what you wrote");
    }

    @Test
    @DisplayName("A clean write carries no warnings field and no notice in its message")
    void cleanWriteStaysSilent() {
        Optional<ToolExecutionResult> res = module.execute("insert_rows",
            Map.of("table_id", TABLE, "rows", List.of(Map.of("due", "2024-01-15"))),
            TENANT, ctx());

        Map<String, Object> data = dataOf(res.get());
        assertThat(data).doesNotContainKey("warnings");
        assertThat((String) data.get("message")).doesNotContain("warnings");
    }

    @Test
    @DisplayName("A bulk write of one repeated finding answers with ONE line carrying the count")
    void repeatedFindingIsFoldedIntoOneLineWithACount() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(Map.of("due", "15/01/2024"));
        }
        when(crudRepository.createRows(eq(TABLE), anyString(), any()))
            .thenReturn(java.util.stream.LongStream.range(0, 25).boxed().toList());

        Optional<ToolExecutionResult> res = module.execute("insert_rows",
            Map.of("table_id", TABLE, "rows", rows), TENANT, ctx());

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) dataOf(res.get()).get("warnings");
        assertThat(warnings)
            .as("25 cells, one finding: the caller reads one line, not twenty-five")
            .hasSize(1);
        assertThat(warnings.get(0)).contains("ISO").contains("24 more like it");
    }

    /**
     * The reason this is grouped rather than truncated, as an executable case.
     *
     * <p>A 30-row import into a table with a DATE column and a FILE column: the first 25 rows carry
     * a non-ISO date and nothing else, and the LAST row also carries a hand-rebuilt file ref. The
     * date warnings are produced first and there are far more of them, so any head-cap - the ten
     * this code first shipped included - reports ten identical date lines, says "and N more of the
     * same kind", and throws away the single line the caller actually needed. Grouping keeps it
     * whatever position it was written in.
     */
    @Test
    @DisplayName("The one unusable value is reported even when 25 benign findings were produced first")
    void theRareUnusableWarningSurvivesAFloodOfBenignOnes() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            rows.add(Map.of("due", "15/01/2024"));
        }
        rows.add(Map.of(
            "due", "15/01/2024",
            "video", Map.of("_type", "file", "path", "1/wf/run/clip.mp4", "name", "clip.mp4")));
        when(crudRepository.createRows(eq(TABLE), anyString(), any()))
            .thenReturn(java.util.stream.LongStream.range(0, 26).boxed().toList());

        Optional<ToolExecutionResult> res = module.execute("insert_rows",
            Map.of("table_id", TABLE, "rows", rows), TENANT, ctx());

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) dataOf(res.get()).get("warnings");
        assertThat(warnings)
            .as("the file finding must be present, however many date lines preceded it")
            .anySatisfy(w -> assertThat(w).contains("video").contains("cannot be displayed"));
        assertThat(warnings)
            .as("and the flood must still be one line")
            .anySatisfy(w -> assertThat(w).contains("ISO").contains("more like it"));
        assertThat(warnings).hasSize(2);
    }

    @Test
    @DisplayName("Two findings that differ only in the value they quote are ONE finding")
    void twoValuesOfTheSameKindGroupTogether() {
        when(crudRepository.createRows(eq(TABLE), anyString(), any()))
            .thenReturn(List.of(1L, 2L));

        Optional<ToolExecutionResult> res = module.execute("insert_rows",
            Map.of("table_id", TABLE, "rows", List.of(
                Map.of("due", "15/01/2024"),
                Map.of("due", "16/02/2024"))),
            TENANT, ctx());

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) dataOf(res.get()).get("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("1 more like it");
    }

    @Test
    @DisplayName("A single warning is passed through with no count appended")
    void aLoneWarningIsNotDecorated() {
        Optional<ToolExecutionResult> res = module.execute("insert_rows",
            Map.of("table_id", TABLE, "rows", List.of(Map.of("due", "15/01/2024"))),
            TENANT, ctx());

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) dataOf(res.get()).get("warnings");
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).doesNotContain("more like it");
    }

    /**
     * update_rows runs a different collector on a different shape (a set map, not a row list), and
     * an early version of this change grouped only the insert side.
     */
    @Test
    @DisplayName("An update reports its warnings the same way an insert does")
    void updateSurfacesItsWarningsToo() {
        when(crudRepository.updateRows(eq(TABLE), anyString(), any(), any())).thenReturn(3);

        Optional<ToolExecutionResult> res = module.execute("update_rows",
            Map.of("table_id", TABLE,
                   "where", Map.of("column", "id", "operator", "=", "value", "7"),
                   "set", Map.of("video",
                       Map.of("_type", "file", "path", "1/wf/run/clip.mp4", "name", "clip.mp4"))),
            TENANT, ctx());

        assertThat(res).isPresent();
        Map<String, Object> data = dataOf(res.get());
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) data.get("warnings");
        assertThat(warnings).isNotNull();
        assertThat(warnings).anySatisfy(w -> assertThat(w)
            .contains("video")
            .contains("cannot be displayed"));
        assertThat((String) data.get("message")).contains("read 'warnings'");
    }
}
