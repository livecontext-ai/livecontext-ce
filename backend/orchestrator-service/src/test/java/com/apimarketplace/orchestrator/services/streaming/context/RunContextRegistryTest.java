package com.apimarketplace.orchestrator.services.streaming.context;

import com.apimarketplace.orchestrator.services.cache.RunCacheRegistry;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import com.apimarketplace.orchestrator.services.streaming.state.RunState;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
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
@DisplayName("RunContextRegistry")
class RunContextRegistryTest {

    @Mock
    private StateSnapshotService stateSnapshotService;

    @Mock
    private RunCacheRegistry cacheRegistry;

    private RunContextRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RunContextRegistry(stateSnapshotService, cacheRegistry);
    }

    @Nested
    @DisplayName("getOrCreate()")
    class GetOrCreateTests {

        @Test
        @DisplayName("Should create new context for new runId")
        void shouldCreateNewContext() {
            RunContext context = registry.getOrCreate("run-1");
            assertNotNull(context);
            assertEquals("run-1", context.getRunId());
        }

        @Test
        @DisplayName("Should return same context for same runId")
        void shouldReturnSameContext() {
            RunContext first = registry.getOrCreate("run-1");
            RunContext second = registry.getOrCreate("run-1");
            assertSame(first, second);
        }

        @Test
        @DisplayName("Should create different contexts for different runIds")
        void shouldCreateDifferentContexts() {
            RunContext ctx1 = registry.getOrCreate("run-1");
            RunContext ctx2 = registry.getOrCreate("run-2");
            assertNotSame(ctx1, ctx2);
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("Should return empty for unknown runId")
        void shouldReturnEmpty() {
            Optional<RunContext> result = registry.get("unknown");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return context when exists")
        void shouldReturnContext() {
            registry.getOrCreate("run-1");
            Optional<RunContext> result = registry.get("run-1");
            assertTrue(result.isPresent());
        }
    }

    @Nested
    @DisplayName("exists()")
    class ExistsTests {

        @Test
        @DisplayName("Should return false for unknown runId")
        void shouldReturnFalse() {
            assertFalse(registry.exists("unknown"));
        }

        @Test
        @DisplayName("Should return true after getOrCreate")
        void shouldReturnTrue() {
            registry.getOrCreate("run-1");
            assertTrue(registry.exists("run-1"));
        }
    }

    @Nested
    @DisplayName("close()")
    class CloseTests {

        @Test
        @DisplayName("Should close and remove context")
        void shouldCloseAndRemove() {
            registry.getOrCreate("run-1");
            boolean result = registry.close("run-1");

            assertTrue(result);
            assertFalse(registry.exists("run-1"));
        }

        @Test
        @DisplayName("Should return false for unknown runId")
        void shouldReturnFalseForUnknown() {
            boolean result = registry.close("unknown");
            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("closeAll()")
    class CloseAllTests {

        @Test
        @DisplayName("Should close all contexts")
        void shouldCloseAll() {
            registry.getOrCreate("run-1");
            registry.getOrCreate("run-2");

            registry.closeAll();

            assertEquals(0, registry.size());
        }
    }

    @Nested
    @DisplayName("getRunState()")
    class GetRunStateTests {

        @Test
        @DisplayName("Should return RunState from context")
        void shouldReturnRunState() {
            RunState state = registry.getRunState("run-1");
            assertNotNull(state);
        }
    }

    @Nested
    @DisplayName("getNodeState()")
    class GetNodeStateTests {

        @Test
        @DisplayName("Should return RunNodeState from context")
        void shouldReturnNodeState() {
            RunNodeState nodeState = registry.getNodeState("run-1");
            assertNotNull(nodeState);
            assertEquals("run-1", nodeState.getRunId());
        }
    }

    @Nested
    @DisplayName("snapshot()")
    class SnapshotTests {

        @Test
        @DisplayName("Should return null for unknown runId")
        void shouldReturnNullForUnknown() {
            assertNull(registry.snapshot("unknown"));
        }
    }

    @Nested
    @DisplayName("isFinalized() / markFinalized()")
    class FinalizedTests {

        @Test
        @DisplayName("Should return false when not finalized")
        void shouldReturnFalseInitially() {
            registry.getOrCreate("run-1");
            assertFalse(registry.isFinalized("run-1"));
        }

        @Test
        @DisplayName("Should return false for unknown runId")
        void shouldReturnFalseForUnknown() {
            assertFalse(registry.isFinalized("unknown"));
        }

        @Test
        @DisplayName("Should mark as finalized")
        void shouldMarkFinalized() {
            registry.getOrCreate("run-1");
            assertTrue(registry.markFinalized("run-1"));
            assertTrue(registry.isFinalized("run-1"));
        }

        @Test
        @DisplayName("Should return false when marking unknown runId")
        void shouldReturnFalseForUnknownMark() {
            assertFalse(registry.markFinalized("unknown"));
        }
    }

    @Nested
    @DisplayName("Batch emitter methods")
    class BatchEmitterTests {

        @Test
        @DisplayName("Should set and get last payload")
        void shouldSetAndGetPayload() {
            registry.getOrCreate("run-1");
            Map<String, Object> payload = Map.of("key", "value");
            registry.setLastPayload("run-1", payload);

            assertEquals(payload, registry.getLastPayload("run-1"));
        }

        @Test
        @DisplayName("Should return null for unknown runId")
        void shouldReturnNullForUnknown() {
            assertNull(registry.getLastPayload("unknown"));
        }

        @Test
        @DisplayName("Should not throw when setting payload on unknown runId")
        void shouldNotThrowForUnknownSet() {
            assertDoesNotThrow(() -> registry.setLastPayload("unknown", Map.of()));
        }
    }

    @Nested
    @DisplayName("Statistics")
    class StatisticsTests {

        @Test
        @DisplayName("Should return correct size")
        void shouldReturnCorrectSize() {
            assertEquals(0, registry.size());
            registry.getOrCreate("run-1");
            assertEquals(1, registry.size());
            registry.getOrCreate("run-2");
            assertEquals(2, registry.size());
        }

    }

    @Nested
    @DisplayName("cleanupStaleContexts()")
    class CleanupStaleContextsTests {

        @Test
        @DisplayName("Should not cleanup fresh contexts")
        void shouldNotCleanupFreshContexts() {
            registry.getOrCreate("run-1");

            registry.cleanupStaleContexts();

            // Context should still exist since it was just created
            assertTrue(registry.exists("run-1"));
        }
    }
}
