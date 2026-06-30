package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.AggregateNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.persistence.OutputSchemaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SplitAggregateHandler.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SplitAggregateHandler")
class SplitAggregateHandlerTest {

    @Mock private SplitContextManager mockContextManager;
    @Mock private TemplateEngine mockTemplateEngine;
    @Mock private OutputSchemaMapper mockOutputSchemaMapper;

    private SplitAggregateHandler handler;

    @BeforeEach
    void setUp() {
        // splitAwareNodeExecutor=null exercises the legacy "iterate all items"
        // fallback in resolveRoutedItemIndices. Tests that assert the post-fix
        // routed-filter behavior (NestedConfigDualWriteRegression-style new
        // tests) construct their own handler with a real or stubbed executor.
        handler = new SplitAggregateHandler(
            mockContextManager, mockTemplateEngine, mockOutputSchemaMapper, null, null);
    }

    private ExecutionContext createContext(int itemIndex) {
        ExecutionState state = ExecutionState.create();
        return new ExecutionContext("run-1", "wr-1", "tenant-1", "item-" + itemIndex, itemIndex,
            null, 0, 0, Map.of(), Map.of(), state, null);
    }

    @Nested
    @DisplayName("isSplitAggregate")
    class IsSplitAggregate {

        @Test
        @DisplayName("should return true when BFS finds active context")
        void shouldReturnTrueWhenBfsFindsContext() {
            SplitContext splitContext = SplitContext.create("core:split", List.of("a", "b"));
            Map<String, ExecutionNode> nodeMap = Map.of();

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            assertTrue(handler.isSplitAggregate("run-1", "core:aggregate", 0, nodeMap));
        }

        @Test
        @DisplayName("should return true when fallback hasContexts finds contexts")
        void shouldReturnTrueOnFallback() {
            Map<String, ExecutionNode> nodeMap = Map.of();

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.empty());
            when(mockContextManager.hasContexts("run-1")).thenReturn(true);

            assertTrue(handler.isSplitAggregate("run-1", "core:aggregate", 0, nodeMap));
        }

        @Test
        @DisplayName("should return false when no contexts exist")
        void shouldReturnFalseWhenNoContexts() {
            Map<String, ExecutionNode> nodeMap = Map.of();

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.empty());
            when(mockContextManager.hasContexts("run-1")).thenReturn(false);

