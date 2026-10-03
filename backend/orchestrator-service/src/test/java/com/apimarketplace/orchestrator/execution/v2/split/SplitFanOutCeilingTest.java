package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Regression tests for LC-064: split fan-out was unbounded by default.
 *
 * <p>{@code maxItems} defaulted to {@code 0 = unlimited} and the item list comes from upstream
 * data (an HTTP response, a table read, a webhook body), so a free account could fetch a
 * million-element array from a URL it controls and split on it: one task and one retained
 * per-item context each, until the whole fan-out completed, on the JVM-global common pool shared
 * with every other tenant on that replica.
 *
 * <p>The contract these tests pin: the node FAILS with an explicit message rather than silently
 * truncating (a green run that processed 10_000 of 1_000_000 items is indistinguishable from
 * success), {@code 0} now means the ceiling rather than unlimited, and an EXPLICIT {@code
 * maxItems} keeps its documented truncating behaviour.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Split fan-out ceiling (LC-064)")
class SplitFanOutCeilingTest {

    @Mock private V2TemplateAdapter templateAdapter;
    @Mock private WorkflowPlan plan;

    /** Small ceiling so the test does not have to build a 10_000-element list to prove the rule. */
    private static final int TEST_CEILING = 5;

    private SplitContextManager contextManager;
    private SplitNodeExecutor executor;
    private ExecutionContext context;

