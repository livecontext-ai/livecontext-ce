package com.apimarketplace.datasource.tools.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for ToolParameterUtils utility class.
 */
@DisplayName("ToolParameterUtils")
class ToolParameterUtilsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== getTableId ====================

    @Nested
    @DisplayName("getTableId")
    class GetTableIdTests {

        @Test
        @DisplayName("Should resolve from datasource_id")
        void shouldResolveFromDatasourceId() {
            Map<String, Object> params = Map.of("datasource_id", 42);
            assertThat(ToolParameterUtils.getTableId(params)).isEqualTo(42L);
        }

        @Test
        @DisplayName("Should resolve from table_id when datasource_id missing")
        void shouldResolveFromTableId() {
            Map<String, Object> params = Map.of("table_id", 7);
            assertThat(ToolParameterUtils.getTableId(params)).isEqualTo(7L);
        }

        @Test
        @DisplayName("Should resolve from id as last fallback")
        void shouldResolveFromId() {
            Map<String, Object> params = Map.of("id", 99);
            assertThat(ToolParameterUtils.getTableId(params)).isEqualTo(99L);
        }

        @Test
        @DisplayName("datasource_id takes priority over table_id")
        void datasourceIdTakesPriority() {
            Map<String, Object> params = Map.of("datasource_id", 1, "table_id", 2, "id", 3);
            assertThat(ToolParameterUtils.getTableId(params)).isEqualTo(1L);
        }

        @Test
        @DisplayName("table_id takes priority over id")
        void tableIdTakesPriorityOverId() {
            Map<String, Object> params = Map.of("table_id", 5, "id", 10);
            assertThat(ToolParameterUtils.getTableId(params)).isEqualTo(5L);
        }

        @Test
        @DisplayName("Should return null when no id parameter present")
        void shouldReturnNullWhenNoId() {
            Map<String, Object> params = Map.of("name", "test");
            assertThat(ToolParameterUtils.getTableId(params)).isNull();
        }

        @Test
        @DisplayName("Should parse string table_id")
        void shouldParseStringTableId() {
            Map<String, Object> params = Map.of("table_id", "22");
            assertThat(ToolParameterUtils.getTableId(params)).isEqualTo(22L);
        }
    }

    // ==================== getLongParam ====================

    @Nested
    @DisplayName("getLongParam")
    class GetLongParamTests {

        @Test
        @DisplayName("Should parse Long from Number")
        void shouldParseFromNumber() {
            Map<String, Object> params = Map.of("id", 42);
            assertThat(ToolParameterUtils.getLongParam(params, "id")).isEqualTo(42L);
        }

        @Test
        @DisplayName("Should parse Long from String")
        void shouldParseFromString() {
            Map<String, Object> params = Map.of("id", "123");
            assertThat(ToolParameterUtils.getLongParam(params, "id")).isEqualTo(123L);
        }

        @Test
        @DisplayName("Should return null for non-numeric String")
        void shouldReturnNullForNonNumeric() {
            Map<String, Object> params = Map.of("id", "abc");
            assertThat(ToolParameterUtils.getLongParam(params, "id")).isNull();
        }

        @Test
        @DisplayName("Should return null for missing key")
        void shouldReturnNullForMissing() {
            Map<String, Object> params = Map.of();
            assertThat(ToolParameterUtils.getLongParam(params, "id")).isNull();
        }
    }

    // ==================== getIntParam ====================

    @Nested
    @DisplayName("getIntParam")
    class GetIntParamTests {

        @Test
        @DisplayName("Should parse int from Number")
        void shouldParseFromNumber() {
            Map<String, Object> params = Map.of("page", 5);
            assertThat(ToolParameterUtils.getIntParam(params, "page", 1)).isEqualTo(5);
        }

        @Test
        @DisplayName("Should parse int from String")
        void shouldParseFromString() {
            Map<String, Object> params = Map.of("page", "3");
            assertThat(ToolParameterUtils.getIntParam(params, "page", 1)).isEqualTo(3);
        }

        @Test
        @DisplayName("Should return default for non-numeric String")
        void shouldReturnDefaultForNonNumeric() {
            Map<String, Object> params = Map.of("page", "abc");
            assertThat(ToolParameterUtils.getIntParam(params, "page", 1)).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return default for missing key")
        void shouldReturnDefaultForMissing() {
            Map<String, Object> params = Map.of();
            assertThat(ToolParameterUtils.getIntParam(params, "page", 10)).isEqualTo(10);
        }
    }

    // ==================== getStringParam ====================

    @Nested
    @DisplayName("getStringParam")
    class GetStringParamTests {

        @Test
        @DisplayName("Should return string for String value")
        void shouldReturnString() {
            Map<String, Object> params = Map.of("name", "test");
            assertThat(ToolParameterUtils.getStringParam(params, "name")).isEqualTo("test");
        }

        @Test
        @DisplayName("Should convert non-String values to string")
        void shouldConvertNonStringToString() {
            Map<String, Object> params = Map.of("count", 42);
            assertThat(ToolParameterUtils.getStringParam(params, "count")).isEqualTo("42");
        }

        @Test
        @DisplayName("Should return null for missing key")
        void shouldReturnNullForMissing() {
            Map<String, Object> params = Map.of();
            assertThat(ToolParameterUtils.getStringParam(params, "name")).isNull();
        }
    }

    // ==================== parseDataArray ====================

    @Nested
    @DisplayName("parseDataArray")
    class ParseDataArrayTests {

        @Test
        @DisplayName("Should parse List of Maps")
        void shouldParseListOfMaps() {
            List<Map<String, Object>> data = List.of(
                Map.of("name", "Alice"),
                Map.of("name", "Bob")
            );
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray(data, objectMapper);
            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsEntry("name", "Alice");
        }

        @Test
        @DisplayName("Should wrap single Map in list")
        void shouldWrapSingleMapInList() {
            Map<String, Object> data = Map.of("name", "Alice");
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray(data, objectMapper);
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).containsEntry("name", "Alice");
        }

        @Test
        @DisplayName("Should parse JSON string to list")
        void shouldParseJsonString() {
            String json = "[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]";
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray(json, objectMapper);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray(null, objectMapper);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should throw for invalid JSON string")
        void shouldThrowForInvalidJson() {
            assertThatThrownBy(() -> ToolParameterUtils.parseDataArray("not json", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON");
        }

        @Test
        @DisplayName("Should return empty list for blank string")
        void shouldReturnEmptyForBlankString() {
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray("  ", objectMapper);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should auto-deserialize double-serialized JSON row strings")
        void shouldAutoDeserializeDoubleSerializedRows() {
            List<String> rows = List.of(
                "{\"columns\": {\"name\": \"Alice\", \"age\": 30}}",
                "{\"columns\": {\"name\": \"Bob\", \"age\": 25}}"
            );
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray(rows, objectMapper);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsKey("columns");
            assertThat(result.get(1)).containsKey("columns");
        }

        @Test
        @DisplayName("Should handle mixed list: Maps and JSON strings")
        void shouldHandleMixedList() {
            List<Object> data = List.of(
                Map.of("name", "Alice"),
                "{\"name\": \"Bob\"}"
            );
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray(data, objectMapper);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsEntry("name", "Alice");
            assertThat(result.get(1)).containsEntry("name", "Bob");
        }

        @Test
        @DisplayName("Should skip non-Map non-JSON items")
        void shouldSkipNonMapNonJsonItems() {
            List<Object> data = List.of(
                Map.of("name", "Alice"),
                "plain string",
                42
            );
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray(data, objectMapper);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("Should handle rows with nested types (arrays, booleans, numbers)")
        void shouldHandleNestedTypes() {
            List<String> rows = List.of(
                "{\"columns\": {\"Texte\": \"Test\", \"Nombre\": 10, \"Checkbox\": true, \"Multi\": [\"t1\",\"t2\"]}}"
            );
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = ToolParameterUtils.parseDataArray(rows, objectMapper);

            assertThat(result).hasSize(1);
            Map<String, Object> columns = (Map<String, Object>) result.get(0).get("columns");
            assertThat(columns).containsEntry("Texte", "Test");
            assertThat(columns).containsEntry("Nombre", 10);
            assertThat(columns).containsEntry("Checkbox", true);
            assertThat(columns.get("Multi")).isInstanceOf(List.class);
        }
    }

    // ==================== parseUpdatesObject ====================

    @Nested
    @DisplayName("parseUpdatesObject")
    class ParseUpdatesObjectTests {

        @Test
        @DisplayName("Should return Map directly")
        void shouldReturnMapDirectly() {
            Map<String, Object> updates = Map.of("name", "New Name");
            Map<String, Object> result = ToolParameterUtils.parseUpdatesObject(updates, objectMapper);
            assertThat(result).containsEntry("name", "New Name");
        }

        @Test
        @DisplayName("Should parse JSON string to Map")
        void shouldParseJsonString() {
            String json = "{\"name\":\"Updated\"}";
            Map<String, Object> result = ToolParameterUtils.parseUpdatesObject(json, objectMapper);
            assertThat(result).containsEntry("name", "Updated");
        }

        @Test
        @DisplayName("Should return empty map for null")
        void shouldReturnEmptyForNull() {
            Map<String, Object> result = ToolParameterUtils.parseUpdatesObject(null, objectMapper);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should throw for invalid JSON string")
        void shouldThrowForInvalidJson() {
            assertThatThrownBy(() -> ToolParameterUtils.parseUpdatesObject("not json", objectMapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid JSON");
        }
    }

    // ==================== parseColumnsArray ====================

    @Nested
    @DisplayName("parseColumnsArray")
    class ParseColumnsArrayTests {

        @Test
        @DisplayName("Should parse list of column Maps")
        void shouldParseListOfColumnMaps() {
            List<Map<String, Object>> columns = List.of(
                Map.of("name", "email", "type", "text"),
                Map.of("name", "count", "type", "number")
            );
            List<Map<String, Object>> result = ToolParameterUtils.parseColumnsArray(columns, objectMapper, null);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsEntry("name", "email");
        }

        @Test
        @DisplayName("Should convert plain strings to column definitions with type text")
        void shouldConvertStringsToColumnDefs() {
            List<String> columns = List.of("email", "phone");
            List<Map<String, Object>> result = ToolParameterUtils.parseColumnsArray(columns, objectMapper, null);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsEntry("name", "email");
            assertThat(result.get(0)).containsEntry("type", "text");
        }

        @Test
        @DisplayName("Should throw for column Map without name")
        void shouldThrowForColumnWithoutName() {
            List<Map<String, Object>> columns = List.of(Map.of("type", "text"));

            assertThatThrownBy(() -> ToolParameterUtils.parseColumnsArray(columns, objectMapper, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        }

        @Test
        @DisplayName("Should throw for non-list input")
        void shouldThrowForNonListInput() {
            assertThatThrownBy(() -> ToolParameterUtils.parseColumnsArray("not-a-list", objectMapper, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("array");
        }

        @Test
        @DisplayName("Should auto-deserialize double-serialized JSON column strings")
        void shouldAutoDeserializeDoubleSerializedJson() {
            List<String> columns = List.of(
                "{\"name\": \"email\", \"type\": \"email\"}",
                "{\"name\": \"rating\", \"type\": \"rating\", \"display\": {\"max\": 5}}"
            );
            List<Map<String, Object>> result = ToolParameterUtils.parseColumnsArray(columns, objectMapper, null);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsEntry("name", "email");
            assertThat(result.get(0)).containsEntry("type", "email");
            assertThat(result.get(1)).containsEntry("name", "rating");
            assertThat(result.get(1)).containsKey("display");
        }

        @Test
        @DisplayName("Should handle all 14 column types in double-serialized form")
        void shouldHandleAll14TypesDoubleSerialized() {
            List<String> columns = List.of(
                "{\"name\": \"c1\", \"type\": \"text\"}",
                "{\"name\": \"c2\", \"type\": \"number\"}",
                "{\"name\": \"c3\", \"type\": \"date\"}",
                "{\"name\": \"c4\", \"type\": \"checkbox\"}",
                "{\"name\": \"c5\", \"type\": \"select\"}",
                "{\"name\": \"c6\", \"type\": \"multi_select\"}",
                "{\"name\": \"c7\", \"type\": \"rating\"}",
                "{\"name\": \"c8\", \"type\": \"sentiment\"}",
                "{\"name\": \"c9\", \"type\": \"progress\"}",
                "{\"name\": \"c10\", \"type\": \"file\"}",
                "{\"name\": \"c11\", \"type\": \"image\"}",
                "{\"name\": \"c12\", \"type\": \"email\"}",
                "{\"name\": \"c13\", \"type\": \"phone\"}",
                "{\"name\": \"c14\", \"type\": \"url\"}"
            );
            List<Map<String, Object>> result = ToolParameterUtils.parseColumnsArray(columns, objectMapper, null);

            assertThat(result).hasSize(14);
            assertThat(result.get(3)).containsEntry("type", "checkbox");
            assertThat(result.get(5)).containsEntry("type", "multi_select");
            assertThat(result.get(11)).containsEntry("type", "email");
            assertThat(result.get(13)).containsEntry("type", "url");
        }
    }

    // ==================== Real LLM scenarios ====================

    @Nested
    @DisplayName("Real LLM scenarios (integration-like)")
    class RealLlmScenarios {

        @Test
        @DisplayName("insert_rows: double-serialized rows with columns wrapper")
        void insertRowsDoubleSerializedWithColumnsWrapper() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 24);
            params.put("action", "insert_rows");
            params.put("rows", List.of(
                "{\"columns\": {\"Texte\": \"Ligne 1\", \"Nombre\": 1, \"Checkbox\": true}}",
                "{\"columns\": {\"Texte\": \"Ligne 2\", \"Nombre\": 2, \"Checkbox\": false}}"
            ));

            Long tableId = ToolParameterUtils.getTableId(params);
            assertThat(tableId).isEqualTo(24L);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = ToolParameterUtils.parseRowsArray(params.get("rows"), objectMapper);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsKey("columns");
        }

        @Test
        @DisplayName("insert_rows: properly serialized rows (Maps)")
        void insertRowsProperlySerializedMaps() {
            Map<String, Object> params = new HashMap<>();
            params.put("table_id", 10);
            params.put("rows", List.of(
                Map.of("columns", Map.of("name", "Alice", "age", 30)),
                Map.of("columns", Map.of("name", "Bob", "age", 25))
            ));

            List<Map<String, Object>> rows = ToolParameterUtils.parseRowsArray(params.get("rows"), objectMapper);
            assertThat(rows).hasSize(2);
        }

        @Test
        @DisplayName("insert_rows: flat rows without columns wrapper")
        void insertRowsFlatRowsWithoutColumnsWrapper() {
            Map<String, Object> params = new HashMap<>();
            params.put("datasource_id", 5);
            params.put("rows", List.of(
                Map.of("name", "Alice", "age", 30),
                Map.of("name", "Bob", "age", 25)
            ));

            Long tableId = ToolParameterUtils.getTableId(params);
            assertThat(tableId).isEqualTo(5L);

            List<Map<String, Object>> rows = ToolParameterUtils.parseRowsArray(params.get("rows"), objectMapper);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsEntry("name", "Alice");
        }

        @Test
        @DisplayName("create: double-serialized columns preserve types")
        void createDoubleSerializedColumnsPreserveTypes() {
            List<String> columns = List.of(
                "{\"name\": \"Status\", \"type\": \"select\", \"display\": {\"options\": [{\"label\": \"Active\", \"value\": \"A\", \"color\": \"#22c55e\"}]}}",
                "{\"name\": \"Done\", \"type\": \"checkbox\"}",
                "{\"name\": \"Email\", \"type\": \"email\"}"
            );

            List<Map<String, Object>> result = ToolParameterUtils.parseColumnsArray(columns, objectMapper, null);

            assertThat(result).hasSize(3);
            assertThat(result.get(0)).containsEntry("type", "select");
            assertThat(result.get(0)).containsKey("display");
            assertThat(result.get(1)).containsEntry("type", "checkbox");
            assertThat(result.get(2)).containsEntry("type", "email");
        }
    }

    // ==================== sanitizeColumnName ====================

    @Nested
    @DisplayName("sanitizeColumnName")
    class SanitizeColumnNameTests {

        @Test
        @DisplayName("Should strip data. prefix")
        void shouldStripDataPrefix() {
            assertThat(ToolParameterUtils.sanitizeColumnName("data.email")).isEqualTo("email");
        }

        @Test
        @DisplayName("Should leave unprefixed names untouched")
        void shouldLeaveUnprefixedNamesUntouched() {
            assertThat(ToolParameterUtils.sanitizeColumnName("email")).isEqualTo("email");
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(ToolParameterUtils.sanitizeColumnName(null)).isNull();
        }

        @Test
        @DisplayName("Should strip only the first data. prefix, not a nested one")
        void shouldStripOnlyFirstPrefix() {
            assertThat(ToolParameterUtils.sanitizeColumnName("data.data.email")).isEqualTo("data.email");
        }

        @Test
        @DisplayName("Should not strip names that merely contain data but do not start with it")
        void shouldNotStripContains() {
            assertThat(ToolParameterUtils.sanitizeColumnName("metadata.email")).isEqualTo("metadata.email");
        }

        @Test
        @DisplayName("Should preserve empty string")
        void shouldPreserveEmpty() {
            assertThat(ToolParameterUtils.sanitizeColumnName("")).isEqualTo("");
        }
    }

    // ==================== validateColumnType ====================

    @Nested
    @DisplayName("validateColumnType")
    class ValidateColumnTypeTests {

        @Test
        @DisplayName("Should accept null as valid (defaults to text downstream)")
        void shouldAcceptNull() {
            assertThat(ToolParameterUtils.validateColumnType(null)).isNull();
        }

        @Test
        @DisplayName("Should accept blank as valid")
        void shouldAcceptBlank() {
            assertThat(ToolParameterUtils.validateColumnType("   ")).isNull();
        }

        @Test
        @DisplayName("Should accept canonical text type")
        void shouldAcceptText() {
            assertThat(ToolParameterUtils.validateColumnType("text")).isNull();
        }

        @Test
        @DisplayName("Should accept multi_select")
        void shouldAcceptMultiSelect() {
            assertThat(ToolParameterUtils.validateColumnType("multi_select")).isNull();
        }

        @Test
        @DisplayName("Should be case-insensitive and trim")
        void shouldBeCaseInsensitive() {
            assertThat(ToolParameterUtils.validateColumnType("  TEXT  ")).isNull();
            assertThat(ToolParameterUtils.validateColumnType("Number")).isNull();
        }

        @Test
        @DisplayName("Should reject unknown type with descriptive message listing valid types")
        void shouldRejectUnknown() {
            String err = ToolParameterUtils.validateColumnType("string");
            assertThat(err).isNotNull();
            assertThat(err).contains("Invalid column type 'string'");
            assertThat(err).contains("text");
            assertThat(err).contains("number");
        }

        @Test
        @DisplayName("Should reject typo like 'boolean'")
        void shouldRejectBoolean() {
            assertThat(ToolParameterUtils.validateColumnType("boolean")).isNotNull();
        }

        @Test
        @DisplayName("Should accept vector type")
        void shouldAcceptVector() {
            assertThat(ToolParameterUtils.validateColumnType("vector")).isNull();
        }
    }

    // ==================== validateReservedColumnName ====================

    @Nested
    @DisplayName("validateReservedColumnName")
    class ValidateReservedColumnNameTests {

        @Test
        @DisplayName("Should accept null")
        void shouldAcceptNull() {
            assertThat(ToolParameterUtils.validateReservedColumnName(null)).isNull();
        }

        @Test
        @DisplayName("Should accept non-reserved name")
        void shouldAcceptNonReserved() {
            assertThat(ToolParameterUtils.validateReservedColumnName("email")).isNull();
        }

        @Test
        @DisplayName("Should reject 'id' as reserved")
        void shouldRejectId() {
            String err = ToolParameterUtils.validateReservedColumnName("id");
            assertThat(err).isNotNull();
            assertThat(err).contains("reserved");
        }

        @Test
        @DisplayName("Should reject 'priority' as reserved (regression guard)")
        void shouldRejectPriority() {
            assertThat(ToolParameterUtils.validateReservedColumnName("priority")).isNotNull();
        }

        @Test
        @DisplayName("Should reject 'data' as reserved")
        void shouldRejectData() {
            assertThat(ToolParameterUtils.validateReservedColumnName("data")).isNotNull();
        }

        @Test
        @DisplayName("Should reject case-insensitively")
        void shouldRejectCaseInsensitively() {
            assertThat(ToolParameterUtils.validateReservedColumnName("ID")).isNotNull();
            assertThat(ToolParameterUtils.validateReservedColumnName("  Tenant_Id ")).isNotNull();
        }

        @Test
        @DisplayName("Error message should list the full reserved set")
        void errorListsReservedSet() {
            String err = ToolParameterUtils.validateReservedColumnName("id");
            assertThat(err).contains("id");
            assertThat(err).contains("data_source_id");
            assertThat(err).contains("tenant_id");
            assertThat(err).contains("priority");
            assertThat(err).contains("row_index");
        }
    }

    // ==================== validateColumnDefinitions ====================

    @Nested
    @DisplayName("validateColumnDefinitions")
    class ValidateColumnDefinitionsTests {

        @Test
        @DisplayName("Accepts a valid batch")
        void acceptsValidBatch() {
            List<Map<String, Object>> cols = List.of(
                Map.of("name", "title", "type", "text"),
                Map.of("name", "score", "type", "number")
            );
            assertThat(ToolParameterUtils.validateColumnDefinitions(cols, true)).isNull();
        }

        @Test
        @DisplayName("Rejects a reserved name")
        void rejectsReserved() {
            List<Map<String, Object>> cols = List.of(Map.of("name", "id", "type", "text"));
            String err = ToolParameterUtils.validateColumnDefinitions(cols, true);
            assertThat(err).isNotNull().contains("reserved");
        }

        @Test
        @DisplayName("Rejects an unknown type")
        void rejectsUnknownType() {
            List<Map<String, Object>> cols = List.of(Map.of("name", "x", "type", "nope"));
            String err = ToolParameterUtils.validateColumnDefinitions(cols, true);
            assertThat(err).isNotNull().contains("Invalid column type");
        }

        @Test
        @DisplayName("Rejects intra-request duplicates case-insensitively")
        void rejectsDuplicates() {
            List<Map<String, Object>> cols = List.of(
                Map.of("name", "Foo", "type", "text"),
                Map.of("name", "FOO", "type", "number")
            );
            String err = ToolParameterUtils.validateColumnDefinitions(cols, true);
            assertThat(err).isNotNull().contains("Duplicate");
        }

        @Test
        @DisplayName("Sanitizes data. prefix before reserved-name check")
        void sanitizesBeforeReservedCheck() {
            List<Map<String, Object>> cols = List.of(Map.of("name", "data.id", "type", "text"));
            String err = ToolParameterUtils.validateColumnDefinitions(cols, true);
            assertThat(err).isNotNull().contains("reserved");
        }

        @Test
        @DisplayName("Tolerates null entries in the list")
        void tolerateNullEntries() {
            List<Map<String, Object>> cols = new ArrayList<>();
            cols.add(Map.of("name", "ok", "type", "text"));
            cols.add(null);
            assertThat(ToolParameterUtils.validateColumnDefinitions(cols, true)).isNull();
        }
    }

    // ==================== tableVisualizationMetadata ====================

    @Nested
    @DisplayName("tableVisualizationMetadata")
    class TableVisualizationMetadataTests {

        @Test
        @DisplayName("Should build metadata with provided title")
        void shouldBuildWithTitle() {
            Map<String, Object> meta = ToolParameterUtils.tableVisualizationMetadata(42L, "My Table");
            @SuppressWarnings("unchecked")
            Map<String, Object> viz = (Map<String, Object>) meta.get("visualization");
            assertThat(viz).containsEntry("type", "table");
            assertThat(viz).containsEntry("id", "42");
            assertThat(viz).containsEntry("title", "My Table");
        }

        @Test
        @DisplayName("Should fall back to 'Table #<id>' when title is null")
        void shouldFallbackOnNullTitle() {
            Map<String, Object> meta = ToolParameterUtils.tableVisualizationMetadata(7L, null);
            @SuppressWarnings("unchecked")
            Map<String, Object> viz = (Map<String, Object>) meta.get("visualization");
            assertThat(viz).containsEntry("title", "Table #7");
        }

        @Test
        @DisplayName("Should fall back on blank title")
        void shouldFallbackOnBlankTitle() {
            Map<String, Object> meta = ToolParameterUtils.tableVisualizationMetadata(9L, "   ");
            @SuppressWarnings("unchecked")
            Map<String, Object> viz = (Map<String, Object>) meta.get("visualization");
            assertThat(viz).containsEntry("title", "Table #9");
        }

        @Test
        @DisplayName("Should stringify numeric ids")
        void shouldStringifyId() {
            Map<String, Object> meta = ToolParameterUtils.tableVisualizationMetadata(123456789L, "T");
            @SuppressWarnings("unchecked")
            Map<String, Object> viz = (Map<String, Object>) meta.get("visualization");
            assertThat(viz.get("id")).isInstanceOf(String.class);
            assertThat(viz.get("id")).isEqualTo("123456789");
        }
    }

    // ==================== Error hint constants ====================

    @Nested
    @DisplayName("Error hint constants")
    class ErrorHintConstantsTests {
        // Guardrails - these messages are the ONLY feedback an agent gets when it calls
        // the tool wrong. If they regress to "X is required" with no example, a blind agent
        // has to re-call help to recover. Each assertion below pins one recoverability affordance.

        @Test
        @DisplayName("table_id hint includes a worked example")
        void tableIdHintHasExample() {
            assertThat(ToolParameterUtils.MISSING_TABLE_ID_HINT)
                .contains("table_id")
                .contains("Example:")
                .contains("table(action=")
                .contains("table_id=");
        }

        @Test
        @DisplayName("name hint includes create example")
        void nameHintHasExample() {
            assertThat(ToolParameterUtils.MISSING_NAME_HINT)
                .contains("name is required")
                .contains("Example:")
                .contains("action='create'");
        }

        @Test
        @DisplayName("update where hint shows a valid where block")
        void updateWhereHintHasExample() {
            assertThat(ToolParameterUtils.MISSING_WHERE_HINT_UPDATE)
                .contains("update_rows")
                .contains("column:")
                .contains("operator:")
                .contains("value:");
        }

        @Test
        @DisplayName("delete where hint shows a valid where block")
        void deleteWhereHintHasExample() {
            assertThat(ToolParameterUtils.MISSING_WHERE_HINT_DELETE)
                .contains("delete_rows")
                .contains("column:")
                .contains("operator:")
                .contains("value:");
        }

        @Test
        @DisplayName("set hint shows a multi-field example")
        void setHintHasExample() {
            assertThat(ToolParameterUtils.MISSING_SET_HINT)
                .contains("set")
                .contains("Example:")
                .contains("{");
        }
    }

    // ==================== validateColumnDefinitions: select/multi_select options ====================

    @Nested
    @DisplayName("validateColumnDefinitions - select/multi_select require display.options")
    class SelectOptionsValidationTests {

        // Regression: prod datasource 44 ("Competitive Intelligence") had a
        // threat_level column persisted as type=select with display: {} - agent
        // ignored the help, backend accepted silently, UI cells looked empty.
        // The validator must reject this up-front.

        @Test
        @DisplayName("select without display rejected with actionable error")
        void selectWithoutDisplayRejected() {
            List<Map<String, Object>> cols = List.of(Map.of("name", "threat_level", "type", "select"));
            String err = ToolParameterUtils.validateColumnDefinitions(cols, true);
            assertThat(err)
                .isNotNull()
                .contains("threat_level")
                .contains("type=select")
                .contains("display.options")
                .contains("text"); // suggests fallback
        }

        @Test
        @DisplayName("select with display.options=[] rejected (empty list)")
        void selectWithEmptyOptionsRejected() {
            Map<String, Object> col = new HashMap<>();
            col.put("name", "status");
            col.put("type", "select");
            col.put("display", Map.of("options", List.of()));
            String err = ToolParameterUtils.validateColumnDefinitions(List.of(col), true);
            assertThat(err).isNotNull().contains("display.options");
        }

        @Test
        @DisplayName("select with display but no options key rejected")
        void selectWithDisplayButNoOptionsRejected() {
            Map<String, Object> col = new HashMap<>();
            col.put("name", "status");
            col.put("type", "select");
            col.put("display", Map.of("color", "#22c55e")); // wrong shape
            String err = ToolParameterUtils.validateColumnDefinitions(List.of(col), true);
            assertThat(err).isNotNull().contains("display.options");
        }

        @Test
        @DisplayName("multi_select without options also rejected")
        void multiSelectWithoutOptionsRejected() {
            List<Map<String, Object>> cols = List.of(Map.of("name", "tags", "type", "multi_select"));
            String err = ToolParameterUtils.validateColumnDefinitions(cols, true);
            assertThat(err).isNotNull().contains("type=multi_select").contains("display.options");
        }

        @Test
        @DisplayName("select with valid options accepted")
        void selectWithValidOptionsAccepted() {
            Map<String, Object> col = new HashMap<>();
            col.put("name", "threat_level");
            col.put("type", "select");
            col.put("display", Map.of("options", List.of(
                Map.of("label", "Low", "value", "Low", "color", "#22c55e"),
                Map.of("label", "High", "value", "High", "color", "#ef4444")
            )));
            assertThat(ToolParameterUtils.validateColumnDefinitions(List.of(col), true)).isNull();
        }

        @Test
        @DisplayName("non-select types unaffected when display absent")
        void textColumnNoDisplayAccepted() {
            List<Map<String, Object>> cols = List.of(
                Map.of("name", "title", "type", "text"),
                Map.of("name", "count", "type", "number"),
                Map.of("name", "due", "type", "date")
            );
            assertThat(ToolParameterUtils.validateColumnDefinitions(cols, true)).isNull();
        }

        @Test
        @DisplayName("case-insensitive type matching: 'SELECT' still requires options")
        void uppercaseSelectRequiresOptions() {
            List<Map<String, Object>> cols = List.of(Map.of("name", "x", "type", "SELECT"));
            String err = ToolParameterUtils.validateColumnDefinitions(cols, true);
            assertThat(err).isNotNull().contains("display.options");
        }

        @Test
        @DisplayName("validateSelectOptions helper: null type is no-op")
        void helperNullTypeNoOp() {
            assertThat(ToolParameterUtils.validateSelectOptions(null, "x", null)).isNull();
        }

        @Test
        @DisplayName("validateSelectOptions helper: text type is no-op")
        void helperTextTypeNoOp() {
            assertThat(ToolParameterUtils.validateSelectOptions("text", "x", null)).isNull();
        }

        @Test
        @DisplayName("display as a non-Map (e.g. malformed string) rejected")
        void selectWithDisplayAsNonMapRejected() {
            // Defends against malformed LLM JSON that sends display as the wrong type
            // (a bare string, an array, etc.); the instanceof Map<?,?> short-circuits
            // and the missing-options branch fires.
            Map<String, Object> col = new HashMap<>();
            col.put("name", "level");
            col.put("type", "select");
            col.put("display", "should-be-an-object");
            String err = ToolParameterUtils.validateColumnDefinitions(List.of(col), true);
            assertThat(err).isNotNull().contains("display.options");
        }

        @Test
        @DisplayName("display.options as a non-list (e.g. a map) rejected")
        void selectWithOptionsAsNonListRejected() {
            // Same defensive intent: options must be an array. A map shape sneaks
            // past a naive non-null check but fails the instanceof List<?> guard.
            Map<String, Object> col = new HashMap<>();
            col.put("name", "level");
            col.put("type", "select");
            col.put("display", Map.of("options", Map.of("Low", "Low", "High", "High")));
            String err = ToolParameterUtils.validateColumnDefinitions(List.of(col), true);
            assertThat(err).isNotNull().contains("display.options");
        }
    }

    // ==================== validateColumnDefinitions: vector requires display.dimension ====================

    @Nested
    @DisplayName("validateColumnDefinitions - vector requires display.dimension")
    class VectorDimensionValidationTests {

        // Vector columns silently degrade when dimension is missing: first inserts
        // succeed, mixed dimensions accumulate, then HNSW index creation throws and
        // similarity queries return inconsistent results. Reject up-front.

        @Test
        @DisplayName("vector without display rejected")
        void vectorWithoutDisplayRejected() {
            List<Map<String, Object>> cols = List.of(Map.of("name", "embedding", "type", "vector"));
            String err = ToolParameterUtils.validateColumnDefinitions(cols, true);
            assertThat(err)
                .isNotNull()
                .contains("embedding")
                .contains("type=vector")
                .contains("display.dimension")
                .contains("HNSW");
        }

        @Test
        @DisplayName("vector with valid dimension accepted")
        void vectorWithValidDimensionAccepted() {
            Map<String, Object> col = new HashMap<>();
            col.put("name", "embedding");
            col.put("type", "vector");
            col.put("display", Map.of("dimension", 1536, "metric", "cosine"));
            assertThat(ToolParameterUtils.validateColumnDefinitions(List.of(col), true)).isNull();
        }

        @Test
        @DisplayName("vector with dimension as numeric string accepted")
        void vectorWithStringDimensionAccepted() {
            // Some JSON marshalers send numbers as strings (e.g. JS Number→string conv).
            Map<String, Object> col = new HashMap<>();
            col.put("name", "embedding");
            col.put("type", "vector");
            col.put("display", Map.of("dimension", "768"));
            assertThat(ToolParameterUtils.validateColumnDefinitions(List.of(col), true)).isNull();
        }

        @Test
        @DisplayName("vector with dimension <= 0 rejected")
        void vectorWithZeroDimensionRejected() {
            Map<String, Object> col = new HashMap<>();
            col.put("name", "embedding");
            col.put("type", "vector");
            col.put("display", Map.of("dimension", 0));
            String err = ToolParameterUtils.validateColumnDefinitions(List.of(col), true);
            assertThat(err).isNotNull().contains("display.dimension");
        }

        @Test
        @DisplayName("vector with negative dimension rejected")
        void vectorWithNegativeDimensionRejected() {
            Map<String, Object> col = new HashMap<>();
            col.put("name", "embedding");
            col.put("type", "vector");
            col.put("display", Map.of("dimension", -5));
            String err = ToolParameterUtils.validateColumnDefinitions(List.of(col), true);
            assertThat(err).isNotNull().contains("display.dimension");
        }

        @Test
        @DisplayName("vector with dimension > MAX_VECTOR_DIMENSION rejected")
        void vectorWithDimensionOverLimitRejected() {
            Map<String, Object> col = new HashMap<>();
            col.put("name", "embedding");
            col.put("type", "vector");
            col.put("display", Map.of("dimension", ToolParameterUtils.MAX_VECTOR_DIMENSION + 1));
            String err = ToolParameterUtils.validateColumnDefinitions(List.of(col), true);
            assertThat(err).isNotNull().contains("display.dimension");
        }

        @Test
        @DisplayName("vector with non-numeric dimension rejected")
        void vectorWithGarbageDimensionRejected() {
            Map<String, Object> col = new HashMap<>();
            col.put("name", "embedding");
            col.put("type", "vector");
            col.put("display", Map.of("dimension", "not-a-number"));
            String err = ToolParameterUtils.validateColumnDefinitions(List.of(col), true);
            assertThat(err).isNotNull().contains("display.dimension");
        }

        @Test
        @DisplayName("validateVectorDimension helper: text type is no-op")
        void helperTextTypeNoOp() {
            assertThat(ToolParameterUtils.validateVectorDimension("text", "x", null)).isNull();
        }

        @Test
        @DisplayName("validateVectorDimension helper: null type is no-op")
        void helperNullTypeNoOp() {
            assertThat(ToolParameterUtils.validateVectorDimension(null, "x", null)).isNull();
        }
    }

    // ==================== Unified single-column validator ====================

    @Nested
    @DisplayName("validateColumnDefinition - single-column entry point")
    class SingleColumnValidatorTests {

        // The unified helper is called from agent tool, REST UI add column, and
        // CRUD create-column. Same contract everywhere.

        @Test
        @DisplayName("reserved name rejected even when type+display valid")
        void reservedNameRejected() {
            String err = ToolParameterUtils.validateColumnDefinition("id", "text", null, true);
            assertThat(err).isNotNull().contains("reserved");
        }

        @Test
        @DisplayName("invalid type rejected")
        void invalidTypeRejected() {
            String err = ToolParameterUtils.validateColumnDefinition("foo", "not_a_type", null, true);
            assertThat(err).isNotNull().contains("Invalid column type");
        }

        @Test
        @DisplayName("select without options rejected")
        void selectWithoutOptionsRejected() {
            String err = ToolParameterUtils.validateColumnDefinition("status", "select", null, true);
            assertThat(err).isNotNull().contains("display.options");
        }

        @Test
        @DisplayName("vector without dimension rejected")
        void vectorWithoutDimensionRejected() {
            String err = ToolParameterUtils.validateColumnDefinition("emb", "vector", null, true);
            assertThat(err).isNotNull().contains("display.dimension");
        }

        @Test
        @DisplayName("text column with no display accepted")
        void textNoDisplayAccepted() {
            assertThat(ToolParameterUtils.validateColumnDefinition("title", "text", null, true)).isNull();
        }

        @Test
        @DisplayName("strips data. prefix from name before reserved check")
        void prefixStrippedBeforeReservedCheck() {
            // sanitizeColumnName drops "data." prefix; reserved-name lookup runs on the
            // sanitized form. This pins that behaviour.
            String err = ToolParameterUtils.validateColumnDefinition("data.id", "text", null, true);
            assertThat(err).isNotNull().contains("reserved");
        }
    }
}
