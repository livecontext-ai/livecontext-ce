package com.apimarketplace.orchestrator.services.streaming.emitter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StreamingBatchScheduler")
class StreamingBatchSchedulerTest {

    @Mock
    private StreamingBatchEmitter emitter;

    private StreamingBatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new StreamingBatchScheduler(emitter);
    }

    @Nested
    @DisplayName("snapshotForRun()")
    class SnapshotForRunTests {

        @Test
        @DisplayName("Should return snapshot from emitter")
        void shouldReturnSnapshotFromEmitter() {
            Map<String, Object> expected = Map.of("type", "batch-update", "nodes", Map.of());
            when(emitter.snapshot("run-1")).thenReturn(expected);

            Map<String, Object> result = scheduler.snapshotForRun("run-1");

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("Should return empty map when emitter returns null")
        void shouldReturnEmptyMapWhenNull() {
            when(emitter.snapshot("run-1")).thenReturn(null);

            Map<String, Object> result = scheduler.snapshotForRun("run-1");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for null runId")
        void shouldReturnEmptyMapForNullRunId() {
            Map<String, Object> result = scheduler.snapshotForRun(null);

            assertTrue(result.isEmpty());
            verifyNoInteractions(emitter);
        }
    }
}
