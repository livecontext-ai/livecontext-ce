package com.apimarketplace.orchestrator.domain;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowExecutionContext")
class WorkflowExecutionContextTest {

    private WorkflowExecutionContext context;

    @BeforeEach
    void setUp() {
        context = new WorkflowExecutionContext("wf-1", "run-1", "tenant-1");
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should initialize with provided fields")
        void shouldInitializeWithFields() {
            assertEquals("wf-1", context.getWorkflowId());
            assertEquals("run-1", context.getRunId());
            assertEquals("tenant-1", context.getTenantId());
            assertNotNull(context.getStartTime());
        }

        @Test
        @DisplayName("Should initialize empty maps")
        void shouldInitializeEmptyMaps() {
            assertTrue(context.getDataContext().isEmpty());
            assertTrue(context.getStepOutputs().isEmpty());
            assertTrue(context.getStepStatuses().isEmpty());
            assertTrue(context.getGlobalVariables().isEmpty());
            assertTrue(context.getLoopStates().isEmpty());
        }

        @Test
        @DisplayName("Should initialize iterations to 0")
        void shouldInitializeIterationsToZero() {
            assertEquals(0, context.getCurrentIteration());
            assertEquals(0, context.getCurrentItemIndex());
        }

        @Test
        @DisplayName("Default constructor should create empty context")
        void defaultConstructorShouldCreateEmpty() {
            WorkflowExecutionContext ctx = new WorkflowExecutionContext();
            assertNull(ctx.getWorkflowId());
            assertNull(ctx.getRunId());
            assertNull(ctx.getTenantId());
            assertNotNull(ctx.getStartTime());
            assertTrue(ctx.isEmpty());
        }
    }

    @Nested
    @DisplayName("Data context operations")
    class DataContextTests {

        @Test
        @DisplayName("Should set and get data item")
        void shouldSetAndGetDataItem() {
            context.setDataItem("data1", Map.of("key", "value"));
            assertEquals(Map.of("key", "value"), context.getDataItem("data1"));
        }

        @Test
        @DisplayName("Should return null for non-existent data item")
        void shouldReturnNullForNonExistent() {
            assertNull(context.getDataItem("nonexistent"));
        }

        @Test
        @DisplayName("Should treat null value write as remove (no NPE on ConcurrentHashMap)")
        void nullWriteShouldBeTreatedAsRemove() {
            // Regression: V2TemplateAdapter.convertToV1Context forwards nullable trigger
            // fields (offset, previous_row, etc. on event-driven datasource triggers)
            // directly into setDataItem. ConcurrentHashMap rejects null values, so the
            // previous implementation threw NPE and killed the downstream set node before
            // a single field could be resolved. setDataItem(null) must be a no-throw no-op.
            context.setDataItem("k", "before");
            assertDoesNotThrow(() -> context.setDataItem("k", null));
            assertNull(context.getDataItem("k"));
            assertFalse(context.getDataContext().containsKey("k"));

            assertDoesNotThrow(() -> context.setDataItem("never-set", null));
            assertNull(context.getDataItem("never-set"));
        }
    }

    @Nested
    @DisplayName("Step output operations")
    class StepOutputTests {

        @Test
        @DisplayName("Should set and get step output")
        void shouldSetAndGetStepOutput() {
            context.setStepOutput("step1", Map.of("result", "ok"));
            assertEquals(Map.of("result", "ok"), context.getStepOutput("step1"));
        }

        @Test
        @DisplayName("Should return null for non-existent step output")
        void shouldReturnNullForNonExistent() {
            assertNull(context.getStepOutput("nonexistent"));
        }

        @Test
        @DisplayName("Should treat null step output write as remove (no NPE on ConcurrentHashMap)")
        void nullWriteShouldBeTreatedAsRemove() {
            context.setStepOutput("s", "before");
            assertDoesNotThrow(() -> context.setStepOutput("s", null));
            assertNull(context.getStepOutput("s"));
            assertFalse(context.getStepOutputs().containsKey("s"));
        }
    }

    @Nested
    @DisplayName("Step status operations")
    class StepStatusTests {

        @Test
        @DisplayName("Should set and get step status")
        void shouldSetAndGetStepStatus() {
            context.setStepStatus("step1", NodeStatus.COMPLETED);
            assertEquals(NodeStatus.COMPLETED, context.getStepStatus("step1"));
        }

        @Test
        @DisplayName("Should default to PENDING for unknown step")
        void shouldDefaultToPending() {
            assertEquals(NodeStatus.PENDING, context.getStepStatus("unknown"));
        }

