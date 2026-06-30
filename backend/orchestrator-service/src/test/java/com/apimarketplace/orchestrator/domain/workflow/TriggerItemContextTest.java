package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TriggerItemContext")
class TriggerItemContextTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {
        @Test
        @DisplayName("Should build with all fields")
        void shouldBuildWithAllFields() {
            Instant now = Instant.now();
            Map<String, Object> payload = Map.of("key", "value");

            TriggerItemContext context = TriggerItemContext.builder()
                .runId("run-123")
                .triggerId("trigger:webhook")
                .itemId("item-456")
                .payload(payload)
                .batchIndex(0)
                .absoluteIndex(5)
                .totalCount(10)
                .hasMore(true)
                .tenantId("tenant-1")
                .receivedAt(now)
                .build();

            assertEquals("run-123", context.getRunId());
            assertEquals("trigger:webhook", context.getTriggerId());
            assertEquals("item-456", context.getItemId());
            assertEquals(payload, context.getPayload());
            assertEquals(0, context.getBatchIndex());
            assertEquals(5, context.getAbsoluteIndex());
            assertEquals(10, context.getTotalCount());
            assertTrue(context.isHasMore());
            assertEquals("tenant-1", context.getTenantId());
            assertEquals(now, context.getReceivedAt());
        }

        @Test
        @DisplayName("Should generate itemId if not provided")
        void shouldGenerateItemIdIfNotProvided() {
            TriggerItemContext context = TriggerItemContext.builder()
                .runId("run-123")
                .triggerId("trigger:webhook")
                .payload(Map.of())
                .tenantId("tenant-1")
                .build();

            assertNotNull(context.getItemId());
            assertFalse(context.getItemId().isEmpty());
        }

        @Test
        @DisplayName("Should default receivedAt to now if not provided")
        void shouldDefaultReceivedAtToNow() {
            Instant before = Instant.now();

            TriggerItemContext context = TriggerItemContext.builder()
                .runId("run-123")
                .triggerId("trigger:webhook")
                .payload(Map.of())
                .tenantId("tenant-1")
                .build();

            Instant after = Instant.now();
            assertNotNull(context.getReceivedAt());
            assertFalse(context.getReceivedAt().isBefore(before));
            assertFalse(context.getReceivedAt().isAfter(after));
        }

        @Test
        @DisplayName("Should throw for null runId")
        void shouldThrowForNullRunId() {
            assertThrows(NullPointerException.class, () ->
                TriggerItemContext.builder()
                    .triggerId("trigger:webhook")
                    .payload(Map.of())
                    .tenantId("tenant-1")
                    .build()
            );
        }

        @Test
        @DisplayName("Should throw for null triggerId")
        void shouldThrowForNullTriggerId() {
            assertThrows(NullPointerException.class, () ->
                TriggerItemContext.builder()
                    .runId("run-123")
                    .payload(Map.of())
                    .tenantId("tenant-1")
                    .build()
            );
        }

        @Test
        @DisplayName("Should throw for null payload")
        void shouldThrowForNullPayload() {
            assertThrows(NullPointerException.class, () ->
                TriggerItemContext.builder()
                    .runId("run-123")
                    .triggerId("trigger:webhook")
                    .tenantId("tenant-1")
                    .build()
            );
        }

        @Test
        @DisplayName("Should throw for null tenantId")
        void shouldThrowForNullTenantId() {
            assertThrows(NullPointerException.class, () ->
                TriggerItemContext.builder()
                    .runId("run-123")
                    .triggerId("trigger:webhook")
                    .payload(Map.of())
                    .build()
            );
        }
    }

    @Nested
    @DisplayName("random() factory")
    class RandomFactoryTests {
        @Test
        @DisplayName("Should create context with provided values")
        void shouldCreateContextWithProvidedValues() {
            Map<String, Object> payload = Map.of("data", "test");

            TriggerItemContext context = TriggerItemContext.random(
                "run-123",
                "trigger:datasource",
                5,
                10,
                "tenant-1",
                payload
            );

            assertEquals("run-123", context.getRunId());
            assertEquals("trigger:datasource", context.getTriggerId());
            assertEquals(5, context.getAbsoluteIndex());
            assertEquals(5, context.getBatchIndex());
            assertEquals(10, context.getTotalCount());
            assertEquals("tenant-1", context.getTenantId());
            assertEquals(payload, context.getPayload());
        }

        @Test
        @DisplayName("Should calculate hasMore correctly")
        void shouldCalculateHasMoreCorrectly() {
            TriggerItemContext midContext = TriggerItemContext.random(
                "run", "trigger", 5, 10, "tenant", Map.of()
            );
            assertTrue(midContext.isHasMore());

            TriggerItemContext lastContext = TriggerItemContext.random(
                "run", "trigger", 9, 10, "tenant", Map.of()
            );
            assertFalse(lastContext.isHasMore());
        }

        @Test
        @DisplayName("Should generate unique itemId")
        void shouldGenerateUniqueItemId() {
            TriggerItemContext ctx1 = TriggerItemContext.random(
                "run", "trigger", 0, 1, "tenant", Map.of()
            );
            TriggerItemContext ctx2 = TriggerItemContext.random(
                "run", "trigger", 0, 1, "tenant", Map.of()
            );

            assertNotEquals(ctx1.getItemId(), ctx2.getItemId());
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {
        @Test
        @DisplayName("Should include key fields")
        void shouldIncludeKeyFields() {
            TriggerItemContext context = TriggerItemContext.builder()
                .runId("run-123")
                .triggerId("trigger:webhook")
                .itemId("item-456")
                .payload(Map.of())
                .batchIndex(2)
                .absoluteIndex(5)
                .totalCount(10)
                .tenantId("tenant-1")
                .build();

            String str = context.toString();

            assertTrue(str.contains("run-123"));
            assertTrue(str.contains("trigger:webhook"));
            assertTrue(str.contains("item-456"));
            assertTrue(str.contains("tenant-1"));
        }
    }

    @Nested
    @DisplayName("Index tracking")
    class IndexTrackingTests {
        @Test
        @DisplayName("Should track batch index separately from absolute index")
        void shouldTrackBatchIndexSeparatelyFromAbsoluteIndex() {
            TriggerItemContext context = TriggerItemContext.builder()
                .runId("run-123")
                .triggerId("trigger:datasource")
                .payload(Map.of())
                .batchIndex(3)
                .absoluteIndex(103)
                .totalCount(200)
                .tenantId("tenant-1")
                .build();

            assertEquals(3, context.getBatchIndex());
            assertEquals(103, context.getAbsoluteIndex());
            assertEquals(200, context.getTotalCount());
        }

        @Test
        @DisplayName("Should handle first item")
        void shouldHandleFirstItem() {
            TriggerItemContext context = TriggerItemContext.builder()
                .runId("run-123")
                .triggerId("trigger:webhook")
                .payload(Map.of())
                .batchIndex(0)
                .absoluteIndex(0)
                .totalCount(1)
                .hasMore(false)
                .tenantId("tenant-1")
                .build();

            assertEquals(0, context.getBatchIndex());
            assertEquals(0, context.getAbsoluteIndex());
            assertEquals(1, context.getTotalCount());
            assertFalse(context.isHasMore());
        }
    }
}
