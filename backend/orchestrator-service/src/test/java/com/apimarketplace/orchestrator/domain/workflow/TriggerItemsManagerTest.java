package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TriggerItemsManager")
class TriggerItemsManagerTest {

    private TriggerItemsManager manager;

    @BeforeEach
    void setUp() {
        manager = new TriggerItemsManager("run-1");
    }

    private Trigger createTrigger(String id, String label) {
        return new Trigger(id, label, "single", "datasource");
    }

    private TriggerBatchResult createBatch(Trigger trigger, List<Map<String, Object>> items,
                                            int offset, int totalCount, boolean hasMore, int nextOffset) {
        return new TriggerBatchResult(trigger, "tenant-1", items, offset, 10, totalCount, hasMore, nextOffset, totalCount);
    }

    @Nested
    @DisplayName("Trigger batch operations")
    class TriggerBatchTests {

        @Test
        @DisplayName("Should register and retrieve batch items")
        void shouldRegisterAndRetrieveItems() {
            Trigger trigger = createTrigger("t1", "My Source");
            List<Map<String, Object>> items = List.of(Map.of("id", 1), Map.of("id", 2));
            TriggerBatchResult batch = createBatch(trigger, items, 0, 2, false, 0);

            manager.registerBatch(trigger, batch);

            String key = trigger.getNormalizedKey();
            assertTrue(manager.hasItems(key));
            assertEquals(2, manager.getItemCount(key));
            assertEquals(items, manager.getItems(key));
        }

        @Test
        @DisplayName("Should return false for hasItems when no batch registered")
        void shouldReturnFalseForUnregistered() {
            assertFalse(manager.hasItems("trigger:nonexistent"));
        }

        @Test
        @DisplayName("Should return empty list for getItems when no batch registered")
        void shouldReturnEmptyListForUnregistered() {
            assertTrue(manager.getItems("trigger:nonexistent").isEmpty());
        }

        @Test
        @DisplayName("Should return 0 for getItemCount when no batch registered")
        void shouldReturnZeroCountForUnregistered() {
            assertEquals(0, manager.getItemCount("trigger:nonexistent"));
        }

        @Test
        @DisplayName("Should return null for getState when no batch registered")
        void shouldReturnNullStateForUnregistered() {
            assertNull(manager.getState("trigger:nonexistent"));
        }

        @Test
        @DisplayName("Should ignore null trigger on registerBatch")
        void shouldIgnoreNullTrigger() {
            manager.registerBatch(null, createBatch(createTrigger("t1", "label"), List.of(), 0, 0, false, 0));
            // No exception thrown, no state stored
        }

        @Test
        @DisplayName("Should ignore null batch on registerBatch")
        void shouldIgnoreNullBatch() {
            manager.registerBatch(createTrigger("t1", "label"), null);
            // No exception thrown
        }

        @Test
        @DisplayName("Should clear all trigger items")
        void shouldClearAll() {
            Trigger trigger = createTrigger("t1", "My Source");
            manager.registerBatch(trigger, createBatch(trigger, List.of(Map.of("a", 1)), 0, 1, false, 0));

            String key = trigger.getNormalizedKey();
            assertTrue(manager.hasItems(key));

            manager.clear();
            assertFalse(manager.hasItems(key));
        }

        @Test
        @DisplayName("Should compute absolute item index")
        void shouldComputeAbsoluteIndex() {
            Trigger trigger = createTrigger("t1", "Source");
            List<Map<String, Object>> items = List.of(Map.of("id", 1), Map.of("id", 2));
            TriggerBatchResult batch = createBatch(trigger, items, 20, 50, true, 30);

            manager.registerBatch(trigger, batch);

            String key = trigger.getNormalizedKey();
            // absoluteIndex = offset + localIndex = 20 + 0 = 20
            assertEquals(20, manager.getAbsoluteItemIndex(key, 0));
            assertEquals(21, manager.getAbsoluteItemIndex(key, 1));
        }

        @Test
        @DisplayName("Should return localIndex when no state for getAbsoluteItemIndex")
        void shouldReturnLocalIndexWhenNoState() {
            assertEquals(5, manager.getAbsoluteItemIndex("trigger:unknown", 5));
        }

