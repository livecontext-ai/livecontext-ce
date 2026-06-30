package com.apimarketplace.orchestrator.execution.v2.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TriggerItem record.
 */
@DisplayName("TriggerItem")
class TriggerItemTest {

    @Test
    @DisplayName("should create trigger item via constructor")
    void shouldCreateViaConstructor() {
        Map<String, Object> data = Map.of("key", "value");
        TriggerItem item = new TriggerItem("item-0", 0, data);

        assertEquals("item-0", item.itemId());
        assertEquals(0, item.index());
        assertEquals(data, item.data());
    }

    @Test
    @DisplayName("should create trigger item via static factory")
    void shouldCreateViaFactory() {
        Map<String, Object> data = Map.of("user_id", 123);
        TriggerItem item = TriggerItem.create("item-1", 1, data);

        assertEquals("item-1", item.itemId());
        assertEquals(1, item.index());
        assertEquals(123, item.data().get("user_id"));
    }

    @Test
    @DisplayName("should handle empty data map")
    void shouldHandleEmptyData() {
        TriggerItem item = TriggerItem.create("item-0", 0, Map.of());
        assertTrue(item.data().isEmpty());
    }
}
