package com.apimarketplace.orchestrator.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.service.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for NodeParamsValidator - validates node parameters against schema from DB.
 * Focuses on the table_id rejection issue for find_rows when used via add_node.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NodeParamsValidator")
class NodeParamsValidatorTest {

    @Mock
    private NodeLibraryService nodeLibraryService;

    @Mock
    private AgentClient agentClient;

    private NodeParamsValidator validator;

    @BeforeEach
    void setUp() {
        validator = new NodeParamsValidator(nodeLibraryService, new ModelCatalogEnricher(agentClient));
    }

    @Nested
    @DisplayName("find_rows validation")
    class FindRowsTests {

        @BeforeEach
        void setUpFindRowsSchema() {
            // Simulate find_rows node_type_documentation with where, limit, offset params
            NodeTypeDocumentationEntity findRowsDoc = new NodeTypeDocumentationEntity();
            findRowsDoc.setType("find_rows");
            findRowsDoc.setParameters(Map.of(
                "where", Map.of("type", "object", "required", false, "description", "Filter condition"),
                "limit", Map.of("type", "number", "required", false, "description", "Max rows"),
                "offset", Map.of("type", "number", "required", false, "description", "Starting position")
            ));
            when(nodeLibraryService.findByType("find_rows")).thenReturn(Optional.of(findRowsDoc));
        }

        @Test
        @DisplayName("Should reject table_id as unknown parameter for find_rows")
        void shouldRejectTableIdAsUnknown() {
            // This is the bug: table_id is a routing param, not a node param.
            // The validator rejects it because it's not in node_type_documentation.
            // The fix is to skip validation for table types in WorkflowBuilderProvider.
            ValidationResult result = validator.validate("find_rows", Map.of(
                "table_id", 1,
                "where", Map.of("column", "status", "operator", "==", "value", "active")
            ));

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                e.code().equals("UNKNOWN_PARAM") && e.parameter().equals("table_id"));
        }

