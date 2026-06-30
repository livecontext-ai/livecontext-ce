package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.ColumnTypeDto;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.SmartDefaultsEngine;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for the event-driven "datasource" / "table" branch of TriggerCreator.
 *
 * Verifies:
 *   - event_types defaults (all three) and normalization (alias, dedup, mixed case)
 *   - filter shape persisted as-is in params
 *   - explicit failure on unknown event_type
 *   - explicit failure on malformed filter (missing column / operator, bad operator, missing value)
 *   - is_null / is_not_null do not require a value
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerCreator - Datasource (event-driven) params")
class TriggerCreatorDatasourceTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private SmartDefaultsEngine smartDefaultsEngine;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private TriggerClient triggerClient;

    private TriggerCreator creator;

    @BeforeEach
    void setUp() {
        creator = new TriggerCreator(sessionStore, dataSourceClient, smartDefaultsEngine, responseOptimizer, triggerClient);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.create("tenant-1", "conv-1", "Test Workflow", null);
    }

    private Map<String, Object> baseParams(String label, Object tableId) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", label);
        p.put("trigger_type", "table");
        p.put("table_id", tableId);
        return p;
    }

    private void stubHappyPathDefaults() {
        lenient().when(smartDefaultsEngine.applyTriggerDefaults(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new LinkedHashMap<>());
        // Return a datasource with no column mapping so buildTriggerSchema stays silent
        DataSourceDto ds = new DataSourceDto(42L, "tenant-1", "orders", null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        lenient().when(dataSourceClient.getDataSource(anyLong(), anyString())).thenReturn(ds);
        lenient().when(dataSourceClient.getDataSourcesByTenant(anyString())).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getParams(WorkflowBuilderSession session) {
        List<Map<String, Object>> triggers = session.getTriggers();
        Map<String, Object> trigger = triggers.get(triggers.size() - 1);
        return (Map<String, Object>) trigger.get("params");
    }

    @SuppressWarnings("unchecked")
    private List<String> getEventTypes(WorkflowBuilderSession session) {
        return (List<String>) getParams(session).get("event_types");
    }

    // ==================== event_types defaults ====================

    @Nested
    @DisplayName("event_types defaulting and normalization")
    class EventTypesDefault {

        @Test
        @DisplayName("Omitting event_types defaults to all three event types")
        void defaultsToAllThreeEventTypes() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            ToolExecutionResult result = creator.executeAddTrigger(session, baseParams("On Order Change", 42L), "tenant-1");
            assertThat(result.success()).isTrue();

            Map<String, Object> params = getParams(session);
            assertThat(params).containsKey("event_types");
            assertThat(getEventTypes(session))
                    .containsExactlyInAnyOrder("row_created", "row_updated", "row_deleted");
        }

        @Test
        @DisplayName("event_types as explicit list is kept verbatim")
        void explicitListIsKept() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("On Insert Only", 42L);
            params.put("event_types", List.of("row_created"));
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getEventTypes(session)).containsExactly("row_created");
        }

        @Test
        @DisplayName("eventTypes camelCase alias is accepted")
        void camelCaseAlias() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("On Update", 42L);
            params.put("eventTypes", List.of("row_updated"));
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getEventTypes(session)).containsExactly("row_updated");
        }

        @Test
        @DisplayName("Comma-separated string of event_types is parsed to a list")
        void commaSeparatedString() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("On Two Events", 42L);
            params.put("event_types", "row_created, row_deleted");
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getEventTypes(session))
                    .containsExactlyInAnyOrder("row_created", "row_deleted");
        }

        @Test
        @DisplayName("Mixed-case event_types are normalized to lowercase")
        void mixedCaseNormalized() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("Mixed Case", 42L);
            params.put("event_types", List.of("Row_Created", "ROW_DELETED"));
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getEventTypes(session))
                    .containsExactlyInAnyOrder("row_created", "row_deleted");
        }

        @Test
        @DisplayName("Duplicate event_types are deduplicated")
        void duplicatesDeduped() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("Dup Test", 42L);
            params.put("event_types", List.of("row_created", "row_created", "row_updated"));
            creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(getEventTypes(session))
                    .containsExactly("row_created", "row_updated");
        }
    }

    // ==================== error trigger validation + schema ====================

    @Nested
    @DisplayName("Error trigger requires parent_workflow_id and exposes documented outputs")
    class ErrorTriggerContract {

        private void stubMinimalDefaults() {
            lenient().when(smartDefaultsEngine.applyTriggerDefaults(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new LinkedHashMap<>());
        }

        @Test
        @DisplayName("Missing parent_workflow_id is rejected with a bootstrap-aware message")
        void missingParentWorkflowIdRejected() {
            stubMinimalDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "On Failure");
            params.put("trigger_type", "error");
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("parent_workflow_id is required for error triggers");
            // The error message must mention the bootstrap step so the agent doesn't get stuck.
            assertThat(result.error()).contains("workflow(action='execute'");
            // Bootstrap returns status='BOOTSTRAPPED' (renamed from WAITING_TRIGGER to make the
            // seed-run lifecycle explicit - see project_error_workflow_bootstrap.md).
            assertThat(result.error()).contains("BOOTSTRAPPED");
        }

        @Test
        @DisplayName("parent_workflow_id stored as the trigger node's id (so dispatcher can match it)")
        void parentWorkflowIdStoredAsTriggerId() {
            stubMinimalDefaults();
            WorkflowBuilderSession session = createSession();

            String parentId = "11111111-2222-3333-4444-555555555555";
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "On Failure");
            params.put("trigger_type", "error");
            params.put("parent_workflow_id", parentId);
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(session.getTriggers()).hasSize(1);
            Map<String, Object> trigger = session.getTriggers().get(0);
            // ErrorTriggerDispatchService → WorkflowRepository.findByErrorTrigger queries
            // the trigger's `id` field (not a separate column). Storing the parent UUID
            // here is what makes the dispatch fire.
            assertThat(trigger.get("id")).isEqualTo(parentId);
            assertThat(trigger.get("type")).isEqualTo("error");
        }

        @Test
        @DisplayName("Legacy 'id' alias still resolves to parent_workflow_id (V11 backwards-compat)")
        void legacyIdAliasAccepted() {
            stubMinimalDefaults();
            WorkflowBuilderSession session = createSession();

            String parentId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Legacy Handler");
            params.put("trigger_type", "error");
            // V11 seed advertised the parameter as "id" - plans saved before V125 use this.
            params.put("id", parentId);
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isTrue();
            assertThat(session.getTriggers().get(0).get("id")).isEqualTo(parentId);
        }

        @Test
        @DisplayName("Schema exposes the documented error-payload outputs (parentWorkflowId, errorMessage, status, …)")
        void schemaExposesDocumentedOutputs() {
            stubMinimalDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "On Order Fail");
            params.put("trigger_type", "error");
            params.put("parent_workflow_id", "11111111-2222-3333-4444-555555555555");
            creator.executeAddTrigger(session, params, "tenant-1");

            WorkflowBuilderSession.NodeSchema schema = session.getNodeSchemas().get("trigger:on_order_fail");
            assertThat(schema).as("schema must be registered for error trigger").isNotNull();
            // Match the keys ErrorTriggerDispatchService.buildErrorPayload actually emits.
            assertThat(schema.getOutputs()).containsKeys(
                "parentWorkflowId", "parentRunId", "status", "errorMessage", "triggeredAt",
                "failedSteps", "completedSteps", "totalSteps", "skippedSteps");
            assertThat(schema.getReferenceSyntax().get("errorMessage"))
                .isEqualTo("{{trigger:on_order_fail.output.errorMessage}}");
        }
    }

    // ==================== schema contract: safe per-column paths ====================

    @Nested
    @DisplayName("schema exposes safe nested .row.<col> paths (no LEGACY_RESERVED_KEYS shadowing)")
    class SchemaContract {

        private void stubDataSourceWithColumns(Map<String, ColumnMappingSpecDto> mappingSpec) {
            lenient().when(smartDefaultsEngine.applyTriggerDefaults(any()))
                    .thenAnswer(inv -> inv.getArgument(0));
            lenient().when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new LinkedHashMap<>());
            DataSourceDto ds = new DataSourceDto(42L, "tenant-1", "tasks", null,
                    null, null, null, null, null, null, null, mappingSpec, null, null, null, null);
            lenient().when(dataSourceClient.getDataSource(anyLong(), anyString())).thenReturn(ds);
            lenient().when(dataSourceClient.getDataSourcesByTenant(anyString())).thenReturn(List.of());
        }

        private ColumnMappingSpecDto col(ColumnTypeDto type) {
            return new ColumnMappingSpecDto(null, type, null, null, null);
        }

        @Test
        @DisplayName("Per-column outputs are exposed as 'row.<column>', not bare '<column>'")
        void perColumnUsesRowDotPrefix() {
            // Includes 'status' (LEGACY_RESERVED_KEYS - would shadow if flat) and a clean column.
            Map<String, ColumnMappingSpecDto> mappingSpec = new LinkedHashMap<>();
            mappingSpec.put("status", col(ColumnTypeDto.TEXT));
            mappingSpec.put("task_name", col(ColumnTypeDto.TEXT));
            stubDataSourceWithColumns(mappingSpec);
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, baseParams("On Task Change", 42L), "tenant-1");

            WorkflowBuilderSession.NodeSchema schema = session.getNodeSchemas().get("trigger:on_task_change");
            assertThat(schema).as("schema must be registered").isNotNull();
            Map<String, String> outputs = schema.getOutputs();
            // Column refs use the safe nested path
            assertThat(outputs).containsKeys("row.status", "row.task_name");
            // Bare top-level column keys MUST NOT be advertised (would silently shadow on reserved names)
            assertThat(outputs).doesNotContainKeys("status", "task_name");
        }

        @Test
        @DisplayName("Event-meta keys (event_type, row_id, row, previous_row) are present at top level")
        void eventMetaKeysPresent() {
            stubDataSourceWithColumns(Map.of("name", col(ColumnTypeDto.TEXT)));
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, baseParams("On Row", 42L), "tenant-1");

            Map<String, String> outputs = session.getNodeSchemas().get("trigger:on_row").getOutputs();
            assertThat(outputs).containsKeys("event_type", "row_id", "row", "previous_row");
        }

        @Test
        @DisplayName("Batch-scan keys (data, count) are advertised so agents can chain core:split")
        void batchScanKeysPresent() {
            stubDataSourceWithColumns(Map.of("name", col(ColumnTypeDto.TEXT)));
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, baseParams("On Row", 42L), "tenant-1");

            Map<String, String> schema = session.getNodeSchemas().get("trigger:on_row").getOutputs();
            assertThat(schema).containsKeys("data", "count");
            assertThat(schema.get("data")).contains("find_rows");
        }

        @Test
        @DisplayName("Reference syntax for a column yields {{node.output.row.<col>}} - never the colliding flat form")
        void referenceSyntaxUsesNestedRowPath() {
            stubDataSourceWithColumns(Map.of("status", col(ColumnTypeDto.TEXT)));
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, baseParams("On Status Change", 42L), "tenant-1");

            Map<String, String> refs = session.getNodeSchemas().get("trigger:on_status_change").getReferenceSyntax();
            assertThat(refs.get("row.status"))
                    .isEqualTo("{{trigger:on_status_change.output.row.status}}");
            // The flat colliding form must not be produced
            assertThat(refs).doesNotContainKey("status");
        }
    }

    // ==================== filter persistence ====================

    @Nested
    @DisplayName("filter is persisted as-is")
    class FilterPersistence {

        @Test
        @DisplayName("Simple equality filter is stored in params.filter")
        void simpleEqualityFilter() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("On Paid Order", 42L);
            params.put("filter", Map.of("column", "status", "operator", "=", "value", "paid"));
            creator.executeAddTrigger(session, params, "tenant-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) getParams(session).get("filter");
            assertThat(filter)
                    .containsEntry("column", "status")
                    .containsEntry("operator", "=")
                    .containsEntry("value", "paid");
        }

        @Test
        @DisplayName("is_null operator persists without a value")
        void isNullFilterNoValueNeeded() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("On Null", 42L);
            params.put("filter", Map.of("column", "assigned_to", "operator", "is_null"));
            creator.executeAddTrigger(session, params, "tenant-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) getParams(session).get("filter");
            assertThat(filter)
                    .containsEntry("column", "assigned_to")
                    .containsEntry("operator", "is_null");
            assertThat(filter).doesNotContainKey("value");
        }

        @Test
        @DisplayName("Omitting filter results in no filter entry in params")
        void filterOmittedIsAbsent() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            creator.executeAddTrigger(session, baseParams("No Filter", 42L), "tenant-1");

            assertThat(getParams(session)).doesNotContainKey("filter");
        }

        @Test
        @DisplayName("'==' is accepted as a wire-form alias for '=' (frontend surfaces '==' in its UI because '=' reads as an assignment)")
        void doubleEqualsOperatorAcceptedAsAlias() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("On Paid Order (==)", 42L);
            params.put("filter", Map.of("column", "status", "operator", "==", "value", "paid"));
            creator.executeAddTrigger(session, params, "tenant-1");

            @SuppressWarnings("unchecked")
            Map<String, Object> filter = (Map<String, Object>) getParams(session).get("filter");
            assertThat(filter)
                    .containsEntry("column", "status")
                    .containsEntry("operator", "==")
                    .containsEntry("value", "paid");
        }
    }

    // ==================== explicit failure on bad input ====================

    @Nested
    @DisplayName("validation rejects malformed input")
    class ValidationFailures {

        @Test
        @DisplayName("Unknown event_type returns failure naming allowed values")
        void unknownEventTypeRejected() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("Bad Event", 42L);
            params.put("event_types", List.of("row_merged"));
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("row_merged");
            assertThat(result.error()).contains("row_created").contains("row_updated").contains("row_deleted");
        }

        @Test
        @DisplayName("filter missing column is rejected")
        void filterMissingColumnRejected() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("Bad Filter", 42L);
            params.put("filter", Map.of("operator", "=", "value", "x"));
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("filter.column");
        }

        @Test
        @DisplayName("filter missing operator is rejected")
        void filterMissingOperatorRejected() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("Bad Filter 2", 42L);
            params.put("filter", Map.of("column", "status", "value", "x"));
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("filter.operator");
        }

        @Test
        @DisplayName("filter operator outside allowed list is rejected")
        void filterInvalidOperatorRejected() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("Bad Op", 42L);
            params.put("filter", Map.of("column", "status", "operator", "regex", "value", ".*"));
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("regex");
            assertThat(result.error()).contains("Allowed:");
        }

        @Test
        @DisplayName("filter with comparator operator but no value is rejected")
        void filterMissingValueForComparatorRejected() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("Needs Value", 42L);
            Map<String, Object> badFilter = new LinkedHashMap<>();
            badFilter.put("column", "amount");
            badFilter.put("operator", ">");
            params.put("filter", badFilter);
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("filter.value");
        }

        @Test
        @DisplayName("filter not a map is rejected")
        void filterWrongShapeRejected() {
            stubHappyPathDefaults();
            WorkflowBuilderSession session = createSession();

            Map<String, Object> params = baseParams("Bad Shape", 42L);
            params.put("filter", "status=paid");
            ToolExecutionResult result = creator.executeAddTrigger(session, params, "tenant-1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("filter");
            assertThat(result.error()).contains("object");
        }
    }
}
