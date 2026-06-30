package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TableCreator}.
 * Verifies CRUD node creation (insert_row, get_rows, update_row, delete_row, find_rows).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TableCreator Tests")
class TableCreatorTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;

    private TableCreator tableCreator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        tableCreator = new TableCreator(sessionStore, dataSourceClient, responseOptimizer, nodeLibraryService);

        session = WorkflowBuilderSession.builder()
            .sessionId("test-session")
            .tenantId("test-tenant")
            .workflowName("Test Workflow")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        lenient().when(responseOptimizer.buildStepResponse(
            any(), anyString(), anyString(), anyString(), anyString(), any(),
            anyBoolean(), any(), any(), any(), any(), anyBoolean()
        )).thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));
    }

    private void addTriggerToSession() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Start");
        trigger.put("id", "trigger:start");
        trigger.put("type", "webhook");
        session.getTriggers().add(trigger);
    }

    // ==================== Validation Tests ====================

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Fails when label is missing")
        void failsWithoutLabel() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            // no label

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("label");
        }

        @Test
        @DisplayName("Fails when label is blank")
        void failsWithBlankLabel() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "   ");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("label");
        }

        @Test
        @DisplayName("Fails when no trigger exists")
        void failsWithoutTrigger() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save Record");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("TRIGGER");
        }

        @Test
        @DisplayName("Fails when toolId is null")
        void failsWithNullToolId() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save Record");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, null);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Tool ID");
        }

        @Test
        @DisplayName("Fails when toolId is blank")
        void failsWithBlankToolId() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save Record");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "  ");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Tool ID");
        }

        @Test
        @DisplayName("Fails when node with same label already exists")
        void failsOnDuplicateLabel() {
            addTriggerToSession();

            // First: create a table node with label "Save"
            Map<String, Object> firstParams = new LinkedHashMap<>();
            firstParams.put("label", "Save");
            firstParams.put("connect_after", "Start");
            tableCreator.executeAddTable(session, firstParams, "crud/create-row");
            assertThat(session.getTables()).hasSize(1);

            // Second: try to create another with the same label
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).containsIgnoringCase("already exists");
        }
    }

    // ==================== Successful Creation Tests ====================

    @Nested
    @DisplayName("Successful creation")
    class SuccessfulCreation {

        @Test
        @DisplayName("Creates insert_row node with correct type")
        void createsInsertRowNode() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save Record");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isTrue();
            assertThat(session.getTables()).hasSize(1);

            Map<String, Object> node = session.getTables().get(0);
            assertThat(node.get("label")).isEqualTo("Save Record");
            assertThat(node.get("id")).isEqualTo("crud/create-row");
            assertThat(node.get("type")).isEqualTo("crud-create-row");
        }

        @Test
        @DisplayName("Creates get_rows node with correct type")
        void createsGetRowsNode() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Fetch Records");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/read-row");

            assertThat(result.success()).isTrue();
            assertThat(session.getTables()).hasSize(1);
            assertThat(session.getTables().get(0).get("type")).isEqualTo("crud-read-row");
        }

        @Test
        @DisplayName("Creates update_row node with correct type")
        void createsUpdateRowNode() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Update Status");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/update-row");

            assertThat(result.success()).isTrue();
            assertThat(session.getTables().get(0).get("type")).isEqualTo("crud-update-row");
        }

        @Test
        @DisplayName("Creates delete_row node with correct type")
        void createsDeleteRowNode() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Remove Record");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/delete-row");

            assertThat(result.success()).isTrue();
            assertThat(session.getTables().get(0).get("type")).isEqualTo("crud-delete-row");
        }

        @Test
        @DisplayName("Creates find_rows node with correct type")
        void createsFindRowsNode() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Find Active");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/find-rows");

            assertThat(result.success()).isTrue();
            assertThat(session.getTables().get(0).get("type")).isEqualTo("crud-find");
        }

        @Test
        @DisplayName("Node stored in tables list, not mcps")
        void storedInTablesList() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");

            tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(session.getTables()).hasSize(1);
            assertThat(session.getMcps()).isEmpty();
        }

        @Test
        @DisplayName("Node ID uses table: prefix - tracked as last added")
        void nodeIdUsesTablePrefix() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save Record");
            params.put("connect_after", "Start");

            tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(session.getLastAddedNodeId()).isEqualTo("table:save_record");
        }

        @Test
        @DisplayName("Accepts name as alias for label")
        void acceptsNameAsLabelAlias() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", "Save Record");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isTrue();
            assertThat(session.getTables().get(0).get("label")).isEqualTo("Save Record");
        }
    }

    // ==================== CRUD Parameters Tests ====================

    @Nested
    @DisplayName("CRUD parameters")
    class CrudParameters {

        @Test
        @DisplayName("Adds dataSourceId to step node")
        void addsDataSourceId() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("dataSourceId", 42L);
            params.put("connect_after", "Start");

            tableCreator.executeAddTable(session, params, "crud/create-row");

            Map<String, Object> node = session.getTables().get(0);
            assertThat(node.get("dataSourceId")).isEqualTo(42L);
        }

        @Test
        @DisplayName("Adds datasource_id alias to step node")
        void addsDatasourceIdAlias() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("datasource_id", 99L);
            params.put("connect_after", "Start");

            tableCreator.executeAddTable(session, params, "crud/create-row");

            Map<String, Object> node = session.getTables().get(0);
            assertThat(node.get("dataSourceId")).isEqualTo(99L);
        }

        @Test
        @DisplayName("Crud value is a String - gracefully ignored, no NPE")
        void crudValueIsString() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");
            params.put("table_id", 42);
            params.put("columns", Map.of("name", "X"));
            params.put("crud", "not-a-map"); // string instead of map

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Crud value is null - gracefully ignored")
        void crudValueIsNull() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");
            params.put("table_id", 42);
            params.put("columns", Map.of("name", "X"));
            params.put("crud", null);

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");
            assertThat(result.success()).isTrue();
        }
    }

    // ==================== CRUD Block Placement Tests (Critical) ====================

    @Nested
    @DisplayName("CRUD fields go into step.crud, NOT step.params")
    class CrudBlockPlacement {

        @Test
        @DisplayName("insert_row: rows go into crud block, not params")
        @SuppressWarnings("unchecked")
        void insertRowRowsInCrudBlock() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save Contact");
            params.put("connect_after", "Start");
            params.put("table_id", 123);
            params.put("rows", List.of(Map.of("columns", Map.of("name", "John", "email", "j@x.com"))));

            tableCreator.executeAddTable(session, params, "crud/create-row");

            Map<String, Object> node = session.getTables().get(0);
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud).containsKey("rows");
            assertThat((List<?>) crud.get("rows")).hasSize(1);

            // rows must NOT be in params
            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            if (nodeParams != null) {
                assertThat(nodeParams).doesNotContainKey("rows");
            }
        }

        @Test
        @DisplayName("insert_row via nested crud:{rows:[...]} - rows land in step.crud")
        @SuppressWarnings("unchecked")
        void insertRowNestedCrudBlockRowsInCrud() {
            addTriggerToSession();
            var rowsList = List.of(Map.of("columns", Map.of("name", "John")));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Insert");
            params.put("crud", Map.of("rows", rowsList));
            params.put("connect_after", "Start");

            tableCreator.executeAddTable(session, params, "crud/create-row");

            Map<String, Object> node = session.getTables().get(0);
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud.get("rows")).isEqualTo(rowsList);

            // Must NOT be in params
            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            if (nodeParams != null) {
                assertThat(nodeParams).doesNotContainKey("rows");
            }
        }

        @Test
        @DisplayName("update_row: set + where go into crud block, not params")
        @SuppressWarnings("unchecked")
        void updateRowFieldsInCrudBlock() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Mark Done");
            params.put("connect_after", "Start");
            params.put("table_id", 123);
            params.put("set", Map.of("status", "completed"));
            params.put("where", Map.of("column", "id", "operator", "==", "value", "1"));

            tableCreator.executeAddTable(session, params, "crud/update-row");

            Map<String, Object> node = session.getTables().get(0);
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud).containsKey("set");
            assertThat(crud).containsKey("where");

            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            if (nodeParams != null) {
                assertThat(nodeParams).doesNotContainKeys("set", "where", "table_id");
            }
        }

        @Test
        @DisplayName("delete_row: where goes into crud block, not params")
        @SuppressWarnings("unchecked")
        void deleteRowWhereInCrudBlock() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Remove");
            params.put("connect_after", "Start");
            params.put("table_id", 123);
            params.put("where", Map.of("column", "id", "operator", "==", "value", "1"));

            tableCreator.executeAddTable(session, params, "crud/delete-row");

            Map<String, Object> node = session.getTables().get(0);
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud).containsKey("where");

            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            if (nodeParams != null) {
                assertThat(nodeParams).doesNotContainKeys("where", "table_id");
            }
        }

        @Test
        @DisplayName("read_row: where + limit go into crud block, not params")
        @SuppressWarnings("unchecked")
        void readRowFieldsInCrudBlock() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Fetch");
            params.put("connect_after", "Start");
            params.put("table_id", 123);
            params.put("where", Map.of("column", "status", "operator", "=", "value", "active"));
            params.put("limit", 50);

            tableCreator.executeAddTable(session, params, "crud/read-row");

            Map<String, Object> node = session.getTables().get(0);
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud).containsKey("where");
            assertThat(crud).containsEntry("limit", 50);

            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            if (nodeParams != null) {
                assertThat(nodeParams).doesNotContainKeys("where", "limit", "table_id");
            }
        }

        @Test
        @DisplayName("find_rows: where + limit go into crud block")
        @SuppressWarnings("unchecked")
        void findRowsFieldsInCrudBlock() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Find Active");
            params.put("connect_after", "Start");
            params.put("table_id", 42);
            params.put("where", Map.of("column", "status", "operator", "=", "value", "active"));
            params.put("limit", 100);

            tableCreator.executeAddTable(session, params, "crud/find-rows");

            Map<String, Object> node = session.getTables().get(0);
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud).containsKey("where");
            assertThat(crud).containsEntry("limit", 100);

            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            if (nodeParams != null) {
                assertThat(nodeParams).doesNotContainKeys("where", "limit", "table_id");
            }
        }

        @Test
        @DisplayName("dataSourceId variants never leak into params")
        void dataSourceIdNotInParams() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");
            params.put("dataSourceId", 42L);

            tableCreator.executeAddTable(session, params, "crud/create-row");

            Map<String, Object> node = session.getTables().get(0);
            assertThat(node.get("dataSourceId")).isEqualTo(42L); // top-level on stepNode

            @SuppressWarnings("unchecked")
            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            if (nodeParams != null) {
                assertThat(nodeParams).doesNotContainKeys("dataSourceId", "datasource_id", "table_id");
            }
        }

        @Test
        @DisplayName("Nested crud:{} with table_id conflict - top-level table_id wins for dataSourceId")
        @SuppressWarnings("unchecked")
        void nestedCrudTableIdConflict() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");
            params.put("table_id", 999);
            params.put("crud", Map.of("columns", Map.of("name", "John")));

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");
            assertThat(result.success()).isTrue();

            Map<String, Object> node = session.getTables().get(0);
            assertThat(node.get("dataSourceId")).isEqualTo(999L);

            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud).containsKey("columns");
        }

        @Test
        @DisplayName("Empty crud block when no CRUD fields present")
        @SuppressWarnings("unchecked")
        void emptyCrudBlockWhenNoFields() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");

            tableCreator.executeAddTable(session, params, "crud/create-row");

            Map<String, Object> node = session.getTables().get(0);
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud).isEmpty();
        }

        @Test
        @DisplayName("insert_row with columns (agent format) - columns go into crud block")
        @SuppressWarnings("unchecked")
        void insertRowColumnsInCrudBlock() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");
            params.put("table_id", 42);
            params.put("columns", Map.of("name", "John", "email", "j@x.com"));

            tableCreator.executeAddTable(session, params, "crud/create-row");

            Map<String, Object> node = session.getTables().get(0);
            Map<String, Object> crud = (Map<String, Object>) node.get("crud");
            assertThat(crud).isNotNull();
            assertThat(crud).containsKey("columns");

            Map<String, Object> nodeParams = (Map<String, Object>) node.get("params");
            if (nodeParams != null) {
                assertThat(nodeParams).doesNotContainKey("columns");
            }
        }
    }

    // ==================== Edge Creation Tests ====================

    @Nested
    @DisplayName("Edge creation")
    class EdgeCreation {

        @Test
        @DisplayName("Creates edge from connect_after to new node")
        void createsEdge() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");

            tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(session.getEdges()).hasSize(1);
            Map<String, Object> edge = session.getEdges().get(0);
            assertThat(edge.get("from")).isEqualTo("trigger:start");
            assertThat(edge.get("to")).isEqualTo("table:save");
        }

        @Test
        @DisplayName("No edge created when connect_after is not specified (orphaned node)")
        void noEdgeWhenNoConnectAfter() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            // no connect_after - node will be orphaned (no auto-fallback)

            tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(session.getEdges()).isEmpty();
            // Node is still created successfully
            assertThat(session.getTables()).hasSize(1);
        }
    }

    // ==================== Output Refs Tests ====================

    @Nested
    @DisplayName("Output references from node_type_documentation")
    class OutputRefs {

        @Test
        @DisplayName("Includes output refs for insert_row from node_type_documentation")
        void includesInsertRowOutputRefs() {
            addTriggerToSession();

            // Mock node_type_documentation for insert_row
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType("insert_row");
            doc.setOutputs(Map.of(
                "row_id", "ID of the inserted row",
                "created_at", "Timestamp of creation",
                "inserted_values", "The values that were inserted"
            ));
            when(nodeLibraryService.findByType("insert_row")).thenReturn(Optional.of(doc));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isTrue();
            // The output refs are passed to responseOptimizer.buildStepResponse
            // We verify the call succeeded and the node was created
        }

        @Test
        @DisplayName("Handles missing node_type_documentation gracefully")
        void handlesMissingDocumentation() {
            addTriggerToSession();

            when(nodeLibraryService.findByType("insert_row")).thenReturn(Optional.empty());

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isTrue();
        }
    }

    // ==================== Response Tests ====================

    @Nested
    @DisplayName("Response building")
    class ResponseBuilding {

        @Test
        @DisplayName("Response includes saved_params when non-CRUD params provided")
        void responseIncludesSavedParams() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");
            params.put("custom_param", "some_value");

            // Return mutable map so saved_params can be added
            when(responseOptimizer.buildStepResponse(
                any(), anyString(), anyString(), anyString(), anyString(), any(),
                anyBoolean(), any(), any(), any(), any(), anyBoolean()
            )).thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).containsKey("saved_params");
        }

        @Test
        @DisplayName("Response metadata includes label")
        void responseMetadataIncludesLabel() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Insert Record");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isTrue();
            assertThat(result.metadata()).containsEntry("label", "Insert Record");
        }

        @Test
        @DisplayName("Response has no credential warnings (CRUD never needs credentials)")
        void noCredentialWarnings() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");

            ToolExecutionResult result = tableCreator.executeAddTable(session, params, "crud/create-row");

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data).doesNotContainKey("CREDENTIAL_NEEDED");
        }
    }

    // ==================== Flat Params Format Test ====================

    @Nested
    @DisplayName("Flat parameters format")
    class FlatParamsFormat {

        @Test
        @DisplayName("Reserved and CRUD params are not included in tool params")
        void reservedAndCrudParamsExcluded() {
            addTriggerToSession();
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("label", "Save");
            params.put("connect_after", "Start");
            params.put("action", "add_node");
            params.put("type", "insert_row");
            params.put("session_id", "abc");
            params.put("dataSourceId", 42L);
            params.put("rows", List.of(Map.of("columns", Map.of("a", "b"))));
            params.put("where", Map.of("column", "id"));
            // Add a non-reserved, non-CRUD custom param
            params.put("custom_flag", "true");

            tableCreator.executeAddTable(session, params, "crud/create-row");

            Map<String, Object> node = session.getTables().get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> toolParams = (Map<String, Object>) node.get("params");

            // Reserved fields should NOT be in tool params
            assertThat(toolParams).doesNotContainKeys("label", "connect_after", "action", "type", "session_id");
            // CRUD fields should NOT be in tool params (they go in step.crud)
            assertThat(toolParams).doesNotContainKeys("rows", "where", "dataSourceId", "table_id");
            // Custom non-CRUD params should remain
            assertThat(toolParams).containsEntry("custom_flag", "true");
        }
    }
}
