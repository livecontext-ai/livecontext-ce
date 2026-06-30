package com.apimarketplace.orchestrator.services.resume;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.engine.StepByStepExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowStreamingService;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.resume.cache.WorkflowCacheManager;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
@DisplayName("StepByStepExecutor")
class StepByStepExecutorTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowStreamingService streamingService;
    @Mock private StepCompletionOrchestrator stepCompletionOrchestrator;
    @Mock private RunStateStore runStateStore;
    @Mock private WorkflowCacheManager cacheManager;
    @Mock private ExecutionContextManager contextManager;
    @Mock private StateSnapshotService stateSnapshotService;

    @Test
    @DisplayName("failedV2ResultPreservesOutputForResolvedParams")
    void failedV2ResultPreservesOutputForResolvedParams() throws Exception {
        StepByStepExecutor executor = new StepByStepExecutor(
            runRepository,
            executionService,
            streamingService,
            stepCompletionOrchestrator,
            runStateStore,
            cacheManager,
            contextManager,
            stateSnapshotService);

        Map<String, Object> resolvedParams = Map.of("query", "from:alerts", "limit", 10);
        Map<String, Object> failedOutput = Map.of(
            "resolved_params", resolvedParams,
            "error_code", "REMOTE_500");
        NodeExecutionResult nodeResult = NodeExecutionResult.failureWithOutput(
            "mcp:gmail_search",
            "Remote service failed",
            failedOutput,
            42L);
        StepByStepExecutionResult v2Result = new StepByStepExecutionResult(
            null,
            nodeResult,
            Set.of(),
            false);

        StepExecutionResult result = invokeConvertV2Result(executor, "mcp:gmail_search", v2Result);

        assertEquals(NodeStatus.FAILED, result.status());
        assertEquals("Remote service failed", result.message());
        assertNotNull(result.output(), "failed output must be preserved so resolved_params reaches input_data");
        assertEquals(resolvedParams, result.output().get("resolved_params"));
        assertEquals("REMOTE_500", result.output().get("error_code"));
    }

    private StepExecutionResult invokeConvertV2Result(
            StepByStepExecutor executor,
            String stepId,
            StepByStepExecutionResult v2Result) throws Exception {
        Method method = StepByStepExecutor.class.getDeclaredMethod(
            "convertV2Result",
            String.class,
            StepByStepExecutionResult.class);
        method.setAccessible(true);
        return (StepExecutionResult) method.invoke(executor, stepId, v2Result);
    }
}