        @Test
        @DisplayName("getState should return TriggerItemsState with correct fields")
        void getStateShouldReturnCorrectState() {
            Trigger trigger = createTrigger("t1", "Source");
            List<Map<String, Object>> items = List.of(Map.of("id", 1));
            TriggerBatchResult batch = new TriggerBatchResult(trigger, "tenant-1", items, 10, 20, 100, true, 30, 200);

            manager.registerBatch(trigger, batch);

            TriggerItemsManager.TriggerItemsState state = manager.getState(trigger.getNormalizedKey());
            assertNotNull(state);
            assertSame(trigger, state.trigger());
            assertEquals(1, state.items().size());
            assertEquals(10, state.offset());
            assertEquals(100, state.totalCount());
            assertTrue(state.hasMore());
            assertEquals(30, state.nextOffset());
            assertEquals(200, state.realTotalCount());
        }
    }

    @Nested
    @DisplayName("Chat trigger inputs")
    class ChatTriggerInputTests {

        @Test
        @DisplayName("Should store and retrieve chat trigger input")
        void shouldStoreAndRetrieve() {
            Map<String, Object> input = Map.of("message", "Hello", "userId", "user-1");
            manager.setChatTriggerInput("step-1", input);

            assertTrue(manager.hasChatTriggerInput("step-1"));
            Map<String, Object> retrieved = manager.getChatTriggerInput("step-1");
            assertEquals("Hello", retrieved.get("message"));
            assertEquals("user-1", retrieved.get("userId"));
        }

        @Test
        @DisplayName("Should return independent copy of input")
        void shouldReturnIndependentCopy() {
            Map<String, Object> input = new HashMap<>();
            input.put("key", "value");
            manager.setChatTriggerInput("step-1", input);

            Map<String, Object> retrieved = manager.getChatTriggerInput("step-1");
            retrieved.put("extra", "data");

            // Original should not be affected
            Map<String, Object> retrieved2 = manager.getChatTriggerInput("step-1");
            assertFalse(retrieved2.containsKey("extra"));
        }

        @Test
        @DisplayName("Should return false for hasChatTriggerInput when not stored")
        void shouldReturnFalseForUnstored() {
            assertFalse(manager.hasChatTriggerInput("nonexistent"));
        }

        @Test
        @DisplayName("Should return null for getChatTriggerInput when not stored")
        void shouldReturnNullForUnstored() {
            assertNull(manager.getChatTriggerInput("nonexistent"));
        }

        @Test
        @DisplayName("Should return null for getChatTriggerInput with null stepId")
        void shouldReturnNullForNullStepId() {
            assertNull(manager.getChatTriggerInput(null));
        }

        @Test
        @DisplayName("Should return false for hasChatTriggerInput with null stepId")
        void shouldReturnFalseForNullStepId() {
            assertFalse(manager.hasChatTriggerInput(null));
        }

        @Test
        @DisplayName("Should ignore null stepId on setChatTriggerInput")
        void shouldIgnoreNullStepIdOnSet() {
            manager.setChatTriggerInput(null, Map.of("key", "value"));
            // No exception
        }

        @Test
        @DisplayName("Should ignore null input on setChatTriggerInput")
        void shouldIgnoreNullInputOnSet() {
            manager.setChatTriggerInput("step-1", null);
            assertFalse(manager.hasChatTriggerInput("step-1"));
        }

        @Test
        @DisplayName("Should clear specific chat trigger input")
        void shouldClearSpecific() {
            manager.setChatTriggerInput("step-1", Map.of("key", "value"));
            assertTrue(manager.hasChatTriggerInput("step-1"));

            manager.clearChatTriggerInput("step-1");
            assertFalse(manager.hasChatTriggerInput("step-1"));
        }

        @Test
        @DisplayName("clearChatTriggerInput with null should not throw")
        void clearWithNullShouldNotThrow() {
            assertDoesNotThrow(() -> manager.clearChatTriggerInput(null));
        }
    }

    @Nested
    @DisplayName("Webhook trigger payloads")
    class WebhookTriggerPayloadTests {

