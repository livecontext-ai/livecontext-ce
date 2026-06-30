package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.streaming.context.RunContext;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import com.apimarketplace.orchestrator.services.streaming.context.RunNodeState;
import com.apimarketplace.orchestrator.services.streaming.context.RunNodeState.StatusCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NodeEventStore")
class NodeEventStoreTest {

    @Mock
    private RunContextRegistry contextRegistry;

    @Mock
    private RunNodeState mockNodeState;

    @Mock
    private RunContext mockContext;

    private NodeEventStore nodeEventStore;

    @BeforeEach
    void setUp() {
        nodeEventStore = new NodeEventStore(contextRegistry);
    }

    @Nested
    @DisplayName("recordNodeExecution()")
    class RecordNodeExecutionTests {

        @Test
        @DisplayName("Should record and return status counts")
        void shouldRecordAndReturnStatusCounts() {
            StatusCounts expected = new StatusCounts(0, 1, 0, 0, 1, 1);
            when(contextRegistry.getNodeState("run-1")).thenReturn(mockNodeState);
            when(mockNodeState.recordExecution("mcp:step1", 0, 0, "COMPLETED")).thenReturn(expected);

            StatusCounts result = nodeEventStore.recordNodeExecution("run-1", "mcp:step1", 0, 0, "COMPLETED");

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("Should return empty counts for null runId")
        void shouldReturnEmptyForNullRunId() {
            StatusCounts result = nodeEventStore.recordNodeExecution(null, "mcp:step1", 0, 0, "COMPLETED");
            assertEquals(StatusCounts.empty(), result);
        }

        @Test
        @DisplayName("Should return empty counts for null nodeId")
        void shouldReturnEmptyForNullNodeId() {
            StatusCounts result = nodeEventStore.recordNodeExecution("run-1", null, 0, 0, "COMPLETED");
            assertEquals(StatusCounts.empty(), result);
        }
    }

    @Nested
    @DisplayName("getStatusCounts()")
    class GetStatusCountsTests {

        @Test
        @DisplayName("Should return counts from context")
        void shouldReturnCountsFromContext() {
            StatusCounts expected = new StatusCounts(1, 5, 0, 0, 5, 6);
            when(contextRegistry.get("run-1")).thenReturn(Optional.of(mockContext));
            when(mockContext.getNodeState()).thenReturn(mockNodeState);
            when(mockNodeState.getStatusCounts("mcp:step1")).thenReturn(expected);

            StatusCounts result = nodeEventStore.getStatusCounts("run-1", "mcp:step1");

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("Should return empty when context not found")
        void shouldReturnEmptyWhenContextNotFound() {
            when(contextRegistry.get("run-1")).thenReturn(Optional.empty());

            StatusCounts result = nodeEventStore.getStatusCounts("run-1", "mcp:step1");

            assertEquals(StatusCounts.empty(), result);
        }

        @Test
        @DisplayName("Should return empty for null runId")
        void shouldReturnEmptyForNullRunId() {
            assertEquals(StatusCounts.empty(), nodeEventStore.getStatusCounts(null, "node"));
        }

        @Test
        @DisplayName("Should return empty for null nodeId")
        void shouldReturnEmptyForNullNodeId() {
            assertEquals(StatusCounts.empty(), nodeEventStore.getStatusCounts("run-1", null));
        }
    }

    @Nested
    @DisplayName("getAllStatusCounts()")
    class GetAllStatusCountsTests {

        @Test
        @DisplayName("Should return all counts from context")
        void shouldReturnAllCounts() {
            Map<String, StatusCounts> expected = Map.of(
                "mcp:step1", new StatusCounts(0, 5, 0, 0, 5, 5),
                "mcp:step2", new StatusCounts(1, 3, 1, 0, 4, 5)
            );
            when(contextRegistry.get("run-1")).thenReturn(Optional.of(mockContext));
            when(mockContext.getNodeState()).thenReturn(mockNodeState);
            when(mockNodeState.getAllStatusCounts()).thenReturn(expected);

            Map<String, StatusCounts> result = nodeEventStore.getAllStatusCounts("run-1");

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("Should return empty map for null runId")
        void shouldReturnEmptyForNull() {
            assertEquals(Map.of(), nodeEventStore.getAllStatusCounts(null));
        }
    }

    @Nested
    @DisplayName("initializeTotalItems()")
    class InitializeTotalItemsTests {

        @Test
        @DisplayName("Should set total items on node state")
        void shouldSetTotalItems() {
            when(contextRegistry.getNodeState("run-1")).thenReturn(mockNodeState);

            nodeEventStore.initializeTotalItems("run-1", 100);

            verify(mockNodeState).setTotalItems(100);
        }

        @Test
        @DisplayName("Should do nothing for null runId")
        void shouldDoNothingForNullRunId() {
            nodeEventStore.initializeTotalItems(null, 100);
            verifyNoInteractions(contextRegistry);
        }

        @Test
        @DisplayName("Should do nothing for zero totalItems")
        void shouldDoNothingForZeroTotalItems() {
            nodeEventStore.initializeTotalItems("run-1", 0);
            verifyNoInteractions(contextRegistry);
        }

        @Test
        @DisplayName("Should do nothing for negative totalItems")
        void shouldDoNothingForNegativeTotalItems() {
            nodeEventStore.initializeTotalItems("run-1", -5);
            verifyNoInteractions(contextRegistry);
        }
    }

    @Nested
    @DisplayName("initializeNodeTotalItems()")
    class InitializeNodeTotalItemsTests {

        @Test
        @DisplayName("Should set total items for specific node")
        void shouldSetNodeTotalItems() {
            when(contextRegistry.getNodeState("run-1")).thenReturn(mockNodeState);

            nodeEventStore.initializeNodeTotalItems("run-1", "mcp:step1", 50);

            verify(mockNodeState).setNodeTotalItems("mcp:step1", 50);
        }

        @Test
        @DisplayName("Should do nothing for null nodeId")
        void shouldDoNothingForNullNodeId() {
            nodeEventStore.initializeNodeTotalItems("run-1", null, 50);
            verifyNoInteractions(contextRegistry);
        }
    }

    @Nested
    @DisplayName("prePopulateCounts()")
    class PrePopulateCountsTests {

        @Test
        @DisplayName("Should delegate to node state")
        void shouldDelegateToNodeState() {
            when(contextRegistry.getNodeState("run-1")).thenReturn(mockNodeState);
            Map<String, Integer> statusCounts = Map.of("SUCCESS", 15, "FAILED", 0);

            nodeEventStore.prePopulateCounts("run-1", "mcp:step1", statusCounts);

            verify(mockNodeState).prePopulateCounts("mcp:step1", statusCounts);
        }

        @Test
        @DisplayName("Should do nothing for null statusCounts")
        void shouldDoNothingForNullStatusCounts() {
            nodeEventStore.prePopulateCounts("run-1", "mcp:step1", null);
            verifyNoInteractions(contextRegistry);
        }
    }
}
