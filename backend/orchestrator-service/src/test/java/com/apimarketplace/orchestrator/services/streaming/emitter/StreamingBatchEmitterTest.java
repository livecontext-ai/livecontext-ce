package com.apimarketplace.orchestrator.services.streaming.emitter;

import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StreamingBatchEmitter")
class StreamingBatchEmitterTest {

    @Mock
    private RunContextRegistry contextRegistry;

    private StreamingBatchEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new StreamingBatchEmitter(contextRegistry);
    }

    @Nested
    @DisplayName("snapshot()")
    class SnapshotTests {

        @Test
        @DisplayName("Should build payload from snapshot")
        void shouldBuildPayloadFromSnapshot() {
            RunStateStore.RunSnapshot snapshot = new RunStateStore.RunSnapshot(
                List.of(Map.of("id", "mcp:step1", "status", "completed")),
                List.of(Map.of("from", "trigger:start", "to", "mcp:step1", "status", "completed")),
                Map.of("status", "RUNNING"),
                Map.of("totalSteps", 5),
                List.of(Map.of("loopId", "core:loop", "iteration", 1)),
                List.of(Map.of("mergeId", "core:merge", "progress", 0.5)),
                List.of(Map.of("level", "INFO", "msg", "test")),
                List.of(),
                false
            );

            when(contextRegistry.snapshot("run-1")).thenReturn(snapshot);

            Map<String, Object> payload = emitter.snapshot("run-1");

            assertEquals("batch-update", payload.get("type"));
            assertNotNull(payload.get("timestamp"));
            assertNotNull(payload.get("nodes"));
            assertNotNull(payload.get("edges"));
            assertNotNull(payload.get("loops"));
            assertNotNull(payload.get("merges"));
            assertNotNull(payload.get("logs"));
            assertNotNull(payload.get("workflowStatus"));
            assertNotNull(payload.get("workflowStatistics"));

            verify(contextRegistry).setLastPayload(eq("run-1"), any());
        }

        @Test
        @DisplayName("Should return last payload when snapshot is null")
        void shouldReturnLastPayloadWhenNoSnapshot() {
            when(contextRegistry.snapshot("run-1")).thenReturn(null);
            Map<String, Object> lastPayload = Map.of("type", "batch-update", "old", true);
            when(contextRegistry.getLastPayload("run-1")).thenReturn(lastPayload);

            Map<String, Object> result = emitter.snapshot("run-1");

            assertEquals(lastPayload, result);
        }

        @Test
        @DisplayName("Should return empty map when no snapshot and no last payload")
        void shouldReturnEmptyMapWhenNothing() {
            when(contextRegistry.snapshot("run-1")).thenReturn(null);
            when(contextRegistry.getLastPayload("run-1")).thenReturn(null);

            Map<String, Object> result = emitter.snapshot("run-1");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should include terminal flag when snapshot is terminal")
        void shouldIncludeTerminalFlag() {
            RunStateStore.RunSnapshot snapshot = new RunStateStore.RunSnapshot(
                List.of(), List.of(),
                Map.of("status", "COMPLETED"), null,
                List.of(), List.of(), List.of(), List.of(), true
            );

            when(contextRegistry.snapshot("run-1")).thenReturn(snapshot);

            Map<String, Object> payload = emitter.snapshot("run-1");

            assertEquals(true, payload.get("terminal"));
        }

        @Test
        @DisplayName("Should not include empty collections")
        void shouldNotIncludeEmptyCollections() {
            RunStateStore.RunSnapshot snapshot = new RunStateStore.RunSnapshot(
                List.of(Map.of("id", "mcp:step1")), List.of(),
                null, null,
                List.of(), List.of(), List.of(), List.of(), false
            );

            when(contextRegistry.snapshot("run-1")).thenReturn(snapshot);

            Map<String, Object> payload = emitter.snapshot("run-1");

            assertNotNull(payload.get("nodes"));
            assertNull(payload.get("edges"));
            assertNull(payload.get("loops"));
            assertNull(payload.get("merges"));
            assertNull(payload.get("logs"));
            assertNull(payload.get("workflowStatus"));
            assertNull(payload.get("workflowStatistics"));
            assertNull(payload.get("terminal"));
        }
    }

    @Nested
    @DisplayName("getLastPayload()")
    class GetLastPayloadTests {

        @Test
        @DisplayName("Should delegate to context registry")
        void shouldDelegateToContextRegistry() {
            Map<String, Object> expected = Map.of("type", "batch-update");
            when(contextRegistry.getLastPayload("run-1")).thenReturn(expected);

            Map<String, Object> result = emitter.getLastPayload("run-1");

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("Should return null when no last payload")
        void shouldReturnNullWhenNoPayload() {
            when(contextRegistry.getLastPayload("run-1")).thenReturn(null);

            assertNull(emitter.getLastPayload("run-1"));
        }
    }
}
