package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests that AggregateNode.getBatchKey() returns runId + ":" + epoch
 * instead of just runId, so parallel epochs on the same run don't mix items.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AggregateNode epoch-scoped batch key")
class AggregateNodeEpochBatchKeyTest {

    /**
     * Two contexts with same runId but different epochs must produce different batch keys.
     * This verifies the fix: batch key = runId + ":" + epoch.
     */
    @Test
    @DisplayName("batchKey includes epoch - two contexts with same runId but different epochs get different batch keys")
    void batchKey_includesEpoch() {
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        // Aggregate with one field
        AggregateNode node = AggregateNode.builder()
                .nodeId("core:agg")
                .templateEngine(templateEngine)
                .addField("names", "{{name}}")
                .build();

        // Context epoch 1 - single item (totalItems=1 by default since no split output)
        ExecutionContext ctxEpoch1 = createContext("run-123", 1, 0, "0");
        NodeExecutionResult result1 = node.execute(ctxEpoch1);
        // With 1 expected item and 1 received, should complete
        assertTrue(result1.isSuccess(), "Epoch 1 should complete with single item");

        // Context epoch 2 - same runId, different epoch
        ExecutionContext ctxEpoch2 = createContext("run-123", 2, 0, "0");
        NodeExecutionResult result2 = node.execute(ctxEpoch2);
        // Should also complete independently (not accumulate with epoch 1)
        assertTrue(result2.isSuccess(), "Epoch 2 should complete independently");

        // Both should have aggregated_count of 1 (not 2, which would happen if epochs shared a batch key)
        assertEquals(1, result1.output().get("aggregated_count"));
        assertEquals(1, result2.output().get("aggregated_count"));
    }

    /**
     * Items from epoch 1 and epoch 2 don't interfere with each other.
     * Feed 3 items to epoch 1, verify aggregation completes.
     * Then feed 3 items to epoch 2, verify independent aggregation.
     */
    @Test
    @DisplayName("parallel epochs isolate aggregation - items from different epochs don't mix")
    void parallelEpochs_isolateAggregation() {
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        AggregateNode node = AggregateNode.builder()
                .nodeId("core:agg")
                .templateEngine(templateEngine)
                .addField("names", "{{name}}")
                .build();

        // --- Epoch 1: 3 items ---
        // Item 0 of epoch 1
        ExecutionContext epoch1Item0 = createContextWithSplit("run-123", 1, 0, "0", 3);
        NodeExecutionResult r1_0 = node.execute(epoch1Item0);
        assertTrue(r1_0.isCollecting(), "Epoch 1 item 0 should be collecting");

        // Item 1 of epoch 1
        ExecutionContext epoch1Item1 = createContextWithSplit("run-123", 1, 1, "1", 3);
        NodeExecutionResult r1_1 = node.execute(epoch1Item1);
        assertTrue(r1_1.isCollecting(), "Epoch 1 item 1 should be collecting");

        // Item 2 of epoch 1 (last)
        ExecutionContext epoch1Item2 = createContextWithSplit("run-123", 1, 2, "2", 3);
        NodeExecutionResult r1_2 = node.execute(epoch1Item2);
        assertTrue(r1_2.isSuccess(), "Epoch 1 item 2 should complete aggregation");
        assertEquals(3, r1_2.output().get("aggregated_count"));

        // --- Epoch 2: 3 items (same runId, different epoch) ---
        // Item 0 of epoch 2
        ExecutionContext epoch2Item0 = createContextWithSplit("run-123", 2, 0, "0", 3);
        NodeExecutionResult r2_0 = node.execute(epoch2Item0);
        assertTrue(r2_0.isCollecting(), "Epoch 2 item 0 should be collecting (not mixed with epoch 1)");

        // Item 1 of epoch 2
        ExecutionContext epoch2Item1 = createContextWithSplit("run-123", 2, 1, "1", 3);
        NodeExecutionResult r2_1 = node.execute(epoch2Item1);
        assertTrue(r2_1.isCollecting(), "Epoch 2 item 1 should be collecting");

        // Item 2 of epoch 2 (last)
        ExecutionContext epoch2Item2 = createContextWithSplit("run-123", 2, 2, "2", 3);
        NodeExecutionResult r2_2 = node.execute(epoch2Item2);
        assertTrue(r2_2.isSuccess(), "Epoch 2 item 2 should complete aggregation independently");
        assertEquals(3, r2_2.output().get("aggregated_count"));
    }

    /**
     * Items within the same epoch are correctly collected.
     */
    @Test
    @DisplayName("same epoch correctly aggregates items")
    void sameEpoch_correctlyAggregates() {
        TemplateEngine templateEngine = mock(TemplateEngine.class);

        AggregateNode node = AggregateNode.builder()
                .nodeId("core:agg")
                .templateEngine(templateEngine)
                .addField("values", "{{value}}")
                .build();

        // 2 items in epoch 5
        ExecutionContext item0 = createContextWithSplit("run-456", 5, 0, "0", 2);
        NodeExecutionResult r0 = node.execute(item0);
        assertTrue(r0.isCollecting(), "First item should be collecting");
        assertEquals(1, r0.output().get("received"));
        assertEquals(2, r0.output().get("expected"));

        ExecutionContext item1 = createContextWithSplit("run-456", 5, 1, "1", 2);
        NodeExecutionResult r1 = node.execute(item1);
        assertTrue(r1.isSuccess(), "Second item should complete aggregation");
        assertEquals(2, r1.output().get("aggregated_count"));
        assertEquals("AGGREGATE", r1.output().get("node_type"));
    }

    // --- Helpers ---

    private ExecutionContext createContext(String runId, int epoch, int itemIndex, String itemId) {
        return ExecutionContext.create(
                runId, "wr-1", "tenant-1", itemId, itemIndex,
                "trigger:test", epoch, 0,
                Map.of(), mock(WorkflowPlan.class)
        );
    }

    private ExecutionContext createContextWithSplit(String runId, int epoch, int itemIndex, String itemId, int totalItems) {
        // Create context with step outputs that contain item_count (as set by Split node)
        ExecutionContext base = ExecutionContext.create(
                runId, "wr-1", "tenant-1", itemId, itemIndex,
                "trigger:test", epoch, 0,
                Map.of(), mock(WorkflowPlan.class)
        );
        // Add split output so AggregateNode.getTotalItems() finds item_count
        Map<String, Object> splitOutput = new HashMap<>();
        splitOutput.put("item_count", totalItems);
        splitOutput.put("node_type", "SPLIT");
        return base.withStepOutput("core:split", splitOutput);
    }
}
