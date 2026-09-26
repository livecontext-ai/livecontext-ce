package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.template.ReportedParams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Numeric settings a plan wrote as {@code {{...}}} templates are resolved when the node runs.
 *
 * <p>The plan parser cannot hold a template in a number, so it sets it aside; before these
 * nodes read it back, each ran on a silent default: a wait of 0 ms, a loop capped at 10, a split
 * of 100, the shared HTTP client's timeout, one approval in 24 h, a table read of 500 rows.
 */
@DisplayName("Templated numeric settings resolved at run time")
class DeferredScalarRuntimeTest {

    private static final String REF = "{{core:cfg.output.n}}";

    private final ExecutionContext context = ExecutionContext.create(
        "run-1", "wr-1", "tenant-1", "item-1", 0, Map.of(), mock(WorkflowPlan.class));

    private static V2TemplateAdapter adapterResolving(Object value) {
        V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
        Map<String, Object> values = new HashMap<>();
        values.put(REF, value);
        when(adapter.resolveTemplates(anyMap(), any())).thenAnswer(TemplateResolutionStubs.resolving(values));
        return adapter;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(NodeExecutionResult result) {
        return (Map<String, Object>) result.output().get("resolved_params");
    }

    @Nested
    @DisplayName("wait.duration")
    class WaitDuration {

        @Test
        @DisplayName("regression: a templated duration waits the resolved time instead of 0 ms")
        void templatedDurationIsResolved() {
            WaitNode node = new WaitNode("core:wait", 0);
            node.setDeferredScalars(Map.of("wait", Map.of("duration", REF)));
            node.setTemplateAdapter(adapterResolving("5"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(), String.valueOf(result.errorMessage()));
            assertEquals(5L, result.output().get("waited_ms"));
            assertEquals(5L, paramsOf(result).get("duration"));
        }

        @Test
        @DisplayName("a templated duration that is not a number fails the wait, naming the setting")
        void nonNumericDurationFails() {
            WaitNode node = new WaitNode("core:wait", 0);
            node.setDeferredScalars(Map.of("wait", Map.of("duration", REF)));
            node.setTemplateAdapter(adapterResolving("soon"));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("wait.duration"), result.errorMessage().orElse(""));
        }
    }

    @Nested
    @DisplayName("loop.maxIterations")
    class LoopMaxIterations {

        @Test
        @DisplayName("regression: a templated cap is the resolved number, not the default 10")
        void templatedCapIsResolved() {
            TemplateEngine engine = mock(TemplateEngine.class);
            when(engine.evaluateTemplateWithMap(eq(REF), anyMap())).thenReturn(3);
            LoopNode node = new LoopNode("core:loop", null, 10, engine);
            node.setDeferredScalars(Map.of("loop", Map.of("maxIterations", REF)));

            NodeExecutionResult result = node.execute(context);

            assertEquals(3, result.output().get("maxIterations"));
            assertEquals(3, paramsOf(result).get("maxIterations"));
        }

        @Test
        @DisplayName("a templated cap resolving to nothing fails the loop instead of running 10 times")
        void capResolvingToNothingFails() {
            TemplateEngine engine = mock(TemplateEngine.class);
            when(engine.evaluateTemplateWithMap(eq(REF), anyMap())).thenReturn(null);
            LoopNode node = new LoopNode("core:loop", null, 10, engine);
            node.setDeferredScalars(Map.of("loop", Map.of("maxIterations", REF)));

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> node.execute(context));
            assertTrue(e.getMessage().contains("loop.maxIterations"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("split.maxItems")
    class SplitMaxItems {

        @Test
        @DisplayName("regression: a templated cap is the resolved number, not the default 100")
        void templatedCapIsResolved() {
            SplitNode node = new SplitNode("core:split", "{{core:src.output.items}}", 0, null, List.of(), null);
            node.setDeferredScalars(Map.of("split", Map.of("maxItems", REF)));
            node.setTemplateAdapter(adapterResolving(7));

            assertEquals(7, node.getSplitMaxItems(context));
            assertEquals(100, node.getSplitMaxItems(), "the context-free form still knows only the default");
        }

        @Test
        @DisplayName("a templated cap of 0 fails: a split over no items was never asked for")
        void zeroCapFails() {
            SplitNode node = new SplitNode("core:split", "{{core:src.output.items}}", 0, null, List.of(), null);
            node.setDeferredScalars(Map.of("split", Map.of("maxItems", REF)));
            node.setTemplateAdapter(adapterResolving(0));

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> node.getSplitMaxItems(context));
            assertTrue(e.getMessage().contains("split.maxItems"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("httpRequest.timeout")
    class HttpTimeout {

        private Object effectiveTimeout(HttpRequestNode node) throws Exception {
            var method = HttpRequestNode.class.getDeclaredMethod("effectiveTimeout", ExecutionContext.class);
            method.setAccessible(true);
            try {
                return method.invoke(node, context);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
        }

        @Test
        @DisplayName("regression: a templated timeout is used instead of the shared client's default")
        void templatedTimeoutIsResolved() throws Exception {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http").urlExpression("http://example.com").method("GET").build();
            node.setDeferredScalars(Map.of("httpRequest", Map.of("timeout", REF)));
            node.setTemplateAdapter(adapterResolving("2500"));

            assertEquals(2500, effectiveTimeout(node));
        }

        @Test
        @DisplayName("a templated timeout that is not a number fails the request")
        void nonNumericTimeoutFails() {
            HttpRequestNode node = HttpRequestNode.builder()
                .nodeId("core:http").urlExpression("http://example.com").method("GET").build();
            node.setDeferredScalars(Map.of("httpRequest", Map.of("timeout", REF)));
            node.setTemplateAdapter(adapterResolving("fast"));

            Exception e = assertThrows(IllegalStateException.class, () -> effectiveTimeout(node));
            assertTrue(e.getMessage().contains("httpRequest.timeout"), e.getMessage());
        }
    }

    @Nested
    @DisplayName("approval.requiredApprovals / timeoutMs")
    class Approval {

        @Test
        @DisplayName("regression: templated counts are resolved, not replaced by 1 approval in 24 h")
        void templatedCountsAreResolved() {
            UserApprovalNode node = new UserApprovalNode("core:approve", List.of("admin"), 1, 86_400_000L, "");
            node.setDeferredScalars(Map.of("approval", Map.of("requiredApprovals", REF)));
            node.setTemplateAdapter(adapterResolving(2));

            // No signal service wired: the node fails right after reporting what it would run with.
            NodeExecutionResult result = node.execute(context);

            assertEquals(2, paramsOf(result).get("requiredApprovals"));
            assertEquals(2, result.output().get("required_approvals"));
        }

        @Test
        @DisplayName("a templated approval count of 0 fails the node naming the setting")
        void zeroApprovalsFails() {
            UserApprovalNode node = new UserApprovalNode("core:approve", List.of("admin"), 1, 86_400_000L, "");
            node.setDeferredScalars(Map.of("approval", Map.of("requiredApprovals", REF)));
            node.setTemplateAdapter(adapterResolving(0));

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertTrue(result.errorMessage().orElse("").contains("approval.requiredApprovals"),
                result.errorMessage().orElse(""));
        }
    }

    @Nested
    @DisplayName("table CRUD limit / offset / similarity")
    class Crud {

        private Step.CrudConfig crud(Map<String, String> templates) {
            return new Step.CrudConfig(null,
                new Step.CrudConfig.SimilarityConfig("embedding", "{{core:q.output.v}}", null, null),
                Map.of(), List.of(), List.of(), null, null, templates);
        }

        @Test
        @DisplayName("regression: templated numbers go into the map to resolve and come out as numbers")
        @SuppressWarnings("unchecked")
        void templatesAreResolvedAndCoerced() {
            Step.CrudConfig config = crud(Map.of("limit", REF, "offset", "{{o}}", "topK", "{{k}}", "threshold", "{{t}}"));
            Map<String, Object> crudMap = new LinkedHashMap<>();
            crudMap.put("similarity", new LinkedHashMap<>(Map.of("column", "embedding")));
            CrudDeferredScalars.putTemplates(crudMap, config);
            assertEquals(REF, crudMap.get("limit"));
            assertEquals("{{k}}", ((Map<String, Object>) crudMap.get("similarity")).get("topK"));

            Map<String, Object> resolvedCrud = new LinkedHashMap<>();
            resolvedCrud.put("limit", "25");
            resolvedCrud.put("offset", 5L);
            resolvedCrud.put("similarity", Map.of("column", "embedding", "topK", "3", "threshold", "0.8"));
            Map<String, Object> coerced = CrudDeferredScalars.coerce(Map.of("crud", resolvedCrud), config);

            Map<String, Object> out = (Map<String, Object>) coerced.get("crud");
            assertEquals(25, out.get("limit"));
            assertEquals(5, out.get("offset"));
            assertEquals(3, ((Map<String, Object>) out.get("similarity")).get("topK"));
            assertEquals(0.8, ((Map<String, Object>) out.get("similarity")).get("threshold"));
            assertEquals(25, CrudDeferredScalars.limitOf(coerced));
        }

        @Test
        @DisplayName("a templated limit that is not a whole number fails instead of reading 500 rows")
        void nonNumericLimitFails() {
            Step.CrudConfig config = crud(Map.of("limit", REF));
            Map<String, Object> resolvedCrud = new LinkedHashMap<>();
            resolvedCrud.put("limit", "many");

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CrudDeferredScalars.coerce(Map.of("crud", resolvedCrud), config));
            assertTrue(e.getMessage().contains("crud.limit"), e.getMessage());
        }

        @Test
        @DisplayName("a limit pulled from a workspace variable is used but withheld in the report")
        @SuppressWarnings("unchecked")
        void workspaceVariableLimitIsWithheld() {
            Step.CrudConfig config = crud(Map.of("limit", "{{$vars.page_size}}"));
            Map<String, Object> resolvedCrud = new LinkedHashMap<>();
            resolvedCrud.put("limit", 40);

            Map<String, Object> reported = CrudDeferredScalars.reportable(Map.of("crud", resolvedCrud), config);

            assertEquals(ReportedParams.WITHHELD_WORKSPACE_VARIABLE, ((Map<String, Object>) reported.get("crud")).get("limit"));
        }

        @Test
        @DisplayName("regression: a find's row cap is the resolved templated limit, not the default 100")
        void findCapUsesResolvedLimit() throws Exception {
            Step step = new Step(null, "crud-find", "Find", null, Map.of(), 1L, crud(Map.of("limit", REF)), null);
            FindNode node = new FindNode("table:find", step, null, 100, null);
            node.setTemplateAdapter(adapterResolving(250));

            var method = FindNode.class.getDeclaredMethod("effectiveMaxItems", ExecutionContext.class);
            method.setAccessible(true);

            assertEquals(250, method.invoke(node, context));
        }
    }
}
