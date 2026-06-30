package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.orchestrator.config.WorkflowExecutionConfig;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.TriggerItemContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerItemContextBuilder")
class TriggerItemContextBuilderTest {

    @Mock
    private WorkflowExecutionConfig config;

    @Mock
    private TriggerPayloadBuilder payloadBuilder;

    @Mock
    private Trigger mockTrigger;

    private TriggerItemContextBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new TriggerItemContextBuilder(config, payloadBuilder);
    }

    @Nested
    @DisplayName("buildTriggerItemContext()")
    class BuildTriggerItemContextTests {

        @Test
        @DisplayName("Should build context with correct fields")
        void shouldBuildWithCorrectFields() {
            when(mockTrigger.getNormalizedKey()).thenReturn("trigger:webhook");
            Map<String, Object> rawPayload = Map.of("data", "value");

            TriggerItemContext ctx = builder.buildTriggerItemContext(
                "run-1", "tenant-1", mockTrigger, rawPayload, 0, 5, 100, false
            );

            assertNotNull(ctx);
            assertEquals("run-1", ctx.getRunId());
            assertEquals("trigger:webhook", ctx.getTriggerId());
            assertEquals("tenant-1", ctx.getTenantId());
            assertEquals(0, ctx.getBatchIndex());
            assertEquals(5, ctx.getAbsoluteIndex());
            assertEquals(100, ctx.getTotalCount());
            assertFalse(ctx.isHasMore());
        }

        @Test
        @DisplayName("Should handle null rawPayload")
        void shouldHandleNullPayload() {
            when(mockTrigger.getNormalizedKey()).thenReturn("trigger:webhook");

            TriggerItemContext ctx = builder.buildTriggerItemContext(
                "run-1", "tenant-1", mockTrigger, null, 0, 0, 10, false
            );

            assertNotNull(ctx);
            assertNotNull(ctx.getPayload());
        }

        @Test
        @DisplayName("Should throw for null runId")
        void shouldThrowForNullRunId() {
            assertThrows(NullPointerException.class,
                () -> builder.buildTriggerItemContext(
                    null, "tenant-1", mockTrigger, null, 0, 0, 10, false
                ));
        }

        @Test
        @DisplayName("Should throw for null trigger")
        void shouldThrowForNullTrigger() {
            assertThrows(NullPointerException.class,
                () -> builder.buildTriggerItemContext(
                    "run-1", "tenant-1", null, null, 0, 0, 10, false
                ));
        }

        @Test
        @DisplayName("Should clamp negative totalCount to 0")
        void shouldClampNegativeTotalCount() {
            when(mockTrigger.getNormalizedKey()).thenReturn("trigger:webhook");

            TriggerItemContext ctx = builder.buildTriggerItemContext(
                "run-1", "tenant-1", mockTrigger, null, 0, 0, -5, false
            );

            assertEquals(0, ctx.getTotalCount());
        }
    }

    @Nested
    @DisplayName("buildTriggerItemContexts()")
    class BuildTriggerItemContextsTests {

        @Test
        @DisplayName("Should build list of contexts from items")
        void shouldBuildListOfContexts() {
            when(mockTrigger.getNormalizedKey()).thenReturn("trigger:webhook");
            List<Map<String, Object>> items = List.of(
                Map.of("id", "1"),
                Map.of("id", "2"),
                Map.of("id", "3")
            );

            List<TriggerItemContext> contexts = builder.buildTriggerItemContexts(
                "run-1", "tenant-1", mockTrigger, items, 0, 3, false
            );

            assertEquals(3, contexts.size());
            assertEquals(0, contexts.get(0).getAbsoluteIndex());
            assertEquals(1, contexts.get(1).getAbsoluteIndex());
            assertEquals(2, contexts.get(2).getAbsoluteIndex());
        }

        @Test
        @DisplayName("Should handle offset for absolute indices")
        void shouldHandleOffset() {
            when(mockTrigger.getNormalizedKey()).thenReturn("trigger:webhook");
            List<Map<String, Object>> items = List.of(
                Map.of("id", "1"),
                Map.of("id", "2")
            );

            List<TriggerItemContext> contexts = builder.buildTriggerItemContexts(
                "run-1", "tenant-1", mockTrigger, items, 10, 20, false
            );

            assertEquals(10, contexts.get(0).getAbsoluteIndex());
            assertEquals(11, contexts.get(1).getAbsoluteIndex());
        }

        @Test
        @DisplayName("Should return empty list for null items")
        void shouldReturnEmptyForNull() {
            List<TriggerItemContext> contexts = builder.buildTriggerItemContexts(
                "run-1", "tenant-1", mockTrigger, null, 0, 0, false
            );

            assertTrue(contexts.isEmpty());
        }

        @Test
        @DisplayName("Should return empty list for empty items")
        void shouldReturnEmptyForEmptyItems() {
            List<TriggerItemContext> contexts = builder.buildTriggerItemContexts(
                "run-1", "tenant-1", mockTrigger, List.of(), 0, 0, false
            );

            assertTrue(contexts.isEmpty());
        }

        @Test
        @DisplayName("Should use items.size() when totalCount is smaller")
        void shouldUseItemsSizeWhenLarger() {
            when(mockTrigger.getNormalizedKey()).thenReturn("trigger:webhook");
            List<Map<String, Object>> items = List.of(
                Map.of("id", "1"),
                Map.of("id", "2"),
                Map.of("id", "3")
            );

            List<TriggerItemContext> contexts = builder.buildTriggerItemContexts(
                "run-1", "tenant-1", mockTrigger, items, 0, 1, false
            );

            assertEquals(3, contexts.size());
            // safeTotal should be max(1, 3) = 3
            assertEquals(3, contexts.get(0).getTotalCount());
        }
    }
}
