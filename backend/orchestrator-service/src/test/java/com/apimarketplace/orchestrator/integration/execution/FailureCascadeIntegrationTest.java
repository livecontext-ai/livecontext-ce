package com.apimarketplace.orchestrator.integration.execution;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.services.V2SkipPropagationService;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import com.apimarketplace.orchestrator.services.streaming.EdgeStatusService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration test for {@link V2SkipPropagationService#cascadeFailureToSuccessors}
 * with the real Spring context.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The cascade routine is wired with a real {@code MeterRegistry} bean
 *       and increments counters correctly under both {@code source=sync} and
 *       {@code source=async} tags.</li>
 *   <li>The Spring-managed bean correctly delegates to
 *       {@code StepCompletionOrchestrator} and {@code EdgeStatusService}
 *       collaborators (mocked here to keep DB writes off the hot path; the
 *       actual DB writes are exercised at unit-test level with the real
 *       persistence adapter, and at unit + this integration level the contract
 *       is the same).</li>
 *   <li>{@code MeterRegistry} bean exposes the new
 *       {@code orchestrator.skip.cascade.descendants} and
 *       {@code orchestrator.skip.cascade.duration} meters after the first
 *       cascade invocation.</li>
 * </ul>
 *
 * <p>This complements the unit tests in {@code V2SkipPropagationServiceTest}
 * by proving the wiring through the actual Spring application context, not just
 * a hand-rolled constructor call.
 */
@IntegrationTest
@DisplayName("Failure Cascade - Spring integration")
class FailureCascadeIntegrationTest {

    @Autowired
    private V2SkipPropagationService skipPropagationService;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private StepCompletionOrchestrator stepCompletionOrchestrator;

    @MockitoBean
    private EdgeStatusService edgeStatusService;

    @Test
    @DisplayName("cascade with Spring-wired beans increments source=async counter and timer")
    void cascadeViaSpringContextIncrementsAsyncCounter() {
        BaseNode succ1 = mock(BaseNode.class);
        when(succ1.getNodeId()).thenReturn("mcp:downstream_a");
        when(succ1.getSuccessors()).thenReturn(List.of());
        when(succ1.getAllChildNodes()).thenReturn(List.of());

        BaseNode succ2 = mock(BaseNode.class);
        when(succ2.getNodeId()).thenReturn("mcp:downstream_b");
        when(succ2.getSuccessors()).thenReturn(List.of());
        when(succ2.getAllChildNodes()).thenReturn(List.of());

        BaseNode failedAgent = mock(BaseNode.class);
        when(failedAgent.getNodeId()).thenReturn("agent:analyze_emails");
        when(failedAgent.getSuccessors()).thenReturn(List.of(succ1, succ2));

        WorkflowExecution execution = mock(WorkflowExecution.class);
        String runId = "run-int-" + UUID.randomUUID().toString().substring(0, 8);

        // Capture pre-existing counter value (other tests in the same context may have
        // incremented it - this is real Spring, not isolated MeterRegistry).
        double preCount = Optional.ofNullable(
            meterRegistry.find("orchestrator.skip.cascade.descendants")
                .tag("source", V2SkipPropagationService.SOURCE_ASYNC)
                .counter())
            .map(c -> c.count())
            .orElse(0.0);

        skipPropagationService.cascadeFailureToSuccessors(
            execution, failedAgent, 0, 5, "trigger:cron",
            /*perItemScope=*/ false,
            V2SkipPropagationService.SOURCE_ASYNC);

        // Counter went up by exactly 2 (one per direct successor).
        double postCount = meterRegistry
            .counter("orchestrator.skip.cascade.descendants",
                "source", V2SkipPropagationService.SOURCE_ASYNC)
            .count();
        assertEquals(preCount + 2.0, postCount, 0.001,
            "cascade should have incremented the async counter by 2 (one per descendant)");

        // Timer recorded at least 1 sample.
        assertTrue(meterRegistry
                .timer("orchestrator.skip.cascade.duration",
                    "source", V2SkipPropagationService.SOURCE_ASYNC)
                .count() >= 1,
            "cascade duration timer should have recorded at least one sample");

        // Real bean delegated to the mocked orchestrator for each successor.
        // (Edge marking of failed→successor is EdgeStatusEmitter's responsibility, NOT
        // cascade's - verified separately in EdgeStatusEmitter and unit tests. Cascade
        // marks descendants' outgoing edges, but our successors have no grandchildren.)
        verify(stepCompletionOrchestrator, times(2)).completeSkipped(any(), any());
    }

    @Test
    @DisplayName("cascade with source=sync registers a distinct counter from source=async")
    void cascadeWithSourceSyncRegistersDistinctCounter() {
        BaseNode successor = mock(BaseNode.class);
        when(successor.getNodeId()).thenReturn("mcp:downstream_sync");
        when(successor.getSuccessors()).thenReturn(List.of());
        when(successor.getAllChildNodes()).thenReturn(List.of());

        BaseNode failed = mock(BaseNode.class);
        when(failed.getNodeId()).thenReturn("mcp:fetch_emails");
        when(failed.getSuccessors()).thenReturn(List.of(successor));

        WorkflowExecution execution = mock(WorkflowExecution.class);

        double preSync = Optional.ofNullable(
            meterRegistry.find("orchestrator.skip.cascade.descendants")
                .tag("source", V2SkipPropagationService.SOURCE_SYNC)
                .counter())
            .map(c -> c.count())
            .orElse(0.0);

        skipPropagationService.cascadeFailureToSuccessors(
            execution, failed, 0, 5, "trigger:cron",
            /*perItemScope=*/ false,
            V2SkipPropagationService.SOURCE_SYNC);

        double postSync = meterRegistry
            .counter("orchestrator.skip.cascade.descendants",
                "source", V2SkipPropagationService.SOURCE_SYNC)
            .count();
        assertEquals(preSync + 1.0, postSync, 0.001,
            "cascade with source=sync should increment the sync-tagged counter");
    }

    @Test
    @DisplayName("cascade with null failedNode is a no-op via real Spring bean")
    void cascadeWithNullFailedNodeIsNoOpViaSpring() {
        WorkflowExecution execution = mock(WorkflowExecution.class);

        // Should not throw - guard at the top of cascadeFailureToSuccessors.
        assertDoesNotThrow(() -> skipPropagationService.cascadeFailureToSuccessors(
            execution, null, 0, 5, "trigger:cron", false,
            V2SkipPropagationService.SOURCE_ASYNC));

        verify(stepCompletionOrchestrator, never()).completeSkipped(any(), any());
        verify(edgeStatusService, never()).markEdgeSkipped(
            any(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Spring context exposes the new meters under /actuator/metrics-style discovery")
    void springContextExposesNewMeters() {
        // Trigger cascade once to instantiate the meters lazily.
        BaseNode successor = mock(BaseNode.class);
        when(successor.getNodeId()).thenReturn("mcp:probe");
        when(successor.getSuccessors()).thenReturn(List.of());
        when(successor.getAllChildNodes()).thenReturn(List.of());
        BaseNode failed = mock(BaseNode.class);
        when(failed.getNodeId()).thenReturn("agent:probe");
        when(failed.getSuccessors()).thenReturn(List.of(successor));

        skipPropagationService.cascadeFailureToSuccessors(
            mock(WorkflowExecution.class), failed, 0, 0, null, false,
            V2SkipPropagationService.SOURCE_ASYNC);

        // Both meter names must now be discoverable in the registry.
        assertNotNull(
            meterRegistry.find("orchestrator.skip.cascade.descendants").counter(),
            "orchestrator.skip.cascade.descendants counter must be registered after first invocation");
        assertNotNull(
            meterRegistry.find("orchestrator.skip.cascade.duration").timer(),
            "orchestrator.skip.cascade.duration timer must be registered after first invocation");
    }
}
