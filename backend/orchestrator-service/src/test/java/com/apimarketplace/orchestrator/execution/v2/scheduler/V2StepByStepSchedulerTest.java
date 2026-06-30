package com.apimarketplace.orchestrator.execution.v2.scheduler;

import com.apimarketplace.orchestrator.services.stepbystep.PendingSignalDbService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("V2StepByStepScheduler Tests")
@ExtendWith(MockitoExtension.class)
class V2StepByStepSchedulerTest {

    @Mock
    private PendingSignalDbService pendingSignalService;

    private V2StepByStepScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new V2StepByStepScheduler(pendingSignalService);
    }

    @Nested
    @DisplayName("getType()")
    class GetTypeTests {

        @Test
        @DisplayName("Should return STEP_BY_STEP type")
        void shouldReturnStepByStepType() {
            // When
            V2ExecutionScheduler.SchedulerType type = scheduler.getType();

            // Then
            assertEquals(V2ExecutionScheduler.SchedulerType.STEP_BY_STEP, type);
        }
    }

    @Nested
    @DisplayName("awaitProceed()")
    class AwaitProceedTests {

        @Test
        @DisplayName("Should return pending future when no pre-approval")
        void shouldReturnPendingFuture() {
            // Given
            when(pendingSignalService.shouldAutoExecute("run-1", "item-1", "mcp:step1")).thenReturn(false);

            // When
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");

            // Then
            assertNotNull(future);
            assertFalse(future.isDone());
            verify(pendingSignalService).markPending("run-1", "item-1", "mcp:step1");
        }

        @Test
        @DisplayName("Should return immediately completed future when pre-approved")
        void shouldReturnCompletedFutureWhenPreApproved() throws ExecutionException, InterruptedException, TimeoutException {
            // Given - pre-approve before await
            when(pendingSignalService.shouldAutoExecute("run-1", "item-1", "mcp:step1")).thenReturn(true);

            // When
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");

            // Then
            assertTrue(future.isDone());
            future.get(100, TimeUnit.MILLISECONDS);
            verify(pendingSignalService, never()).markPending(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Should wait for signal")
        void shouldWaitForSignal() throws ExecutionException, InterruptedException, TimeoutException {
            // Given
            when(pendingSignalService.shouldAutoExecute("run-1", "item-1", "mcp:step1")).thenReturn(false);
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");
            assertFalse(future.isDone());

            // When
            scheduler.signalProceed("run-1", "item-1", "mcp:step1");

            // Then
            assertTrue(future.isDone());
            future.get(100, TimeUnit.MILLISECONDS);
        }

        @Test
        @DisplayName("Should handle multiple awaits for different nodes")
        void shouldHandleMultipleAwaitsForDifferentNodes() {
            // Given
            when(pendingSignalService.shouldAutoExecute(anyString(), anyString(), anyString())).thenReturn(false);

            // When
            CompletableFuture<Void> future1 = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");
            CompletableFuture<Void> future2 = scheduler.awaitProceed("run-1", "item-1", "mcp:step2");

            // Then - both should be pending
            assertFalse(future1.isDone());
            assertFalse(future2.isDone());

            // When - signal first one
            scheduler.signalProceed("run-1", "item-1", "mcp:step1");

            // Then - only first should be done
            assertTrue(future1.isDone());
            assertFalse(future2.isDone());
        }
    }

    @Nested
    @DisplayName("signalProceed()")
    class SignalProceedTests {

        @Test
        @DisplayName("Should complete waiting future")
        void shouldCompleteWaitingFuture() throws ExecutionException, InterruptedException, TimeoutException {
            // Given
            when(pendingSignalService.shouldAutoExecute("run-1", "item-1", "mcp:step1")).thenReturn(false);
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");

            // When
            scheduler.signalProceed("run-1", "item-1", "mcp:step1");

            // Then
            assertTrue(future.isDone());
            future.get(100, TimeUnit.MILLISECONDS);
            verify(pendingSignalService).removePending("run-1", "item-1", "mcp:step1");
        }

        @Test
        @DisplayName("Should pre-approve in DB if no waiting future exists")
        void shouldPreApproveIfNoWaitingFuture() {
            // When - signal before await
            scheduler.signalProceed("run-1", "item-1", "mcp:step1");

            // Then
            verify(pendingSignalService).markPreApproved("run-1", "item-1", "mcp:step1");
        }

        @Test
        @DisplayName("Should complete all matching nodes with wildcard itemId")
        void shouldCompleteAllMatchingNodesWithWildcard() throws ExecutionException, InterruptedException, TimeoutException {
            // Given - multiple items waiting for same node
            when(pendingSignalService.shouldAutoExecute(anyString(), anyString(), anyString())).thenReturn(false);
            CompletableFuture<Void> future1 = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");
            CompletableFuture<Void> future2 = scheduler.awaitProceed("run-1", "item-2", "mcp:step1");
            CompletableFuture<Void> future3 = scheduler.awaitProceed("run-1", "item-3", "mcp:step1");

            // When - signal with wildcard
            scheduler.signalProceed("run-1", "*", "mcp:step1");

            // Then - all should be done
            assertTrue(future1.isDone());
            assertTrue(future2.isDone());
            assertTrue(future3.isDone());
        }

        @Test
        @DisplayName("Should not affect other runs with wildcard")
        void shouldNotAffectOtherRunsWithWildcard() {
            // Given
            when(pendingSignalService.shouldAutoExecute(anyString(), anyString(), anyString())).thenReturn(false);
            CompletableFuture<Void> run1Future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");
            CompletableFuture<Void> run2Future = scheduler.awaitProceed("run-2", "item-1", "mcp:step1");

            // When - signal run-1 with wildcard
            scheduler.signalProceed("run-1", "*", "mcp:step1");

            // Then
            assertTrue(run1Future.isDone());
            assertFalse(run2Future.isDone());
        }
    }

    @Nested
    @DisplayName("cleanup()")
    class CleanupTests {

        @Test
        @DisplayName("Should cancel all pending futures for run")
        void shouldCancelAllPendingFuturesForRun() {
            // Given
            when(pendingSignalService.shouldAutoExecute(anyString(), anyString(), anyString())).thenReturn(false);
            CompletableFuture<Void> future1 = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");
            CompletableFuture<Void> future2 = scheduler.awaitProceed("run-1", "item-2", "mcp:step2");

            // When
            scheduler.cleanup("run-1");

            // Then - futures should be done (cancelled)
            assertTrue(future1.isDone() || future1.isCompletedExceptionally() || future1.isCancelled());
            assertTrue(future2.isDone() || future2.isCompletedExceptionally() || future2.isCancelled());
        }

        @Test
        @DisplayName("Should not affect other runs")
        void shouldNotAffectOtherRuns() {
            // Given
            when(pendingSignalService.shouldAutoExecute(anyString(), anyString(), anyString())).thenReturn(false);
            CompletableFuture<Void> run1Future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");
            CompletableFuture<Void> run2Future = scheduler.awaitProceed("run-2", "item-1", "mcp:step1");

            // When
            scheduler.cleanup("run-1");

            // Then - run1 should be done/cancelled, run2 should still be pending
            assertTrue(run1Future.isDone() || run1Future.isCompletedExceptionally() || run1Future.isCancelled());
            assertFalse(run2Future.isDone());
            assertFalse(run2Future.isCancelled());
        }

        @Test
        @DisplayName("Should handle cleanup with no pending nodes")
        void shouldHandleCleanupWithNoPendingNodes() {
            // When/Then - should not throw
            assertDoesNotThrow(() -> scheduler.cleanup("non-existent-run"));
        }
    }

    @Nested
    @DisplayName("getPendingNodeIds()")
    class GetPendingNodeIdsTests {

        @Test
        @DisplayName("Should return pending node IDs from DB")
        void shouldReturnPendingNodeIdsFromDb() {
            // Given
            when(pendingSignalService.getPendingNodeIds("run-1")).thenReturn(Set.of("mcp:step1", "mcp:step2"));

            // When
            Set<String> pending = scheduler.getPendingNodeIds("run-1");

            // Then
            assertEquals(2, pending.size());
            assertTrue(pending.contains("mcp:step1"));
            assertTrue(pending.contains("mcp:step2"));
        }

        @Test
        @DisplayName("Should return empty set when no pending nodes")
        void shouldReturnEmptySetWhenNoPendingNodes() {
            // Given
            when(pendingSignalService.getPendingNodeIds("run-1")).thenReturn(Set.of());

            // When
            Set<String> pending = scheduler.getPendingNodeIds("run-1");

            // Then
            assertTrue(pending.isEmpty());
        }
    }

    @Nested
    @DisplayName("isNodeWaiting()")
    class IsNodeWaitingTests {

        @Test
        @DisplayName("Should return true when node is waiting in DB")
        void shouldReturnTrueWhenNodeWaiting() {
            // Given
            when(pendingSignalService.isPending("run-1", "item-1", "mcp:step1")).thenReturn(true);

            // When
            boolean waiting = scheduler.isNodeWaiting("run-1", "item-1", "mcp:step1");

            // Then
            assertTrue(waiting);
        }

        @Test
        @DisplayName("Should return false when node is not waiting")
        void shouldReturnFalseWhenNodeNotWaiting() {
            // Given
            when(pendingSignalService.isPending("run-1", "item-1", "mcp:step1")).thenReturn(false);

            // When
            boolean waiting = scheduler.isNodeWaiting("run-1", "item-1", "mcp:step1");

            // Then
            assertFalse(waiting);
        }
    }

    @Nested
    @DisplayName("getPendingCount()")
    class GetPendingCountTests {

        @Test
        @DisplayName("Should return 0 when no pending nodes")
        void shouldReturn0WhenNoPendingNodes() {
            // Given
            when(pendingSignalService.getPendingNodeIds("run-1")).thenReturn(Set.of());

            // When
            int count = scheduler.getPendingCount("run-1");

            // Then
            assertEquals(0, count);
        }

        @Test
        @DisplayName("Should return correct count from DB")
        void shouldReturnCorrectCount() {
            // Given
            when(pendingSignalService.getPendingNodeIds("run-1")).thenReturn(Set.of("mcp:step1", "mcp:step2", "mcp:step3"));

            // When
            int count = scheduler.getPendingCount("run-1");

            // Then
            assertEquals(3, count);
        }
    }

    @Nested
    @DisplayName("Timeout behavior")
    class TimeoutBehaviorTests {

        @Test
        @DisplayName("Should timeout after configured period")
        void shouldTimeoutAfterConfiguredPeriod() {
            // Given
            when(pendingSignalService.shouldAutoExecute("run-1", "item-1", "mcp:step1")).thenReturn(false);
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");

            // When - try to wait with a short timeout (the scheduler has 30 min timeout internally)
            assertThrows(TimeoutException.class, () -> {
                future.get(100, TimeUnit.MILLISECONDS);
            });
        }
    }

    @Nested
    @DisplayName("Integration scenarios")
    class IntegrationScenariosTests {

        @Test
        @DisplayName("Should support step-by-step execution flow")
        void shouldSupportStepByStepFlow() throws ExecutionException, InterruptedException, TimeoutException {
            // Given
            String runId = "workflow-run-123";
            String itemId = "item-1";
            when(pendingSignalService.shouldAutoExecute(anyString(), anyString(), anyString())).thenReturn(false);

            // When - first node waits
            CompletableFuture<Void> step1 = scheduler.awaitProceed(runId, itemId, "mcp:step1");
            assertFalse(step1.isDone());

            // Signal step 1
            scheduler.signalProceed(runId, itemId, "mcp:step1");
            step1.get(100, TimeUnit.MILLISECONDS);

            // Next step waits
            CompletableFuture<Void> step2 = scheduler.awaitProceed(runId, itemId, "mcp:step2");
            assertFalse(step2.isDone());

            // Signal step 2
            scheduler.signalProceed(runId, itemId, "mcp:step2");
            step2.get(100, TimeUnit.MILLISECONDS);

            // Then - all completed
            assertTrue(step1.isDone());
            assertTrue(step2.isDone());

            // Cleanup
            scheduler.cleanup(runId);
        }

        @Test
        @DisplayName("Should handle parallel items in forEach scenario")
        void shouldHandleParallelItemsInForEach() throws ExecutionException, InterruptedException, TimeoutException {
            // Given - forEach spawns 3 items
            String runId = "run-1";
            when(pendingSignalService.shouldAutoExecute(anyString(), anyString(), anyString())).thenReturn(false);

            CompletableFuture<Void> item1 = scheduler.awaitProceed(runId, "item-1.1", "mcp:body_step");
            CompletableFuture<Void> item2 = scheduler.awaitProceed(runId, "item-1.2", "mcp:body_step");
            CompletableFuture<Void> item3 = scheduler.awaitProceed(runId, "item-1.3", "mcp:body_step");

            // When - signal all with wildcard
            scheduler.signalProceed(runId, "*", "mcp:body_step");

            // Then - all should complete
            assertTrue(item1.isDone());
            assertTrue(item2.isDone());
            assertTrue(item3.isDone());

            item1.get(100, TimeUnit.MILLISECONDS);
            item2.get(100, TimeUnit.MILLISECONDS);
            item3.get(100, TimeUnit.MILLISECONDS);
        }
    }
}