        @Test
        @DisplayName("isStepCompleted should return true for completed step")
        void isStepCompletedShouldWork() {
            context.setStepStatus("step1", NodeStatus.COMPLETED);
            assertTrue(context.isStepCompleted("step1"));
            assertFalse(context.isStepCompleted("step2"));
        }

        @Test
        @DisplayName("isStepFailed should return true for failed step")
        void isStepFailedShouldWork() {
            context.setStepStatus("step1", NodeStatus.FAILED);
            assertTrue(context.isStepFailed("step1"));
            assertFalse(context.isStepFailed("step2"));
        }
    }

    @Nested
    @DisplayName("Step persistence methods")
    class StepPersistenceTests {

        @Test
        @DisplayName("Should set and get step tool ID")
        void shouldSetAndGetStepToolId() {
            context.setStepToolId("step1", "tool-123");
            assertEquals("tool-123", context.getStepToolId("step1"));
        }

        @Test
        @DisplayName("Should set and get step params")
        void shouldSetAndGetStepParams() {
            Map<String, Object> params = Map.of("url", "https://api.example.com");
            context.setStepParams("step1", params);
            assertEquals(params, context.getStepParams("step1"));
        }

        @Test
        @DisplayName("Should set and get step start time")
        void shouldSetAndGetStepStartTime() {
            Instant now = Instant.now();
            context.setStepStartTime("step1", now);
            assertEquals(now, context.getStepStartTime("step1"));
        }
    }

    @Nested
    @DisplayName("Global variable operations")
    class GlobalVariableTests {

        @Test
        @DisplayName("Should set and get global variable")
        void shouldSetAndGetGlobalVariable() {
            context.setGlobalVariable("count", 42);
            assertEquals(42, context.getGlobalVariable("count"));
        }

        @Test
        @DisplayName("Should increment existing numeric global variable")
        void shouldIncrementExistingNumeric() {
            context.setGlobalVariable("count", 5);
            context.incrementGlobalVariable("count");
            assertEquals(6, context.getGlobalVariable("count"));
        }

        @Test
        @DisplayName("Should initialize to 1 when incrementing non-existent variable")
        void shouldInitializeOnIncrement() {
            context.incrementGlobalVariable("counter");
            assertEquals(1, context.getGlobalVariable("counter"));
        }

        @Test
        @DisplayName("Should initialize to 1 when incrementing non-number variable")
        void shouldInitializeOnIncrementNonNumber() {
            context.setGlobalVariable("counter", "not-a-number");
            context.incrementGlobalVariable("counter");
            assertEquals(1, context.getGlobalVariable("counter"));
        }

        @Test
        @DisplayName("getGlobalVariableAsInt should return int value")
        void getGlobalVariableAsIntShouldWork() {
            context.setGlobalVariable("count", 42);
            assertEquals(42, context.getGlobalVariableAsInt("count"));
        }

        @Test
        @DisplayName("getGlobalVariableAsInt should return 0 for non-existent")
        void getGlobalVariableAsIntShouldReturnZero() {
            assertEquals(0, context.getGlobalVariableAsInt("nonexistent"));
        }

        @Test
        @DisplayName("getGlobalVariableAsInt should return 0 for non-number")
        void getGlobalVariableAsIntShouldReturnZeroForNonNumber() {
            context.setGlobalVariable("key", "not-a-number");
            assertEquals(0, context.getGlobalVariableAsInt("key"));
        }
    }

    @Nested
    @DisplayName("Loop state operations")
    class LoopStateTests {

        @Test
        @DisplayName("Should set and get loop state")
        void shouldSetAndGetLoopState() {
            context.setLoopState("loop1", Map.of("iteration", 3));
            assertEquals(Map.of("iteration", 3), context.getLoopState("loop1"));
        }

        @Test
        @DisplayName("Should increment loop iteration")
        void shouldIncrementLoopIteration() {
            assertEquals(0, context.getLoopIteration("loop1"));
            context.incrementLoopIteration("loop1");
            assertEquals(1, context.getLoopIteration("loop1"));
            context.incrementLoopIteration("loop1");
            assertEquals(2, context.getLoopIteration("loop1"));
        }

        @Test
        @DisplayName("Should set and get loop hash")
        void shouldSetAndGetLoopHash() {
            context.setLoopHash("loop1", "hash-abc");
            assertEquals("hash-abc", context.getLoopHash("loop1"));
        }

        @Test
        @DisplayName("getLoopHash should return null for non-existent")
        void getLoopHashShouldReturnNull() {
            assertNull(context.getLoopHash("nonexistent"));
        }

