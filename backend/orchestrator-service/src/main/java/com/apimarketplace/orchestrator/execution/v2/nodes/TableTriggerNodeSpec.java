package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Node specification for the event-driven datasource trigger.
 *
 * Each fire represents ONE row-level event (row_created / row_updated /
 * row_deleted). Dynamic columns from {@code row} are also flattened at the
 * top level so authors can write {@code {{trigger.column_name}}} directly.
 */
@Component
public class TableTriggerNodeSpec implements NodeSpec {

    /**
     * Keys that must NEVER be flattened as row columns (engine metadata +
     * our own structured payload keys). If a row column happened to collide
     * with any of these names, the structured key wins.
     */
    private static final Set<String> RESERVED_FIELDS = Set.of(
        // engine metadata (spec-specific; engine-envelope keys handled via ENGINE_ENVELOPE_KEYS)
        "itemId",
        "iteration", "currentIteration", "status", "http_status",
        "tenant_id", "tenantId", "trigger_id", "triggerId",
        "epoch", "spawn", "absoluteIndex",
        // structured payload keys surfaced by this trigger
        "row", "previous_row", "event_type", "row_id", "datasource_id",
        "triggered_at", "triggered_by", "triggeredBy"
        // engine-envelope keys (node_type, item_index, itemIndex, item_id, resolved_params)
        // are removed via GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS in customTransform
    );

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("TABLE_TRIGGER")
            .label("Datasource Trigger")
            .category("trigger")
            .variablePrefix("trigger")
            .description("Event-driven trigger. Fires one workflow run per row created/updated/deleted in a datasource.")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("row")
                    .type("object")
                    .description("Row that triggered the event. Current state for row_created/row_updated; last-known state for row_deleted.")
                    .build(),
                OutputFieldDef.builder()
                    .key("previous_row")
                    .type("object")
                    .description("Pre-change row. Populated only for row_updated; null otherwise.")
                    .build(),
                OutputFieldDef.builder()
                    .key("event_type")
                    .type("string")
                    .description("row_created | row_updated | row_deleted")
                    .build(),
                OutputFieldDef.builder()
                    .key("row_id")
                    .type("number")
                    .description("ID of the affected row in the datasource.")
                    .build(),
                OutputFieldDef.builder()
                    .key("datasource_id")
                    .type("number")
                    .description("ID of the datasource that emitted the event.")
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_at")
                    .type("datetime")
                    .description("ISO-8601 timestamp captured after commit.")
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_by")
                    .type("string")
                    .description("User identifier of who triggered this event, resolved by trigger-user resolver (empty string when no tenant identity available).")
                    .defaultValue("")
                    .aliases(List.of("triggeredBy"))
                    .build(),
                OutputFieldDef.builder()
                    .key("trigger_id")
                    .type("string")
                    .description("Internal id of the trigger that fired")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_id")
                    .type("string")
                    .description("Item identifier for split-context tracking")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_index")
                    .type("number")
                    .description("Index when iterating over items")
                    .build()
                // Dynamic row columns are also stored at top level so authors can write
                // {{trigger.column_name}} directly. Column names vary per datasource schema
                // and cannot be declared statically. Reserved payload keys (row, previous_row,
                // event_type, row_id, datasource_id, triggered_at, triggered_by) are never
                // overwritten by row columns.
            ))
            .keywords(List.of("table", "datasource", "trigger", "row", "event", "reactive"))
            .build();
    }

    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        Map<String, Object> dbOutput = new HashMap<>();

        copyIfPresent(backendOutput, dbOutput, "row");
        copyIfPresent(backendOutput, dbOutput, "previous_row");
        copyIfPresent(backendOutput, dbOutput, "event_type");
        copyIfPresent(backendOutput, dbOutput, "row_id");
        copyIfPresent(backendOutput, dbOutput, "datasource_id");
        copyIfPresent(backendOutput, dbOutput, "triggered_at");
        copyIfPresent(backendOutput, dbOutput, "triggered_by");

        // Flatten dynamic row columns at the top level. Reserved names cannot
        // be overwritten - if the row has a column literally called
        // "event_type" or "row_id", the structured value wins.
        for (Map.Entry<String, Object> entry : backendOutput.entrySet()) {
            String key = entry.getKey();
            if (RESERVED_FIELDS.contains(key)) continue;
            if (GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.contains(key)) continue;
            dbOutput.putIfAbsent(key, entry.getValue());
        }
        // Remove engine-envelope keys shared across all node specs
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(dbOutput::remove);

        return dbOutput;
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst, String key) {
        if (src.containsKey(key)) {
            dst.put(key, src.get(key));
        }
    }
}
