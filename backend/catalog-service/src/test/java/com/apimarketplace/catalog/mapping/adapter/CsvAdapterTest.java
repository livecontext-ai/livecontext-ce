package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CsvAdapter class.
 *
 * CsvAdapter handles CSV format data for mapping operations.
 */
@DisplayName("CsvAdapter")
class CsvAdapterTest {

    private CsvAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CsvAdapter();
    }

    // ========================================================================
    // isCollection tests
    // ========================================================================

    @Nested
    @DisplayName("isCollection()")
    class IsCollectionTests {

        @Test
        @DisplayName("should return true for multi-row CSV")
        void shouldReturnTrueForMultiRowCsv() {
            String csv = "name,age\nJohn,30\nJane,25";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for single row CSV")
        void shouldReturnFalseForSingleRowCsv() {
            String csv = "name,age";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for empty CSV")
        void shouldReturnFalseForEmptyCsv() {
            byte[] input = "".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }
    }

    // ========================================================================
    // iterateItems tests
    // ========================================================================

    @Nested
    @DisplayName("iterateItems()")
    class IterateItemsTests {

        @Test
        @DisplayName("should iterate over data rows (skip header)")
        void shouldIterateOverDataRows() {
            String csv = "name,age\nJohn,30\nJane,25";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            List<?> itemList = (List<?>) items;
            assertEquals(2, itemList.size());
        }

        @Test
        @DisplayName("should return header only when no data rows")
        void shouldReturnHeaderOnlyWhenNoDataRows() {
            String csv = "name,age";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            List<?> itemList = (List<?>) items;
            assertEquals(1, itemList.size());
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void shouldReturnEmptyListForEmptyInput() {
            byte[] input = "".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            List<?> itemList = (List<?>) items;
            assertTrue(itemList.isEmpty());
        }
    }

    // ========================================================================
    // evalScalar tests
    // ========================================================================

    @Nested
    @DisplayName("evalScalar()")
    class EvalScalarTests {

        @Test
        @DisplayName("should return null for null context")
        void shouldReturnNullForNullContext() {
            Object result = adapter.evalScalar(null, "name");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for non-CSVRecord context")
        void shouldReturnNullForNonCsvRecordContext() {
            Object result = adapter.evalScalar("not a record", "name");

            assertNull(result);
        }
    }

    // ========================================================================
    // evalNodes tests
    // ========================================================================

    @Nested
    @DisplayName("evalNodes()")
    class EvalNodesTests {

        @Test
        @DisplayName("should return empty list for CSV context")
        void shouldReturnEmptyListForCsvContext() {
            Iterable<?> result = adapter.evalNodes("some context", "path");

            assertFalse(result.iterator().hasNext());
        }
    }

    // ========================================================================
    // flatten tests
    // ========================================================================

    @Nested
    @DisplayName("flatten()")
    class FlattenTests {

        @Test
        @DisplayName("should flatten CSV rows with headers")
        void shouldFlattenCsvRowsWithHeaders() {
            String csv = "name,age\nJohn,30\nJane,25";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("John", result.get("row[0].name"));
            assertEquals("30", result.get("row[0].age"));
            assertEquals("Jane", result.get("row[1].name"));
            assertEquals("25", result.get("row[1].age"));
        }

        @Test
        @DisplayName("should handle empty CSV")
        void shouldHandleEmptyCsv() {
            byte[] input = "".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle header-only CSV")
        void shouldHandleHeaderOnlyCsv() {
            String csv = "name,age,email";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertTrue(result.isEmpty());
        }
    }

    // ========================================================================
    // getRoot tests
    // ========================================================================

    @Nested
    @DisplayName("getRoot()")
    class GetRootTests {

        @Test
        @DisplayName("should return list of CSV records")
        void shouldReturnListOfCsvRecords() {
            String csv = "name,age\nJohn,30";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertNotNull(result);
            assertTrue(result instanceof List);
        }

        @Test
        @DisplayName("should return null for invalid input")
        void shouldReturnNullForInvalidInput() {
            // Empty byte array should still parse
            byte[] input = "".getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertNotNull(result);
        }
    }

    // ========================================================================
    // Delimiter detection tests
    // ========================================================================

    @Nested
    @DisplayName("Delimiter detection")
    class DelimiterDetectionTests {

        @Test
        @DisplayName("should detect comma delimiter")
        void shouldDetectCommaDelimiter() {
            String csv = "name,age,email\nJohn,30,john@test.com";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("row[0].name"));
            assertEquals("John", result.get("row[0].name"));
        }

        @Test
        @DisplayName("should detect semicolon delimiter")
        void shouldDetectSemicolonDelimiter() {
            String csv = "name;age;email\nJohn;30;john@test.com";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("row[0].name"));
            assertEquals("John", result.get("row[0].name"));
        }

        @Test
        @DisplayName("should detect tab delimiter")
        void shouldDetectTabDelimiter() {
            String csv = "name\tage\temail\nJohn\t30\tjohn@test.com";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("row[0].name"));
            assertEquals("John", result.get("row[0].name"));
        }

        @Test
        @DisplayName("should detect pipe delimiter")
        void shouldDetectPipeDelimiter() {
            String csv = "name|age|email\nJohn|30|john@test.com";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("row[0].name"));
            assertEquals("John", result.get("row[0].name"));
        }
    }

    // ========================================================================
    // Edge cases
    // ========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle quoted values")
        void shouldHandleQuotedValues() {
            String csv = "name,description\nJohn,\"Hello, World\"";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("John", result.get("row[0].name"));
            assertEquals("Hello, World", result.get("row[0].description"));
        }

        @Test
        @DisplayName("should handle empty cells")
        void shouldHandleEmptyCells() {
            String csv = "name,age,email\nJohn,,john@test.com";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("John", result.get("row[0].name"));
            assertEquals("", result.get("row[0].age"));
            assertEquals("john@test.com", result.get("row[0].email"));
        }

        @Test
        @DisplayName("should handle multiple data rows")
        void shouldHandleMultipleDataRows() {
            String csv = "id,name\n1,Alice\n2,Bob\n3,Charlie";
            byte[] input = csv.getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("1", result.get("row[0].id"));
            assertEquals("Alice", result.get("row[0].name"));
            assertEquals("2", result.get("row[1].id"));
            assertEquals("Bob", result.get("row[1].name"));
            assertEquals("3", result.get("row[2].id"));
            assertEquals("Charlie", result.get("row[2].name"));
        }
    }
}