        @Test
        @DisplayName("Should add and check loop hashes")
        void shouldAddAndCheckLoopHashes() {
            assertFalse(context.hasSeenHash("loop1", "hash-1"));

            context.addLoopHash("loop1", "hash-1");
            assertTrue(context.hasSeenHash("loop1", "hash-1"));
            assertFalse(context.hasSeenHash("loop1", "hash-2"));

            context.addLoopHash("loop1", "hash-2");
            assertTrue(context.hasSeenHash("loop1", "hash-2"));
        }

        @Test
        @DisplayName("hasSeenHash should return false when no hashes added")
        void hasSeenHashShouldReturnFalseWhenEmpty() {
            assertFalse(context.hasSeenHash("loop1", "any"));
        }
    }

    @Nested
    @DisplayName("Iteration management")
    class IterationManagementTests {

        @Test
        @DisplayName("Should increment and reset iteration")
        void shouldIncrementAndResetIteration() {
            context.incrementIteration();
            assertEquals(1, context.getCurrentIteration());
            context.incrementIteration();
            assertEquals(2, context.getCurrentIteration());
            context.resetIteration();
            assertEquals(0, context.getCurrentIteration());
        }

        @Test
        @DisplayName("Should set current iteration")
        void shouldSetCurrentIteration() {
            context.setCurrentIteration(10);
            assertEquals(10, context.getCurrentIteration());
        }

        @Test
        @DisplayName("Should increment and reset item index")
        void shouldIncrementAndResetItemIndex() {
            context.incrementItemIndex();
            assertEquals(1, context.getCurrentItemIndex());
            context.incrementItemIndex();
            assertEquals(2, context.getCurrentItemIndex());
            context.resetItemIndex();
            assertEquals(0, context.getCurrentItemIndex());
        }

        @Test
        @DisplayName("Should set current item index")
        void shouldSetCurrentItemIndex() {
            context.setCurrentItemIndex(5);
            assertEquals(5, context.getCurrentItemIndex());
        }
    }

    @Nested
    @DisplayName("DAG architecture methods")
    class DagMethodTests {

        @Test
        @DisplayName("setGlobalData should merge into global variables")
        void shouldMergeGlobalData() {
            context.setGlobalVariable("existing", "value");
            context.setGlobalData(Map.of("new", "data"));

            assertEquals("value", context.getGlobalVariable("existing"));
            assertEquals("data", context.getGlobalVariable("new"));
        }

        @Test
        @DisplayName("setGlobalData should ignore null")
        void shouldIgnoreNull() {
            context.setGlobalVariable("existing", "value");
            context.setGlobalData(null);
            assertEquals("value", context.getGlobalVariable("existing"));
        }

        @Test
        @DisplayName("getGlobalData should return a copy")
        void shouldReturnCopy() {
            context.setGlobalVariable("key", "value");
            Map<String, Object> data = context.getGlobalData();
            data.put("extra", "added");

            // Original should not be affected
            assertNull(context.getGlobalVariable("extra"));
        }
    }

    @Nested
    @DisplayName("cleanup()")
    class CleanupTests {

        @Test
        @DisplayName("Should clear all maps")
        void shouldClearAllMaps() {
            context.setDataItem("data1", "value");
            context.setStepOutput("step1", "output");
            context.setStepStatus("step1", NodeStatus.COMPLETED);
            context.setGlobalVariable("var1", "val");
            context.setLoopState("loop1", "state");
            context.setStepToolId("step1", "tool");
            context.setStepParams("step1", Map.of());
            context.setStepStartTime("step1", Instant.now());

            assertFalse(context.isEmpty());

            context.cleanup();

            assertTrue(context.isEmpty());
            assertEquals(0, context.getDataSize());
        }
    }

    @Nested
    @DisplayName("isEmpty() and getDataSize()")
    class IsEmptyAndSizeTests {

        @Test
        @DisplayName("Should return true when empty")
        void shouldReturnTrueWhenEmpty() {
            assertTrue(context.isEmpty());
            assertEquals(0, context.getDataSize());
        }

        @Test
        @DisplayName("Should return false when data exists")
        void shouldReturnFalseWhenDataExists() {
            context.setDataItem("key", "value");
            assertFalse(context.isEmpty());
            assertEquals(1, context.getDataSize());
        }

        @Test
        @DisplayName("Should count all maps for data size")
        void shouldCountAllMaps() {
            context.setDataItem("d1", "v");
            context.setStepOutput("s1", "o");
            context.setStepStatus("s1", NodeStatus.RUNNING);
            context.setStepToolId("s1", "t");
            context.setStepParams("s1", Map.of());
            context.setStepStartTime("s1", Instant.now());

            assertEquals(6, context.getDataSize());
        }
    }
}