            assertFalse(handler.isSplitAggregate("run-1", "core:aggregate", 0, nodeMap));
        }
    }

    @Nested
    @DisplayName("handleAggregate")
    class HandleAggregate {

        @Test
        @DisplayName("should return empty aggregate result when no context found")
        void shouldReturnEmptyWhenNoContextFound() {
            Map<String, ExecutionNode> nodeMap = Map.of();
            ExecutionContext context = createContext(0);

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.empty());
            when(mockContextManager.getAllContexts("run-1")).thenReturn(Map.of());

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(false, result.output().get("split_aggregate"));
            assertEquals(0, result.output().get("aggregated_count"));
        }

        @Test
        @DisplayName("should aggregate results from BFS-found context")
        void shouldAggregateFromBfsContext() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            ExecutionContext context = createContext(0);

            SplitContext splitContext = SplitContext.create("core:split:0", List.of("item1", "item2"));
            splitContext = splitContext.withResults("mcp:step1", List.of(
                Map.of("key", "val1"),
                Map.of("key", "val2")
            ));

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(true, result.output().get("split_aggregate"));
            assertEquals(2, result.output().get("aggregated_count"));
            assertEquals(2, result.output().get("item_count"));

            // Verify context removal was called
            verify(mockContextManager).removeContext("run-1", "core:split", 0);
        }

        @Test
        @DisplayName("should fall back to any active context when BFS fails")
        void shouldFallbackToAnyActiveContext() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            ExecutionContext context = createContext(0);

            SplitContext splitContext = SplitContext.create("core:split:0", List.of("a", "b", "c"));

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.empty());
            when(mockContextManager.getAllContexts("run-1"))
                .thenReturn(Map.of("core:split:0", splitContext));

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(true, result.output().get("split_aggregate"));
            assertEquals(3, result.output().get("item_count"));
        }

        @Test
        @DisplayName("should include items and results in output")
        void shouldIncludeItemsAndResultsInOutput() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            ExecutionContext context = createContext(0);

            List<Object> items = List.of("x", "y");
            SplitContext splitContext = SplitContext.create("core:split:0", items);
            splitContext = splitContext.withResults("mcp:process", List.of("result_x", "result_y"));

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            @SuppressWarnings("unchecked")
            Map<String, Object> aggregatedResults = (Map<String, Object>) result.output().get("aggregated_results");
            assertNotNull(aggregatedResults);
            assertEquals(items, result.output().get("items"));

            // Should include results key with latest results
            assertNotNull(result.output().get("results"));
        }

        @Test
        @DisplayName("should set metadata with split_aggregate flag")
        void shouldSetMetadata() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            ExecutionContext context = createContext(0);

            SplitContext splitContext = SplitContext.create("core:split:0", List.of("a"));
            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            assertTrue((Boolean) result.metadata().get("split_aggregate"));
        }

        @Test
        @DisplayName("should use workflow item index from context")
        void shouldUseWorkflowItemIndex() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            ExecutionContext context = createContext(2);

            SplitContext splitContext = SplitContext.create("core:split:2", List.of("item"));
            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 2, nodeMap))
                .thenReturn(Optional.of(splitContext));

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 2, context, nodeMap);

            assertNotNull(result);
            assertEquals(2, result.output().get("item_index"));
        }

        @Test
        @DisplayName("should find context by item suffix in fallback")
        void shouldFindContextByItemSuffixInFallback() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            ExecutionContext context = createContext(1);

            SplitContext ctx0 = SplitContext.create("core:split:0", List.of("a"));
            SplitContext ctx1 = SplitContext.create("core:split:1", List.of("b"));

            Map<String, SplitContext> allContexts = new HashMap<>();
            allContexts.put("core:split:0", ctx0);
            allContexts.put("core:split:1", ctx1);

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 1, nodeMap))
                .thenReturn(Optional.empty());
            when(mockContextManager.getAllContexts("run-1")).thenReturn(allContexts);

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 1, context, nodeMap);

            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
        }
    }

    @Nested
    @DisplayName("configured field evaluation")
    class ConfiguredFieldEvaluation {

        @Test
        @DisplayName("should evaluate configured fields from AggregateNode")
        @SuppressWarnings("unchecked")
        void shouldEvaluateConfiguredFields() {
            // Build an AggregateNode with configured fields
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:aggregate")
                .templateEngine(null) // not used by SplitAggregateHandler
                .addField("image_url", "{{core:download.output.file}}")
                .addField("title", "{{core:split_items.output.current_item.name}}")
                .build();

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:aggregate", aggregateNode);

            ExecutionContext context = createContext(0);

            // Split context with 2 items, each with a download result
            List<Object> splitItems = List.of(
                Map.of("name", "Item A"),
                Map.of("name", "Item B")
            );
            SplitContext splitContext = SplitContext.create("core:split_items:0", splitItems);

            // Raw download results (before schema mapping)
            Map<String, Object> rawResult0 = new HashMap<>();
            rawResult0.put("node_type", "DOWNLOAD_FILE");
            rawResult0.put("file", "some_file_ref_0");

            Map<String, Object> rawResult1 = new HashMap<>();
            rawResult1.put("node_type", "DOWNLOAD_FILE");
            rawResult1.put("file", "some_file_ref_1");

            splitContext = splitContext.withResults("core:download", List.of(rawResult0, rawResult1));

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            // Schema mapper transforms raw results to documented fields
            Map<String, Object> mapped0 = Map.of("file", "/api/proxy/files/proxy?key=path0");
            Map<String, Object> mapped1 = Map.of("file", "/api/proxy/files/proxy?key=path1");
            when(mockOutputSchemaMapper.transformToDbSchema(rawResult0, "DOWNLOAD_FILE")).thenReturn(mapped0);
            when(mockOutputSchemaMapper.transformToDbSchema(rawResult1, "DOWNLOAD_FILE")).thenReturn(mapped1);

            // Template engine resolves expressions from the eval context
            // For item 0: {{core:download.output.file}} → /api/proxy/files/proxy?key=path0
            // For item 1: {{core:download.output.file}} → /api/proxy/files/proxy?key=path1
            when(mockTemplateEngine.resolveWithMap(eq("{{core:download.output.file}}"), any()))
                .thenReturn("/api/proxy/files/proxy?key=path0", "/api/proxy/files/proxy?key=path1");
            when(mockTemplateEngine.resolveWithMap(eq("{{core:split_items.output.current_item.name}}"), any()))
                .thenReturn("Item A", "Item B");

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            // Should contain configured field results
            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());

            List<Object> imageUrls = (List<Object>) result.output().get("image_url");
            assertNotNull(imageUrls, "image_url field should be present in output");
            assertEquals(2, imageUrls.size());
            assertEquals("/api/proxy/files/proxy?key=path0", imageUrls.get(0));
            assertEquals("/api/proxy/files/proxy?key=path1", imageUrls.get(1));

            List<Object> titles = (List<Object>) result.output().get("title");
            assertNotNull(titles, "title field should be present in output");
            assertEquals(2, titles.size());
            assertEquals("Item A", titles.get(0));
            assertEquals("Item B", titles.get(1));
        }

        @Test
        @DisplayName("should produce empty field lists when no AggregateNode in nodeMap")
        void shouldSkipFieldsWhenNotAggregateNode() {
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            // No AggregateNode in nodeMap for "core:aggregate"
            ExecutionContext context = createContext(0);

            SplitContext splitContext = SplitContext.create("core:split:0", List.of("a"));
            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            // No configured field keys should be present (only standard keys)
            assertNull(result.output().get("image_url"));
        }

        @Test
        @DisplayName("should handle AggregateNode with empty fields list")
        void shouldHandleEmptyFields() {
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:aggregate")
                .build();

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:aggregate", aggregateNode);

            ExecutionContext context = createContext(0);
            SplitContext splitContext = SplitContext.create("core:split:0", List.of("a"));
            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            assertNotNull(result);
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(1, result.output().get("aggregated_count"));
        }

        @Test
        @DisplayName("Regression: chained-downstream per-item slots (populated via withResultAtIndex) yield N distinct aggregate values, not the same one N times")
        @SuppressWarnings("unchecked")
        void shouldYieldDistinctValuesFromChainedDownstreamPerItemSlots() {
            // Production bug repro: workflow `split → read_email → clean_email → aggregate`.
            // Prod aggregate held 31 copies of one Gmail message id because clean_email's
            // per-item outputs were never recorded into the SplitContext. The producer-side fix
            // adds withResultAtIndex slot-by-slot writes - this test exercises the FULL
            // contract: SplitContext populated via withResultAtIndex (one call per item, the
            // way the executor now records chained-downstream results) → SplitAggregateHandler
            // → distinct aggregate values.
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:aggregate")
                .templateEngine(null)
                .addField("email_ids", "{{core:clean_email.output.id}}")
                .build();

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:aggregate", aggregateNode);

            ExecutionContext context = createContext(0);

            // Build the SplitContext the way the patched SplitAwareNodeExecutor builds it for
            // chained-downstream nodes: three sequential withResultAtIndex calls, one per item,
            // with DISTINCT values per item. Pre-fix, withResults batched all items at once
            // and per-item callers either clobbered each other or didn't write at all.
            SplitContext splitContext = SplitContext.create("core:split:0", List.of("a", "b", "c"));
            splitContext = splitContext.withResultAtIndex("core:clean_email", 0, 3, Map.of("id", "id-0"));
            splitContext = splitContext.withResultAtIndex("core:clean_email", 1, 3, Map.of("id", "id-1"));
            splitContext = splitContext.withResultAtIndex("core:clean_email", 2, 3, Map.of("id", "id-2"));

            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            // No schema mapping on these results (no node_type key) - handler returns the raw map.
            // Template engine resolves {{core:clean_email.output.id}} per-item from the eval context.
            when(mockTemplateEngine.resolveWithMap(eq("{{core:clean_email.output.id}}"), any()))
                .thenReturn("id-0", "id-1", "id-2");

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(3, result.output().get("aggregated_count"));

            List<Object> ids = (List<Object>) result.output().get("email_ids");
            assertNotNull(ids, "email_ids field must be populated from per-item slots");
            assertEquals(3, ids.size());
            // The bug: pre-fix, all three would equal "id-0" (or whichever item happened to
            // win the race). Post-fix, every slot must be distinct - that IS the bug-free contract.
            assertEquals(List.of("id-0", "id-1", "id-2"), ids);
        }

        @Test
        @DisplayName("should handle expression evaluation failure gracefully")
        void shouldHandleExpressionFailure() {
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:aggregate")
                .addField("broken", "{{invalid.expression}}")
                .build();

            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:aggregate", aggregateNode);

            ExecutionContext context = createContext(0);
            SplitContext splitContext = SplitContext.create("core:split:0", List.of("item1"));
            when(mockContextManager.findActiveContext("run-1", "core:aggregate", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));

            when(mockTemplateEngine.resolveWithMap(eq("{{invalid.expression}}"), any()))
                .thenThrow(new RuntimeException("Cannot resolve"));

            NodeExecutionResult result = handler.handleAggregate("run-1", "core:aggregate", 0, context, nodeMap);

            assertNotNull(result);
            @SuppressWarnings("unchecked")
            List<Object> brokenField = (List<Object>) result.output().get("broken");
            assertNotNull(brokenField);
            assertEquals(1, brokenField.size());
            assertNull(brokenField.get(0), "Failed expression should produce null");
        }
    }

    /**
     * Regression for the 2026-05-14 prod bug surfaced on run
     * {@code run_<id>} (Gmail Auto-Labeler workflow):
     * an aggregate sitting behind {@code classify:category_6 → apply_urgent
     * → record_urgent → collect_urgents} fired COMPLETED with N urgent_lines
     * entries even when ZERO emails classified as urgent - and with mixed
     * urgent/non-urgent input it produced one entry per split item
     * (regardless of which port the item routed through), so the Telegram
     * message contained 5+ "urgent" lines for emails that were actually
     * categorized as newsletters/finance/etc.
     *
     * <p>Post-fix contract enforced by these tests:
     * <ol>
     *   <li>0 items routed → return SKIPPED with cascade flag so downstream
     *       linear successors (build_urgent_msg, send_urgent_telegram) skip
     *       too.</li>
     *   <li>K of N items routed → return COMPLETED with field entries of
     *       size K (NOT N).</li>
     *   <li>All N items routed → return COMPLETED with field entries of
     *       size N - same as legacy, no regression on the happy path.</li>
     *   <li>Multi-predecessor (disjoint branches feeding into aggregate via
     *       union semantic): items from EITHER branch contribute, no
     *       intersection-shaped under-count.</li>
     * </ol>
     */
    @Nested
    @DisplayName("Routed-item filtering - prod bug 2026-05-14")
    class RoutedItemFiltering {

        private SplitAwareNodeExecutor mockSplitAwareNodeExecutor;
        private SplitAggregateHandler routedHandler;

        @BeforeEach
        void initRoutedHandler() {
            // Real handler with a STUBBED SplitAwareNodeExecutor so we can
            // control the routed-indices answer the DB-backed helper would
            // normally produce. Use a fresh handler/mock per test (not the
            // class-level one which passes null).
            mockSplitAwareNodeExecutor = mock(SplitAwareNodeExecutor.class);
            routedHandler = new SplitAggregateHandler(
                mockContextManager, mockTemplateEngine, mockOutputSchemaMapper,
                mockSplitAwareNodeExecutor, null);
        }

        @Test
        @DisplayName("0 items routed → SKIPPED with cascade flag (the prod bug)")
        void zeroRoutedReturnsSkippedWithCascadeFlag() {
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:collect_urgents")
                .templateEngine(null)
                .addField("urgent_lines", "🚨 {{core:parse_headers.output.from}}")
                .build();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:collect_urgents", aggregateNode);

            ExecutionContext context = createContext(0);
            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("email1", "email2", "email3", "email4", "email5"));
            when(mockContextManager.findActiveContext("run-1", "core:collect_urgents", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            // Prod scenario: all 5 emails classified as non-urgent → 0 items
            // routed through record_urgent → collect_urgents path.
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(5), eq(0), eq("core:each_email:0")))
                .thenReturn(java.util.Set.of());

            NodeExecutionResult result = routedHandler.handleAggregate(
                "run-1", "core:collect_urgents", 0, context, nodeMap);

            assertEquals(NodeStatus.SKIPPED, result.status(),
                "0 routed items must SKIP (prod bug: was COMPLETED)");
            assertEquals(Boolean.TRUE, result.metadata().get(
                com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS),
                "cascade flag must be set so downstream successors are skipped too");
            // Context closed even on skipped path so downstream sees no
            // lingering split scope.
            verify(mockContextManager).removeContext("run-1", "core:each_email", 0);
        }

        @Test
        @DisplayName("1 of 5 routed → COMPLETED with field entries of size 1, not 5")
        @SuppressWarnings("unchecked")
        void partialRoutingProducesFilteredFieldCount() {
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:collect_urgents")
                .templateEngine(null)
                .addField("urgent_subjects", "{{core:parse_headers.output.subject}}")
                .build();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:collect_urgents", aggregateNode);

            ExecutionContext context = createContext(0);
            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("e1", "e2", "e3", "e4", "e5"));
            // Sparse parse_headers results: all 5 ran (parse_headers is BEFORE
            // classify), each with its own subject.
            List<Object> parseResults = List.of(
                Map.of("subject", "Newsletter A"),
                Map.of("subject", "Bank Statement"),
                Map.of("subject", "URGENT: server down"),
                Map.of("subject", "Promo offer"),
                Map.of("subject", "Tech news")
            );
            splitContext = splitContext.withResults("core:parse_headers", parseResults);
            when(mockContextManager.findActiveContext("run-1", "core:collect_urgents", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            // Only item 2 (index) classified as urgent.
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(5), eq(0), eq("core:each_email:0")))
                .thenReturn(java.util.Set.of(2));
            when(mockTemplateEngine.resolveWithMap(anyString(), any()))
                .thenAnswer(inv -> {
                    String expr = inv.getArgument(0);
                    java.util.Map<String, Object> ctx = inv.getArgument(1);
                    // Re-evaluate by looking at the item's parse_headers row in
                    // the eval context - the handler flattens parse_headers
                    // output into the context.
                    Object ph = ctx.get("core:parse_headers");
                    if (ph instanceof java.util.Map<?, ?> phMap
                            && phMap.get("output") instanceof java.util.Map<?, ?> out
                            && out.get("subject") instanceof String s) {
                        return s;
                    }
                    return null;
                });

            NodeExecutionResult result = routedHandler.handleAggregate(
                "run-1", "core:collect_urgents", 0, context, nodeMap);

            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(1, result.output().get("aggregated_count"),
                "aggregated_count must reflect routed count, not total split count");
            assertEquals(5, result.output().get("total_items"),
                "total_items preserves the split's original size for observability");
            List<Object> subjects = (List<Object>) result.output().get("urgent_subjects");
            assertNotNull(subjects);
            assertEquals(1, subjects.size(),
                "exactly 1 routed item → 1 field entry (prod bug: was 5)");
            assertEquals("URGENT: server down", subjects.get(0));
        }

        @Test
        @DisplayName("All N routed → no regression on the happy path")
        @SuppressWarnings("unchecked")
        void fullRoutingPreservesLegacyBehavior() {
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:agg")
                .templateEngine(null)
                .addField("ids", "{{core:upstream.output.id}}")
                .build();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:agg", aggregateNode);

            ExecutionContext context = createContext(0);
            SplitContext splitContext = SplitContext.create(
                "core:split:0", List.of("a", "b", "c"));
            splitContext = splitContext.withResults("core:upstream", List.of(
                Map.of("id", "x1"), Map.of("id", "x2"), Map.of("id", "x3")));
            when(mockContextManager.findActiveContext("run-1", "core:agg", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(3), eq(0), eq("core:split:0")))
                .thenReturn(java.util.Set.of(0, 1, 2));
            when(mockTemplateEngine.resolveWithMap(anyString(), any()))
                .thenAnswer(inv -> {
                    java.util.Map<String, Object> ctx = inv.getArgument(1);
                    Object up = ctx.get("core:upstream");
                    if (up instanceof java.util.Map<?, ?> upMap
                            && upMap.get("output") instanceof java.util.Map<?, ?> out) {
                        return out.get("id");
                    }
                    return null;
                });

            NodeExecutionResult result = routedHandler.handleAggregate(
                "run-1", "core:agg", 0, context, nodeMap);

            assertEquals(NodeStatus.COMPLETED, result.status());
            assertEquals(3, result.output().get("aggregated_count"));
            assertEquals(3, result.output().get("total_items"));
            List<Object> ids = (List<Object>) result.output().get("ids");
            assertEquals(List.of("x1", "x2", "x3"), ids,
                "fully-routed aggregate yields the same N entries as pre-fix");
        }

        @Test
        @DisplayName("aggregated_results.<upstream> is also filtered (audit M1 - shape coherence)")
        @SuppressWarnings("unchecked")
        void aggregatedResultsSharesFilteredShape() {
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:agg")
                .templateEngine(null)
                .build();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:agg", aggregateNode);

            ExecutionContext context = createContext(0);
            SplitContext splitContext = SplitContext.create(
                "core:split:0", List.of("a", "b", "c", "d"));
            splitContext = splitContext.withResults("core:upstream", List.of(
                Map.of("v", 1), Map.of("v", 2), Map.of("v", 3), Map.of("v", 4)));
            when(mockContextManager.findActiveContext("run-1", "core:agg", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            // Only items 1 and 3 routed (disjoint subset).
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(4), eq(0), eq("core:split:0")))
                .thenReturn(java.util.Set.of(1, 3));

            NodeExecutionResult result = routedHandler.handleAggregate(
                "run-1", "core:agg", 0, context, nodeMap);

            assertEquals(NodeStatus.COMPLETED, result.status());
            Map<String, Object> aggregated =
                (Map<String, Object>) result.output().get("aggregated_results");
            List<Object> upstreamFiltered = (List<Object>) aggregated.get("core:upstream");
            assertEquals(2, upstreamFiltered.size(),
                "aggregated_results.<upstream> must filter to routed indices, not iterate all 4");
            assertEquals(Map.of("v", 2), upstreamFiltered.get(0));
            assertEquals(Map.of("v", 4), upstreamFiltered.get(1));
        }
    }

    /**
     * Regression for the 2026-06-05 prod bug on workflow
     * {@code 7320dda4-…} (Gmail digest → Telegram): the urgent-email Telegram
     * message arrived as "🚨 EMAILS URGENTS (1)" with BLANK sender/subject/snippet
     * lines, intermittently (epoch 329/331 empty, 328/330 fine - same data shape).
     *
     * <p>Root cause: {@code collect_urgents} resolves
     * {@code {{core:parse_headers.output.transformed.subject}}} from the in-memory
     * {@code SplitContext.resultsByNode}. With the agent classify node dispatched
     * async to a Redis worker queue ({@code SCALING_BACKEND=redis}) and the
     * orchestrator running 2 replicas, the post-agent continuation (incl. the
     * aggregate) resumes on a DIFFERENT pod whose in-memory SplitContext was rebuilt
     * with the split items + the agent's own batch but WITHOUT the pre-agent per-item
     * outputs (parse_headers / get_content) - those live only in the origin pod's RAM.
     * {@code buildItemEvalContext} then silently dropped the node ({@code continue} on
     * a missing slot) → empty field. The split→aggregate per-item resolution is also
     * lost after a restart (the same in-memory cache).
     *
     * <p>Fix: {@code resolvePerItemResults} backfills any routed slot missing from the
     * in-memory SplitContext from the DURABLE step-output store (the only cross-pod /
     * restart-safe source) - mirroring the already-DB-backed routed-index resolution.
     * These tests pin: backfill recovers the field; the warm path performs NO durable
     * read; and an empty durable store degrades gracefully (null, no throw).
     */
    @Nested
    @DisplayName("Durable per-item fallback - prod bug 2026-06-05 (split→aggregate cross-pod)")
    class DurablePerItemFallback {

        @Mock private StepOutputService mockStepOutputService;
        private SplitAwareNodeExecutor mockSplitAwareNodeExecutor;
        private SplitAggregateHandler durableHandler;

        @BeforeEach
        void initDurableHandler() {
            mockSplitAwareNodeExecutor = mock(SplitAwareNodeExecutor.class);
            durableHandler = new SplitAggregateHandler(
                mockContextManager, mockTemplateEngine, mockOutputSchemaMapper,
                mockSplitAwareNodeExecutor, mockStepOutputService);
        }

        /** Reads {@code ctx.get(nodeId).output.<field>} as a String, or "" if absent. */
        @SuppressWarnings("unchecked")
        private String nested(Map<String, Object> ctx, String nodeId, String field) {
            Object n = ctx.get(nodeId);
            if (n instanceof Map<?, ?> nm && nm.get("output") instanceof Map<?, ?> out
                    && out.get(field) instanceof String s) {
                return s;
            }
            return "";
        }

        /** Template mock that reads {@code core:parse_headers.output.subject} from the eval context. */
        private void stubTemplateReadsParseHeadersSubject() {
            when(mockTemplateEngine.resolveWithMap(anyString(), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> ctx = inv.getArgument(1);
                    Object ph = ctx.get("core:parse_headers");
                    if (ph instanceof Map<?, ?> phMap
                            && phMap.get("output") instanceof Map<?, ?> out
                            && out.get("subject") instanceof String s) {
                        return s;
                    }
                    return null;
                });
        }

        private AggregateNode urgentSubjectsNode() {
            return AggregateNode.builder()
                .nodeId("core:collect_urgents")
                .templateEngine(null)
                .addField("urgent_subjects", "{{core:parse_headers.output.subject}}")
                .build();
        }

        @Test
        @DisplayName("routed item's predecessor output ENTIRELY absent in-memory (cross-pod resume) → recovered from durable store")
        @SuppressWarnings("unchecked")
        void absentInMemorySlotRecoveredFromDurableStore() {
            AggregateNode aggregateNode = urgentSubjectsNode();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:collect_urgents", aggregateNode);

            ExecutionContext context = createContext(0); // run-1, tenant-1, epoch 0

            // Cross-pod resume: the resumed pod's SplitContext has the items but NO
            // parse_headers results (recorded only in the origin pod's RAM).
            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("e1", "e2"));
            when(mockContextManager.findActiveContext("run-1", "core:collect_urgents", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            // Item 1 (index) is the urgent one routed to collect_urgents.
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(2), eq(0), eq("core:each_email:0")))
                .thenReturn(java.util.Set.of(1));
            // Durable store HAS the per-item output the in-memory cache lost.
            when(mockStepOutputService.loadPerItemNodeOutputs(
                    "run-1", "core:parse_headers", 0, "tenant-1"))
                .thenReturn(Map.of(1, Map.of("subject", "URGENT: server down")));
            stubTemplateReadsParseHeadersSubject();

            NodeExecutionResult result = durableHandler.handleAggregate(
                "run-1", "core:collect_urgents", 0, context, nodeMap);

            assertEquals(NodeStatus.COMPLETED, result.status());
            List<Object> subjects = (List<Object>) result.output().get("urgent_subjects");
            assertNotNull(subjects);
            assertEquals(1, subjects.size());
            // Pre-fix: in-memory had no parse_headers slot → eval context lacked it →
            // template returned null → "🚨 EMAILS URGENTS (1)" with a blank 📌 line.
            // Post-fix: backfilled from the durable store.
            assertEquals("URGENT: server down", subjects.get(0),
                "routed item's subject must be recovered from the durable store, not blank");
        }

        @Test
        @DisplayName("routed slot NULL in-memory (partial cache) → backfilled from durable store")
        @SuppressWarnings("unchecked")
        void nullRoutedSlotBackfilledFromDurableStore() {
            AggregateNode aggregateNode = urgentSubjectsNode();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:collect_urgents", aggregateNode);

            ExecutionContext context = createContext(0);

            // parse_headers present for item 0 but NULL at item 1 (the routed/urgent one).
            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("e1", "e2"));
            splitContext = splitContext.withResults("core:parse_headers",
                java.util.Arrays.asList(Map.of("subject", "Newsletter"), null));
            when(mockContextManager.findActiveContext("run-1", "core:collect_urgents", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(2), eq(0), eq("core:each_email:0")))
                .thenReturn(java.util.Set.of(1));
            when(mockStepOutputService.loadPerItemNodeOutputs(
                    "run-1", "core:parse_headers", 0, "tenant-1"))
                .thenReturn(Map.of(1, Map.of("subject", "URGENT: disk full")));
            stubTemplateReadsParseHeadersSubject();

            NodeExecutionResult result = durableHandler.handleAggregate(
                "run-1", "core:collect_urgents", 0, context, nodeMap);

            List<Object> subjects = (List<Object>) result.output().get("urgent_subjects");
            assertEquals(1, subjects.size());
            assertEquals("URGENT: disk full", subjects.get(0));
        }

        @Test
        @DisplayName("warm path - routed slot already present in-memory → NO durable read (no-op)")
        @SuppressWarnings("unchecked")
        void warmPathPerformsNoDurableRead() {
            AggregateNode aggregateNode = urgentSubjectsNode();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:collect_urgents", aggregateNode);

            ExecutionContext context = createContext(0);

            // In-memory cache complete for the routed item.
            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("e1", "e2"));
            splitContext = splitContext.withResults("core:parse_headers", List.of(
                Map.of("subject", "Newsletter"), Map.of("subject", "URGENT: in-memory")));
            when(mockContextManager.findActiveContext("run-1", "core:collect_urgents", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(2), eq(0), eq("core:each_email:0")))
                .thenReturn(java.util.Set.of(1));
            stubTemplateReadsParseHeadersSubject();

            NodeExecutionResult result = durableHandler.handleAggregate(
                "run-1", "core:collect_urgents", 0, context, nodeMap);

            List<Object> subjects = (List<Object>) result.output().get("urgent_subjects");
            assertEquals("URGENT: in-memory", subjects.get(0),
                "warm path resolves from the in-memory cache unchanged");
            // The durable store must NOT be touched when the cache is warm (perf + safety).
            verifyNoInteractions(mockStepOutputService);
        }

        @Test
        @DisplayName("multiple absent upstream nodes referenced in ONE expression (parse_headers + get_content) both backfilled")
        @SuppressWarnings("unchecked")
        void multipleAbsentUpstreamNodesInOneExpressionAllBackfilled() {
            // The real prod line: "🚨 {{...from}}\n💬 {{...snippet}}" reads TWO different
            // upstream nodes (one core:, one mcp:) in a single expression - both were lost
            // cross-pod. Exercises the NODE_REF while-loop (>1 ref/expr) + multi-node ensure.
            AggregateNode aggregateNode = AggregateNode.builder()
                .nodeId("core:collect_urgents")
                .templateEngine(null)
                .addField("urgent_lines",
                    "🚨 {{core:parse_headers.output.from}}\n💬 {{mcp:get_content.output.snippet}}")
                .build();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:collect_urgents", aggregateNode);

            ExecutionContext context = createContext(0);
            // Cross-pod resume: neither pre-agent node survived in the resumed pod's cache.
            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("e1", "e2"));
            when(mockContextManager.findActiveContext("run-1", "core:collect_urgents", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(2), eq(0), eq("core:each_email:0")))
                .thenReturn(java.util.Set.of(1));
            when(mockStepOutputService.loadPerItemNodeOutputs(
                    "run-1", "core:parse_headers", 0, "tenant-1"))
                .thenReturn(Map.of(1, Map.of("from", "Facebook <security@facebookmail.com>")));
            when(mockStepOutputService.loadPerItemNodeOutputs(
                    "run-1", "mcp:get_content", 0, "tenant-1"))
                .thenReturn(Map.of(1, Map.of("snippet", "Did you just sign in near Rosny-sous-Bois?")));
            // Template combines both nodes' fields from the eval context.
            when(mockTemplateEngine.resolveWithMap(anyString(), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> ctx = inv.getArgument(1);
                    String from = nested(ctx, "core:parse_headers", "from");
                    String snippet = nested(ctx, "mcp:get_content", "snippet");
                    return "🚨 " + from + "\n💬 " + snippet;
                });

            NodeExecutionResult result = durableHandler.handleAggregate(
                "run-1", "core:collect_urgents", 0, context, nodeMap);

            List<Object> lines = (List<Object>) result.output().get("urgent_lines");
            assertEquals(1, lines.size());
            assertEquals(
                "🚨 Facebook <security@facebookmail.com>\n💬 Did you just sign in near Rosny-sous-Bois?",
                lines.get(0),
                "both upstream nodes referenced in one expression must be recovered from the durable store");
        }

        @Test
        @DisplayName("aggregated_results.<node> is also backfilled (not just configured fields)")
        @SuppressWarnings("unchecked")
        void aggregatedResultsAlsoBackfilled() {
            AggregateNode aggregateNode = urgentSubjectsNode();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:collect_urgents", aggregateNode);

            ExecutionContext context = createContext(0);
            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("e1", "e2"));
            when(mockContextManager.findActiveContext("run-1", "core:collect_urgents", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(2), eq(0), eq("core:each_email:0")))
                .thenReturn(java.util.Set.of(1));
            when(mockStepOutputService.loadPerItemNodeOutputs(
                    "run-1", "core:parse_headers", 0, "tenant-1"))
                .thenReturn(Map.of(1, Map.of("subject", "URGENT: server down")));
            stubTemplateReadsParseHeadersSubject();

            NodeExecutionResult result = durableHandler.handleAggregate(
                "run-1", "core:collect_urgents", 0, context, nodeMap);

            Map<String, Object> aggregated =
                (Map<String, Object>) result.output().get("aggregated_results");
            List<Object> phFiltered = (List<Object>) aggregated.get("core:parse_headers");
            assertNotNull(phFiltered, "aggregated_results must include the durably-recovered node");
            assertEquals(1, phFiltered.size());
            assertEquals(Map.of("subject", "URGENT: server down"), phFiltered.get(0),
                "aggregated_results.<node> reads the backfilled per-item output, not blank");
        }

        @Test
        @DisplayName("durable store empty too → graceful null (no throw, no worse than pre-fix)")
        @SuppressWarnings("unchecked")
        void emptyDurableStoreDegradesGracefully() {
            AggregateNode aggregateNode = urgentSubjectsNode();
            Map<String, ExecutionNode> nodeMap = new HashMap<>();
            nodeMap.put("core:collect_urgents", aggregateNode);

            ExecutionContext context = createContext(0);

            SplitContext splitContext = SplitContext.create(
                "core:each_email:0", List.of("e1", "e2"));
            when(mockContextManager.findActiveContext("run-1", "core:collect_urgents", 0, nodeMap))
                .thenReturn(Optional.of(splitContext));
            when(mockSplitAwareNodeExecutor.resolveRoutedItemIndices(
                    eq(aggregateNode), eq("run-1"), eq(2), eq(0), eq("core:each_email:0")))
                .thenReturn(java.util.Set.of(1));
            when(mockStepOutputService.loadPerItemNodeOutputs(
                    "run-1", "core:parse_headers", 0, "tenant-1"))
                .thenReturn(Map.of()); // nothing durable either
            stubTemplateReadsParseHeadersSubject();

            NodeExecutionResult result = durableHandler.handleAggregate(
                "run-1", "core:collect_urgents", 0, context, nodeMap);

            assertEquals(NodeStatus.COMPLETED, result.status());
            List<Object> subjects = (List<Object>) result.output().get("urgent_subjects");
            assertEquals(1, subjects.size());
            assertNull(subjects.get(0), "no durable data → null (graceful), not an exception");
        }
    }
}
