package com.apimarketplace.orchestrator.execution.v2.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("V2AutoScheduler Tests")
class V2AutoSchedulerTest {

    private V2AutoScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new V2AutoScheduler();
    }

    @Nested
    @DisplayName("getType()")
    class GetTypeTests {

        @Test
        @DisplayName("Should return AUTO type")
        void shouldReturnAutoType() {
            // When
            V2ExecutionScheduler.SchedulerType type = scheduler.getType();

            // Then
            assertEquals(V2ExecutionScheduler.SchedulerType.AUTO, type);
        }
    }

    @Nested
    @DisplayName("awaitProceed()")
    class AwaitProceedTests {

        @Test
        @DisplayName("Should return immediately completed future")
        void shouldReturnImmediatelyCompletedFuture() throws ExecutionException, InterruptedException, TimeoutException {
            // When
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");

            // Then
            assertNotNull(future);
            assertTrue(future.isDone());
            assertNull(future.get(100, TimeUnit.MILLISECONDS));
        }

        @Test
        @DisplayName("Should not block execution")
        void shouldNotBlockExecution() throws ExecutionException, InterruptedException, TimeoutException {
            // When
            long start = System.currentTimeMillis();
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");
            future.get(1, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - start;

            // Then - should complete almost instantly
            assertTrue(duration < 100, "Should complete in less than 100ms but took " + duration + "ms");
        }

        @Test
        @DisplayName("Should handle null runId")
        void shouldHandleNullRunId() {
            // When
            CompletableFuture<Void> future = scheduler.awaitProceed(null, "item-1", "mcp:step1");

            // Then
            assertNotNull(future);
            assertTrue(future.isDone());
        }

        @Test
        @DisplayName("Should handle null itemId")
        void shouldHandleNullItemId() {
            // When
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", null, "mcp:step1");

            // Then
            assertNotNull(future);
            assertTrue(future.isDone());
        }

        @Test
        @DisplayName("Should handle null nodeId")
        void shouldHandleNullNodeId() {
            // When
            CompletableFuture<Void> future = scheduler.awaitProceed("run-1", "item-1", null);

            // Then
            assertNotNull(future);
            assertTrue(future.isDone());
        }

        @Test
        @DisplayName("Should handle multiple concurrent calls")
        void shouldHandleMultipleConcurrentCalls() throws ExecutionException, InterruptedException, TimeoutException {
            // When
            CompletableFuture<Void> future1 = scheduler.awaitProceed("run-1", "item-1", "mcp:step1");
            CompletableFuture<Void> future2 = scheduler.awaitProceed("run-1", "item-1", "mcp:step2");
            CompletableFuture<Void> future3 = scheduler.awaitProceed("run-2", "item-1", "mcp:step1");

            // Then - all should be immediately completed
            assertTrue(future1.isDone());
            assertTrue(future2.isDone());
            assertTrue(future3.isDone());

            CompletableFuture.allOf(future1, future2, future3).get(1, TimeUnit.SECONDS);
        }
    }

    @Nested
    @DisplayName("signalProceed()")
    class SignalProceedTests {

        @Test
        @DisplayName("Should be a no-op (no exception)")
        void shouldBeNoOp() {
            // When/Then - should not throw exception
            assertDoesNotThrow(() -> scheduler.signalProceed("run-1", "item-1", "mcp:step1"));
        }

        @Test
        @DisplayName("Should handle null parameters")
        void shouldHandleNullParameters() {
            // When/Then - should not throw exception
            assertDoesNotThrow(() -> scheduler.signalProceed(null, null, null));
        }

        @Test
        @DisplayName("Should handle wildcard itemId")
        void shouldHandleWildcardItemId() {
            // When/Then - should not throw exception
            assertDoesNotThrow(() -> scheduler.signalProceed("run-1", "*", "mcp:step1"));
        }
    }

    @Nested
    @DisplayName("cleanup()")
    class CleanupTests {

        @Test
        @DisplayName("Should be a no-op (no exception)")
        void shouldBeNoOp() {
            // When/Then - should not throw exception
            assertDoesNotThrow(() -> scheduler.cleanup("run-1"));
        }

        @Test
        @DisplayName("Should handle null runId")
        void shouldHandleNullRunId() {
            // When/Then - should not throw exception
            assertDoesNotThrow(() -> scheduler.cleanup(null));
        }

        @Test
        @DisplayName("Should handle multiple cleanup calls")
        void shouldHandleMultipleCleanupCalls() {
            // When/Then - should not throw exception
            assertDoesNotThrow(() -> {
                scheduler.cleanup("run-1");
                scheduler.cleanup("run-1");
                scheduler.cleanup("run-2");
            });
        }
    }

    @Nested
    @DisplayName("Integration behavior")
    class IntegrationBehaviorTests {

        @Test
        @DisplayName("Should work correctly in auto execution flow")
        void shouldWorkInAutoExecutionFlow() throws ExecutionException, InterruptedException, TimeoutException {
            // Given - simulate auto workflow execution
            String runId = "workflow-run-123";
            String itemId = "item-1";

            // When - execute multiple nodes in sequence (simulating auto mode)
            CompletableFuture<Void> step1 = scheduler.awaitProceed(runId, itemId, "mcp:step1");
            step1.get(100, TimeUnit.MILLISECONDS);

            CompletableFuture<Void> step2 = scheduler.awaitProceed(runId, itemId, "mcp:step2");
            step2.get(100, TimeUnit.MILLISECONDS);

            CompletableFuture<Void> step3 = scheduler.awaitProceed(runId, itemId, "mcp:step3");
            step3.get(100, TimeUnit.MILLISECONDS);

            // Then - all should complete without blocking
            assertTrue(step1.isDone());
            assertTrue(step2.isDone());
            assertTrue(step3.isDone());

            // Cleanup
            scheduler.cleanup(runId);
        }

        @Test
        @DisplayName("Signal proceed should have no effect on awaitProceed")
        void signalProceedShouldHaveNoEffect() throws ExecutionException, InterruptedException, TimeoutException {
            // Given
            String runId = "run-1";
            String itemId = "item-1";
            String nodeId = "mcp:step1";

            // When - signal before await
            scheduler.signalProceed(runId, itemId, nodeId);
            CompletableFuture<Void> future = scheduler.awaitProceed(runId, itemId, nodeId);

            // Then - should still complete immediately (signal is no-op)
            assertTrue(future.isDone());
            future.get(100, TimeUnit.MILLISECONDS);
        }
    }
}
