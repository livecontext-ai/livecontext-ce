package com.apimarketplace.orchestrator.services.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SnapshotReader")
class SnapshotReaderTest {

    @Nested
    @DisplayName("SnapshotData record")
    class SnapshotDataTests {

        @Test
        @DisplayName("Should create SnapshotData with all fields")
        void shouldCreate() {
            Map<String, Object> snapshot = Map.of("type", "batch-update");
            SnapshotReader.SnapshotData data = new SnapshotReader.SnapshotData(
                snapshot, "COMPLETED", SnapshotReader.SnapshotSource.DATABASE
            );

            assertEquals(snapshot, data.snapshot());
            assertEquals("COMPLETED", data.terminalStatus());
            assertEquals(SnapshotReader.SnapshotSource.DATABASE, data.source());
        }

        @Test
        @DisplayName("Should return true for hasTerminalStatus when status is set")
        void shouldReturnTrueForHasTerminalStatus() {
            SnapshotReader.SnapshotData data = new SnapshotReader.SnapshotData(
                Map.of(), "COMPLETED", SnapshotReader.SnapshotSource.DATABASE
            );

            assertTrue(data.hasTerminalStatus());
        }

        @Test
        @DisplayName("Should return false for hasTerminalStatus when status is null")
        void shouldReturnFalseForHasTerminalStatus() {
            SnapshotReader.SnapshotData data = new SnapshotReader.SnapshotData(
                Map.of(), null, SnapshotReader.SnapshotSource.LIVE
            );

            assertFalse(data.hasTerminalStatus());
        }
    }

    @Nested
    @DisplayName("SnapshotSource enum")
    class SnapshotSourceTests {

        @Test
        @DisplayName("Should have LIVE and DATABASE values")
        void shouldHaveExpectedValues() {
            SnapshotReader.SnapshotSource[] values = SnapshotReader.SnapshotSource.values();

            assertEquals(2, values.length);
            assertEquals(SnapshotReader.SnapshotSource.LIVE, SnapshotReader.SnapshotSource.valueOf("LIVE"));
            assertEquals(SnapshotReader.SnapshotSource.DATABASE, SnapshotReader.SnapshotSource.valueOf("DATABASE"));
        }
    }
}
