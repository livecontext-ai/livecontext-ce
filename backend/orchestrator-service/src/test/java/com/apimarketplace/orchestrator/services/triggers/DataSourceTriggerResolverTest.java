package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceTriggerResolver")
class DataSourceTriggerResolverTest {

    @Mock
    private WorkflowExecutionConfig config;

    @Mock
    private DataSourceClient dataSourceClient;

    @Mock
    private com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver userResolver;

    private DataSourceTriggerResolver resolver;

    @BeforeEach
    void setUp() {
        TriggerPayloadBuilder payloadBuilder = new TriggerPayloadBuilder(config);
        resolver = new DataSourceTriggerResolver(config, dataSourceClient, payloadBuilder, userResolver);

        lenient().when(config.getTriggerBatchSize()).thenReturn(50);
        lenient().when(dataSourceClient.getDataSource(anyLong(), anyString()))
                .thenReturn(new DataSourceDto(
                        19L, "t-1", "orders",
                        null, null, null, null,
                        null, null, null, null, null,
                        null, null, null, null));
        lenient().when(dataSourceClient.getItems(anyLong(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(new DataSourceItemDto(
                        131L, 19L, "t-1",
                        Map.of("status", "paid", "amount", 999),
                        0, Instant.parse("2026-04-17T20:00:00Z"))));
        lenient().when(dataSourceClient.getItemsCount(anyLong(), anyString())).thenReturn(1);
    }

    private Trigger datasourceTrigger() {
        return new Trigger("19", "on_payment_confirmed", "receive_one", "datasource");
    }

    @Nested
    @DisplayName("Event-driven fire: V97 docs-prescribed references must resolve")
    class EventDrivenPromotionTests {

        @Test
        @DisplayName("Promotes event_type/row/previous_row/row_id to top level when event_type is in resolvedInputs")
        void promotesEventFieldsToTopLevel() {
            // Arrange: simulate an event-driven row_updated payload that
            // DatasourceTriggerDispatchService would deliver via resolvedInputs.
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("event_type", "row_updated");
            inputs.put("row", Map.of("id", 131, "status", "paid"));
            inputs.put("previous_row", Map.of("id", 131, "status", "pending"));
            inputs.put("row_id", 131);
            inputs.put("datasource_id", 19);
            inputs.put("triggered_at", "2026-04-17T21:19:56Z");
            inputs.put("status", "paid"); // flattened column - also appears in legacy 'status' slot

            // Act
            Map<String, Object> payload = resolver.resolve(datasourceTrigger(), "t-1", inputs);

            // Assert: event-driven fields are reachable at top level - matches V97 docs that
            // claim {{trigger:<label>.output.event_type}}, .output.row, .output.previous_row,
            // .output.row_id all work without a _inputs. hop.
            assertEquals("row_updated", payload.get("event_type"));
            assertEquals(Map.of("id", 131, "status", "paid"), payload.get("row"));
            assertEquals(Map.of("id", 131, "status", "pending"), payload.get("previous_row"));
            assertEquals(131, payload.get("row_id"));
            assertEquals(19, payload.get("datasource_id"));
            assertEquals("2026-04-17T21:19:56Z", payload.get("triggered_at"));

            // _inputs is preserved for backward-compat consumers that already navigate through it
            assertTrue(payload.containsKey("_inputs"));
            @SuppressWarnings("unchecked")
            Map<String, Object> preserved = (Map<String, Object>) payload.get("_inputs");
            assertEquals("row_updated", preserved.get("event_type"));
        }

        @Test
        @DisplayName("Legacy batch-scan keys take precedence over flattened event columns (putIfAbsent)")
        void legacyKeysTakePrecedence() {
            // Flattened column 'status' in inputs would collide with the batch-scan 'status'
            // payload key ("success" from TriggerBatchResult.toLegacyPayload). The promotion
            // must not shadow the legacy key - existing consumers of trigger.output.status
            // (schedule/manual fires) must keep getting "success".
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("event_type", "row_updated");
            inputs.put("status", "paid");     // would-be shadow of legacy "success"
            inputs.put("offset", 99);          // would-be shadow of legacy 0
            inputs.put("count", 42);           // would-be shadow of legacy 1

            Map<String, Object> payload = resolver.resolve(datasourceTrigger(), "t-1", inputs);

            assertEquals("success", payload.get("status"), "legacy batch-scan status must not be shadowed");
            assertEquals(0, payload.get("offset"), "legacy batch-scan offset must not be shadowed");
            assertEquals(1, payload.get("count"), "legacy batch-scan count must not be shadowed");

            // But the event-only keys still land at top level
            assertEquals("row_updated", payload.get("event_type"));
        }

        @Test
        @DisplayName("Non-event resolvedInputs (no event_type) is NOT promoted - only _inputs is preserved")
        void nonEventInputsAreNotPromoted() {
            // When the trigger is fired manually or on schedule (no event_type), the event-path
            // promotion must NOT kick in. Only _inputs wrapper stays, preserving the previous
            // behavior for manual fires.
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("custom_param", "from-manual-fire");

            Map<String, Object> payload = resolver.resolve(datasourceTrigger(), "t-1", inputs);

            assertFalse(payload.containsKey("custom_param"),
                    "non-event inputs should NOT leak to top level - event_type gate is the guard");
            assertTrue(payload.containsKey("_inputs"));
        }

        @Test
        @DisplayName("Null-valued event fields (e.g. previous_row on row_created) are skipped, not promoted as null")
        void nullValuedEventFieldsAreSkipped() {
            // row_created fires with previous_row = null. Promoting null into the payload would
            // shadow any future legacy mapping AND would blow up downstream (ConcurrentHashMap
            // in WorkflowExecutionContext rejects null values - see companion fix there).
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("event_type", "row_created");
            inputs.put("row", Map.of("id", 131, "status", "paid"));
            inputs.put("previous_row", null);
            inputs.put("row_id", 131);

            Map<String, Object> payload = resolver.resolve(datasourceTrigger(), "t-1", inputs);

            assertEquals("row_created", payload.get("event_type"));
            assertFalse(payload.containsKey("previous_row"),
                    "null previous_row must be skipped, not stored as null");
            assertEquals(131, payload.get("row_id"));
        }

        @Test
        @DisplayName("row_deleted: promotes event fields; the 'row' field (last snapshot) is promoted even when user columns collide with legacy keys")
        void rowDeletedPromotesEventFields() {
            // row_deleted carries the deleted row as 'row' (last known snapshot before deletion)
            // and previous_row = null (nothing 'before' a deletion - the row IS the previous state).
            // This symmetrically exercises the row_created path (null row? no - the 'row' is the
            // deleted snapshot) and additionally asserts that a broader set of legacy reserved
            // keys (data / hasMore / strategy / triggerId / tenantId) cannot be shadowed by a
            // flattened column of the same name.
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("event_type", "row_deleted");
            inputs.put("row", Map.of("id", 131, "status", "paid"));
            inputs.put("previous_row", null);
            inputs.put("row_id", 131);
            // Evil-twin flattened columns that would shadow legacy keys if promotion were naive:
            inputs.put("data", "evil-data");
            inputs.put("hasMore", "evil-hasMore");
            inputs.put("strategy", "evil-strategy");
            inputs.put("triggerId", "evil-triggerId");
            inputs.put("tenantId", "evil-tenantId");

            Map<String, Object> payload = resolver.resolve(datasourceTrigger(), "t-1", inputs);

            // Event fields land at top level
            assertEquals("row_deleted", payload.get("event_type"));
            assertEquals(Map.of("id", 131, "status", "paid"), payload.get("row"));
            assertEquals(131, payload.get("row_id"));
            assertFalse(payload.containsKey("previous_row"),
                    "null previous_row must be skipped for row_deleted too");

            // Legacy batch-scan keys unshadowed - the evil twins stay reachable only via _inputs
            assertNotEquals("evil-data", payload.get("data"), "legacy 'data' (items list) must not be shadowed");
            assertTrue(payload.get("data") instanceof List, "legacy 'data' must remain the items list");
            assertNotEquals("evil-hasMore", payload.get("hasMore"));
            assertEquals("receive_one", payload.get("strategy"), "legacy 'strategy' (from trigger) must win");
            assertNotEquals("evil-triggerId", payload.get("triggerId"));
            assertNotEquals("evil-tenantId", payload.get("tenantId"));

            // Evil twins are still reachable through the _inputs wrapper (promotion preserves it)
            @SuppressWarnings("unchecked")
            Map<String, Object> preserved = (Map<String, Object>) payload.get("_inputs");
            assertEquals("evil-data", preserved.get("data"));
            assertEquals("evil-hasMore", preserved.get("hasMore"));
            assertEquals("evil-strategy", preserved.get("strategy"));
            assertEquals("evil-triggerId", preserved.get("triggerId"));
            assertEquals("evil-tenantId", preserved.get("tenantId"));
        }

        @Test
        @DisplayName("Non-event path preserves the exact _inputs content (not just the key)")
        void nonEventInputsPreservesExactContent() {
            // The prior test only asserts containsKey("_inputs"). Audit feedback: assert the
            // full content survives so manual/schedule consumers that already navigate
            // trigger.output._inputs.<field> keep working after the promotion refactor.
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("custom_param", "from-manual-fire");
            inputs.put("nested", Map.of("k", "v", "n", 42));
            inputs.put("list_param", List.of("a", "b", "c"));

            Map<String, Object> payload = resolver.resolve(datasourceTrigger(), "t-1", inputs);

            assertFalse(payload.containsKey("custom_param"),
                    "non-event inputs must NOT be promoted to top level");
            assertTrue(payload.containsKey("_inputs"));

            @SuppressWarnings("unchecked")
            Map<String, Object> preserved = (Map<String, Object>) payload.get("_inputs");
            assertEquals("from-manual-fire", preserved.get("custom_param"));
            assertEquals(Map.of("k", "v", "n", 42), preserved.get("nested"));
            assertEquals(List.of("a", "b", "c"), preserved.get("list_param"));
        }

        @Test
        @DisplayName("Flattened-column-from-row end-to-end: columns delivered alongside event_type are promoted to top level (mirrors DatasourceTriggerDispatchService)")
        void flattenedColumnsFromRowArePromotedEndToEnd() {
            // Real flow: DatasourceTriggerDispatchService flattens the changed row's columns
            // into resolvedInputs alongside event_type. We simulate that full shape here to
            // lock the end-to-end behavior rather than just the promotion primitive.
            //
            // Columns: amount (no legacy collision), customer_id (no collision),
            // source (COLLIDES with legacy 'source' key), count (COLLIDES).
            Map<String, Object> inputs = new HashMap<>();
            inputs.put("event_type", "row_updated");
            inputs.put("row", Map.of("id", 131, "status", "paid", "amount", 999, "customer_id", "cust-42"));
            inputs.put("previous_row", Map.of("id", 131, "status", "pending", "amount", 999, "customer_id", "cust-42"));
            inputs.put("row_id", 131);
            inputs.put("datasource_id", 19);
            inputs.put("triggered_at", "2026-04-17T21:19:56Z");
            // Flattened columns (what DatasourceTriggerDispatchService actually puts there):
            inputs.put("amount", 999);
            inputs.put("customer_id", "cust-42");
            inputs.put("source", "stripe-webhook");    // collides with legacy 'source' = 'datasource'
            inputs.put("count", 7);                     // collides with legacy 'count' = 1
            inputs.put("status", "paid");               // collides with legacy 'status' = 'success'

            Map<String, Object> payload = resolver.resolve(datasourceTrigger(), "t-1", inputs);

            // Non-colliding columns reach top level
            assertEquals(999, payload.get("amount"), "'amount' (no legacy collision) must be at top level");
            assertEquals("cust-42", payload.get("customer_id"),
                    "'customer_id' (no legacy collision) must be at top level");

            // Colliding columns stay shadowed by legacy values
            assertEquals("datasource", payload.get("source"), "legacy 'source'='datasource' wins over row column");
            assertEquals(1, payload.get("count"), "legacy 'count'=1 wins over row column");
            assertEquals("success", payload.get("status"), "legacy 'status'='success' wins over row column");

            // Collided columns remain reachable via _inputs (the escape hatch)
            @SuppressWarnings("unchecked")
            Map<String, Object> preserved = (Map<String, Object>) payload.get("_inputs");
            assertEquals("stripe-webhook", preserved.get("source"));
            assertEquals(7, preserved.get("count"));
            assertEquals("paid", preserved.get("status"));

            // Event-core fields still land correctly
            assertEquals("row_updated", payload.get("event_type"));
            assertEquals(131, payload.get("row_id"));
            assertEquals(19, payload.get("datasource_id"));
            assertEquals("2026-04-17T21:19:56Z", payload.get("triggered_at"));
        }
    }
}
