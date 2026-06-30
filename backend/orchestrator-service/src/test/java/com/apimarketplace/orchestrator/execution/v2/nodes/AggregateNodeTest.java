package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AggregateNode.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AggregateNode")
class AggregateNodeTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private TemplateEngine mockTemplateEngine;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    @Nested
    @DisplayName("Constructor and properties")
    class ConstructorTests {

        @Test
        @DisplayName("should have AGGREGATE node type")
        void shouldHaveAggregateType() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);
            assertEquals(NodeType.AGGREGATE, node.getType());
        }

        @Test
        @DisplayName("should handle null fields list")
        void shouldHandleNullFields() {
            AggregateNode node = new AggregateNode("core:agg", null, null);
            assertNotNull(node.getFields());
            assertTrue(node.getFields().isEmpty());
        }

        @Test
        @DisplayName("isAggregateNode should return true")
        void shouldBeAggregateNode() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);
            assertTrue(node.isAggregateNode());
        }
    }

    @Nested
    @DisplayName("canExecute")
    class CanExecute {

        @Test
        @DisplayName("should always return true")
        void shouldAlwaysReturnTrue() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);
            assertTrue(node.canExecute(context));
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("should return collecting status when not all items received (single item = complete)")
        void shouldCollectSingleItem() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);

            NodeExecutionResult result = node.execute(context);

            // With 1 item expected and 1 received, should be SUCCESS
            assertTrue(result.isSuccess());
            assertEquals("AGGREGATE", result.output().get("node_type"));
        }

        @Test
        @DisplayName("should produce aggregated output with fields")
        void shouldProduceAggregatedOutput() {
            List<AggregateNode.AggregateField> fields = List.of(
                new AggregateNode.AggregateField("names", "{{name}}")
            );

            AggregateNode node = new AggregateNode("core:agg", fields, null);

            // Execute (single item = 1 expected, 1 received = complete)
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertNotNull(result.output().get("aggregated_count"));
        }
    }

    @Nested
    @DisplayName("getNextNodes")
    class GetNextNodes {

        @Test
        @DisplayName("should return empty list when collecting")
        void shouldReturnEmptyWhenCollecting() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);
            ExecutionNode successor = new ExitNode("next", "done");
            node.addSuccessor(successor);

            Map<String, Object> output = Map.of("status", "collecting");
            NodeExecutionResult result = NodeExecutionResult.success("core:agg", output);

            List<ExecutionNode> next = node.getNextNodes(result);
            assertTrue(next.isEmpty());
        }

        @Test
        @DisplayName("should return successors when aggregation is complete")
        void shouldReturnSuccessorsWhenComplete() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);
            ExitNode successor = new ExitNode("next", "done");
            node.addSuccessor(successor);

            Map<String, Object> output = Map.of("aggregated_count", 3);
            NodeExecutionResult result = NodeExecutionResult.success("core:agg", output);

            List<ExecutionNode> next = node.getNextNodes(result);
            assertEquals(1, next.size());
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build AggregateNode with builder")
        void shouldBuild() {
            AggregateNode node = AggregateNode.builder()
                .nodeId("core:my_aggregate")
                .templateEngine(mockTemplateEngine)
                .addField("names", "{{name}}")
                .addField("scores", "{{score}}")
                .build();

            assertEquals("core:my_aggregate", node.getNodeId());
            assertEquals(2, node.getFields().size());
            assertEquals("names", node.getFields().get(0).label());
            assertEquals("{{name}}", node.getFields().get(0).expression());
        }
    }

    @Nested
    @DisplayName("AggregateField record")
    class AggregateFieldTests {

        @Test
        @DisplayName("should store label and expression")
        void shouldStoreFields() {
            AggregateNode.AggregateField field = new AggregateNode.AggregateField("fieldLabel", "{{expr}}");
            assertEquals("fieldLabel", field.label());
            assertEquals("{{expr}}", field.expression());
        }
    }

    @Nested
    @DisplayName("Cleanup after completion")
    class CleanupTests {

        @Test
        @DisplayName("should clean up batch data after onComplete")
        void shouldCleanUpBatchDataAfterOnComplete() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);

            // Execute to populate internal maps
            NodeExecutionResult result = node.execute(context);
            assertTrue(result.isSuccess());

            // Trigger onComplete (simulates lifecycle callback)
            node.onComplete(context, result);

            // Execute again with same context - if cleanup worked, internal state is fresh
            // and the node should produce a new aggregation without stale data
            NodeExecutionResult secondResult = node.execute(context);
            assertTrue(secondResult.isSuccess());
            assertEquals(1, secondResult.output().get("aggregated_count"));
        }

        @Test
        @DisplayName("should clean up all batches via cleanupAllBatches")
        void shouldCleanUpAllBatches() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);

            // Execute with different run IDs to create multiple batches
            ExecutionContext ctx1 = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
            ExecutionContext ctx2 = ExecutionContext.create("run-2", "wr-2", "tenant-1", "item-0", 0, Map.of(), mockPlan);

            node.execute(ctx1);
            node.execute(ctx2);

            // Cleanup all
            node.cleanupAllBatches();

            // Should be able to re-execute without any leftover state
            NodeExecutionResult freshResult = node.execute(ctx1);
            assertTrue(freshResult.isSuccess());
            assertEquals(1, freshResult.output().get("aggregated_count"));
        }

        @Test
        @DisplayName("cleanupBatch should be safe to call with unknown key")
        void shouldHandleCleanupOfUnknownBatch() {
            AggregateNode node = new AggregateNode("core:agg", List.of(), null);
            // Should not throw
            assertDoesNotThrow(() -> node.cleanupBatch("nonexistent:0"));
        }
    }

    // #F1: Aggregate used to call templateEngine.resolveWithMap() which always returns a String,
    // so Number/Boolean/Map values from upstream were coerced to strings. The fix switches to
    // evaluateTemplateWithMap() which preserves the typed result for pure {{...}} expressions.
    @Nested
    @DisplayName("Type preservation (#F1)")
    class TypePreservationTests {

        @Test
        @DisplayName("should preserve Integer values through aggregation")
        void shouldPreserveIntegers() {
            when(mockTemplateEngine.evaluateTemplateWithMap(anyString(), any()))
                .thenReturn(42);

            AggregateNode node = new AggregateNode("core:agg",
                List.of(new AggregateNode.AggregateField("nums", "{{core:x.output.n}}")),
                mockTemplateEngine);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            Object nums = result.output().get("nums");
            assertInstanceOf(List.class, nums);
            List<?> list = (List<?>) nums;
            assertEquals(1, list.size());
            assertEquals(42, list.get(0));
            assertInstanceOf(Integer.class, list.get(0));
        }

        @Test
        @DisplayName("should preserve Double values through aggregation")
        void shouldPreserveDoubles() {
            when(mockTemplateEngine.evaluateTemplateWithMap(anyString(), any()))
                .thenReturn(3.14);

            AggregateNode node = new AggregateNode("core:agg",
                List.of(new AggregateNode.AggregateField("vals", "{{core:x.output.pi}}")),
                mockTemplateEngine);

            NodeExecutionResult result = node.execute(context);

            Object vals = result.output().get("vals");
            List<?> list = (List<?>) vals;
            assertEquals(3.14, list.get(0));
            assertInstanceOf(Double.class, list.get(0));
        }

        @Test
        @DisplayName("should preserve Boolean values through aggregation")
        void shouldPreserveBooleans() {
            when(mockTemplateEngine.evaluateTemplateWithMap(anyString(), any()))
                .thenReturn(true);

            AggregateNode node = new AggregateNode("core:agg",
                List.of(new AggregateNode.AggregateField("flags", "{{core:x.output.ok}}")),
                mockTemplateEngine);

            NodeExecutionResult result = node.execute(context);

            List<?> flags = (List<?>) result.output().get("flags");
            assertEquals(Boolean.TRUE, flags.get(0));
            assertInstanceOf(Boolean.class, flags.get(0));
        }

        @Test
        @DisplayName("should preserve Map values through aggregation")
        void shouldPreserveMaps() {
            Map<String, Object> nested = Map.of("k", "v", "n", 1);
            when(mockTemplateEngine.evaluateTemplateWithMap(anyString(), any()))
                .thenReturn(nested);

            AggregateNode node = new AggregateNode("core:agg",
                List.of(new AggregateNode.AggregateField("records", "{{core:x.output.obj}}")),
                mockTemplateEngine);

            NodeExecutionResult result = node.execute(context);

            List<?> records = (List<?>) result.output().get("records");
            assertEquals(nested, records.get(0));
            assertInstanceOf(Map.class, records.get(0));
        }

        @Test
        @DisplayName("should preserve existing strings unchanged")
        void shouldPreserveStrings() {
            when(mockTemplateEngine.evaluateTemplateWithMap(anyString(), any()))
                .thenReturn("alpha-0");

            AggregateNode node = new AggregateNode("core:agg",
                List.of(new AggregateNode.AggregateField("tags", "{{core:x.output.tag}}")),
                mockTemplateEngine);

            NodeExecutionResult result = node.execute(context);

            List<?> tags = (List<?>) result.output().get("tags");
            assertEquals("alpha-0", tags.get(0));
            assertInstanceOf(String.class, tags.get(0));
        }
    }

    @Nested
    @DisplayName("Concurrent item arrival")
    class ConcurrencyTests {

        @Test
        @DisplayName("should handle concurrent items without race condition")
        void shouldHandleConcurrentItemsAtomically() throws Exception {
            int totalItems = 10;
            AggregateNode node = new AggregateNode("core:agg", List.of(
                new AggregateNode.AggregateField("values", "{{item}}")
            ), null);

            // Create split output so the node knows total items
            Map<String, Object> splitOutput = Map.of(
                "item_count", totalItems,
                "node_type", "SPLIT"
            );

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalItems);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger collectingCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(totalItems);
            try {
                for (int i = 0; i < totalItems; i++) {
                    final int itemIndex = i;
                    executor.submit(() -> {
                        try {
                            startLatch.await(); // All threads start simultaneously
                            ExecutionContext itemContext = ExecutionContext.create(
                                "run-1", "wr-1", "tenant-1",
                                "0." + itemIndex, itemIndex,
                                null, 0, 0,
                                Map.of(), mockPlan
                            );
                            // Inject step outputs so getTotalItems finds item_count
                            itemContext = itemContext.withStepOutput("core:split", splitOutput);

                            NodeExecutionResult result = node.execute(itemContext);
                            if (result.isSuccess() && !"collecting".equals(result.output().get("status"))) {
                                successCount.incrementAndGet();
                            } else {
                                collectingCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            // Should not happen
                            e.printStackTrace();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown(); // Release all threads
                assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "All threads should complete within 10s");

                // Exactly one thread should see the final aggregation (success),
                // and the rest should see "collecting" status
                assertEquals(1, successCount.get(),
                    "Exactly one thread should produce the final aggregated result");
                assertEquals(totalItems - 1, collectingCount.get(),
                    "All other threads should see collecting status");
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("a lagging item must not finalize a second time after cleanup already ran")
        void shouldNotDoubleFinalizeWhenALaggingItemReadsAfterCleanup() throws Exception {
            // Deterministic regression for the post-cleanup double-finalize race. The test
            // seam parks item A right AFTER it increments (received=1) and BEFORE it reads
            // the expected total - the exact window of the race. While A is parked, item B
            // increments (received=2), finalizes, and REMOVES the batch maps; then A resumes
            // and runs its own expected-read + finalize check.
            //
            // Pre-fix, A re-read its expected count from the just-removed per-batch state and
            // got the default (1), so received(1) >= 1 made A finalize a SECOND time (two
            // "final" results for one batch). The fix reads expected from the stable per-item
            // local (totalItems) after the seam, so received(1) >= 2 is false and A stays
            // "collecting". Pinning the interleaving makes the guard deterministic, not
            // dependent on scheduler timing.
            int totalItems = 2;
            AggregateNode node = new AggregateNode("core:agg", List.of(
                new AggregateNode.AggregateField("values", "{{item}}")
            ), null);
            Map<String, Object> splitOutput = Map.of("item_count", totalItems, "node_type", "SPLIT");

            CountDownLatch aParked = new CountDownLatch(1);
            CountDownLatch bFinalized = new CountDownLatch(1);

            // Park only the first item (received == 1 = item A) until B has finalized + cleaned.
            node.afterCountIncrementedHookForTest = received -> {
                if (received == 1) {
                    aParked.countDown();
                    try {
                        assertTrue(bFinalized.await(5, TimeUnit.SECONDS), "B should finalize within 5s");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };

            ExecutionContext ctxA = ExecutionContext.create(
                "run-x", "wr-1", "tenant-1", "0.0", 0, null, 0, 0, Map.of(), mockPlan
            ).withStepOutput("core:split", splitOutput);
            ExecutionContext ctxB = ExecutionContext.create(
                "run-x", "wr-1", "tenant-1", "0.1", 1, null, 0, 0, Map.of(), mockPlan
            ).withStepOutput("core:split", splitOutput);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<NodeExecutionResult> fA = executor.submit(() -> node.execute(ctxA));
                // A must increment (received=1) and park BEFORE B runs, so B deterministically
                // reaches received=2 and becomes the finalizer.
                assertTrue(aParked.await(5, TimeUnit.SECONDS), "A should reach the seam");

                Future<NodeExecutionResult> fB = executor.submit(() -> node.execute(ctxB));
                NodeExecutionResult resultB = fB.get(5, TimeUnit.SECONDS);
                assertTrue(resultB.isSuccess(), "B should succeed");
                assertNotEquals("collecting", resultB.output().get("status"),
                    "B (received==totalItems) should be the single finalizer");

                bFinalized.countDown(); // release A to run its finalize check after cleanup
                NodeExecutionResult resultA = fA.get(5, TimeUnit.SECONDS);

                assertEquals("collecting", resultA.output().get("status"),
                    "A must not produce a second final result after the batch was finalized + cleaned");
            } finally {
                executor.shutdownNow();
            }
        }
    }
}
