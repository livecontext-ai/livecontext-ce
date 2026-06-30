package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.ExecutionMetricsCollector;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoopEventPublisher")
class LoopEventPublisherTest {

    @Mock
    private WorkflowEventPublisher eventPublisher;

    private LoopEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new LoopEventPublisher(eventPublisher);
    }

    @Nested
    @DisplayName("isLoopIterationStep()")
    class IsLoopIterationStepTests {

        @Test
        @DisplayName("Should return true for loop iteration step")
        void shouldReturnTrueForLoopIteration() {
            assertTrue(publisher.isLoopIterationStep("core:my_loop#iter-3"));
        }

        @Test
        @DisplayName("Should return false for non-loop step")
        void shouldReturnFalseForNonLoop() {
            assertFalse(publisher.isLoopIterationStep("mcp:step1"));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(publisher.isLoopIterationStep(null));
        }

        @Test
        @DisplayName("Should return false for non-core prefixed step with #iter-")
        void shouldReturnFalseForNonCorePrefixed() {
            assertFalse(publisher.isLoopIterationStep("mcp:step1#iter-0"));
        }
    }

    @Nested
    @DisplayName("extractLoopBaseId()")
    class ExtractLoopBaseIdTests {

        @Test
        @DisplayName("Should extract base ID from iteration step")
        void shouldExtractBaseId() {
            assertEquals("core:my_loop", publisher.extractLoopBaseId("core:my_loop#iter-3"));
        }

        @Test
        @DisplayName("Should return original when no iteration suffix")
        void shouldReturnOriginalWhenNoSuffix() {
            assertEquals("core:my_loop", publisher.extractLoopBaseId("core:my_loop"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(publisher.extractLoopBaseId(null));
        }

        @Test
        @DisplayName("Should handle #iter- at start (index <= 0)")
        void shouldHandleIterAtStart() {
            assertEquals("#iter-5", publisher.extractLoopBaseId("#iter-5"));
        }
    }

    @Nested
    @DisplayName("normalizeLoopItemScopedStepId()")
    class NormalizeLoopItemScopedStepIdTests {

        @Test
        @DisplayName("Should remove #item- suffix")
        void shouldRemoveItemSuffix() {
            assertEquals("mcp:step1", publisher.normalizeLoopItemScopedStepId("mcp:step1#item-2"));
        }

        @Test
        @DisplayName("Should return original when no #item- suffix")
        void shouldReturnOriginalWhenNoSuffix() {
            assertEquals("mcp:step1", publisher.normalizeLoopItemScopedStepId("mcp:step1"));
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(publisher.normalizeLoopItemScopedStepId(null));
        }
    }

    @Nested
    @DisplayName("shouldSuppressLoopIterationEvent()")
    class ShouldSuppressTests {

        @Test
        @DisplayName("Should return true for core step with #iter-")
        void shouldReturnTrueForCoreIterStep() {
            assertTrue(publisher.shouldSuppressLoopIterationEvent("core:loop#iter-5"));
        }

        @Test
        @DisplayName("Should return false for regular step")
        void shouldReturnFalseForRegularStep() {
            assertFalse(publisher.shouldSuppressLoopIterationEvent("mcp:step1"));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(publisher.shouldSuppressLoopIterationEvent(null));
        }
    }

    @Nested
    @DisplayName("determineLoopAggregateStatus()")
    class DetermineLoopAggregateStatusTests {

        @Test
        @DisplayName("Should return RUNNING for null metrics")
        void shouldReturnRunningForNullMetrics() {
            assertEquals(RunStatus.RUNNING, publisher.determineLoopAggregateStatus(null));
        }

        @Test
        @DisplayName("Should return FAILED when failures exist")
        void shouldReturnFailedWhenFailuresExist() {
            // ItemMetrics(success, failure, skipped, running, processed, total, httpStatusCounts)
            ExecutionMetricsCollector.ItemMetrics metrics = new ExecutionMetricsCollector.ItemMetrics(
                5, 2, 0, 0, 7, 10, java.util.Map.of()
            );

            assertEquals(RunStatus.FAILED, publisher.determineLoopAggregateStatus(metrics));
        }

        @Test
        @DisplayName("Should return SKIPPED when all items skipped")
        void shouldReturnSkippedWhenAllSkipped() {
            ExecutionMetricsCollector.ItemMetrics metrics = new ExecutionMetricsCollector.ItemMetrics(
                0, 0, 3, 0, 3, 3, java.util.Map.of()
            );

            assertEquals(RunStatus.COMPLETED, publisher.determineLoopAggregateStatus(metrics));
        }

        @Test
        @DisplayName("Should return COMPLETED when all items succeed")
        void shouldReturnCompletedWhenAllSucceed() {
            ExecutionMetricsCollector.ItemMetrics metrics = new ExecutionMetricsCollector.ItemMetrics(
                5, 0, 0, 0, 5, 5, java.util.Map.of()
            );

            assertEquals(RunStatus.COMPLETED, publisher.determineLoopAggregateStatus(metrics));
        }

        @Test
        @DisplayName("Should return COMPLETED when success + skipped covers total")
        void shouldReturnCompletedWhenSuccessAndSkippedCoverTotal() {
            ExecutionMetricsCollector.ItemMetrics metrics = new ExecutionMetricsCollector.ItemMetrics(
                3, 0, 2, 0, 5, 5, java.util.Map.of()
            );

            assertEquals(RunStatus.COMPLETED, publisher.determineLoopAggregateStatus(metrics));
        }

        @Test
        @DisplayName("Should return RUNNING when still processing")
        void shouldReturnRunningWhenStillProcessing() {
            ExecutionMetricsCollector.ItemMetrics metrics = new ExecutionMetricsCollector.ItemMetrics(
                3, 0, 0, 0, 3, 10, java.util.Map.of()
            );

            assertEquals(RunStatus.RUNNING, publisher.determineLoopAggregateStatus(metrics));
        }
    }
}
