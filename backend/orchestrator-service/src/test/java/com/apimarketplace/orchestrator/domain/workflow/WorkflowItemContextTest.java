package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowItemContext")
class WorkflowItemContextTest {

    @Mock
    private WorkflowExecution baseExecution;

    @Mock
    private TriggerItemContext triggerItem;

    private WorkflowItemContext context;

    @BeforeEach
    void setUp() {
        context = new WorkflowItemContext(baseExecution, triggerItem);
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {
        @Test
        @DisplayName("Should create context with base execution and trigger item")
        void shouldCreateContextWithBaseExecutionAndTriggerItem() {
            assertSame(baseExecution, context.getBaseExecution());
            assertSame(triggerItem, context.getTriggerItem());
        }

        @Test
        @DisplayName("Should initialize empty step statuses")
        void shouldInitializeEmptyStepStatuses() {
            assertNotNull(context.getStepStatuses());
            assertTrue(context.getStepStatuses().isEmpty());
        }

        @Test
        @DisplayName("Should initialize empty step outputs")
        void shouldInitializeEmptyStepOutputs() {
            assertNotNull(context.getStepOutputs());
            assertTrue(context.getStepOutputs().isEmpty());
        }
    }

    @Nested
    @DisplayName("Step status tracking")
    class StepStatusTrackingTests {
        @Test
        @DisplayName("Should track step status")
        void shouldTrackStepStatus() {
            context.getStepStatuses().put("mcp:step1", WorkflowItemContext.StepStatus.RUNNING);

            assertEquals(WorkflowItemContext.StepStatus.RUNNING, context.getStepStatuses().get("mcp:step1"));
        }

        @Test
        @DisplayName("Should update step status")
        void shouldUpdateStepStatus() {
            context.getStepStatuses().put("mcp:step1", WorkflowItemContext.StepStatus.PENDING);
            context.getStepStatuses().put("mcp:step1", WorkflowItemContext.StepStatus.RUNNING);
            context.getStepStatuses().put("mcp:step1", WorkflowItemContext.StepStatus.COMPLETED);

            assertEquals(WorkflowItemContext.StepStatus.COMPLETED, context.getStepStatuses().get("mcp:step1"));
        }

        @Test
        @DisplayName("Should track multiple step statuses")
        void shouldTrackMultipleStepStatuses() {
            context.getStepStatuses().put("mcp:step1", WorkflowItemContext.StepStatus.COMPLETED);
            context.getStepStatuses().put("mcp:step2", WorkflowItemContext.StepStatus.RUNNING);
            context.getStepStatuses().put("mcp:step3", WorkflowItemContext.StepStatus.PENDING);
            context.getStepStatuses().put("mcp:step4", WorkflowItemContext.StepStatus.FAILED);
            context.getStepStatuses().put("mcp:step5", WorkflowItemContext.StepStatus.SKIPPED);

            assertEquals(5, context.getStepStatuses().size());
            assertEquals(WorkflowItemContext.StepStatus.COMPLETED, context.getStepStatuses().get("mcp:step1"));
            assertEquals(WorkflowItemContext.StepStatus.FAILED, context.getStepStatuses().get("mcp:step4"));
            assertEquals(WorkflowItemContext.StepStatus.SKIPPED, context.getStepStatuses().get("mcp:step5"));
        }
    }

    @Nested
    @DisplayName("Step output tracking")
    class StepOutputTrackingTests {
        @Test
        @DisplayName("Should track step output")
        void shouldTrackStepOutput() {
            Map<String, Object> output = Map.of("result", "success", "data", 42);
            context.getStepOutputs().put("mcp:step1", output);

            assertEquals(output, context.getStepOutputs().get("mcp:step1"));
        }

        @Test
        @DisplayName("Should track multiple step outputs")
        void shouldTrackMultipleStepOutputs() {
            context.getStepOutputs().put("mcp:step1", Map.of("a", 1));
            context.getStepOutputs().put("mcp:step2", Map.of("b", 2));
            context.getStepOutputs().put("mcp:step3", Map.of("c", 3));

            assertEquals(3, context.getStepOutputs().size());
        }

        @Test
        @DisplayName("Should allow overwriting step output")
        @SuppressWarnings("unchecked")
        void shouldAllowOverwritingStepOutput() {
            context.getStepOutputs().put("mcp:step1", Map.of("old", "value"));
            context.getStepOutputs().put("mcp:step1", Map.of("new", "value"));

            Map<String, Object> output = (Map<String, Object>) context.getStepOutputs().get("mcp:step1");
            assertTrue(output.containsKey("new"));
            assertFalse(output.containsKey("old"));
        }
    }

    @Nested
    @DisplayName("StepStatus enum")
    class StepStatusEnumTests {
        @Test
        @DisplayName("Should have all expected statuses")
        void shouldHaveAllExpectedStatuses() {
            WorkflowItemContext.StepStatus[] statuses = WorkflowItemContext.StepStatus.values();

            assertEquals(5, statuses.length);
            assertNotNull(WorkflowItemContext.StepStatus.PENDING);
            assertNotNull(WorkflowItemContext.StepStatus.RUNNING);
            assertNotNull(WorkflowItemContext.StepStatus.COMPLETED);
            assertNotNull(WorkflowItemContext.StepStatus.FAILED);
            assertNotNull(WorkflowItemContext.StepStatus.SKIPPED);
        }

        @Test
        @DisplayName("Should convert from string")
        void shouldConvertFromString() {
            assertEquals(WorkflowItemContext.StepStatus.PENDING, WorkflowItemContext.StepStatus.valueOf("PENDING"));
            assertEquals(WorkflowItemContext.StepStatus.RUNNING, WorkflowItemContext.StepStatus.valueOf("RUNNING"));
            assertEquals(WorkflowItemContext.StepStatus.COMPLETED, WorkflowItemContext.StepStatus.valueOf("COMPLETED"));
            assertEquals(WorkflowItemContext.StepStatus.FAILED, WorkflowItemContext.StepStatus.valueOf("FAILED"));
            assertEquals(WorkflowItemContext.StepStatus.SKIPPED, WorkflowItemContext.StepStatus.valueOf("SKIPPED"));
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafetyTests {
        @Test
        @DisplayName("Should handle concurrent status updates")
        void shouldHandleConcurrentStatusUpdates() throws InterruptedException {
            int numThreads = 10;
            int operationsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch latch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            String stepId = "mcp:step_" + threadId + "_" + i;
                            context.getStepStatuses().put(stepId, WorkflowItemContext.StepStatus.RUNNING);
                            context.getStepStatuses().put(stepId, WorkflowItemContext.StepStatus.COMPLETED);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertEquals(numThreads * operationsPerThread, context.getStepStatuses().size());
        }

        @Test
        @DisplayName("Should handle concurrent output updates")
        void shouldHandleConcurrentOutputUpdates() throws InterruptedException {
            int numThreads = 10;
            int operationsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch latch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            String stepId = "mcp:step_" + threadId + "_" + i;
                            context.getStepOutputs().put(stepId, Map.of("value", i));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertEquals(numThreads * operationsPerThread, context.getStepOutputs().size());
        }
    }
}
