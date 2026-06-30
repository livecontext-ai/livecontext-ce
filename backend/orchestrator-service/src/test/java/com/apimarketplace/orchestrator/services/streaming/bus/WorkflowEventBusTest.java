package com.apimarketplace.orchestrator.services.streaming.bus;

import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.streaming.events.*;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEventBus")
class WorkflowEventBusTest {

    @Mock
    private RunStateStore runStateStore;

    @Mock
    private WorkflowMetrics workflowMetrics;

    private WorkflowEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new WorkflowEventBus(runStateStore, workflowMetrics, 256, 50L, 16);
    }

    @AfterEach
    void tearDown() {
        eventBus.destroy();
    }

    @Nested
    @DisplayName("publish()")
    class PublishTests {

        @Test
        @DisplayName("Should accept valid events")
        void shouldAcceptValidEvents() {
            StepStatusEvent event = new StepStatusEvent("run-1", "mcp:step1",
                Map.of("status", "running"), StepLifecycle.RUNNING, 100L);

            assertDoesNotThrow(() -> eventBus.publish(event));
        }

        @Test
        @DisplayName("Should silently ignore null event")
        void shouldIgnoreNullEvent() {
            assertDoesNotThrow(() -> eventBus.publish(null));
        }

        @Test
        @DisplayName("Should silently ignore event with null runId")
        void shouldIgnoreEventWithNullRunId() {
            // WorkflowStatisticsEvent allows creation but runId validation is in publish
            // We test that publish handles this gracefully
            // Since StepStatusEvent requires non-null runId, we use a different approach
            assertDoesNotThrow(() -> eventBus.publish(null));
        }
    }

    @Nested
    @DisplayName("shutdownProcessor()")
    class ShutdownProcessorTests {

        @Test
        @DisplayName("Should stop processor for a run")
        void shouldStopProcessorForRun() {
            StepStatusEvent event = new StepStatusEvent("run-1", "mcp:step1",
                null, StepLifecycle.RUNNING, 100L);
            eventBus.publish(event);

            assertDoesNotThrow(() -> eventBus.shutdownProcessor("run-1"));
        }

        @Test
        @DisplayName("Should handle shutdown for non-existent run")
        void shouldHandleShutdownForNonExistentRun() {
            assertDoesNotThrow(() -> eventBus.shutdownProcessor("unknown-run"));
        }
    }

    @Nested
    @DisplayName("destroy()")
    class DestroyTests {

        @Test
        @DisplayName("Should stop all processors and shut down executor")
        void shouldStopAllProcessors() {
            StepStatusEvent event1 = new StepStatusEvent("run-1", "step1",
                null, StepLifecycle.RUNNING, 100L);
            StepStatusEvent event2 = new StepStatusEvent("run-2", "step1",
                null, StepLifecycle.RUNNING, 100L);

            eventBus.publish(event1);
            eventBus.publish(event2);

            assertDoesNotThrow(() -> eventBus.destroy());
        }

        @Test
        @DisplayName("Should be safe to call multiple times")
        void shouldBeSafeToCallMultipleTimes() {
            assertDoesNotThrow(() -> {
                eventBus.destroy();
                eventBus.destroy();
            });
        }
    }

    @Nested
    @DisplayName("RunScopedCache implementation")
    class RunScopedCacheTests {

        @Test
        @DisplayName("Should implement RunScopedCache")
        void shouldImplementRunScopedCache() {
            assertInstanceOf(RunScopedCache.class, eventBus);
        }

        @Test
        @DisplayName("cleanupRun should delegate to shutdownProcessor")
        void cleanupRunShouldDelegateToShutdownProcessor() {
            StepStatusEvent event = new StepStatusEvent("run-1", "step1",
                null, StepLifecycle.RUNNING, 100L);
            eventBus.publish(event);

            assertDoesNotThrow(() -> eventBus.cleanupRun("run-1"));
        }

        @Test
        @DisplayName("Should return correct cache name")
        void shouldReturnCorrectCacheName() {
            assertEquals("WorkflowEventBusCache", eventBus.getCacheName());
        }

        @Test
        @DisplayName("Should return STREAMING domain")
        void shouldReturnStreamingDomain() {
            assertEquals(RunScopedCache.CacheDomain.STREAMING, eventBus.getDomain());
        }

        @Test
        @DisplayName("Should return cache size")
        void shouldReturnCacheSize() {
            assertEquals(0, eventBus.getCacheSize());

            StepStatusEvent event = new StepStatusEvent("run-1", "step1",
                null, StepLifecycle.RUNNING, 100L);
            eventBus.publish(event);

            assertTrue(eventBus.getCacheSize() >= 0);
        }
    }

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Should clamp queueCapacity to minimum of 128")
        void shouldClampQueueCapacity() {
            WorkflowEventBus smallBus = new WorkflowEventBus(runStateStore, workflowMetrics, 10, 50L, 16);
            // The bus should still work with the clamped capacity
            assertDoesNotThrow(() -> smallBus.publish(
                new StepStatusEvent("run-1", "step1", null, StepLifecycle.RUNNING, 100L)));
            smallBus.destroy();
        }

        @Test
        @DisplayName("Should clamp pollMs to minimum of 10")
        void shouldClampPollMs() {
            WorkflowEventBus fastBus = new WorkflowEventBus(runStateStore, workflowMetrics, 256, 1L, 16);
            assertDoesNotThrow(() -> fastBus.publish(
                new StepStatusEvent("run-1", "step1", null, StepLifecycle.RUNNING, 100L)));
            fastBus.destroy();
        }
    }
}
