package com.apimarketplace.publication.service;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one snapshot size budget of every listing type. Regression: table, interface and workflow
 * (application) listings had no size guard at all, only agents did, so a full-table copy could
 * ship an unbounded JSONB snapshot.
 */
@DisplayName("PublicationSnapshotBudget - one budget for every listing type")
class PublicationSnapshotBudgetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(Map.of("data", Map.of("n", i)));
        return rows;
    }

    private static PublicationSnapshotBudget budgetOf(int maxRows) {
        return new PublicationSnapshotBudget(MAPPER, PublicationSnapshotBudget.DEFAULT_MAX_BYTES, maxRows);
    }

    private static Map<String, Object> tableListing(String name, int rows) {
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("name", name);
        table.put("mappingSpec", Map.of());
        table.put("items", rows(rows));
        return table;
    }

    @Test
    @DisplayName("finds the copied tables of every snapshot shape: TABLE, interface-embedded, workflow plan, sub-workflow, agent")
    void findsTablesInEverySnapshotShape() {
        Map<String, Object> subPlan = Map.of("tables", List.of(Map.of(
                "dataSourceId", 3, "_snapshot_ds_name", "Sub", "_snapshot_ds_items", rows(2))));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("tables", List.of(Map.of("dataSourceId", 1, "_snapshot_ds_name", "Top", "_snapshot_ds_items", rows(1))));
        plan.put("_snapshot_subworkflows", Map.of("wf-2", Map.of("plan", subPlan)));
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("datasources", Map.of("7", tableListing("AgentTable", 4)));
        agent.put("workflows", Map.of("wf-9", Map.of("plan", plan)));
        Map<String, Object> iface = Map.of("htmlTemplate", "<div/>", "embeddedTable", tableListing("Embedded", 5));

        PublicationSnapshotBudget budget = budgetOf(100);

        assertThat(budget.tableCopies(tableListing("Standalone", 6)))
                .extracting(PublicationSnapshotBudget.TableCopy::name).containsExactly("Standalone");
        // Named, but with no id: the map key "embeddedTable" is a field name, not a table id.
        assertThat(budget.tableCopies(iface)).extracting(t -> t.name() + ":" + t.id())
                .containsExactly("Embedded:null");
        assertThat(budget.tableCopies(agent))
                .extracting(t -> t.name() + ":" + t.id() + ":" + t.rows().size())
                .containsExactlyInAnyOrder("AgentTable:7:4", "Top:1:1", "Sub:3:2");
    }

    @Test
    @DisplayName("a table exactly at the row limit passes; one row more is refused, naming the table and the limit")
    void exactlyAtTheLimitPassesOneMoreIsRefused() {
        PublicationSnapshotBudget budget = budgetOf(5);

        assertThatCode(() -> budget.assertWithinBudget(tableListing("Orders", 5), PublicationSnapshotBudget.Listing.TABLE))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> budget.assertWithinBudget(tableListing("Orders", 6), PublicationSnapshotBudget.Listing.TABLE))
                .isInstanceOfSatisfying(PublicationValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE);
                    assertThat(e.getMessage()).isEqualTo("Table 'Orders' has 6 rows (max 5 rows per published table). "
                            + "Delete rows from the table, then publish again.");
                    assertThat(e.getDetails()).containsEntry("maxTableRows", 5);
                });
    }

    @Test
    @DisplayName("a copy that stopped one row past the cap is reported as 'more than' the cap, never as its truncated count")
    void cappedCopyIsReportedAsMoreThanTheCap() {
        PublicationSnapshotBudget budget = budgetOf(DataSourceClient.MAX_COPY_ROWS);

        assertThatThrownBy(() -> budget.assertTableRows(PublicationSnapshotBudget.Listing.WORKFLOW, "9", "Orders",
                DataSourceClient.MAX_COPY_ROWS + 1))
                .hasMessageStartingWith("Table 'Orders' has more than " + DataSourceClient.MAX_COPY_ROWS + " rows")
                .isInstanceOfSatisfying(PublicationValidationException.class, e -> {
                    // No cut-off count anywhere: "more than 5000" and "(5001 rows)" would contradict.
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> breakdown = (List<Map<String, Object>>) e.getDetails().get("breakdown");
                    assertThat(breakdown.get(0)).containsEntry("id", "9").doesNotContainKey("items");
                    assertThat(e.getDetails().get("reason")).isEqualTo("Table 'Orders' has more than "
                            + DataSourceClient.MAX_COPY_ROWS + " rows (max " + DataSourceClient.MAX_COPY_ROWS
                            + " rows per published table).");
                });
    }

    @Test
    @DisplayName("agents keep their own refusal code and wording; every other listing gets the generic code")
    void listingDecidesTheCode() {
        PublicationSnapshotBudget budget = budgetOf(1);
        Map<String, Object> agent = Map.of("datasources", Map.of("7", tableListing("T", 2)));

        assertThatThrownBy(() -> budget.assertWithinBudget(agent, PublicationSnapshotBudget.Listing.AGENT))
                .isInstanceOfSatisfying(PublicationValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PublicationValidationException.AGENT_SNAPSHOT_TOO_LARGE);
                    assertThat(e.getMessage()).endsWith("Remove it from the agent's resource selection or reduce its content.");
                });
        assertThat(PublicationSnapshotBudget.Listing.forResource(PublicationType.INTERFACE).errorCode())
                .isEqualTo(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE);
        assertThat(PublicationSnapshotBudget.Listing.forResource(PublicationType.TABLE))
                .isEqualTo(PublicationSnapshotBudget.Listing.TABLE);
    }

    @Test
    @DisplayName("a workflow snapshot over the byte limit is refused with a breakdown of its copied tables, heaviest first")
    void byteLimitBreaksDownAPlanByItsTables() {
        PublicationSnapshotBudget budget = new PublicationSnapshotBudget(MAPPER, 500, 1000);
        Map<String, Object> plan = Map.of("tables", List.of(
                Map.of("dataSourceId", 1, "_snapshot_ds_name", "Small", "_snapshot_ds_items", rows(2)),
                Map.of("dataSourceId", 2, "_snapshot_ds_name", "Big", "_snapshot_ds_items", rows(60))));

        assertThatThrownBy(() -> budget.assertWithinBudget(plan, PublicationSnapshotBudget.Listing.WORKFLOW))
                .isInstanceOfSatisfying(PublicationValidationException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(PublicationValidationException.PUBLICATION_SNAPSHOT_TOO_LARGE);
                    assertThat(e.getDetails()).containsEntry("maxBytes", 500L);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> breakdown = (List<Map<String, Object>>) e.getDetails().get("breakdown");
                    assertThat(breakdown).extracting(b -> b.get("name")).containsExactly("Big", "Small");
                    assertThat(breakdown.get(0)).containsEntry("items", 60);
                });
    }

    @Test
    @DisplayName("a configured row limit above what a copy can read is clamped to MAX_COPY_ROWS")
    void rowLimitIsClampedToTheCopyCap() {
        PublicationSnapshotBudget budget = budgetOf(DataSourceClient.MAX_COPY_ROWS * 2);

        assertThat(budget.maxTableRows()).isEqualTo(DataSourceClient.MAX_COPY_ROWS);
        assertThat(PublicationSnapshotBudget.defaults(MAPPER).maxTableRows()).isEqualTo(5000);
    }
}
