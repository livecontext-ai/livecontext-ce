package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ItemResult record.
 */
@DisplayName("ItemResult")
class ItemResultTest {

    @Nested
    @DisplayName("success factory method")
    class SuccessFactory {

        @Test
        @DisplayName("should create successful item result")
        void shouldCreateSuccessResult() {
            ExecutionContext ctx = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), null);
            ItemResult result = ItemResult.success("item-0", ctx);

            assertEquals("item-0", result.itemId());
            assertEquals(NodeStatus.COMPLETED, result.status());
            assertNotNull(result.outputs());
            assertTrue(result.errorMessage().isEmpty());
            assertTrue(result.isSuccess());
            assertFalse(result.isFailure());
        }
    }

    @Nested
    @DisplayName("failure factory method")
    class FailureFactory {

        @Test
        @DisplayName("should create failed item result with error message")
        void shouldCreateFailureResult() {
            ItemResult result = ItemResult.failure("item-1", "Connection timeout");

            assertEquals("item-1", result.itemId());
            assertEquals(NodeStatus.FAILED, result.status());
            assertTrue(result.outputs().isEmpty());
            assertTrue(result.errorMessage().isPresent());
            assertEquals("Connection timeout", result.errorMessage().get());
            assertFalse(result.isSuccess());
            assertTrue(result.isFailure());
        }
    }

    @Nested
    @DisplayName("isSuccess / isFailure")
    class StatusChecks {

        @Test
        @DisplayName("isSuccess returns true only for SUCCESS")
        void isSuccessCheck() {
            ItemResult success = new ItemResult("id", NodeStatus.COMPLETED, Map.of(), java.util.Optional.empty());
            ItemResult failure = new ItemResult("id", NodeStatus.FAILED, Map.of(), java.util.Optional.of("err"));

            assertTrue(success.isSuccess());
            assertFalse(success.isFailure());
            assertFalse(failure.isSuccess());
            assertTrue(failure.isFailure());
        }
    }
}
