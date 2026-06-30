package com.apimarketplace.orchestrator.services.streaming.context;

import com.apimarketplace.orchestrator.services.streaming.state.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunContext")
class RunContextTest {

    @Mock
    private RunState mockRunState;

    @Mock
    private RunNodeState mockNodeState;

    private RunContext context;

    @BeforeEach
    void setUp() {
        context = new RunContext("run-1", mockRunState, mockNodeState);
    }

    @Nested
    @DisplayName("Getters")
    class GetterTests {

        @Test
        @DisplayName("Should return runId")
        void shouldReturnRunId() {
            assertEquals("run-1", context.getRunId());
        }

        @Test
        @DisplayName("Should return RunState")
        void shouldReturnRunState() {
            assertSame(mockRunState, context.getRunState());
        }

        @Test
        @DisplayName("Should return RunNodeState")
        void shouldReturnRunNodeState() {
            assertSame(mockNodeState, context.getNodeState());
        }

        @Test
        @DisplayName("Should return createdAt timestamp")
        void shouldReturnCreatedAt() {
            assertTrue(context.getCreatedAt() > 0);
        }
    }

    @Nested
    @DisplayName("Batch emitter state")
    class BatchEmitterTests {

        @Test
        @DisplayName("Should return null lastPayload initially")
        void shouldReturnNullInitially() {
            assertNull(context.getLastPayload());
        }

        @Test
        @DisplayName("Should set and get lastPayload")
        void shouldSetAndGetPayload() {
            Map<String, Object> payload = Map.of("key", "value");
            context.setLastPayload(payload);

            assertEquals(payload, context.getLastPayload());
        }
    }

    @Nested
    @DisplayName("Finalization")
    class FinalizationTests {

        @Test
        @DisplayName("Should not be finalized initially")
        void shouldNotBeFinalizedInitially() {
            assertFalse(context.isFinalized());
        }

        @Test
        @DisplayName("Should mark as finalized")
        void shouldMarkAsFinalized() {
            assertTrue(context.markFinalized());
            assertTrue(context.isFinalized());
        }

        @Test
        @DisplayName("Should return false when already finalized")
        void shouldReturnFalseWhenAlreadyFinalized() {
            context.markFinalized();
            assertFalse(context.markFinalized());
        }
    }

    @Nested
    @DisplayName("Cleanup callbacks")
    class CleanupCallbackTests {

        @Test
        @DisplayName("Should execute cleanup callbacks on close")
        void shouldExecuteCallbacksOnClose() {
            AtomicBoolean called = new AtomicBoolean(false);
            context.addCleanupCallback(() -> called.set(true));

            context.close();

            assertTrue(called.get());
        }

        @Test
        @DisplayName("Should not add callback when closed")
        void shouldNotAddCallbackWhenClosed() {
            context.close();
            AtomicBoolean called = new AtomicBoolean(false);
            context.addCleanupCallback(() -> called.set(true));

            // Callback was not added because context is closed
            assertFalse(called.get());
        }

        @Test
        @DisplayName("Should ignore null callback")
        void shouldIgnoreNullCallback() {
            assertDoesNotThrow(() -> context.addCleanupCallback(null));
        }
    }

    @Nested
    @DisplayName("close()")
    class CloseTests {

        @Test
        @DisplayName("Should be idempotent")
        void shouldBeIdempotent() {
            context.close();
            assertDoesNotThrow(() -> context.close());
        }

        @Test
        @DisplayName("Should mark as closed")
        void shouldMarkAsClosed() {
            assertFalse(context.isClosed());
            context.close();
            assertTrue(context.isClosed());
        }

        @Test
        @DisplayName("Should clear last payload")
        void shouldClearLastPayload() {
            context.setLastPayload(Map.of("key", "value"));
            context.close();
            assertNull(context.getLastPayload());
        }

        @Test
        @DisplayName("Should handle exception in cleanup callback gracefully")
        void shouldHandleExceptionInCleanupCallback() {
            context.addCleanupCallback(() -> {
                throw new RuntimeException("cleanup error");
            });

            assertDoesNotThrow(() -> context.close());
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should include runId in toString")
        void shouldIncludeRunId() {
            String str = context.toString();
            assertTrue(str.contains("run-1"));
        }

        @Test
        @DisplayName("Should include finalized and closed status")
        void shouldIncludeStatus() {
            String str = context.toString();
            assertTrue(str.contains("finalized=false"));
            assertTrue(str.contains("closed=false"));
        }
    }
}