        @Test
        @DisplayName("Should accept valid find_rows params (where, limit)")
        void shouldAcceptValidParams() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "where", Map.of("column", "status", "operator", "==", "value", "active"),
                "limit", 50
            ));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("Should accept find_rows alias 'filter' for 'where'")
        void shouldAcceptFilterAlias() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "filter", Map.of("column", "name", "operator", "==", "value", "test")
            ));

            assertThat(result.valid()).isTrue();
        }

        // #TC3: `find_rows` help docs recommend `dataSourceId`. Validator previously
        // rejected it as UNKNOWN_PARAM even though the help said to use it. Now accepted
        // as an alias of `table_id`.
        @Test
        @DisplayName("#TC3 Should ACCEPT dataSourceId as alias for table_id (find_rows)")
        void shouldAcceptDataSourceIdAsAlias() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "dataSourceId", 1
            ));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("#TC3 Should ACCEPT datasource_id as alias for table_id (find_rows)")
        void shouldAcceptDatasourceIdSnakeAsAlias() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "datasource_id", 1
            ));

            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("#TC3 Should ACCEPT tableId (camelCase) as alias for table_id (find_rows)")
        void shouldAcceptTableIdCamelCaseAsAlias() {
            ValidationResult result = validator.validate("find_rows", Map.of(
                "tableId", 1
            ));

            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("#TC3 CRUD dataSourceId alias across all table ops")
    class CrudDataSourceIdAliasTests {

        private void stubSchema(String type, Map<String, Object> schema) {
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType(type);
            doc.setParameters(schema);
            when(nodeLibraryService.findByType(type)).thenReturn(Optional.of(doc));
        }

        @Test
        @DisplayName("insert_row accepts dataSourceId alias")
        void insertRowAcceptsDataSourceId() {
            stubSchema("insert_row", Map.of(
                "columns", Map.of("type", "object", "required", false)
            ));
            ValidationResult result = validator.validate("insert_row", Map.of(
                "dataSourceId", 1, "columns", Map.of("name", "x")
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("get_rows accepts dataSourceId alias")
        void getRowsAcceptsDataSourceId() {
            stubSchema("get_rows", Map.of(
                "where", Map.of("type", "object", "required", false)
            ));
            ValidationResult result = validator.validate("get_rows", Map.of("dataSourceId", 1));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("update_row accepts dataSourceId alias")
        void updateRowAcceptsDataSourceId() {
            stubSchema("update_row", Map.of(
                "where", Map.of("type", "object", "required", false),
                "set", Map.of("type", "object", "required", false)
            ));
            ValidationResult result = validator.validate("update_row", Map.of(
                "dataSourceId", 1, "set", Map.of("s", 1), "where", Map.of("c", "id")
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("delete_row accepts dataSourceId alias")
        void deleteRowAcceptsDataSourceId() {
            stubSchema("delete_row", Map.of(
                "where", Map.of("type", "object", "required", false)
            ));
            ValidationResult result = validator.validate("delete_row", Map.of(
                "dataSourceId", 1, "where", Map.of("c", "id")
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("create_column accepts dataSourceId alias")
        void createColumnAcceptsDataSourceId() {
            stubSchema("create_column", Map.of());
            ValidationResult result = validator.validate("create_column", Map.of("dataSourceId", 1));
            assertThat(result.valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("Interface aliases - snake_case ↔ camelCase parity for toggles")
    class InterfaceAliases {

        private void stubInterfaceSchema() {
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType("interface");
            // Mirrors the V228 + V229 + V230 + V376 DB doc state (canonical keys are camelCase).
            doc.setParameters(Map.of(
                "interface_id", Map.of("type", "uuid", "required", true),
                "variable_mapping", Map.of("type", "object", "required", false),
                "action_mapping", Map.of("type", "object", "required", false),
                "isEntryInterface", Map.of("type", "boolean", "required", false),
                "generateScreenshot", Map.of("type", "boolean", "required", false),
                "exposeRenderedSource", Map.of("type", "boolean", "required", false),
                "generatePdf", Map.of("type", "boolean", "required", false),
                "pdfFormat", Map.of("type", "string", "required", false),
                "pdfLandscape", Map.of("type", "boolean", "required", false)
            ));
            when(nodeLibraryService.findByType("interface")).thenReturn(Optional.of(doc));
        }

        @Test
        @DisplayName("camelCase generateScreenshot accepted (canonical key)")
        void camelCaseGenerateScreenshotAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generateScreenshot", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("snake_case generate_screenshot accepted (alias)")
        void snakeCaseGenerateScreenshotAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generate_screenshot", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("camelCase exposeRenderedSource accepted (canonical key)")
        void camelCaseExposeRenderedSourceAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "exposeRenderedSource", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("snake_case expose_rendered_source accepted (alias)")
        void snakeCaseExposeRenderedSourceAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "expose_rendered_source", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("snake_case is_entry_interface accepted (alias)")
        void snakeCaseIsEntryInterfaceAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "is_entry_interface", true
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("camelCase generatePdf / pdfFormat / pdfLandscape accepted (canonical keys)")
        void camelCasePdfParamsAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generatePdf", true,
                "pdfFormat", "A4",
                "pdfLandscape", false
            ));
            assertThat(result.valid()).isTrue();
        }

        @Test
        @DisplayName("snake_case generate_pdf / pdf_format / pdf_landscape accepted (alias) - regression: builder MCP rejected these")
        void snakeCasePdfParamsAccepted() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generate_pdf", true,
                "pdf_format", "A4",
                "pdf_landscape", true
            ));
            assertThat(result.valid())
                .as("snake_case PDF params must validate via PARAM_ALIASES (caught live via the workflow MCP tool)")
                .isTrue();
        }

        @Test
        @DisplayName("Both snake_case toggles together accepted (regression guard for MCP add_node from agent)")
        void bothSnakeCaseTogglesAcceptedTogether() {
            stubInterfaceSchema();
            ValidationResult result = validator.validate("interface", Map.of(
                "interface_id", "11111111-2222-3333-4444-555555555555",
                "generate_screenshot", true,
                "expose_rendered_source", true
            ));
            assertThat(result.valid()).isTrue();
        }
    }

    @Test
    @DisplayName("Should return success for unknown node type")
    void shouldReturnErrorForUnknownType() {
        when(nodeLibraryService.findByType("nonexistent")).thenReturn(Optional.empty());

        ValidationResult result = validator.validate("nonexistent", Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.code().equals("UNKNOWN_TYPE"));
    }

    @Test
    @DisplayName("Should return success for node with no schema params")
    void shouldReturnSuccessForNoSchemaParams() {
        NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
        doc.setType("merge");
        doc.setParameters(Map.of());
        when(nodeLibraryService.findByType("merge")).thenReturn(Optional.of(doc));

        ValidationResult result = validator.validate("merge", Map.of("anything", "here"));

        assertThat(result.valid()).isTrue();
    }
}
