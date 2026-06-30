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
 * Unit tests for TextAdapter class.
 *
 * TextAdapter handles plain text format data for mapping operations.
 */
@DisplayName("TextAdapter")
class TextAdapterTest {

    private TextAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new TextAdapter();
    }

    // ========================================================================
    // isCollection tests
    // ========================================================================

    @Nested
    @DisplayName("isCollection()")
    class IsCollectionTests {

        @Test
        @DisplayName("should return true for multi-line text")
        void shouldReturnTrueForMultiLineText() {
            byte[] input = "line1\nline2\nline3".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for single line text")
        void shouldReturnFalseForSingleLineText() {
            byte[] input = "single line".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            boolean result = adapter.isCollection(spec, input);

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for empty text")
        void shouldReturnFalseForEmptyText() {
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
        @DisplayName("should iterate over lines")
        void shouldIterateOverLines() {
            byte[] input = "line1\nline2\nline3".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            List<?> itemList = (List<?>) items;
            assertEquals(3, itemList.size());
            assertEquals("line1", itemList.get(0));
            assertEquals("line2", itemList.get(1));
            assertEquals("line3", itemList.get(2));
        }

        @Test
        @DisplayName("should handle single line")
        void shouldHandleSingleLine() {
            byte[] input = "single line".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            List<?> itemList = (List<?>) items;
            assertEquals(1, itemList.size());
            assertEquals("single line", itemList.get(0));
        }

        @Test
        @DisplayName("should return empty list for empty input")
        void shouldReturnEmptyListForEmptyInput() {
            byte[] input = "".getBytes(StandardCharsets.UTF_8);
            SourceSpec spec = new SourceSpec();

            Iterable<?> items = adapter.iterateItems(spec, input);

            List<?> itemList = (List<?>) items;
            assertEquals(1, itemList.size()); // Empty string is still one element
        }
    }

    // ========================================================================
    // evalScalar tests
    // ========================================================================

    @Nested
    @DisplayName("evalScalar()")
    class EvalScalarTests {

        @Test
        @DisplayName("should extract value from key-value pair using regex")
        void shouldExtractValueFromKeyValuePair() {
            String line = "name: John Doe";

            // Use regex pattern with capture group to extract value
            Object result = adapter.evalScalar(line, "^name:\\s*(.+)$");

            assertEquals("John Doe", result);
        }

        @Test
        @DisplayName("should return null for non-matching key")
        void shouldReturnNullForNonMatchingKey() {
            String line = "name: John Doe";

            Object result = adapter.evalScalar(line, "email");

            assertNull(result);
        }

        @Test
        @DisplayName("should extract using regex pattern")
        void shouldExtractUsingRegexPattern() {
            String line = "test123abc";

            Object result = adapter.evalScalar(line, "^test(\\d+)abc$");

            assertEquals("123", result);
        }

        @Test
        @DisplayName("should return null for non-matching regex")
        void shouldReturnNullForNonMatchingRegex() {
            String line = "no match here";

            Object result = adapter.evalScalar(line, "^test(\\d+)abc$");

            assertNull(result);
        }

        @Test
        @DisplayName("should return null for null context")
        void shouldReturnNullForNullContext() {
            Object result = adapter.evalScalar(null, "key");

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
        @DisplayName("should return empty list for text context")
        void shouldReturnEmptyListForTextContext() {
            Iterable<?> result = adapter.evalNodes("some text", "path");

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
        @DisplayName("should flatten lines")
        void shouldFlattenLines() {
            byte[] input = "line1\nline2\nline3".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result);
            assertEquals("line1", result.get("line[0]"));
            assertEquals("line2", result.get("line[1]"));
            assertEquals("line3", result.get("line[2]"));
        }

        @Test
        @DisplayName("should extract key-value pairs")
        void shouldExtractKeyValuePairs() {
            byte[] input = "name: John\nemail: john@test.com".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertEquals("John", result.get("kv.name"));
            assertEquals("john@test.com", result.get("kv.email"));
        }

        @Test
        @DisplayName("should extract emails")
        void shouldExtractEmails() {
            byte[] input = "Contact us at support@example.com or info@test.org".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("emails"));
            @SuppressWarnings("unchecked")
            List<String> emails = (List<String>) result.get("emails");
            assertTrue(emails.contains("support@example.com"));
            assertTrue(emails.contains("info@test.org"));
        }

        @Test
        @DisplayName("should extract URLs")
        void shouldExtractUrls() {
            byte[] input = "Visit https://example.com or http://test.org".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("urls"));
            @SuppressWarnings("unchecked")
            List<String> urls = (List<String>) result.get("urls");
            assertTrue(urls.contains("https://example.com"));
            assertTrue(urls.contains("http://test.org"));
        }

        @Test
        @DisplayName("should extract phone numbers")
        void shouldExtractPhoneNumbers() {
            byte[] input = "Call 555-123-4567 or 800.555.1234".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("phones"));
            @SuppressWarnings("unchecked")
            List<String> phones = (List<String>) result.get("phones");
            assertEquals(2, phones.size());
        }

        @Test
        @DisplayName("should extract dates")
        void shouldExtractDates() {
            byte[] input = "Dates: 2024-01-15 and 12/25/2024".getBytes(StandardCharsets.UTF_8);

            Map<String, Object> result = adapter.flatten(input);

            assertNotNull(result.get("dates"));
            @SuppressWarnings("unchecked")
            List<String> dates = (List<String>) result.get("dates");
            assertEquals(2, dates.size());
        }
    }

    // ========================================================================
    // getRoot tests
    // ========================================================================

    @Nested
    @DisplayName("getRoot()")
    class GetRootTests {

        @Test
        @DisplayName("should return text content as string")
        void shouldReturnTextContentAsString() {
            byte[] input = "Hello, World!".getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertEquals("Hello, World!", result);
        }

        @Test
        @DisplayName("should preserve newlines")
        void shouldPreserveNewlines() {
            byte[] input = "line1\nline2\nline3".getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertEquals("line1\nline2\nline3", result);
        }

        @Test
        @DisplayName("should handle empty input")
        void shouldHandleEmptyInput() {
            byte[] input = "".getBytes(StandardCharsets.UTF_8);

            Object result = adapter.getRoot(input);

            assertEquals("", result);
        }
    }
}
