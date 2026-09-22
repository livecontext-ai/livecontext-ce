package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The six collection nodes report the upstream collection they worked on under {@code input},
 * and they all got that through the same call: {@code ReportedParams.reportValue}.
 *
 * <p>One shared behaviour across six files, and no test exercised it, so all six could be
 * reverted together in silence. Two things have to hold at once and they pull against each
 * other: the rows a reader came to see must be READABLE, and a row carries whatever its
 * producer put in it - including a column an author called {@code password}. Bounding alone
 * (which is what these nodes did before) let a small one straight through; masking the whole
 * thing would empty the column of the data the node exists to transform.
 *
 * <p>Written as one class because it is one decision. Six near-identical tests in six files
 * would drift the way the two aggregate producers drifted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("a collection node reports the rows it worked on, masked and bounded")
class CollectionNodeReportedInputTest {

    private static final String SECRET = "s3cr3t-should-never-be-persisted";

    @Mock private WorkflowPlan mockPlan;
    @Mock private TemplateEngine engine;

    private ExecutionContext context() {
        return ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    /** One row a reader wants to see, and one field they must not. */
    private static List<Object> rowsWithACredentialColumn() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user", "bob");
        row.put("password", SECRET);
        return List.of(row);
    }

    /** More rows than any step row should carry: 400 of them, each ~200 chars. */
    private static List<Object> anOversizedCollection() {
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("blob", "y".repeat(200));
            rows.add(row);
        }
        return rows;
    }

    private <T extends BaseNode> T wired(T node, Object resolvesTo) {
        when(engine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
            .thenReturn(resolvesTo);
        node.setTemplateAdapter(new V2TemplateAdapter(engine));
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(NodeExecutionResult result) {
        return (Map<String, Object>) result.output().get("resolved_params");
    }

    /** Every node under test, built the way the engine builds it, reading one expression. */
    private List<Map.Entry<String, BaseNode>> nodesReading(String expression) {
        List<Map.Entry<String, BaseNode>> all = new ArrayList<>();
        all.add(Map.entry("filter", new FilterNode("core:filter",
            List.of(new Core.FilterCondition("user", "is_not_empty", null)), "and", expression)));
        all.add(Map.entry("sort", new SortNode("core:sort",
            List.of(new Core.SortField("user", "asc")), expression)));
        all.add(Map.entry("limit", new LimitNode("core:limit", 10, "first", 0, expression)));
        all.add(Map.entry("remove_duplicates", new RemoveDuplicatesNode("core:dedupe",
            List.of("user"), "first", expression)));
        all.add(Map.entry("summarize", new SummarizeNode("core:summarize",
            new Core.SummarizeConfig(List.of(), List.of("user"), expression))));
        return all;
    }

    @Test
    @DisplayName("a credential-named column of the rows never reaches the row, on any of them")
    void masksACredentialColumnOfTheUpstreamRows() {
        for (Map.Entry<String, BaseNode> entry : nodesReading("{{core:fetch.output.rows}}")) {
            BaseNode node = wired(entry.getValue(), rowsWithACredentialColumn());

            Map<String, Object> params = paramsOf(node.execute(context()));

            assertThat(params).as("%s must report what it worked on", entry.getKey()).isNotNull();
            assertThat(params.toString())
                .as("%s published a credential-named column of its upstream rows", entry.getKey())
                .doesNotContain(SECRET);
            assertThat(params.toString())
                .as("%s must still show the rows: masking everything empties the column "
                    + "of the data the node exists to transform", entry.getKey())
                .contains("bob");
        }
    }

    @Test
    @DisplayName("an oversized collection is described rather than copied onto every item's row")
    void boundsAnOversizedUpstreamCollection() {
        for (Map.Entry<String, BaseNode> entry : nodesReading("{{core:fetch.output.rows}}")) {
            BaseNode node = wired(entry.getValue(), anOversizedCollection());

            Map<String, Object> params = paramsOf(node.execute(context()));

            assertThat(String.valueOf(params.get("input")).length())
                .as("%s copied a whole upstream collection onto the row of every item "
                    + "of every split", entry.getKey())
                .isLessThan(2_000);
        }
    }

    @Test
    @DisplayName("compare_datasets reports BOTH its datasets under the same rule")
    void masksAndBoundsBothComparedDatasets() {
        // Two keys instead of one, and both are upstream collections, so both need the walk.
        CompareDatasetsNode node = new CompareDatasetsNode("core:compare",
            new Core.CompareDatasetsConfig("{{core:a.output.rows}}", "{{core:b.output.rows}}",
                List.of("user"), true, true, true));
        wired(node, rowsWithACredentialColumn());

        Map<String, Object> params = paramsOf(node.execute(context()));

        assertThat(params.toString()).doesNotContain(SECRET);
        assertThat(params.toString()).contains("bob");
    }


    @Test
    @DisplayName("the two aggregate producers write byte-identical rows, which is the claim the shared builder makes")
    @SuppressWarnings("unchecked")
    void bothAggregateProducersWriteTheSameRow() {
        // AggregateReportedParamsTest pins the shared builder; nothing pinned that the SPLIT
        // path actually calls it. Re-inlining a copy in SplitAggregateHandler left every test
        // green, so the class's own claim - "structurally impossible rather than merely
        // fixed" - was prose. This drives the handler's private builder against the node's.
        List<AggregateNode.AggregateField> fields = List.of(
            new AggregateNode.AggregateField("token", "{{core:auth.output.session_label}}"),
            new AggregateNode.AggregateField("total", "{{core:rows.output.amount}}"));
        AggregateNode node = new AggregateNode("core:aggregate", fields, null);

        com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler handler =
            new com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler(
                null, null, null, null, null);
        java.lang.reflect.Method build;
        try {
            build = com.apimarketplace.orchestrator.execution.v2.split.SplitAggregateHandler.class
                .getDeclaredMethod(
                "buildAggregateResolvedParams", String.class, Map.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("the split path's builder was renamed or removed", e);
        }
        build.setAccessible(true);
        Map<String, Object> onTheSplitPath;
        try {
            onTheSplitPath = (Map<String, Object>) build.invoke(
                handler, "core:aggregate", Map.of("core:aggregate", node));
        } catch (Exception e) {
            throw new AssertionError("the split path's builder threw", e);
        }

        assertThat(onTheSplitPath)
            .as("one node, one row, whichever producer writes it")
            .isEqualTo(AggregateNode.buildReportedParams(fields, "core:aggregate"));
        assertThat(onTheSplitPath.get("token"))
            .as("and the author's label is readable on both, because its value is an expression")
            .isEqualTo("{{core:auth.output.session_label}}");
    }
}
