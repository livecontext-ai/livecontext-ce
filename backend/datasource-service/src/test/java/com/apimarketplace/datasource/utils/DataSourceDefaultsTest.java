package com.apimarketplace.datasource.utils;

import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DataSourceDefaults")
class DataSourceDefaultsTest {

    // ========================================================================
    // generateColumnOrder()
    // ========================================================================

    @Nested
    @DisplayName("generateColumnOrder()")
    class GenerateColumnOrderTests {

        @Test
        @DisplayName("Should include all system columns first")
        void shouldIncludeSystemColumnsFirst() {
            Set<String> dataFields = new LinkedHashSet<>();
            List<Map<String, Object>> result = DataSourceDefaults.generateColumnOrder(dataFields);

            assertNotNull(result);
            assertEquals(5, result.size());
            assertEquals("checkbox", result.get(0).get("field"));
            assertEquals(0, result.get(0).get("order"));
            assertEquals("index", result.get(1).get("field"));
            assertEquals(1, result.get(1).get("order"));
            assertEquals("id", result.get(2).get("field"));
            assertEquals(2, result.get(2).get("order"));
            assertEquals("priority", result.get(3).get("field"));
            assertEquals(3, result.get(3).get("order"));
            assertEquals("created_at", result.get(4).get("field"));
            assertEquals(4, result.get(4).get("order"));
        }

        @Test
        @DisplayName("Should add data fields after system columns")
        void shouldAddDataFieldsAfterSystemColumns() {
            Set<String> dataFields = new LinkedHashSet<>(List.of("name", "email"));
            List<Map<String, Object>> result = DataSourceDefaults.generateColumnOrder(dataFields);

            assertEquals(7, result.size());
            assertEquals("name", result.get(5).get("field"));
            assertEquals(5, result.get(5).get("order"));
            assertEquals("email", result.get(6).get("field"));
            assertEquals(6, result.get(6).get("order"));
        }

        @Test
        @DisplayName("Should skip data fields that overlap with system columns")
        void shouldSkipOverlappingSystemColumns() {
            Set<String> dataFields = new LinkedHashSet<>(List.of("id", "name", "created_at"));
            List<Map<String, Object>> result = DataSourceDefaults.generateColumnOrder(dataFields);

            // 5 system + 1 "name" (id and created_at already in system)
            assertEquals(6, result.size());
            assertEquals("name", result.get(5).get("field"));
        }

        @Test
        @DisplayName("Should handle empty data fields")
        void shouldHandleEmptyDataFields() {
            List<Map<String, Object>> result = DataSourceDefaults.generateColumnOrder(Set.of());
            assertEquals(5, result.size()); // Only system columns
        }
    }

    // ========================================================================
    // generateMappingSpec()
    // ========================================================================

    @Nested
    @DisplayName("generateMappingSpec()")
    class GenerateMappingSpecTests {

        @Test
        @DisplayName("Should return empty map for null data")
        void shouldReturnEmptyMapForNull() {
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for empty data")
        void shouldReturnEmptyMapForEmptyData() {
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should infer TEXT type for strings")
        void shouldInferTextTypeForStrings() {
            List<Map<String, Object>> data = List.of(Map.of("name", "John"));
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertNotNull(result.get("name"));
            assertEquals(ColumnType.TEXT, result.get("name").type());
            assertEquals(ColumnStructure.SCALAR, result.get("name").structure());
            assertEquals("data.name", result.get("name").path());
        }

        @Test
        @DisplayName("Should infer NUMBER type for numeric values")
        void shouldInferNumberTypeForNumbers() {
            List<Map<String, Object>> data = List.of(Map.of("age", 25));
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.NUMBER, result.get("age").type());
            assertEquals(ColumnStructure.SCALAR, result.get("age").structure());
        }

        @Test
        @DisplayName("Should infer CHECKBOX type for boolean values")
        void shouldInferCheckboxType() {
            List<Map<String, Object>> data = List.of(Map.of("active", true));
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.CHECKBOX, result.get("active").type());
        }

        @Test
        @DisplayName("Should infer MULTI_SELECT type for list values")
        void shouldInferMultiSelectTypeForLists() {
            Map<String, Object> item = new HashMap<>();
            item.put("tags", List.of("a", "b"));
            List<Map<String, Object>> data = List.of(item);
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.MULTI_SELECT, result.get("tags").type());
            assertEquals(ColumnStructure.ARRAY, result.get("tags").structure());
        }

        @Test
        @DisplayName("Should infer TEXT type for map values")
        void shouldInferTextTypeForMaps() {
            Map<String, Object> item = new HashMap<>();
            item.put("address", Map.of("city", "Montreal"));
            List<Map<String, Object>> data = List.of(item);
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.TEXT, result.get("address").type());
            assertEquals(ColumnStructure.OBJECT, result.get("address").structure());
        }

        @Test
        @DisplayName("Should infer DATE type for ISO date strings")
        void shouldInferDateType() {
            List<Map<String, Object>> data = List.of(Map.of("createdAt", "2024-01-15T10:30:00Z"));
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.DATE, result.get("createdAt").type());
        }

        @Test
        @DisplayName("Should infer URL type for URL strings")
        void shouldInferUrlType() {
            List<Map<String, Object>> data = List.of(Map.of("website", "https://example.com"));
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.URL, result.get("website").type());
        }

        @Test
        @DisplayName("Should infer URL type for http URLs")
        void shouldInferUrlTypeForHttpUrls() {
            List<Map<String, Object>> data = List.of(Map.of("url", "http://example.com/api"));
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.URL, result.get("url").type());
        }

        @Test
        @DisplayName("Should infer EMAIL type for email strings")
        void shouldInferEmailType() {
            List<Map<String, Object>> data = List.of(Map.of("contact", "user@example.com"));
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.EMAIL, result.get("contact").type());
        }

        @Test
        @DisplayName("Should infer TEXT type for null values")
        void shouldInferTextTypeForNullValues() {
            Map<String, Object> item = new HashMap<>();
            item.put("field", null);
            List<Map<String, Object>> data = List.of(item);
            Map<String, ColumnMappingSpec> result = DataSourceDefaults.generateMappingSpec(data);

            assertEquals(ColumnType.TEXT, result.get("field").type());
            assertEquals(ColumnStructure.SCALAR, result.get("field").structure());
        }
    }

    // ========================================================================
    // extractFieldNames()
    // ========================================================================

    @Nested
    @DisplayName("extractFieldNames()")
    class ExtractFieldNamesTests {

        @Test
        @DisplayName("Should return empty set for null data")
        void shouldReturnEmptyForNull() {
            Set<String> result = DataSourceDefaults.extractFieldNames(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty set for empty data")
        void shouldReturnEmptyForEmptyData() {
            Set<String> result = DataSourceDefaults.extractFieldNames(List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should extract all unique field names across items")
        void shouldExtractAllUniqueFieldNames() {
            List<Map<String, Object>> data = List.of(
                Map.of("name", "A", "age", 1),
                Map.of("name", "B", "email", "b@test.com")
            );
            Set<String> result = DataSourceDefaults.extractFieldNames(data);

            assertEquals(3, result.size());
            assertTrue(result.contains("name"));
            assertTrue(result.contains("age"));
            assertTrue(result.contains("email"));
        }
    }
}
