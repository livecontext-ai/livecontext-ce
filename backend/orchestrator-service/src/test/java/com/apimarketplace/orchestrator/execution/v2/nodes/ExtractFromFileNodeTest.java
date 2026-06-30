package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ExtractFromFileNode.
 * ExtractFromFileNode imports CSV, XLSX, and JSON file content into structured JSON items.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ExtractFromFileNode")
class ExtractFromFileNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "John");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    /** Run the node and return the ordered list of chunk "content" strings. */
    private List<Object> chunkContents(Core.ExtractFromFileConfig config) {
        ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
        NodeExecutionResult result = node.execute(context);
        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
        List<Object> contents = new ArrayList<>();
        for (Map<String, Object> item : items) {
            contents.add(item.get("content"));
        }
        return contents;
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create ExtractFromFileNode with nodeId and config")
        void shouldCreateExtractFromFileNodeWithNodeIdAndConfig() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "name,age\nJohn,30", ",", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);

            assertEquals("core:extract", node.getNodeId());
            assertEquals(NodeType.EXTRACT_FROM_FILE, node.getType());
            assertNotNull(node.getExtractFromFileConfig());
            assertEquals("csv", node.getExtractFromFileConfig().format());
        }

        @Test
        @DisplayName("Should default format to csv when null")
        void shouldDefaultFormatToCsvWhenNull() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                null, "a,b\n1,2", null, null, "no", null, null, null, null, null, null);

            assertEquals("csv", config.format());
            assertEquals(",", config.delimiter());
        }

        @Test
        @DisplayName("Should default delimiter to comma when null")
        void shouldDefaultDelimiterToCommaWhenNull() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "data", null, null, "yes", null, null, null, null, null, null);

            assertEquals(",", config.delimiter());
        }

        @Test
        @DisplayName("Should create ExtractFromFileNode using builder")
        void shouldCreateExtractFromFileNodeUsingBuilder() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "json", "[{\"a\":1}]", null, null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = ExtractFromFileNode.builder()
                .nodeId("core:my_extract")
                .extractFromFileConfig(config)
                .build();

            assertEquals("core:my_extract", node.getNodeId());
            assertEquals("json", node.getExtractFromFileConfig().format());
        }

        @Test
        @DisplayName("Should handle null config in constructor")
        void shouldHandleNullConfigInConstructor() {
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", null);

            assertEquals("core:extract", node.getNodeId());
            assertNull(node.getExtractFromFileConfig());
        }
    }

    // ===============================================================
    // execute() - CSV parsing with headers
    // ===============================================================

    @Nested
    @DisplayName("execute() - CSV with headers")
    class ExecuteCsvWithHeadersTests {

        @Test
        @DisplayName("Should parse CSV with headers into list of maps")
        void shouldParseCsvWithHeadersIntoListOfMaps() {
            String csv = "name,age,city\nJohn,30,NYC\nJane,25,LA";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals("John", items.get(0).get("name"));
            assertEquals("30", items.get(0).get("age"));
            assertEquals("NYC", items.get(0).get("city"));
            assertEquals("Jane", items.get(1).get("name"));
        }

        @Test
        @DisplayName("Should return correct metadata for CSV")
        void shouldReturnCorrectMetadataForCsv() {
            String csv = "name,age\nJohn,30\nJane,25";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("csv", result.output().get("format"));
            assertEquals(2, result.output().get("rowCount"));
            assertEquals(2, result.output().get("columnCount"));
            assertEquals(true, result.output().get("success"));

            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) result.output().get("columns");
            assertTrue(columns.contains("name"));
            assertTrue(columns.contains("age"));
        }

        @Test
        @DisplayName("Should handle CSV with only header row")
        void shouldHandleCsvWithOnlyHeaderRow() {
            String csv = "name,age,city";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("rowCount"));
        }

        @Test
        @DisplayName("Should handle CSV with quoted fields")
        void shouldHandleCsvWithQuotedFields() {
            String csv = "name,description\nJohn,\"Has a, comma\"\nJane,\"Simple\"";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals("Has a, comma", items.get(0).get("description"));
        }
    }

    // ===============================================================
    // execute() - CSV parsing without headers
    // ===============================================================

    @Nested
    @DisplayName("execute() - CSV without headers")
    class ExecuteCsvWithoutHeadersTests {

        @Test
        @DisplayName("Should parse CSV without headers using column_N naming")
        void shouldParseCsvWithoutHeadersUsingColumnNNaming() {
            String csv = "John,30,NYC\nJane,25,LA";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals("John", items.get(0).get("column_0"));
            assertEquals("30", items.get(0).get("column_1"));
            assertEquals("NYC", items.get(0).get("column_2"));
        }

        @Test
        @DisplayName("Should include all rows when no headers")
        void shouldIncludeAllRowsWhenNoHeaders() {
            String csv = "A,B\nC,D\nE,F";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(3, result.output().get("rowCount"));
        }
    }

    // ===============================================================
    // execute() - CSV with custom delimiter
    // ===============================================================

    @Nested
    @DisplayName("execute() - CSV with custom delimiter")
    class ExecuteCsvCustomDelimiterTests {

        @Test
        @DisplayName("Should parse CSV with semicolon delimiter")
        void shouldParseCsvWithSemicolonDelimiter() {
            String csv = "name;age;city\nJohn;30;NYC";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ";", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, items.size());
            assertEquals("John", items.get(0).get("name"));
            assertEquals("30", items.get(0).get("age"));
        }

        @Test
        @DisplayName("Should parse CSV with tab delimiter")
        void shouldParseCsvWithTabDelimiter() {
            String csv = "name\tage\nJohn\t30";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, "\t", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, items.size());
            assertEquals("John", items.get(0).get("name"));
        }

        @Test
        @DisplayName("Should parse CSV with pipe delimiter")
        void shouldParseCsvWithPipeDelimiter() {
            String csv = "name|age\nJohn|30\nJane|25";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, "|", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals("John", items.get(0).get("name"));
        }
    }

    // ===============================================================
    // execute() - XLSX parsing
    // ===============================================================

    @Nested
    @DisplayName("execute() - XLSX parsing")
    class ExecuteXlsxTests {

        @Test
        @DisplayName("Should parse XLSX with headers from base64")
        void shouldParseXlsxWithHeadersFromBase64() throws Exception {
            String base64 = createXlsxBase64(
                new String[]{"name", "age"},
                new Object[][]{{"John", 30}, {"Jane", 25}});

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "xlsx", base64, null, null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals("John", items.get(0).get("name"));
            assertEquals(30L, items.get(0).get("age"));
        }

        @Test
        @DisplayName("Should parse XLSX without headers using column_N naming")
        void shouldParseXlsxWithoutHeadersUsingColumnNNaming() throws Exception {
            String base64 = createXlsxBase64WithoutHeaders(
                new Object[][]{{"John", 30}, {"Jane", 25}});

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "xlsx", base64, null, null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals("John", items.get(0).get("column_0"));
            assertEquals(30L, items.get(0).get("column_1"));
        }

        @Test
        @DisplayName("Should parse XLSX with named sheet")
        void shouldParseXlsxWithNamedSheet() throws Exception {
            String base64 = createXlsxBase64WithSheetName(
                "MyData",
                new String[]{"id", "value"},
                new Object[][]{{1, "A"}, {2, "B"}});

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "xlsx", base64, null, "MyData", "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals(1L, items.get(0).get("id"));
            assertEquals("A", items.get(0).get("value"));
        }

        @Test
        @DisplayName("Should fail for invalid sheet name")
        void shouldFailForInvalidSheetName() throws Exception {
            String base64 = createXlsxBase64(
                new String[]{"name"},
                new Object[][]{{"John"}});

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "xlsx", base64, null, "NonExistentSheet", "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail for invalid base64 XLSX content")
        void shouldFailForInvalidBase64XlsxContent() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "xlsx", "not-valid-base64!!!", null, null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // execute() - JSON parsing
    // ===============================================================

    @Nested
    @DisplayName("execute() - JSON parsing")
    class ExecuteJsonTests {

        @Test
        @DisplayName("Should parse JSON array of objects")
        void shouldParseJsonArrayOfObjects() {
            String json = "[{\"name\":\"John\",\"age\":30},{\"name\":\"Jane\",\"age\":25}]";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "json", json, null, null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals("John", items.get(0).get("name"));
            assertEquals(30, items.get(0).get("age"));
        }

        @Test
        @DisplayName("Should parse single JSON object and wrap in list")
        void shouldParseSingleJsonObjectAndWrapInList() {
            String json = "{\"name\":\"John\",\"age\":30}";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "json", json, null, null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, items.size());
            assertEquals("John", items.get(0).get("name"));
        }

        @Test
        @DisplayName("Should handle empty JSON array")
        void shouldHandleEmptyJsonArray() {
            String json = "[]";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "json", json, null, null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(0, result.output().get("rowCount"));
        }

        @Test
        @DisplayName("Should fail for invalid JSON")
        void shouldFailForInvalidJson() {
            String json = "not valid json{{}";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "json", json, null, null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should return correct column info for JSON")
        void shouldReturnCorrectColumnInfoForJson() {
            String json = "[{\"id\":1,\"name\":\"John\",\"email\":\"john@test.com\"}]";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "json", json, null, null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(3, result.output().get("columnCount"));

            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) result.output().get("columns");
            assertEquals(3, columns.size());
        }
    }

    // ===============================================================
    // execute() - Error handling
    // ===============================================================

    @Nested
    @DisplayName("execute() - Error handling")
    class ExecuteErrorHandlingTests {

        @Test
        @DisplayName("Should fail for empty input value")
        void shouldFailForEmptyInputValue() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "", ",", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail for null input value")
        void shouldFailForNullInputValue() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", null, ",", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail for unknown format")
        void shouldFailForUnknownFormat() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "yaml", "key: value", null, null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail when config is null")
        void shouldFailWhenConfigIsNull() {
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", null);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should handle malformed CSV gracefully")
        void shouldHandleMalformedCsvGracefully() {
            // A single value with no delimiter - should still parse as one column
            String csv = "just-a-value";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "no", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("rowCount"));
        }
    }

    // ===============================================================
    // execute() - Metadata tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Metadata")
    class ExecuteMetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields")
        void shouldIncludeMandatoryMetadataFields() {
            String csv = "a,b\n1,2";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("EXTRACT_FROM_FILE", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include resolved_params with config details")
        void shouldIncludeInputDataWithConfigDetails() {
            String csv = "a,b\n1,2";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ";", null, "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            Map<String, Object> inputData = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals("csv", inputData.get("format"));
            assertEquals(";", inputData.get("delimiter"));
            assertEquals("yes", inputData.get("hasHeaders"));
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should build ExtractFromFileNode with all fields")
        void shouldBuildExtractFromFileNodeWithAllFields() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "xlsx", "base64data", null, "Sheet1", "yes", null, null, null, null, null, null);

            ExtractFromFileNode node = ExtractFromFileNode.builder()
                .nodeId("core:my_extractor")
                .extractFromFileConfig(config)
                .build();

            assertEquals("core:my_extractor", node.getNodeId());
            assertEquals(NodeType.EXTRACT_FROM_FILE, node.getType());
            assertEquals("xlsx", node.getExtractFromFileConfig().format());
            assertEquals("Sheet1", node.getExtractFromFileConfig().sheetName());
            assertTrue(node.getExtractFromFileConfig().includeHeaders());
        }

        @Test
        @DisplayName("Should build ExtractFromFileNode with null config")
        void shouldBuildExtractFromFileNodeWithNullConfig() {
            ExtractFromFileNode node = ExtractFromFileNode.builder()
                .nodeId("core:extract")
                .extractFromFileConfig(null)
                .build();

            assertEquals("core:extract", node.getNodeId());
            assertNull(node.getExtractFromFileConfig());
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "a\n1", ",", null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:extract", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "a\n1", ",", null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:extract", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // onComplete() tests
    // ===============================================================

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "a\n1", ",", null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = NodeExecutionResult.success("core:extract", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "a\n1", ",", null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = NodeExecutionResult.failure("core:extract", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // FileRef Input tests
    // ===============================================================

    @Nested
    @DisplayName("FileRef Input")
    class FileRefInputTests {

        @Mock
        private FileStorageService fileStorageService;

        @Mock
        private V2TemplateAdapter templateAdapter;

        @Test
        @DisplayName("Should download and parse CSV from FileRef")
        void shouldDownloadAndParseCsvFromFileRef() {
            String csvContent = "name,age\nAlice,30\nBob,25";
            when(fileStorageService.download("tenant/wf/run/step/data.csv"))
                .thenReturn(Optional.of(csvContent.getBytes(StandardCharsets.UTF_8)));

            // Template adapter returns a FileRef map
            Map<String, Object> fileRefMap = new LinkedHashMap<>();
            fileRefMap.put("_type", "file");
            fileRefMap.put("path", "tenant/wf/run/step/data.csv");
            fileRefMap.put("name", "data.csv");
            fileRefMap.put("mimeType", "text/csv");
            fileRefMap.put("size", 100L);

            when(templateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
                .thenReturn(Map.of("__expr__", fileRefMap));

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "{{core:convert_to_file.output.file}}", ",", null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            node.setTemplateAdapter(templateAdapter);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(2, items.size());
            assertEquals("Alice", items.get(0).get("name"));
            assertEquals("30", items.get(0).get("age"));
        }

        @Test
        @DisplayName("Should download and parse XLSX from FileRef")
        void shouldDownloadAndParseXlsxFromFileRef() throws Exception {
            // Create an actual XLSX in memory
            byte[] xlsxBytes;
            try (Workbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet("Sheet1");
                Row header = sheet.createRow(0);
                header.createCell(0).setCellValue("id");
                header.createCell(1).setCellValue("value");
                Row row = sheet.createRow(1);
                row.createCell(0).setCellValue(1.0);
                row.createCell(1).setCellValue("test");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                workbook.write(baos);
                xlsxBytes = baos.toByteArray();
            }

            when(fileStorageService.download("tenant/wf/run/step/data.xlsx"))
                .thenReturn(Optional.of(xlsxBytes));

            Map<String, Object> fileRefMap = new LinkedHashMap<>();
            fileRefMap.put("_type", "file");
            fileRefMap.put("path", "tenant/wf/run/step/data.xlsx");
            fileRefMap.put("name", "data.xlsx");

            when(templateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
                .thenReturn(Map.of("__expr__", fileRefMap));

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "xlsx", "{{core:convert_to_file.output.file}}", null, null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            node.setTemplateAdapter(templateAdapter);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, items.size());
            assertEquals(1L, items.get(0).get("id"));
            assertEquals("test", items.get(0).get("value"));
        }

        @Test
        @DisplayName("Should download and parse JSON from FileRef")
        void shouldDownloadAndParseJsonFromFileRef() {
            String jsonContent = "[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]";
            when(fileStorageService.download("path/data.json"))
                .thenReturn(Optional.of(jsonContent.getBytes(StandardCharsets.UTF_8)));

            Map<String, Object> fileRefMap = new LinkedHashMap<>();
            fileRefMap.put("_type", "file");
            fileRefMap.put("path", "path/data.json");

            when(templateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
                .thenReturn(Map.of("__expr__", fileRefMap));

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "json", "{{upstream.output.file}}", null, null, "no", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            node.setTemplateAdapter(templateAdapter);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals(2, result.output().get("rowCount"));
        }

        @Test
        @DisplayName("Should fall back to string input when not a FileRef")
        void shouldFallBackToStringInput() {
            // Template adapter returns a plain string, not a FileRef map
            when(templateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
                .thenReturn(Map.of("__expr__", "name,age\nJohn,30"));

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "{{some.expression}}", ",", null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            node.setTemplateAdapter(templateAdapter);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, items.size());
            assertEquals("John", items.get(0).get("name"));
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("Should fail when file not found in S3")
        void shouldFailWhenFileNotFoundInS3() {
            when(fileStorageService.download("missing/path"))
                .thenReturn(Optional.empty());

            Map<String, Object> fileRefMap = new LinkedHashMap<>();
            fileRefMap.put("_type", "file");
            fileRefMap.put("path", "missing/path");

            when(templateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
                .thenReturn(Map.of("__expr__", fileRefMap));

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "{{upstream.output.file}}", ",", null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            node.setTemplateAdapter(templateAdapter);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail when FileStorageService is null for FileRef")
        void shouldFailWhenFileStorageServiceIsNull() {
            Map<String, Object> fileRefMap = new LinkedHashMap<>();
            fileRefMap.put("_type", "file");
            fileRefMap.put("path", "some/path");

            when(templateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
                .thenReturn(Map.of("__expr__", fileRefMap));

            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "{{upstream.output.file}}", ",", null, "yes", null, null, null, null, null, null);
            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            node.setTemplateAdapter(templateAdapter);
            // No fileStorageService set

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }

    /**
     * Create a Base64-encoded XLSX with headers and data rows.
     */
    private String createXlsxBase64(String[] headers, Object[][] data) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");

            // Header row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            // Data rows
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < data[r].length; c++) {
                    Cell cell = row.createCell(c);
                    if (data[r][c] instanceof Number) {
                        cell.setCellValue(((Number) data[r][c]).doubleValue());
                    } else {
                        cell.setCellValue(String.valueOf(data[r][c]));
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    /**
     * Create a Base64-encoded XLSX without headers (data only).
     */
    private String createXlsxBase64WithoutHeaders(Object[][] data) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");

            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    Cell cell = row.createCell(c);
                    if (data[r][c] instanceof Number) {
                        cell.setCellValue(((Number) data[r][c]).doubleValue());
                    } else {
                        cell.setCellValue(String.valueOf(data[r][c]));
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    /**
     * Create a Base64-encoded XLSX with a named sheet.
     */
    private String createXlsxBase64WithSheetName(String sheetName, String[] headers, Object[][] data) throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);

            // Header row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                headerRow.createCell(i).setCellValue(headers[i]);
            }

            // Data rows
            for (int r = 0; r < data.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < data[r].length; c++) {
                    Cell cell = row.createCell(c);
                    if (data[r][c] instanceof Number) {
                        cell.setCellValue(((Number) data[r][c]).doubleValue());
                    } else {
                        cell.setCellValue(String.valueOf(data[r][c]));
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    // ===============================================================
    // execute() - Text mode (TXT, HTML)
    // ===============================================================

    @Nested
    @DisplayName("execute() - Text mode")
    class ExecuteTextModeTests {

        @Test
        @DisplayName("Should extract plain text without chunking")
        void shouldExtractPlainTextWithoutChunking() {
            String text = "Hello world. This is a test document.";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals("text", output.get("mode"));
            assertEquals("txt", output.get("format"));
            assertEquals(1, output.get("rowCount"));
            assertEquals(text.length(), output.get("text_length"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) output.get("items");
            assertEquals(1, items.size());
            assertEquals(text, items.get(0).get("content"));
            assertEquals(0, items.get(0).get("chunk_index"));
            assertEquals(1, items.get(0).get("total_chunks"));
        }

        @Test
        @DisplayName("Should extract HTML text")
        void shouldExtractHtmlText() {
            String html = "<html><body><h1>Title</h1><p>Paragraph content here.</p></body></html>";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "html", html, null, null, null,
                "text", null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, items.size());
            String content = (String) items.get(0).get("content");
            assertTrue(content.contains("Title"));
            assertTrue(content.contains("Paragraph content here"));
            // HTML tags should be stripped
            assertFalse(content.contains("<h1>"));
            assertFalse(content.contains("<p>"));
        }

        @Test
        @DisplayName("Should handle empty text input")
        void shouldHandleEmptyTextInput() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", "", null, null, null,
                "text", null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should handle null value in text mode")
        void shouldHandleNullValueInTextMode() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", null, null, null, null,
                "text", null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
        }

        @Test
        @DisplayName("Should reject unsupported format in text mode")
        void shouldRejectUnsupportedFormatInTextMode() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "xlsx", "somedata", null, null, null,
                "text", null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            // xlsx in text mode falls to default case and throws
            assertFalse(result.isSuccess());
        }
    }

    // ===============================================================
    // Chunking - fixed_size strategy
    // ===============================================================

    @Nested
    @DisplayName("Chunking - fixed_size")
    class ChunkingFixedSizeTests {

        @Test
        @DisplayName("Should chunk text with fixed_size strategy")
        void shouldChunkTextWithFixedSizeStrategy() {
            String text = "AAAAAAAAAA" + "BBBBBBBBBB" + "CCCCCCCCCC"; // 30 chars
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 10, 0, "fixed_size", null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(3, items.size());
            assertEquals("AAAAAAAAAA", items.get(0).get("content"));
            assertEquals("BBBBBBBBBB", items.get(1).get("content"));
            assertEquals("CCCCCCCCCC", items.get(2).get("content"));
            assertEquals(0, items.get(0).get("chunk_index"));
            assertEquals(1, items.get(1).get("chunk_index"));
            assertEquals(2, items.get(2).get("chunk_index"));
            assertEquals(3, items.get(0).get("total_chunks"));
        }

        @Test
        @DisplayName("Should chunk text with overlap")
        void shouldChunkTextWithOverlap() {
            String text = "0123456789ABCDEFGHIJ"; // 20 chars
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 10, 3, "fixed_size", null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            // step = 10 - 3 = 7, so: [0..10], [7..17], [14..20]
            assertEquals(3, items.size());
            assertEquals("0123456789", items.get(0).get("content"));
            assertEquals("789ABCDEFG", items.get(1).get("content"));
            assertEquals("EFGHIJ", items.get(2).get("content")); // last chunk shorter (indices 14..19)
        }

        @Test
        @DisplayName("Should handle text shorter than chunk size")
        void shouldHandleTextShorterThanChunkSize() {
            String text = "Short text";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 500, 50, "fixed_size", null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(1, items.size());
            assertEquals("Short text", items.get(0).get("content"));
        }

        @Test
        @DisplayName("Should include chunking metadata in output")
        void shouldIncludeChunkingMetadataInOutput() {
            String text = "A".repeat(100);
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 30, 5, "fixed_size", null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals("fixed_size", output.get("chunking_strategy"));
            assertEquals(30, output.get("chunk_size"));
            assertEquals(5, output.get("overlap"));
            assertEquals(100, output.get("text_length"));
        }

        @Test
        @DisplayName("Should produce byte-identical char chunks whether chunkUnit is defaulted or explicit 'char'")
        void charModeChunkingUnchangedByChunkUnitField() {
            String text = "0123456789ABCDEFGHIJ"; // 20 chars, same fixture as shouldChunkTextWithOverlap
            // 11-arg constructor (no chunkUnit -> defaults to char)
            Core.ExtractFromFileConfig legacy = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 10, 3, "fixed_size", null);
            // 12-arg constructor with explicit chunkUnit = "char"
            Core.ExtractFromFileConfig explicitChar = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 10, 3, "fixed_size", null, "char");

            List<?> legacyItems = chunkContents(legacy);
            List<?> explicitItems = chunkContents(explicitChar);

            // Both must equal the pre-feature behavior exactly: step = 10 - 3 = 7
            assertEquals(List.of("0123456789", "789ABCDEFG", "EFGHIJ"), legacyItems);
            assertEquals(legacyItems, explicitItems);
        }
    }

    // ===============================================================
    // Chunking - recursive strategy
    // ===============================================================

    @Nested
    @DisplayName("Chunking - recursive")
    class ChunkingRecursiveTests {

        @Test
        @DisplayName("Should split at paragraph boundaries")
        void shouldSplitAtParagraphBoundaries() {
            String text = "Paragraph one.\n\nParagraph two.\n\nParagraph three.";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 100, 0, "recursive", null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            // All 3 paragraphs fit in one chunk of 100 chars
            assertEquals(1, items.size());
            String content = (String) items.get(0).get("content");
            assertTrue(content.contains("Paragraph one"));
            assertTrue(content.contains("Paragraph three"));
        }

        @Test
        @DisplayName("Should split long paragraphs at sentence boundaries")
        void shouldSplitLongParagraphsAtSentenceBoundaries() {
            // Each sentence ~20 chars, chunk size 25 means merge at most 1 sentence per chunk
            String text = "First sentence here. Second sentence here. Third sentence here.";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 25, 0, "recursive", null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertTrue(items.size() >= 2, "Should produce multiple chunks from sentence splitting");
        }
    }

    // ===============================================================
    // Chunking - separator strategy
    // ===============================================================

    @Nested
    @DisplayName("Chunking - separator")
    class ChunkingSeparatorTests {

        @Test
        @DisplayName("Should split by custom separator")
        void shouldSplitByCustomSeparator() {
            String text = "Section A---Section B---Section C";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 10, 0, "separator", "---");

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertEquals(3, items.size());
            assertEquals("Section A", items.get(0).get("content"));
            assertEquals("Section B", items.get(1).get("content"));
            assertEquals("Section C", items.get(2).get("content"));
        }

        @Test
        @DisplayName("Should merge small sections into chunks")
        void shouldMergeSmallSectionsIntoChunks() {
            String text = "A---B---C---D---E";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 10, 0, "separator", "---");

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            // Each section is 1 char, chunk size 10 → merged into fewer chunks
            assertTrue(items.size() < 5, "Small sections should be merged");
        }
    }

    // ===============================================================
    // Config defaults and edge cases
    // ===============================================================

    @Nested
    @DisplayName("Config defaults")
    class ConfigDefaultsTests {

        @Test
        @DisplayName("Should default mode to structured")
        void shouldDefaultModeToStructured() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", "a,b", null, null, null,
                null, null, null, null, null, null);
            assertEquals("structured", config.mode());
            assertFalse(config.isTextMode());
        }

        @Test
        @DisplayName("Should default chunking fields")
        void shouldDefaultChunkingFields() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "pdf", null, null, null, null,
                "text", null, null, null, null, null);
            assertEquals(500, config.chunkSize());
            assertEquals(50, config.overlap());
            assertEquals("fixed_size", config.chunkingStrategy());
            assertEquals("\n\n", config.separator());
            assertFalse(config.isChunkingEnabled());
        }

        @Test
        @DisplayName("Should clamp invalid chunkSize to default")
        void shouldClampInvalidChunkSizeToDefault() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", "test", null, null, null,
                "text", true, 0, -5, null, null);
            assertEquals(500, config.chunkSize());
            assertEquals(50, config.overlap()); // -5 clamped to default
        }

        @Test
        @DisplayName("Should detect text mode correctly")
        void shouldDetectTextModeCorrectly() {
            Core.ExtractFromFileConfig textConfig = new Core.ExtractFromFileConfig(
                "pdf", null, null, null, null,
                "text", true, null, null, null, null);
            assertTrue(textConfig.isTextMode());
            assertTrue(textConfig.isChunkingEnabled());

            Core.ExtractFromFileConfig structuredConfig = new Core.ExtractFromFileConfig(
                "csv", null, null, null, null,
                "structured", false, null, null, null, null);
            assertFalse(structuredConfig.isTextMode());
            assertFalse(structuredConfig.isChunkingEnabled());
        }

        @Test
        @DisplayName("Should default chunkUnit to char (11-arg constructor)")
        void shouldDefaultChunkUnitToChar() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "pdf", null, null, null, null,
                "text", true, null, null, null, null);
            assertEquals("char", config.chunkUnit());
            assertFalse(config.isTokenUnit());
        }

        @Test
        @DisplayName("Should normalize chunkUnit case-insensitively to token")
        void shouldNormalizeChunkUnitToToken() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "pdf", null, null, null, null,
                "text", true, null, null, null, null, "TOKEN");
            assertEquals("token", config.chunkUnit());
            assertTrue(config.isTokenUnit());
        }

        @Test
        @DisplayName("Should fall back unknown chunkUnit to char")
        void shouldFallbackUnknownChunkUnitToChar() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "pdf", null, null, null, null,
                "text", true, null, null, null, null, "words");
            assertEquals("char", config.chunkUnit());
            assertFalse(config.isTokenUnit());
        }
    }

    // ===============================================================
    // Chunking - token unit (cl100k_base)
    // ===============================================================

    @Nested
    @DisplayName("Chunking - token unit")
    class ChunkingTokenUnitTests {

        // ASCII English prose: ~4 chars/token, so token-sized chunks are far longer in chars.
        private static final String PROSE =
            "Retrieval augmented generation pipelines split documents into chunks. "
            + "Each chunk is embedded into a vector and stored for similarity search. "
            + "Token based chunking keeps every chunk within the embedding context window.";

        private ExtractFromFileNode.SizeMeter tokenMeter() {
            return ExtractFromFileNode.meterFor("token");
        }

        @Test
        @DisplayName("Should size fixed_size chunks in tokens, not characters")
        void shouldChunkFixedSizeByTokens() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", PROSE, null, null, null,
                "text", true, 10, 0, "fixed_size", null, "token");

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("token", result.output().get("chunk_unit"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertTrue(items.size() > 1, "Long prose should produce multiple token chunks");

            ExtractFromFileNode.SizeMeter meter = tokenMeter();
            for (Map<String, Object> item : items) {
                String content = (String) item.get("content");
                assertTrue(meter.measure(content) <= 10,
                    "Each chunk must be <= 10 tokens, was " + meter.measure(content));
            }
            // Discriminator: a full 10-token chunk of English is much longer than 10 chars,
            // which is impossible under character metering.
            String first = (String) items.get(0).get("content");
            assertTrue(first.length() > 10,
                "Token sizing must yield chunks longer than chunkSize chars, was " + first.length());
        }

        @Test
        @DisplayName("Should overlap fixed_size chunks by whole tokens")
        void shouldCountOverlapInTokens() {
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", PROSE, null, null, null,
                "text", true, 20, 5, "fixed_size", null, "token");

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertTrue(items.size() >= 2);

            ExtractFromFileNode.SizeMeter meter = tokenMeter();
            for (Map<String, Object> item : items) {
                assertTrue(meter.measure((String) item.get("content")) <= 20);
            }
            // The last 5 tokens of chunk 0 reappear at the start of chunk 1 (token-counted overlap).
            String first = (String) items.get(0).get("content");
            String second = (String) items.get(1).get("content");
            assertTrue(second.startsWith(meter.tail(first, 5)),
                "Chunk 1 should begin with the last 5 tokens of chunk 0");
        }

        @Test
        @DisplayName("Should respect token size in recursive strategy")
        void shouldRespectTokenSizeInRecursiveStrategy() {
            String text = "First paragraph about embeddings and chunks.\n\n"
                + "Second paragraph about vectors and similarity search across documents.";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 8, 0, "recursive", null, "token");

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertTrue(items.size() >= 2);
            ExtractFromFileNode.SizeMeter meter = tokenMeter();
            for (Map<String, Object> item : items) {
                assertTrue(meter.measure((String) item.get("content")) <= 8);
            }
        }

        @Test
        @DisplayName("Should respect token size in separator strategy")
        void shouldRespectTokenSizeInSeparatorStrategy() {
            String text = "Alpha section about retrieval---Beta section about embedding---Gamma section about ranking";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "txt", text, null, null, null,
                "text", true, 6, 0, "separator", "---", "token");

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
            assertTrue(items.size() >= 2);
            ExtractFromFileNode.SizeMeter meter = tokenMeter();
            for (Map<String, Object> item : items) {
                assertTrue(meter.measure((String) item.get("content")) <= 6);
            }
        }

        @Test
        @DisplayName("Should emit chunk_unit in output for both units")
        void shouldEmitChunkUnitInOutput() {
            Core.ExtractFromFileConfig tokenConfig = new Core.ExtractFromFileConfig(
                "txt", PROSE, null, null, null,
                "text", true, 20, 0, "fixed_size", null, "token");
            Core.ExtractFromFileConfig charConfig = new Core.ExtractFromFileConfig(
                "txt", PROSE, null, null, null,
                "text", true, 20, 0, "fixed_size", null);

            assertEquals("token",
                new ExtractFromFileNode("core:extract", tokenConfig).execute(context).output().get("chunk_unit"));
            assertEquals("char",
                new ExtractFromFileNode("core:extract", charConfig).execute(context).output().get("chunk_unit"));
        }
    }

    // ===============================================================
    // SizeMeter implementations
    // ===============================================================

    @Nested
    @DisplayName("SizeMeter")
    class SizeMeterTests {

        @Test
        @DisplayName("meterFor returns the char meter for char and unknown units")
        void meterForReturnsCharMeter() {
            assertSame(ExtractFromFileNode.CHAR_METER, ExtractFromFileNode.meterFor("char"));
            assertSame(ExtractFromFileNode.CHAR_METER, ExtractFromFileNode.meterFor("anything"));
        }

        @Test
        @DisplayName("meterFor returns a token meter for the token unit")
        void meterForReturnsTokenMeter() {
            assertInstanceOf(ExtractFromFileNode.TokenMeter.class, ExtractFromFileNode.meterFor("token"));
        }

        @Test
        @DisplayName("CharMeter measures, tails and splits by characters")
        void charMeterBehavior() {
            ExtractFromFileNode.SizeMeter m = ExtractFromFileNode.CHAR_METER;
            assertEquals(5, m.measure("hello"));
            assertEquals("lo", m.tail("hello", 2));
            assertEquals("hello", m.tail("hello", 99));
            assertEquals(List.of("0123", "4567", "89"), m.hardSplit("0123456789", 4, 4));
        }

        @Test
        @DisplayName("TokenMeter counts tokens (fewer than chars for ASCII) and round-trips slices")
        void tokenMeterBehavior() {
            ExtractFromFileNode.SizeMeter m = ExtractFromFileNode.meterFor("token");
            String text = "hello world from the tokenizer";
            int tokens = m.measure(text);
            assertTrue(tokens >= 1);
            assertTrue(tokens < text.length(), "ASCII text has fewer tokens than characters");
            // hardSplit windows are each <= size tokens and cover the whole text in order
            List<String> parts = m.hardSplit(text, 2, 2);
            assertEquals(text, String.join("", parts));
            for (String p : parts) {
                assertTrue(m.measure(p) <= 2);
            }
        }

        @Test
        @DisplayName("HeuristicTokenMeter approximates ~4 chars per token")
        void heuristicTokenMeterBehavior() {
            ExtractFromFileNode.SizeMeter m = new ExtractFromFileNode.HeuristicTokenMeter();
            assertEquals(2, m.measure("12345678"));   // 8 chars / 4
            assertEquals(1, m.measure("abc"));         // ceil(3 / 4)
            assertEquals("5678", m.tail("12345678", 1)); // last 1 token ~ last 4 chars
            assertEquals(List.of("0123", "4567", "89"), m.hardSplit("0123456789", 1, 1)); // 1 token ~ 4 chars
        }
    }

    // ===============================================================
    // Structured mode still works (regression)
    // ===============================================================

    @Nested
    @DisplayName("Structured mode regression")
    class StructuredModeRegressionTests {

        @Test
        @DisplayName("Should still parse CSV in structured mode")
        void shouldStillParseCsvInStructuredMode() {
            String csv = "name,age\nAlice,30\nBob,25";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "yes",
                "structured", null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Map<String, Object> output = result.output();
            assertEquals("structured", output.get("mode"));
            assertEquals(2, output.get("rowCount"));
        }

        @Test
        @DisplayName("Should still parse CSV when mode is null (defaults to structured)")
        void shouldStillParseCsvWhenModeIsNull() {
            String csv = "x,y\n1,2";
            Core.ExtractFromFileConfig config = new Core.ExtractFromFileConfig(
                "csv", csv, ",", null, "yes",
                null, null, null, null, null, null);

            ExtractFromFileNode node = new ExtractFromFileNode("core:extract", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("structured", result.output().get("mode"));
            assertEquals(1, result.output().get("rowCount"));
        }
    }
}
