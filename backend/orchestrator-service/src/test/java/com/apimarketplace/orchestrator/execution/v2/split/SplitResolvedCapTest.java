package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SplitAwareNodeExecutor - nested and fallback split caps")
class SplitResolvedCapTest {

    private final ExecutionContext context = ExecutionContext.create(
        "run-1", "wr-1", "tenant-1", "item-1", 0, Map.of(), mock(WorkflowPlan.class));

    @Test
    @DisplayName("regression: a templated maxItems that cannot resolve fails THIS split instead of escaping the engine")
    void badCapFailsTheSplit() {
        ExecutionNode node = mock(ExecutionNode.class);
        when(node.getSplitMaxItems(any(ExecutionContext.class)))
            .thenThrow(new IllegalStateException("split.maxItems '{{core:cfg.output.n}}' resolved to nothing"));
        SplitNodeExecutor executor = mock(SplitNodeExecutor.class);

        NodeExecutionResult result = SplitAwareNodeExecutor.executeSplitWithResolvedCap(
            executor, "run-1", "core:inner", node, 0, context);

        assertTrue(result.isFailure());
        assertTrue(result.errorMessage().orElse("").contains("split.maxItems"), result.errorMessage().orElse(""));
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("a resolved cap is handed to the split executor")
    void resolvedCapIsUsed() {
        ExecutionNode node = mock(ExecutionNode.class);
        when(node.getSplitMaxItems(any(ExecutionContext.class))).thenReturn(7);
        when(node.getListExpression()).thenReturn("{{core:src.output.items}}");
        when(node.getSplitStrategy()).thenReturn("stop-on-error");
        SplitNodeExecutor executor = mock(SplitNodeExecutor.class);
        NodeExecutionResult ok = NodeExecutionResult.success("core:inner", Map.of());
        when(executor.execute(anyString(), anyString(), anyString(), anyInt(), anyString(), anyInt(), any()))
            .thenReturn(ok);

        NodeExecutionResult result = SplitAwareNodeExecutor.executeSplitWithResolvedCap(
            executor, "run-1", "core:inner", node, 0, context);

        assertSame(ok, result);
        verify(executor).execute("run-1", "core:inner", "{{core:src.output.items}}", 7, "stop-on-error", 0, context);
    }
}