    /**
     * Stubbed by RETURN TYPE, not by argument shape, on purpose. {@code createContext} has three
     * overloads and gained an epoch-carrying one; a stub pinned to one of them stops matching the
     * moment the executor moves to another, and the executor then reads a null context and every
     * test in this class NPEs on a change that has nothing to do with the fan-out ceiling.
     */
    @BeforeEach
    void setUp() {
        contextManager = org.mockito.Mockito.mock(SplitContextManager.class, invocation -> {
            if (invocation.getMethod().getReturnType() == SplitContext.class) {
                List<Object> items = List.of();
                for (Object argument : invocation.getArguments()) {
                    if (argument instanceof List<?> list) {
                        @SuppressWarnings("unchecked")
                        List<Object> typed = (List<Object>) list;
                        items = typed;
                    }
                }
                return new SplitContext("ctx", items, Map.of(), 0);
            }
            return org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        executor = new SplitNodeExecutor(contextManager, templateAdapter, TEST_CEILING);
        context = ExecutionContext.create(
                "run-1", "wf-run-1", "tenant-1", "item-0", 0, new HashMap<>(), plan);
    }

    /**
     * No context was created, by METHOD NAME rather than by signature: a refused fan-out must
     * create nothing, and that has to keep being asserted across {@code createContext} overloads.
     */
    private void assertNoContextCreated() {
        assertThat(org.mockito.Mockito.mockingDetails(contextManager).getInvocations())
                .as("a refused fan-out must not create a split context")
                .noneMatch(invocation -> "createContext".equals(invocation.getMethod().getName()));
    }

    private static List<Object> items(int count) {
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(Map.of("i", i));
        }
        return list;
    }

    @Test
    @DisplayName("maxItems=0 over the ceiling FAILS the node - it must not truncate silently")
    void unlimitedOverCeilingFails() {
        when(templateAdapter.evaluateTemplate(anyString(), any())).thenReturn(items(TEST_CEILING + 1));

        NodeExecutionResult result = executor.execute(
                "run-1", "core:split", "{{mcp:fetch.output.items}}", 0, null, 0, context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage()).isPresent();
        assertThat(result.errorMessage().get())
                .as("the message has to name the count, the ceiling and the fix")
                .contains(String.valueOf(TEST_CEILING + 1))
                .contains(String.valueOf(TEST_CEILING))
                .contains("maxItems");
        // No context is created, so nothing downstream ever fans out over the ceiling.
        assertNoContextCreated();
    }

    @Test
    @DisplayName("maxItems=0 exactly AT the ceiling still runs - the bound is inclusive")
    void unlimitedAtCeilingIsAllowed() {
        when(templateAdapter.evaluateTemplate(anyString(), any())).thenReturn(items(TEST_CEILING));

        NodeExecutionResult result = executor.execute(
                "run-1", "core:split", "{{mcp:fetch.output.items}}", 0, null, 0, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output().get("item_count")).isEqualTo(TEST_CEILING);
    }

    @Test
    @DisplayName("an EXPLICIT maxItems keeps truncating - that is the documented feature, not the bug")
    void explicitMaxItemsStillTruncates() {
        when(templateAdapter.evaluateTemplate(anyString(), any())).thenReturn(items(TEST_CEILING + 50));

        NodeExecutionResult result = executor.execute(
                "run-1", "core:split", "{{mcp:fetch.output.items}}", 3, null, 0, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output().get("item_count")).isEqualTo(3);
    }

    @Test
    @DisplayName("an ordinary small split is untouched - the ceiling must be a no-op for real workflows")
    void ordinarySplitIsUnaffected() {
        when(templateAdapter.evaluateTemplate(anyString(), any())).thenReturn(items(2));

        NodeExecutionResult result = executor.execute(
                "run-1", "core:split", "{{mcp:fetch.output.items}}", 0, null, 0, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output().get("item_count")).isEqualTo(2);
    }

    @Test
    @DisplayName("the PRE-RESOLVED path is bounded too - same fan-out, and a fix on one path only is half a fix")
    void preResolvedPathIsBoundedToo() {
        NodeExecutionResult result = executor.executeWithItems(
                "run-1", "core:find", items(TEST_CEILING + 1), 0, 0, context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage().get()).contains("maxItems");
        assertNoContextCreated();
    }

    @Test
    @DisplayName("the pre-resolved path with an explicit maxItems still truncates")
    void preResolvedPathTruncatesWithExplicitMax() {
        NodeExecutionResult result = executor.executeWithItems(
                "run-1", "core:find", items(TEST_CEILING + 50), 2, 0, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output().get("item_count")).isEqualTo(2);
    }

    @Test
    @DisplayName("an EXPLICIT maxItems ABOVE the ceiling FAILS an oversized list - it must not truncate to the ceiling")
    void explicitMaxItemsAboveCeilingFails() {
        // The case the plan-build clamp used to swallow: a plan asking for 1_000_000 was
        // rewritten to the ceiling and the run went green having processed a fraction of the
        // data, with only a server-side WARN - the same silent truncation the maxItems<=0 branch
        // refuses on principle.
        when(templateAdapter.evaluateTemplate(anyString(), any())).thenReturn(items(TEST_CEILING + 1));

        NodeExecutionResult result = executor.execute(
                "run-1", "core:split", "{{mcp:fetch.output.items}}", 1_000_000, null, 0, context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage()).isPresent();
        assertThat(result.errorMessage().get())
                .as("the message has to name the declared value, the ceiling and the fix")
                .contains("1000000")
                .contains(String.valueOf(TEST_CEILING))
                .contains("maxItems");
        assertNoContextCreated();
    }

    @Test
    @DisplayName("an oversized maxItems with a NORMAL-sized list still runs - nothing exceeded the ceiling")
    void explicitMaxItemsAboveCeilingWithSmallListStillRuns() {
        // The live-path guard: plans in production carry whatever number their author typed.
        // Refusing them outright at build time would break runs that never fan out anywhere near
        // the ceiling, which is a bigger outage than the truncation being fixed.
        when(templateAdapter.evaluateTemplate(anyString(), any())).thenReturn(items(2));

        NodeExecutionResult result = executor.execute(
                "run-1", "core:split", "{{mcp:fetch.output.items}}", 1_000_000, null, 0, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output().get("item_count")).isEqualTo(2);
    }

    @Test
    @DisplayName("the pre-resolved path refuses an oversized maxItems too")
    void preResolvedPathRefusesExplicitMaxAboveCeiling() {
        NodeExecutionResult result = executor.executeWithItems(
                "run-1", "core:find", items(TEST_CEILING + 1), 1_000_000, 0, context);

        assertThat(result.isFailure()).isTrue();
        assertThat(result.errorMessage().get()).contains("1000000");
        assertNoContextCreated();
    }

    @Test
    @DisplayName("REALITY CHECK: a split with no maxItems reaches the executor as 100, not as 0")
    void splitNodeCoercesUnlimitedMarkerToOneHundred() {
        // Pinned because it is easy to read the executor's javadoc and believe the maxItems<=0
        // branch guards production. It does not: SplitNode's constructor replaces a non-positive
        // maxItems with 100 and UnifiedExecutionEngine passes getSplitMaxItems() to the executor,
        // so on the engine path the executor never sees 0. The fan-out is bounded there by the
        // 100 default; the <=0 branch only fires for direct callers of the executor. Anyone
        // raising or removing that default has to make the ceiling branch reachable first.
        com.apimarketplace.orchestrator.execution.v2.nodes.SplitNode node =
                new com.apimarketplace.orchestrator.execution.v2.nodes.SplitNode(
                        "core:split", "{{mcp:fetch.output.items}}", 0, "continue-anyway",
                        List.of(), null);

        assertThat(node.getSplitMaxItems()).isEqualTo(100);
    }
}