        @Test
        @DisplayName("Should store and retrieve webhook trigger payload")
        void shouldStoreAndRetrieve() {
            Map<String, Object> payload = Map.of("body", "data", "headers", Map.of());
            manager.setWebhookTriggerPayload("step-1", payload);

            assertTrue(manager.hasWebhookTriggerPayload("step-1"));
            Map<String, Object> retrieved = manager.getWebhookTriggerPayload("step-1");
            assertEquals("data", retrieved.get("body"));
        }

        @Test
        @DisplayName("Should return independent copy of payload")
        void shouldReturnIndependentCopy() {
            Map<String, Object> payload = new HashMap<>();
            payload.put("key", "value");
            manager.setWebhookTriggerPayload("step-1", payload);

            Map<String, Object> retrieved = manager.getWebhookTriggerPayload("step-1");
            retrieved.put("extra", "data");

            Map<String, Object> retrieved2 = manager.getWebhookTriggerPayload("step-1");
            assertFalse(retrieved2.containsKey("extra"));
        }

        @Test
        @DisplayName("Should return false for hasWebhookTriggerPayload when not stored")
        void shouldReturnFalseForUnstored() {
            assertFalse(manager.hasWebhookTriggerPayload("nonexistent"));
        }

        @Test
        @DisplayName("Should return null for getWebhookTriggerPayload when not stored")
        void shouldReturnNullForUnstored() {
            assertNull(manager.getWebhookTriggerPayload("nonexistent"));
        }

        @Test
        @DisplayName("Should return null for getWebhookTriggerPayload with null stepId")
        void shouldReturnNullForNullStepId() {
            assertNull(manager.getWebhookTriggerPayload(null));
        }

        @Test
        @DisplayName("Should return false for hasWebhookTriggerPayload with null stepId")
        void shouldReturnFalseForNullStepId() {
            assertFalse(manager.hasWebhookTriggerPayload(null));
        }

        @Test
        @DisplayName("Should ignore null stepId on setWebhookTriggerPayload")
        void shouldIgnoreNullStepIdOnSet() {
            manager.setWebhookTriggerPayload(null, Map.of("key", "value"));
            // No exception
        }

        @Test
        @DisplayName("Should ignore null payload on setWebhookTriggerPayload")
        void shouldIgnoreNullPayloadOnSet() {
            manager.setWebhookTriggerPayload("step-1", null);
            assertFalse(manager.hasWebhookTriggerPayload("step-1"));
        }

        @Test
        @DisplayName("Should clear specific webhook trigger payload")
        void shouldClearSpecific() {
            manager.setWebhookTriggerPayload("step-1", Map.of("key", "value"));
            assertTrue(manager.hasWebhookTriggerPayload("step-1"));

            manager.clearWebhookTriggerPayload("step-1");
            assertFalse(manager.hasWebhookTriggerPayload("step-1"));
        }

        @Test
        @DisplayName("clearWebhookTriggerPayload with null should not throw")
        void clearWithNullShouldNotThrow() {
            assertDoesNotThrow(() -> manager.clearWebhookTriggerPayload(null));
        }
    }

    @Nested
    @DisplayName("TriggerItemsState")
    class TriggerItemsStateTests {

        @Test
        @DisplayName("Should compute absoluteIndex correctly")
        void shouldComputeAbsoluteIndex() {
            Trigger trigger = createTrigger("t1", "Source");
            TriggerItemsManager.TriggerItemsState state = new TriggerItemsManager.TriggerItemsState(
                trigger, List.of(Map.of("a", 1)), 15, 100, true, 25, 200
            );

            assertEquals(15, state.absoluteIndex(0));
            assertEquals(16, state.absoluteIndex(1));
            assertEquals(20, state.absoluteIndex(5));
        }

        @Test
        @DisplayName("Should handle null items as empty list")
        void shouldHandleNullItems() {
            TriggerItemsManager.TriggerItemsState state = new TriggerItemsManager.TriggerItemsState(
                null, null, 0, 0, false, 0, 0
            );

            assertNotNull(state.items());
            assertTrue(state.items().isEmpty());
        }
    }
}
