package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.file.FileRef;
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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ConvertToFileNode.
 * ConvertToFileNode exports JSON data to CSV, XLSX, JSON, or TXT file content.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConvertToFileNode")
class ConvertToFileNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;
    private ExecutionContext contextWithStepOutputs;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "John");
        triggerData.put("age", "30");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );

        // Context with step outputs containing a list of maps
        Map<String, Object> triggerData2 = new HashMap<>();
        triggerData2.put("source", "test");

        List<Map<String, Object>> dataList = new ArrayList<>();
        Map<String, Object> row1 = new LinkedHashMap<>();
        row1.put("id", 1);
        row1.put("name", "Alice");
        row1.put("email", "alice@example.com");
        dataList.add(row1);
        Map<String, Object> row2 = new LinkedHashMap<>();
        row2.put("id", 2);
        row2.put("name", "Bob");
        row2.put("email", "bob@example.com");
        dataList.add(row2);
        Map<String, Object> row3 = new LinkedHashMap<>();
        row3.put("id", 3);
        row3.put("name", "Charlie");
        row3.put("email", "charlie@example.com");
        dataList.add(row3);

        // Build stepOutputs map with the list stored directly
        Map<String, Object> stepOutputs = new HashMap<>();
        stepOutputs.put("query_results", dataList);

        contextWithStepOutputs = new ExecutionContext(
            "run-2",
            "workflow-run-2",
            "tenant-1",
            "item-2",
            0,
            null,
            0,
            0,
            triggerData2,
            stepOutputs,
            ExecutionState.create(),
            mockPlan
        );
    }

    @Test
    @DisplayName("keeps full-template list values structured when exporting CSV")
    void keepsFullTemplateListValuesStructuredWhenExportingCsv() {
        Map<String, Object> ada = new LinkedHashMap<>();
        ada.put("id", "1");
        ada.put("name", "Ada");
        Map<String, Object> grace = new LinkedHashMap<>();
        grace.put("id", "2");
        grace.put("name", "Grace");
        List<Map<String, Object>> rows = List.of(ada, grace);
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any(ExecutionContext.class)))
            .thenReturn(Map.of("__expr__", rows));

        Core.ConvertToFileConfig config = new Core.ConvertToFileConfig(
            "csv",
            "{{core:extract.output.items}}",
            "people",
            ",",
            "yes"
        );
        ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
        node.setTemplateAdapter(mockTemplateAdapter);

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        assertEquals(2, result.output().get("row_count"));
        String csv = (String) result.output().get("result");
        assertTrue(csv.contains("id,name"));
        assertTrue(csv.contains("1,Ada"));
        assertTrue(csv.contains("2,Grace"));
    }

    // ===============================================================
    // Shape-parity regression tests
    // ===============================================================

    /**
     * Regression pin (PR2 2026-05-15 - canonical-only): execute() MUST emit the canonical
     * FileRef under "file" and MUST NOT emit the legacy flat keys (file_url / file_name /
     * file_size / content_type). Pre-PR2 the node dual-emitted both shapes; the legacy keys
     * are now completely absent from the output map (never null, never present). Consumers
     * read the FileRef sub-fields (file.url(), file.name(), file.size(), file.mimeType()).
     * customTransform() is identity and must preserve the canonical shape while still
     * stripping engine-envelope keys (node_type / item_index / itemIndex / item_id /
     * resolved_params).
     */
    @Nested
    @DisplayName("Shape parity: execute() output matches persisted shape (canonical-only)")
    class ShapeParityTests {

        @Mock
        private FileStorageService fileStorageService;

        @Test
        @DisplayName("emits only canonical FileRef under `file` - no legacy flats (PR2 2026-05-15)")
        void shouldEmitOnlyCanonicalFileRef() {
            FileRef stored = FileRef.of("tenant/wf/run/node/report.csv", "report.csv", "text/csv", 100L);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(stored);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);
            assertTrue(result.isSuccess());

            Map<String, Object> executeOutput = result.output();

            // Load-bearing pin: FileRef under `file` is the ONLY file shape emitted.
            assertInstanceOf(FileRef.class, executeOutput.get("file"),
                "execute() must emit canonical FileRef under `file` (PR2)");

            // PR2 clean break - must NOT emit any legacy flat field.
            assertFalse(executeOutput.containsKey("file_url"),
                "PR2 clean break - must NOT emit legacy file_url");
            assertFalse(executeOutput.containsKey("file_name"),
                "PR2 clean break - must NOT emit legacy file_name");
            assertFalse(executeOutput.containsKey("file_size"),
                "PR2 clean break - must NOT emit legacy file_size");
            assertFalse(executeOutput.containsKey("content_type"),
                "PR2 clean break - must NOT emit legacy content_type");
        }

        @Test
        @DisplayName("executeOutputMatchesPersistedShape: canonical FileRef survives customTransform()")
        void executeOutputMatchesPersistedShape() {
            FileRef stored = FileRef.of("tenant/wf/run/node/report.csv", "report.csv", "text/csv", 100L);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(stored);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);
            assertTrue(result.isSuccess());

            Map<String, Object> executeOutput = result.output();

            // PR2 2026-05-15: canonical FileRef only - legacy flat keys are absent.
            assertInstanceOf(FileRef.class, executeOutput.get("file"),
                "execute() `file` value must be a FileRef record (PR2)");
            assertTrue(executeOutput.containsKey("row_count"),    "execute() must contain row_count");

            // PR2 clean break - legacy flat fields are gone for good.
            assertFalse(executeOutput.containsKey("file_url"),
                "PR2 clean break - must NOT emit legacy file_url");
            assertFalse(executeOutput.containsKey("file_name"),
                "PR2 clean break - must NOT emit legacy file_name");
            assertFalse(executeOutput.containsKey("file_size"),
                "PR2 clean break - must NOT emit legacy file_size");
            assertFalse(executeOutput.containsKey("content_type"),
                "PR2 clean break - must NOT emit legacy content_type");

            // execute() must NOT produce the old camelCase keys (snake_case is the contract).
            assertFalse(executeOutput.containsKey("filename"), "execute() must NOT contain camelCase 'filename'");
            assertFalse(executeOutput.containsKey("rowCount"), "execute() must NOT contain camelCase 'rowCount'");
            assertFalse(executeOutput.containsKey("size"),     "execute() must NOT contain old 'size' key");

            // customTransform must strip engine-envelope keys and preserve canonical FileRef.
            ConvertToFileNodeSpec spec = new ConvertToFileNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);
            assertInstanceOf(FileRef.class, persistedOutput.get("file"),
                "persisted `file` value must be a FileRef record");
            assertTrue(persistedOutput.containsKey("row_count"),     "persisted output must contain row_count");

            // PR2 clean break - persisted output also free of legacy flats.
            assertFalse(persistedOutput.containsKey("file_url"),
                "PR2 clean break - persisted output must NOT contain legacy file_url");
            assertFalse(persistedOutput.containsKey("file_name"),
                "PR2 clean break - persisted output must NOT contain legacy file_name");
            assertFalse(persistedOutput.containsKey("file_size"),
                "PR2 clean break - persisted output must NOT contain legacy file_size");
            assertFalse(persistedOutput.containsKey("content_type"),
                "PR2 clean break - persisted output must NOT contain legacy content_type");

            assertFalse(persistedOutput.containsKey("node_type"),    "persisted output must NOT contain engine key node_type");
            assertFalse(persistedOutput.containsKey("item_index"),   "persisted output must NOT contain engine key item_index");
            assertFalse(persistedOutput.containsKey("itemIndex"),    "persisted output must NOT contain engine key itemIndex");
            assertFalse(persistedOutput.containsKey("item_id"),      "persisted output must NOT contain engine key item_id");
            assertFalse(persistedOutput.containsKey("resolved_params"), "persisted output must NOT contain engine key resolved_params");
        }
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create ConvertToFileNode with nodeId and config")
        void shouldCreateConvertToFileNodeWithNodeIdAndConfig() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "data_ref", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            assertEquals("core:convert", node.getNodeId());
            assertEquals(NodeType.CONVERT_TO_FILE, node.getType());
            assertNotNull(node.getConvertToFileConfig());
            assertEquals("csv", node.getConvertToFileConfig().format());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            ConvertToFileNode node = new ConvertToFileNode("core:convert", null);

            assertEquals("core:convert", node.getNodeId());
            assertNull(node.getConvertToFileConfig());
        }

        @Test
        @DisplayName("Should apply config defaults for null fields")
        void shouldApplyConfigDefaultsForNullFields() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig(null, null, null, null, "yes");

            assertEquals("csv", config.format());
            assertEquals("export", config.filename());
            assertEquals(",", config.delimiter());
        }

        @Test
        @DisplayName("Should normalize format to lowercase")
        void shouldNormalizeFormatToLowercase() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("CSV", null, null, null, "yes");
            assertEquals("csv", config.format());

            Core.ConvertToFileConfig config2 = new Core.ConvertToFileConfig("Xlsx", null, null, null, "yes");
            assertEquals("xlsx", config2.format());
        }
    }

    // ===============================================================
    // execute() - CSV conversion tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - CSV conversion")
    class ExecuteCsvTests {

        @Mock
        private FileStorageService fileStorageService;

        @Test
        @DisplayName("Should convert data to CSV with headers")
        void shouldConvertDataToCsvWithHeaders() {
            FileRef stored = FileRef.of("tenant/wf/run/node/report.csv", "report.csv", "text/csv", 100L);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(stored);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            String csv = (String) result.output().get("result");
            assertNotNull(csv);
            assertTrue(csv.contains("id"));
            assertTrue(csv.contains("name"));
            assertTrue(csv.contains("email"));
            assertTrue(csv.contains("Alice"));
            assertTrue(csv.contains("Bob"));
            assertTrue(csv.contains("Charlie"));
            assertEquals("csv", result.output().get("format"));
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("report.csv", file.name());
            assertEquals(3, result.output().get("row_count"));
            assertEquals(true, result.output().get("success"));
        }

        @Test
        @DisplayName("Should convert data to CSV without headers")
        void shouldConvertDataToCsvWithoutHeaders() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "query_results", "report", ",", "no");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            String csv = (String) result.output().get("result");
            assertNotNull(csv);
            // First line should be data, not headers
            String firstLine = csv.split("\n")[0];
            assertTrue(firstLine.contains("1"));
            assertTrue(firstLine.contains("Alice"));
        }

        @Test
        @DisplayName("Should use custom delimiter")
        void shouldUseCustomDelimiter() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "query_results", "report", ";", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            String csv = (String) result.output().get("result");
            assertNotNull(csv);
            // Check delimiter is used
            assertTrue(csv.contains(";"));
        }

        @Test
        @DisplayName("Should handle empty data for CSV")
        void shouldHandleEmptyDataForCsv() {
            // Use a value that does not exist in step outputs, with empty trigger data
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "nonexistent", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            // Context with empty trigger data
            ExecutionContext emptyContext = ExecutionContext.create(
                "run-3", "workflow-run-3", "tenant-1", "item-3", 0,
                new HashMap<>(), mockPlan
            );

            NodeExecutionResult result = node.execute(emptyContext);

            assertTrue(result.isSuccess());
            String csv = (String) result.output().get("result");
            assertEquals("", csv);
            assertEquals(0, result.output().get("row_count"));
        }

        @Test
        @DisplayName("Should escape CSV fields containing delimiter")
        void shouldEscapeCsvFieldsContainingDelimiter() {
            List<Map<String, Object>> data = new ArrayList<>();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("description", "Hello, World");
            row.put("note", "Line1\nLine2");
            data.add(row);

            Map<String, Object> specialOutputs = new HashMap<>();
            specialOutputs.put("special_data", data);
            ExecutionContext ctxWithSpecial = new ExecutionContext(
                "run-4", "workflow-run-4", "tenant-1", "item-4", 0,
                null, 0, 0, new HashMap<>(), specialOutputs,
                ExecutionState.create(), mockPlan
            );

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "special_data", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(ctxWithSpecial);

            assertTrue(result.isSuccess());
            String csv = (String) result.output().get("result");
            // Fields with commas or newlines should be quoted
            assertTrue(csv.contains("\"Hello, World\""));
            assertTrue(csv.contains("\"Line1\nLine2\""));
        }

        @Test
        @DisplayName("Should fallback to trigger data when no step output found")
        void shouldFallbackToTriggerDataForCsv() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", null, "export", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            String csv = (String) result.output().get("result");
            assertNotNull(csv);
            assertTrue(csv.contains("name"));
            assertTrue(csv.contains("John"));
            assertEquals(1, result.output().get("row_count"));
        }
    }

    // ===============================================================
    // execute() - XLSX conversion tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - XLSX conversion")
    class ExecuteXlsxTests {

        @Mock
        private FileStorageService fileStorageService;

        @Test
        @DisplayName("Should convert data to XLSX as base64")
        void shouldConvertDataToXlsxAsBase64() throws Exception {
            FileRef stored = FileRef.of("tenant/wf/run/node/report.xlsx", "report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 200L);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(stored);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("xlsx", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            String base64 = (String) result.output().get("result");
            assertNotNull(base64);
            assertEquals("xlsx", result.output().get("format"));
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("report.xlsx", file.name());
            assertEquals(3, result.output().get("row_count"));

            // Verify base64 can be decoded
            byte[] decoded = Base64.getDecoder().decode(base64);
            assertNotNull(decoded);
            assertTrue(decoded.length > 0);
        }

        @Test
        @DisplayName("Should produce valid XLSX with correct row and column counts")
        void shouldProduceValidXlsxWithCorrectCounts() throws Exception {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("xlsx", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);
            assertTrue(result.isSuccess());

            String base64 = (String) result.output().get("result");
            byte[] decoded = Base64.getDecoder().decode(base64);

            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(decoded))) {
                Sheet sheet = workbook.getSheetAt(0);
                assertNotNull(sheet);

                // Header row + 3 data rows = 4 total rows
                assertEquals(4, sheet.getPhysicalNumberOfRows());

                // Verify header row
                Row headerRow = sheet.getRow(0);
                assertEquals("id", headerRow.getCell(0).getStringCellValue());
                assertEquals("name", headerRow.getCell(1).getStringCellValue());
                assertEquals("email", headerRow.getCell(2).getStringCellValue());

                // Verify first data row
                Row dataRow = sheet.getRow(1);
                assertEquals(1.0, dataRow.getCell(0).getNumericCellValue());
                assertEquals("Alice", dataRow.getCell(1).getStringCellValue());
                assertEquals("alice@example.com", dataRow.getCell(2).getStringCellValue());

                // 3 columns
                assertEquals(3, headerRow.getPhysicalNumberOfCells());
            }
        }

        @Test
        @DisplayName("Should produce XLSX without headers when includeHeaders is false")
        void shouldProduceXlsxWithoutHeaders() throws Exception {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("xlsx", "query_results", "report", ",", "no");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);
            assertTrue(result.isSuccess());

            String base64 = (String) result.output().get("result");
            byte[] decoded = Base64.getDecoder().decode(base64);

            try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(decoded))) {
                Sheet sheet = workbook.getSheetAt(0);
                // 3 data rows only (no header)
                assertEquals(3, sheet.getPhysicalNumberOfRows());

                // First row is data, not header
                Row firstRow = sheet.getRow(0);
                assertEquals(1.0, firstRow.getCell(0).getNumericCellValue());
            }
        }

        @Test
        @DisplayName("Should handle empty data for XLSX")
        void shouldHandleEmptyDataForXlsx() throws Exception {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("xlsx", "nonexistent", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            ExecutionContext emptyContext = ExecutionContext.create(
                "run-5", "workflow-run-5", "tenant-1", "item-5", 0,
                new HashMap<>(), mockPlan
            );

            NodeExecutionResult result = node.execute(emptyContext);

            assertTrue(result.isSuccess());
            String base64 = (String) result.output().get("result");
            assertNotNull(base64);
            assertEquals(0, result.output().get("row_count"));

            // Should be decodable
            byte[] decoded = Base64.getDecoder().decode(base64);
            assertTrue(decoded.length > 0);
        }
    }

    // ===============================================================
    // execute() - JSON conversion tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - JSON conversion")
    class ExecuteJsonTests {

        @Mock
        private FileStorageService fileStorageService;

        @Test
        @DisplayName("Should convert data to pretty-printed JSON")
        void shouldConvertDataToPrettyPrintedJson() {
            FileRef stored = FileRef.of("tenant/wf/run/node/data.json", "data.json", "application/json", 50L);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(stored);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("json", "query_results", "data", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            String json = (String) result.output().get("result");
            assertNotNull(json);
            assertTrue(json.contains("Alice"));
            assertTrue(json.contains("Bob"));
            assertTrue(json.contains("Charlie"));
            // Verify it's pretty-printed (contains indentation)
            assertTrue(json.contains("\n"));
            assertEquals("json", result.output().get("format"));
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("data.json", file.name());
            assertEquals(3, result.output().get("row_count"));
        }

        @Test
        @DisplayName("Should produce valid JSON")
        void shouldProduceValidJson() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("json", "query_results", "out", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);
            assertTrue(result.isSuccess());

            String json = (String) result.output().get("result");
            // Should start with [ since it's a list
            assertTrue(json.trim().startsWith("["));
            assertTrue(json.trim().endsWith("]"));
        }

        @Test
        @DisplayName("Should handle empty data for JSON")
        void shouldHandleEmptyDataForJson() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("json", "nonexistent", "data", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            ExecutionContext emptyContext = ExecutionContext.create(
                "run-6", "workflow-run-6", "tenant-1", "item-6", 0,
                new HashMap<>(), mockPlan
            );

            NodeExecutionResult result = node.execute(emptyContext);

            assertTrue(result.isSuccess());
            String json = (String) result.output().get("result");
            assertEquals("[ ]", json.trim());
            assertEquals(0, result.output().get("row_count"));
        }
    }

    // ===============================================================
    // execute() - TXT conversion tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - TXT conversion")
    class ExecuteTxtTests {

        @Mock
        private FileStorageService fileStorageService;

        @Test
        @DisplayName("Should convert data to key=value text format")
        void shouldConvertDataToKeyValueTextFormat() {
            FileRef stored = FileRef.of("tenant/wf/run/node/output.txt", "output.txt", "text/plain", 30L);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(stored);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("txt", "query_results", "output", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            String txt = (String) result.output().get("result");
            assertNotNull(txt);
            assertTrue(txt.contains("id=1"));
            assertTrue(txt.contains("name=Alice"));
            assertTrue(txt.contains("email=alice@example.com"));
            assertTrue(txt.contains("name=Bob"));
            assertEquals("txt", result.output().get("format"));
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("output.txt", file.name());
            assertEquals(3, result.output().get("row_count"));
        }

        @Test
        @DisplayName("Should separate rows with blank lines")
        void shouldSeparateRowsWithBlankLines() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("txt", "query_results", "output", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            String txt = (String) result.output().get("result");
            // Rows separated by blank line: \n\n
            assertTrue(txt.contains("\n\n"));
        }

        @Test
        @DisplayName("Should handle empty data for TXT")
        void shouldHandleEmptyDataForTxt() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("txt", "nonexistent", "output", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            ExecutionContext emptyContext = ExecutionContext.create(
                "run-7", "workflow-run-7", "tenant-1", "item-7", 0,
                new HashMap<>(), mockPlan
            );

            NodeExecutionResult result = node.execute(emptyContext);

            assertTrue(result.isSuccess());
            String txt = (String) result.output().get("result");
            assertEquals("", txt);
            assertEquals(0, result.output().get("row_count"));
        }
    }

    // ===============================================================
    // execute() - Error handling tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Error handling")
    class ExecuteErrorHandlingTests {

        @Test
        @DisplayName("Should fail for unknown format")
        void shouldFailForUnknownFormat() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("pdf", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should handle null config gracefully (defaults to csv)")
        void shouldHandleNullConfigGracefully() {
            ConvertToFileNode node = new ConvertToFileNode("core:convert", null);

            NodeExecutionResult result = node.execute(context);

            // Null config defaults to csv format, uses trigger data as fallback
            assertTrue(result.isSuccess());
            assertEquals("csv", result.output().get("format"));
        }

        @Test
        @DisplayName("Should handle null value gracefully")
        void shouldHandleNullValueGracefully() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", null, "export", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(context);

            // Null value falls back to trigger data
            assertTrue(result.isSuccess());
            assertEquals(1, result.output().get("row_count"));
        }

        @Test
        @DisplayName("Should include mandatory metadata fields on success")
        void shouldIncludeMandatoryMetadataFieldsOnSuccess() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", null, "export", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("CONVERT_TO_FILE", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
            // PR2 2026-05-15: file_size is no longer emitted; size lives on FileRef when S3 wired.
            assertFalse(result.output().containsKey("file_size"),
                "PR2 clean break - must NOT emit legacy file_size");
        }

        @Test
        @DisplayName("failureOutputContainsCanonicalKeys: persisted output mirrors declared shape with safe defaults on failure")
        void failureOutputContainsCanonicalKeys() {
            // Unknown format triggers the catch-path that previously leaked 'error' into output
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("pdf", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isFailure(), "Expected failure on unknown format");
            Map<String, Object> executeOutput = result.output();
            assertFalse(executeOutput.containsKey("error"),
                "execute() output must NOT contain undeclared 'error' key - use NodeExecutionResult error channel");

            ConvertToFileNodeSpec spec = new ConvertToFileNodeSpec();
            Map<String, Object> persistedOutput = spec.customTransform(executeOutput);
            assertFalse(persistedOutput.containsKey("error"),
                "persisted output must NOT contain undeclared 'error' key");

            // Canonical declared keys must be present on failure (mirrors LimitNode pattern)
            assertEquals(false, persistedOutput.get("success"));
            assertEquals("pdf", persistedOutput.get("format"));
            assertEquals(0, persistedOutput.get("row_count"));
            assertTrue(persistedOutput.containsKey("result"));
            assertNull(persistedOutput.get("result"));
            // PR2 2026-05-15: file is canonical-null on failure (mirrors DownloadFileNode for symmetry -
            // consumers null-check a single shape across all 4 producers).
            assertTrue(persistedOutput.containsKey("file"),
                "PR2: `file` key must be present on failure with null value (null-check parity with DownloadFileNode.buildFailureOutput)");
            assertNull(persistedOutput.get("file"),
                "PR2: `file` value must be null on failure (no FileRef produced)");
            assertFalse(persistedOutput.containsKey("file_url"),
                "PR2 clean break - must NOT emit legacy file_url");
            assertFalse(persistedOutput.containsKey("file_name"),
                "PR2 clean break - must NOT emit legacy file_name");
        }
    }

    // ===============================================================
    // Builder tests
    // ===============================================================

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("Should create ConvertToFileNode using builder")
        void shouldCreateConvertToFileNodeUsingBuilder() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("xlsx", "data_ref", "report", ";", "no");

            ConvertToFileNode node = ConvertToFileNode.builder()
                .nodeId("core:my_convert")
                .convertToFileConfig(config)
                .build();

            assertEquals("core:my_convert", node.getNodeId());
            assertEquals(NodeType.CONVERT_TO_FILE, node.getType());
            assertEquals("xlsx", node.getConvertToFileConfig().format());
            assertEquals("report", node.getConvertToFileConfig().filename());
            assertEquals(";", node.getConvertToFileConfig().delimiter());
            assertFalse(node.getConvertToFileConfig().hasHeaders());
        }

        @Test
        @DisplayName("Should create builder with static method")
        void shouldCreateBuilderWithStaticMethod() {
            ConvertToFileNode.Builder builder = ConvertToFileNode.builder();
            assertNotNull(builder);
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
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", null, "export", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:convert", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", null, "export", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:convert", "Error");

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
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", null, "export", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            NodeExecutionResult result = NodeExecutionResult.success("core:convert", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", null, "export", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            NodeExecutionResult result = NodeExecutionResult.failure("core:convert", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // S3 Upload tests
    // ===============================================================

    @Nested
    @DisplayName("S3 Upload")
    class S3UploadTests {

        @Mock
        private FileStorageService fileStorageService;

        @Test
        @DisplayName("Should upload CSV to S3 and include FileRef in result")
        void shouldUploadCsvToS3() {
            FileRef expectedRef = FileRef.of("tenant-1/wf/run/node/report.csv", "report.csv", "text/csv", 100);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), eq("report.csv"), eq("text/csv"), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(expectedRef);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("report.csv", file.name());
            assertEquals("text/csv", file.mimeType());
            verify(fileStorageService).upload(anyString(), any(), anyString(), anyString(), eq("report.csv"), eq("text/csv"), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any());
        }

        @Test
        @DisplayName("Should upload XLSX with decoded bytes to S3")
        void shouldUploadXlsxWithDecodedBytes() {
            FileRef expectedRef = FileRef.of("path/report.xlsx", "report.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 200);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), eq("report.xlsx"),
                eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(expectedRef);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("xlsx", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            assertInstanceOf(FileRef.class, result.output().get("file"),
                "execute() must emit canonical FileRef under `file`");
            // Verify bytes passed to S3 are decoded from base64, not the raw base64 string
            verify(fileStorageService).upload(anyString(), any(), anyString(), anyString(), eq("report.xlsx"),
                eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"), argThat((byte[] bytes) -> {
                    // The XLSX content should start with PK (ZIP magic bytes)
                    return bytes.length > 0 && bytes[0] == 0x50 && bytes[1] == 0x4B;
                }), anyInt(), anyInt(), nullable(Integer.class), any());
        }

        @Test
        @DisplayName("Should upload JSON to S3")
        void shouldUploadJsonToS3() {
            FileRef expectedRef = FileRef.of("path/data.json", "data.json", "application/json", 50);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), eq("data.json"), eq("application/json"), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(expectedRef);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("json", "query_results", "data", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("application/json", file.mimeType());
        }

        @Test
        @DisplayName("Should upload TXT to S3")
        void shouldUploadTxtToS3() {
            FileRef expectedRef = FileRef.of("path/output.txt", "output.txt", "text/plain", 30);
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), eq("output.txt"), eq("text/plain"), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenReturn(expectedRef);

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("txt", "query_results", "output", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            FileRef file = (FileRef) result.output().get("file");
            assertNotNull(file, "execute() must emit canonical FileRef under `file`");
            assertEquals("text/plain", file.mimeType());
        }

        @Test
        @DisplayName("Should succeed when FileStorageService is null (no `file` emitted)")
        void shouldSucceedWhenFileStorageServiceIsNull() {
            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            // No fileStorageService set

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            // PR2 2026-05-15: no S3 → no canonical `file` AND no legacy flats. Result content still present.
            assertFalse(result.output().containsKey("file"),
                "no FileStorageService → no canonical `file` (nothing uploaded)");
            assertFalse(result.output().containsKey("file_url"),
                "PR2 clean break - must NOT emit legacy file_url");
            assertNotNull(result.output().get("result"));
            assertEquals("csv", result.output().get("format"));
            assertEquals(3, result.output().get("row_count"));
            assertEquals(true, result.output().get("success"));
        }

        @Test
        @DisplayName("Should succeed when S3 upload fails (no `file` emitted)")
        void shouldSucceedWhenS3UploadFails() {
            when(fileStorageService.upload(anyString(), any(), anyString(), anyString(), anyString(), anyString(), any(byte[].class), anyInt(), anyInt(), nullable(Integer.class), any()))
                .thenThrow(new RuntimeException("S3 connection refused"));

            Core.ConvertToFileConfig config = new Core.ConvertToFileConfig("csv", "query_results", "report", ",", "yes");
            ConvertToFileNode node = new ConvertToFileNode("core:convert", config);
            node.setFileStorageService(fileStorageService);

            NodeExecutionResult result = node.execute(contextWithStepOutputs);

            assertTrue(result.isSuccess());
            // PR2 2026-05-15: upload failed → no canonical `file` AND no legacy flats.
            assertFalse(result.output().containsKey("file"),
                "S3 upload failed → no canonical `file` (nothing uploaded)");
            assertFalse(result.output().containsKey("file_url"),
                "PR2 clean break - must NOT emit legacy file_url");
            assertNotNull(result.output().get("result"));
            assertEquals("csv", result.output().get("format"));
            assertEquals(3, result.output().get("row_count"));
            assertEquals(true, result.output().get("success"));
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
}
