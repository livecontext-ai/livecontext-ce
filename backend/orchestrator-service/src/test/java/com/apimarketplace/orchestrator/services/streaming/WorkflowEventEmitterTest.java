package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowEventEmitter")
class WorkflowEventEmitterTest {

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private RunContextRegistry contextRegistry;

    @Mock
    private WorkflowMetrics workflowMetrics;

    private WorkflowEventEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new WorkflowEventEmitter(eventPublisher, contextRegistry, workflowMetrics);
    }

    @Nested
    @DisplayName("isTerminalStatus()")
    class IsTerminalStatusTests {

        @ParameterizedTest
        @ValueSource(strings = {"COMPLETED", "FAILED", "CANCELLED", "PARTIAL_SUCCESS"})
        @DisplayName("Should return true for terminal statuses")
        void shouldReturnTrueForTerminalStatuses(String status) {
            assertTrue(emitter.isTerminalStatus(status));
        }

        @ParameterizedTest
        @ValueSource(strings = {"RUNNING", "PENDING", "READY", "UNKNOWN"})
        @DisplayName("Should return false for non-terminal statuses")
        void shouldReturnFalseForNonTerminalStatuses(String status) {
            assertFalse(emitter.isTerminalStatus(status));
        }

        @ParameterizedTest
        @NullSource
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull(String status) {
            assertFalse(emitter.isTerminalStatus(status));
        }
    }

    @Nested
    @DisplayName("buildConnectionEventData()")
    class BuildConnectionEventDataTests {

        @Test
        @DisplayName("Should build connection event data")
        void shouldBuildConnectionEventData() {
            Map<String, Object> result = emitter.buildConnectionEventData("run-1", "Connected successfully");

            assertEquals("Connected successfully", result.get("message"));
            assertEquals("CONNECTED", result.get("status"));
            assertEquals("run-1", result.get("runId"));
            assertNotNull(result.get("timestamp"));
        }
    }

    @Nested
    @DisplayName("buildFinalStatusEventData()")
    class BuildFinalStatusEventDataTests {

        @Test
        @DisplayName("Should build final status event data")
        void shouldBuildFinalStatusEventData() {
            Map<String, Object> result = emitter.buildFinalStatusEventData("run-1", "COMPLETED");

            assertEquals("workflowStatus", result.get("type"));
            assertEquals("run-1", result.get("runId"));
            assertEquals("COMPLETED", result.get("status"));
            assertEquals("Workflow execution completed", result.get("message"));
            assertEquals(false, result.get("isRunning"));
            assertNotNull(result.get("timestamp"));
        }
    }

    @Nested
    @DisplayName("isFinalized() and markFinalized()")
    class FinalizationTests {

        @Test
        @DisplayName("Should delegate isFinalized to contextRegistry")
        void shouldDelegateIsFinalized() {
            when(contextRegistry.isFinalized("run-1")).thenReturn(true);

            assertTrue(emitter.isFinalized("run-1"));
            verify(contextRegistry).isFinalized("run-1");
        }

        @Test
        @DisplayName("Should delegate markFinalized to contextRegistry")
        void shouldDelegateMarkFinalized() {
            emitter.markFinalized("run-1");

            verify(contextRegistry).markFinalized("run-1");
        }
    }
}
