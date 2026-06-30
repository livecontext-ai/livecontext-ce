package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContext;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SignalContextResolver.
 * Note: SignalContextResolver is package-private, so these tests
 * must be in the same package.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SignalContextResolver")
class SignalContextResolverTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private V2TemplateAdapter mockAdapter;

    private ExecutionContext createContext(Map<String, Object> globalData) {
        ExecutionState state = ExecutionState.create();
        for (Map.Entry<String, Object> entry : globalData.entrySet()) {
            state = state.withGlobalData(entry.getKey(), entry.getValue());
        }
        return new ExecutionContext("run-1", "wr-1", "tenant-1", "item-0", 0,
            null, 0, 0,  // triggerId, epoch, spawn
            Map.of(), Map.of(), state, mockPlan);
    }

    @Nested
    @DisplayName("resolveDagTriggerId")
    class ResolveDagTriggerId {

        @Test
        @DisplayName("should return explicit field value when set")
        void shouldReturnExplicitFieldValue() {
            ExecutionContext context = createContext(Map.of());
            String result = SignalContextResolver.resolveDagTriggerId("node-1", "trigger:explicit", context);
            assertEquals("trigger:explicit", result);
        }

        @Test
        @DisplayName("should return from global data when field is null")
        void shouldReturnFromGlobalData() {
            ExecutionContext context = createContext(Map.of("dagTriggerId", "trigger:from_global"));
            String result = SignalContextResolver.resolveDagTriggerId("node-1", null, context);
            assertEquals("trigger:from_global", result);
        }

        @Test
        @DisplayName("should return context triggerId when field is null")
        void shouldReturnContextTriggerId() {
            // Create context with explicit triggerId set
            ExecutionState state = ExecutionState.create();
            ExecutionContext context = new ExecutionContext("run-1", "wr-1", "tenant-1", "item-0", 0,
                "trigger:webhook", 0, 0,  // triggerId set
                Map.of(), Map.of(), state, mockPlan);
            String result = SignalContextResolver.resolveDagTriggerId("node-1", null, context);
            assertEquals("trigger:webhook", result);
        }

        @Test
        @DisplayName("should return null for null plan")
        void shouldReturnNullForNullPlan() {
            ExecutionContext context = new ExecutionContext("run-1", "wr-1", "t-1", "item-0", 0,
                null, 0, 0,  // triggerId, epoch, spawn
                Map.of(), Map.of(), ExecutionState.create(), null);
            String result = SignalContextResolver.resolveDagTriggerId("node-1", null, context);
            assertNull(result);
        }

        @Test
        @DisplayName("should return null when no triggerId available anywhere")
        void shouldReturnNullWhenNoTriggerId() {
            ExecutionContext context = createContext(Map.of());
            String result = SignalContextResolver.resolveDagTriggerId("node-1", null, context);
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("resolveEpoch")
    class ResolveEpoch {

        @Test
        @DisplayName("should return explicit field value when non-zero")
        void shouldReturnExplicitFieldValue() {
            ExecutionContext context = createContext(Map.of());
            int result = SignalContextResolver.resolveEpoch(5, context);
            assertEquals(5, result);
        }

        @Test
        @DisplayName("should return from global data when field is zero")
        void shouldReturnFromGlobalData() {
            ExecutionContext context = createContext(Map.of("epoch", 3));
            int result = SignalContextResolver.resolveEpoch(0, context);
            assertEquals(3, result);
        }

        @Test
        @DisplayName("should return 0 when nothing is set")
        void shouldReturnZeroDefault() {
            ExecutionContext context = createContext(Map.of());
            int result = SignalContextResolver.resolveEpoch(0, context);
            assertEquals(0, result);
        }
    }

    /**
     * Regression for the multi-pod (replicas>=2) split->approval / split->wait fan-out bug:
     * the per-item signal's persisted {@code split_item_data} carried only display fields
     * (current_item / current_index), NOT the {@code splitNodeId} + {@code items} that
     * {@link SplitContextManager#restoreContext} needs. On a single pod the in-memory
     * SplitContext masked it; on a different pod (cross-instance signal resume) restoreContext
     * bailed ("Missing splitNodeId or items") and the downstream node (e.g. send_email) lost
     * its split scope -> ran ONCE instead of N.
     */
    @Nested
    @DisplayName("buildSplitItemData - cross-pod split restoration")
    class BuildSplitItemData {

        private ExecutionContext splitContext(Object item, int index, String scopedSplitId, List<Object> items) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("item", item);
            g.put("index", index);
            if (scopedSplitId != null) g.put("current_split_id", scopedSplitId);
            if (items != null) g.put("items", items);
            return createContext(g);
        }

        private int asInt(Object o) {
            return ((Number) o).intValue();
        }

        @Test
        @DisplayName("inside a split: persists splitNodeId (base id) + items + indices AND keeps the display fields")
        void persistsRestorationFields() {
            List<Object> items = List.of(Map.of("name", "Alice"), Map.of("name", "Bob"), Map.of("name", "Charlie"));
            ExecutionContext ctx = splitContext(Map.of("name", "Alice"), 0, "core:split_items:0", items);

            Map<String, Object> data = SignalContextResolver.buildSplitItemData(ctx);

            assertNotNull(data);
            assertEquals(Map.of("name", "Alice"), data.get("current_item"));
            assertEquals(0, asInt(data.get("current_index")));
            assertEquals("core:split_items", data.get("splitNodeId"));
            assertEquals(items, data.get("items"));
            assertEquals(0, asInt(data.get("itemIndex")));
            assertEquals(0, asInt(data.get("workflowItemIndex")));
        }

        @Test
        @DisplayName("REGRESSION: the persisted blob rehydrates the split scope on a FRESH pod via restoreContext (pre-fix this failed)")
        void outputRehydratesSplitContextOnAnotherPod() {
            List<Object> items = List.of("a", "b", "c");
            ExecutionContext ctx = splitContext("b", 1, "core:split_items:0", items);

            Map<String, Object> persisted = SignalContextResolver.buildSplitItemData(ctx);

            // Simulate pod B: a brand-new SplitContextManager with NO in-memory context,
            // exactly like the replica that resolves a cross-instance signal.
            SplitContextManager podB = new SplitContextManager();
            assertFalse(podB.hasContexts("run-1"), "pod B starts with no in-memory split context");

            podB.restoreContext("run-1", "core:review_item", persisted);

            Optional<SplitContext> restored = podB.getContext("run-1", "core:split_items", 0);
            assertTrue(restored.isPresent(),
                "restoreContext must rebuild the split scope from the persisted blob; pre-fix it bailed and send_email ran once instead of N");
            assertEquals(3, restored.get().itemCount());
        }

        @Test
        @DisplayName("workflowItemIndex parsed from a NESTED scoped key (.../sN suffix stripped)")
        void parsesWorkflowItemIndexFromNestedKey() {
            ExecutionContext ctx = splitContext("z", 0, "core:inner_loop:2/s1", List.of("z"));

            Map<String, Object> data = SignalContextResolver.buildSplitItemData(ctx);

            assertEquals("core:inner_loop", data.get("splitNodeId"));
            assertEquals(2, asInt(data.get("workflowItemIndex")));
        }

        @Test
        @DisplayName("outside a split: returns null (non-split signals carry no split_item_data, unchanged)")
        void noSplitReturnsNull() {
            assertNull(SignalContextResolver.buildSplitItemData(createContext(Map.of())));
        }

        @Test
        @DisplayName("PARITY: toDisplayItemContext strips EXACTLY the keys buildSplitItemData adds, leaving only display fields (denylist stays complete)")
        void displayProjectionStripsAllRestorationKeys() {
            Map<String, Object> produced = SignalContextResolver.buildSplitItemData(
                splitContext(Map.of("name", "Alice"), 0, "core:split_items:0", List.of("a", "b", "c")));

            // The producer added the restoration keys...
            assertTrue(produced.keySet().containsAll(SplitContextManager.RESTORATION_KEYS),
                "buildSplitItemData must persist every restoration key");
            // ...and the display projection must remove ALL of them. If a future restoration key
            // is added to buildSplitItemData but not to RESTORATION_KEYS, this fails (re-opening
            // the UI preview / payload leak).
            Map<String, Object> display = SplitContextManager.toDisplayItemContext(produced);
            assertEquals(java.util.Set.of("current_item", "current_index"), display.keySet());
        }

        @Test
        @DisplayName("item present but no current_split_id: keeps display fields, adds no restoration fields (backward-safe)")
        void itemPresentButNoSplitId() {
            ExecutionContext ctx = splitContext("x", 0, null, null);

            Map<String, Object> data = SignalContextResolver.buildSplitItemData(ctx);

            assertNotNull(data);
            assertEquals("x", data.get("current_item"));
            assertFalse(data.containsKey("splitNodeId"), "no split id -> no restoration fields");
            assertFalse(data.containsKey("items"));
        }
    }

    @Nested
    @DisplayName("resolveApprovalContext")
    class ResolveApprovalContext {

        @Test
        @DisplayName("resolves a literal + {{...}} template to the rendered display string")
        void resolvesTemplate() {
            ExecutionContext ctx = createContext(Map.of());
            when(mockAdapter.evaluateTemplate("Approve {{x}}?", ctx)).thenReturn("Approve 120 EUR?");
            assertEquals("Approve 120 EUR?",
                SignalContextResolver.resolveApprovalContext("Approve {{x}}?", ctx, mockAdapter));
        }

        @Test
        @DisplayName("blank or null template -> null and the adapter is never invoked")
        void blankReturnsNull() {
            ExecutionContext ctx = createContext(Map.of());
            assertNull(SignalContextResolver.resolveApprovalContext(null, ctx, mockAdapter));
            assertNull(SignalContextResolver.resolveApprovalContext("   ", ctx, mockAdapter));
            verifyNoInteractions(mockAdapter);
        }

        @Test
        @DisplayName("null adapter -> null (no template engine available, e.g. not injected)")
        void nullAdapterReturnsNull() {
            ExecutionContext ctx = createContext(Map.of());
            assertNull(SignalContextResolver.resolveApprovalContext("{{x}}", ctx, null));
        }

        @Test
        @DisplayName("adapter resolving to null or blank -> null (nothing meaningful to show)")
        void emptyResolutionReturnsNull() {
            ExecutionContext ctx = createContext(Map.of());
            when(mockAdapter.evaluateTemplate("{{missing}}", ctx)).thenReturn(null);
            assertNull(SignalContextResolver.resolveApprovalContext("{{missing}}", ctx, mockAdapter));
            when(mockAdapter.evaluateTemplate("{{blank}}", ctx)).thenReturn("   ");
            assertNull(SignalContextResolver.resolveApprovalContext("{{blank}}", ctx, mockAdapter));
        }

        @Test
        @DisplayName("SOFT-REQUIRED: a malformed template that throws NEVER fails the node (returns null)")
        void malformedTemplateNeverThrows() {
            ExecutionContext ctx = createContext(Map.of());
            when(mockAdapter.evaluateTemplate("{{bad(", ctx)).thenThrow(new RuntimeException("parse error"));
            assertNull(SignalContextResolver.resolveApprovalContext("{{bad(", ctx, mockAdapter));
        }

        @Test
        @DisplayName("caps the resolved text at MAX_ITEM_CONTEXT_JSON_CHARS")
        void capsLongText() {
            ExecutionContext ctx = createContext(Map.of());
            String huge = "x".repeat(SignalContextResolver.MAX_ITEM_CONTEXT_JSON_CHARS + 500);
            when(mockAdapter.evaluateTemplate("{{x}}", ctx)).thenReturn(huge);
            String result = SignalContextResolver.resolveApprovalContext("{{x}}", ctx, mockAdapter);
            assertEquals(SignalContextResolver.MAX_ITEM_CONTEXT_JSON_CHARS, result.length());
        }

        @Test
        @DisplayName("a pure expression resolving to a non-string value is stringified")
        void stringifiesNonString() {
            ExecutionContext ctx = createContext(Map.of());
            when(mockAdapter.evaluateTemplate("{{amount}}", ctx)).thenReturn(120);
            assertEquals("120", SignalContextResolver.resolveApprovalContext("{{amount}}", ctx, mockAdapter));
        }
    }
}
