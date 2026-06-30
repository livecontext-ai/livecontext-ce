package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.context.RunNodeState.StatusCounts;
import com.apimarketplace.orchestrator.services.streaming.state.NodeEventStore;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NodeEventEmitterService")
class NodeEventEmitterServiceTest {

    @Mock
    private NodeEventStore nodeEventStore;

    @Mock
    private RunStateStore runStateStore;

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private StepEventBuilder stepEventBuilder;

    private NodeEventEmitterService service;

    @BeforeEach
    void setUp() {
        service = new NodeEventEmitterService(nodeEventStore, runStateStore, eventPublisher, stepEventBuilder);
    }

    @Nested
    @DisplayName("initializeTotalItems()")
    class InitializeTotalItemsTests {

        @Test
        @DisplayName("Should delegate to nodeEventStore")
        void shouldDelegate() {
            service.initializeTotalItems("run-1", 10);

            verify(nodeEventStore).initializeTotalItems("run-1", 10);
        }
    }

    @Nested
    @DisplayName("initializeNodeTotalItems()")
    class InitializeNodeTotalItemsTests {

        @Test
        @DisplayName("Should delegate to nodeEventStore with node label")
        void shouldDelegate() {
            service.initializeNodeTotalItems("run-1", "my_step", 5);

            verify(nodeEventStore).initializeNodeTotalItems("run-1", "my_step", 5);
        }
    }

    @Nested
    @DisplayName("prePopulateCounts()")
    class PrePopulateCountsTests {

        @Test
        @DisplayName("Should delegate to nodeEventStore")
        void shouldDelegate() {
            Map<String, Integer> counts = Map.of("SUCCESS", 15, "FAILED", 0);

            service.prePopulateCounts("run-1", "step1", counts);

            verify(nodeEventStore).prePopulateCounts("run-1", "step1", counts);
        }

        @Test
        @DisplayName("Should skip when runId is null")
        void shouldSkipWhenRunIdNull() {
            service.prePopulateCounts(null, "step1", Map.of("SUCCESS", 1));

            verifyNoInteractions(nodeEventStore);
        }

        @Test
        @DisplayName("Should skip when nodeId is null")
        void shouldSkipWhenNodeIdNull() {
            service.prePopulateCounts("run-1", null, Map.of("SUCCESS", 1));

            verifyNoInteractions(nodeEventStore);
        }

        @Test
        @DisplayName("Should skip when statusCounts is null")
        void shouldSkipWhenCountsNull() {
            service.prePopulateCounts("run-1", "step1", null);

            verifyNoInteractions(nodeEventStore);
        }

        @Test
        @DisplayName("Should skip when statusCounts is empty")
        void shouldSkipWhenCountsEmpty() {
            service.prePopulateCounts("run-1", "step1", Map.of());

            verifyNoInteractions(nodeEventStore);
        }
    }

    @Nested
    @DisplayName("getStatusCounts()")
    class GetStatusCountsTests {

        @Test
        @DisplayName("Should return status counts map from nodeEventStore")
        void shouldReturnCountsMap() {
            StatusCounts counts = new StatusCounts(1, 2, 0, 0, 3, 4);
            when(nodeEventStore.getStatusCounts("run-1", "my_step")).thenReturn(counts);

            Map<String, Object> result = service.getStatusCounts("run-1", "my_step");

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("recordNodeExecution()")
    class RecordNodeExecutionTests {

        @Test
        @DisplayName("Should delegate to nodeEventStore and return counts")
        void shouldDelegateAndReturnCounts() {
            StatusCounts expectedCounts = new StatusCounts(1, 1, 0, 0, 1, 2);
            when(nodeEventStore.recordNodeExecution("run-1", "my_step", 0, 0, "COMPLETED"))
                .thenReturn(expectedCounts);

            StatusCounts result = service.recordNodeExecution("run-1", "my_step", 0, 0, "COMPLETED");

            assertEquals(expectedCounts, result);
        }
    }

    @Nested
    @DisplayName("onStepPersisted() - null safety")
    class OnStepPersistedNullSafetyTests {

        @Test
        @DisplayName("Should skip when execution is null")
        void shouldSkipWhenExecutionNull() {
            StepExecutionResult result = mock(StepExecutionResult.class);

            service.onStepPersisted(null, "step1", "alias", "normalized", result, 0, 0);

            verifyNoInteractions(nodeEventStore);
        }

        @Test
        @DisplayName("Should skip when result is null")
        void shouldSkipWhenResultNull() {
            WorkflowExecution execution = mock(WorkflowExecution.class);

            service.onStepPersisted(execution, "step1", "alias", "normalized", null, 0, 0);

            verifyNoInteractions(nodeEventStore);
        }
    }

    @Nested
    @DisplayName("recordStepExecution() - null safety")
    class RecordStepExecutionNullSafetyTests {

        @Test
        @DisplayName("Should skip when execution is null")
        void shouldSkipWhenExecutionNull() {
            StepExecutionResult result = mock(StepExecutionResult.class);

            service.recordStepExecution(null, "label", result, 0, 0);

            verifyNoInteractions(nodeEventStore);
        }

        @Test
        @DisplayName("Should skip when result is null")
        void shouldSkipWhenResultNull() {
            WorkflowExecution execution = mock(WorkflowExecution.class);

            service.recordStepExecution(execution, "label", null, 0, 0);

            verifyNoInteractions(nodeEventStore);
        }
    }
}
