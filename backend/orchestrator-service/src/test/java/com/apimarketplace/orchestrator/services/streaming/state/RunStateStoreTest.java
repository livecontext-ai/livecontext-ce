package com.apimarketplace.orchestrator.services.streaming.state;

import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunStateStore")
class RunStateStoreTest {

    @Mock
    private RunContextRegistry contextRegistry;

    private RunStateStore runStateStore;

    @BeforeEach
    void setUp() {
        runStateStore = new RunStateStore(contextRegistry);
    }

    @Nested
    @DisplayName("getOrCreateRunState()")
    class GetOrCreateRunStateTests {

        @Test
        @DisplayName("Should delegate to contextRegistry")
        void shouldDelegateToContextRegistry() {
            RunState mockState = mock(RunState.class);
            when(contextRegistry.getRunState("run-1")).thenReturn(mockState);

            RunState result = runStateStore.getOrCreateRunState("run-1");

            assertSame(mockState, result);
            verify(contextRegistry).getRunState("run-1");
        }
    }

    @Nested
    @DisplayName("snapshot()")
    class SnapshotTests {

        @Test
        @DisplayName("Should delegate to contextRegistry")
        void shouldDelegateToContextRegistry() {
            when(contextRegistry.snapshot("run-1")).thenReturn(null);

            RunStateStore.RunSnapshot result = runStateStore.snapshot("run-1");

            assertNull(result);
            verify(contextRegistry).snapshot("run-1");
        }
    }

    @Nested
    @DisplayName("purge()")
    class PurgeTests {

        @Test
        @DisplayName("Should handle null runId gracefully")
        void shouldHandleNullRunId() {
            assertDoesNotThrow(() -> runStateStore.purge(null));
        }

        @Test
        @DisplayName("Should not close context directly")
        void shouldNotCloseContextDirectly() {
            runStateStore.purge("run-1");
            verify(contextRegistry, never()).close(anyString());
        }
    }

    @Nested
    @DisplayName("RunSnapshot record")
    class RunSnapshotTests {

        @Test
        @DisplayName("Should store all fields")
        void shouldStoreAllFields() {
            RunStateStore.RunSnapshot snapshot = new RunStateStore.RunSnapshot(
                java.util.List.of(), java.util.List.of(),
                java.util.Map.of(), java.util.Map.of(),
                java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(),
                true
            );

            assertTrue(snapshot.terminal());
            assertNotNull(snapshot.steps());
            assertNotNull(snapshot.edges());
            assertNotNull(snapshot.workflowStatus());
            assertNotNull(snapshot.workflowStatistics());
            assertNotNull(snapshot.loops());
            assertNotNull(snapshot.merges());
            assertNotNull(snapshot.logs());
            assertNotNull(snapshot.agentToolCalls());
        }
    }
}
