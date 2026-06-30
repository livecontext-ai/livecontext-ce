package com.apimarketplace.orchestrator.services.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ItemContextEnricher")
class ItemContextEnricherTest {

    private ItemContextEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new ItemContextEnricher();
    }

    @Nested
    @DisplayName("putScalarIfAbsent()")
    class PutScalarIfAbsentTests {

        @Test
        @DisplayName("Should add String value when key absent")
        void shouldAddStringValue() {
            Map<String, Object> target = new HashMap<>();
            boolean result = enricher.putScalarIfAbsent(target, "key", "value");
            assertTrue(result);
            assertEquals("value", target.get("key"));
        }

        @Test
        @DisplayName("Should add Number value when key absent")
        void shouldAddNumberValue() {
            Map<String, Object> target = new HashMap<>();
            boolean result = enricher.putScalarIfAbsent(target, "key", 42);
            assertTrue(result);
            assertEquals(42, target.get("key"));
        }

        @Test
        @DisplayName("Should add Boolean value when key absent")
        void shouldAddBooleanValue() {
            Map<String, Object> target = new HashMap<>();
            boolean result = enricher.putScalarIfAbsent(target, "key", true);
            assertTrue(result);
            assertEquals(true, target.get("key"));
        }

        @Test
        @DisplayName("Should not overwrite existing key")
        void shouldNotOverwriteExistingKey() {
            Map<String, Object> target = new HashMap<>();
            target.put("key", "existing");
            boolean result = enricher.putScalarIfAbsent(target, "key", "new");
            assertFalse(result);
            assertEquals("existing", target.get("key"));
        }

        @Test
        @DisplayName("Should return false for null target")
        void shouldReturnFalseForNullTarget() {
            assertFalse(enricher.putScalarIfAbsent(null, "key", "value"));
        }

        @Test
        @DisplayName("Should return false for null key")
        void shouldReturnFalseForNullKey() {
            assertFalse(enricher.putScalarIfAbsent(new HashMap<>(), null, "value"));
        }

        @Test
        @DisplayName("Should return false for null value")
        void shouldReturnFalseForNullValue() {
            assertFalse(enricher.putScalarIfAbsent(new HashMap<>(), "key", null));
        }

        @Test
        @DisplayName("Should return false for non-scalar value")
        void shouldReturnFalseForNonScalar() {
            assertFalse(enricher.putScalarIfAbsent(new HashMap<>(), "key", Map.of()));
        }
    }

    @Nested
    @DisplayName("addScalarMetadata()")
    class AddScalarMetadataTests {

        @Test
        @DisplayName("Should add scalar metadata")
        void shouldAddScalarMetadata() {
            Map<String, Object> target = new HashMap<>();
            enricher.addScalarMetadata(target, "key", "value");
            assertEquals("value", target.get("key"));
        }

        @Test
        @DisplayName("Should overwrite existing value")
        void shouldOverwriteExisting() {
            Map<String, Object> target = new HashMap<>();
            target.put("key", "old");
            enricher.addScalarMetadata(target, "key", "new");
            assertEquals("new", target.get("key"));
        }

        @Test
        @DisplayName("Should skip null value")
        void shouldSkipNullValue() {
            Map<String, Object> target = new HashMap<>();
            enricher.addScalarMetadata(target, "key", null);
            assertFalse(target.containsKey("key"));
        }

        @Test
        @DisplayName("Should skip non-scalar value")
        void shouldSkipNonScalar() {
            Map<String, Object> target = new HashMap<>();
            enricher.addScalarMetadata(target, "key", Map.of("nested", true));
            assertFalse(target.containsKey("key"));
        }

        @Test
        @DisplayName("Should do nothing for null target")
        void shouldDoNothingForNullTarget() {
            assertDoesNotThrow(() -> enricher.addScalarMetadata(null, "key", "value"));
        }
    }

    @Nested
    @DisplayName("appendItemMetadata()")
    class AppendItemMetadataTests {

        @Test
        @DisplayName("Should append all item metadata fields")
        void shouldAppendAllFields() {
            Map<String, Object> eventData = new HashMap<>();
            Map<String, Object> rawOutput = new LinkedHashMap<>();
            rawOutput.put("itemId", "item-1");
            rawOutput.put("triggerId", "trigger:webhook");
            rawOutput.put("absoluteIndex", 5);
            rawOutput.put("itemIndex", 3);
            rawOutput.put("tenantId", "tenant-1");

            enricher.appendItemMetadata(eventData, rawOutput);

            assertEquals("item-1", eventData.get("itemId"));
            assertEquals("trigger:webhook", eventData.get("triggerId"));
            assertEquals(5, eventData.get("absoluteIndex"));
            assertEquals(3, eventData.get("itemIndex"));
            assertEquals("tenant-1", eventData.get("tenantId"));
        }

        @Test
        @DisplayName("Should prefer item_index over itemIndex")
        void shouldPreferItemIndexUnderscore() {
            Map<String, Object> eventData = new HashMap<>();
            Map<String, Object> rawOutput = new LinkedHashMap<>();
            rawOutput.put("item_index", 10);
            rawOutput.put("itemIndex", 20);

            enricher.appendItemMetadata(eventData, rawOutput);

            assertEquals(10, eventData.get("itemIndex"));
        }

        @Test
        @DisplayName("Should do nothing for null eventData")
        void shouldDoNothingForNullEventData() {
            assertDoesNotThrow(() -> enricher.appendItemMetadata(null, Map.of("itemId", "1")));
        }

        @Test
        @DisplayName("Should do nothing for null rawOutput")
        void shouldDoNothingForNullRawOutput() {
            Map<String, Object> eventData = new HashMap<>();
            enricher.appendItemMetadata(eventData, null);
            assertTrue(eventData.isEmpty());
        }

        @Test
        @DisplayName("Should do nothing for empty rawOutput")
        void shouldDoNothingForEmptyRawOutput() {
            Map<String, Object> eventData = new HashMap<>();
            enricher.appendItemMetadata(eventData, Map.of());
            assertTrue(eventData.isEmpty());
        }
    }

    @Nested
    @DisplayName("extractIntegerFromOutput()")
    class ExtractIntegerFromOutputTests {

        @Test
        @DisplayName("Should return null for null result")
        void shouldReturnNullForNullResult() {
            assertNull(enricher.extractIntegerFromOutput(null, "key"));
        }
    }

    @Nested
    @DisplayName("enrichWithItemContext()")
    class EnrichWithItemContextTests {

        @Test
        @DisplayName("Should return result when execution is null")
        void shouldReturnResultWhenExecutionNull() {
            assertNull(enricher.enrichWithItemContext(null, null));
        }

        @Test
        @DisplayName("Should return result when result is null")
        void shouldReturnResultWhenResultNull() {
            assertNull(enricher.enrichWithItemContext(null, null));
        }
    }

    @Nested
    @DisplayName("mergeItemContextMetadata()")
    class MergeItemContextMetadataTests {

        @Test
        @DisplayName("Should return original output when context is null")
        void shouldReturnOriginalWhenContextNull() {
            Map<String, Object> output = Map.of("key", "value");
            assertSame(output, enricher.mergeItemContextMetadata(output, null));
        }
    }
}
