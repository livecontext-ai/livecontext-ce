package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("StatusCounts")
class StatusCountsTest {

    private StatusCounts counts;

    @BeforeEach
    void setUp() {
        counts = new StatusCounts();
    }

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("Should initialize all counts to 0")
        void shouldInitializeToZero() {
            assertEquals(0, counts.getRunning());
            assertEquals(0, counts.getCompleted());
            assertEquals(0, counts.getFailed());
            assertEquals(0, counts.getSkipped());
            assertEquals(0, counts.getTotal());
        }
    }

    @Nested
    @DisplayName("Parameterized constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("Should initialize with provided values")
        void shouldInitializeWithValues() {
            StatusCounts sc = new StatusCounts(1, 2, 3, 4, 10);
            assertEquals(1, sc.getRunning());
            assertEquals(2, sc.getCompleted());
            assertEquals(3, sc.getFailed());
            assertEquals(4, sc.getSkipped());
            assertEquals(10, sc.getTotal());
        }
    }

    @Nested
    @DisplayName("Increment/Decrement methods")
    class IncrementDecrementTests {

        @Test
        @DisplayName("Should increment and decrement running")
        void shouldIncrementDecrementRunning() {
            counts.incrementRunning();
            counts.incrementRunning();
            assertEquals(2, counts.getRunning());
            counts.decrementRunning();
            assertEquals(1, counts.getRunning());
        }

        @Test
        @DisplayName("Should increment and decrement completed")
        void shouldIncrementDecrementCompleted() {
            counts.incrementCompleted();
            assertEquals(1, counts.getCompleted());
            counts.decrementCompleted();
            assertEquals(0, counts.getCompleted());
        }

        @Test
        @DisplayName("Should increment and decrement failed")
        void shouldIncrementDecrementFailed() {
            counts.incrementFailed();
            assertEquals(1, counts.getFailed());
            counts.decrementFailed();
            assertEquals(0, counts.getFailed());
        }

        @Test
        @DisplayName("Should increment and decrement skipped")
        void shouldIncrementDecrementSkipped() {
            counts.incrementSkipped();
            assertEquals(1, counts.getSkipped());
            counts.decrementSkipped();
            assertEquals(0, counts.getSkipped());
        }

        @Test
        @DisplayName("Should set and increment total")
        void shouldSetAndIncrementTotal() {
            counts.setTotal(10);
            assertEquals(10, counts.getTotal());
            counts.incrementTotal();
            assertEquals(11, counts.getTotal());
        }
    }

    @Nested
    @DisplayName("getProcessed()")
    class GetProcessedTests {

        @Test
        @DisplayName("Should return sum of completed, failed, and skipped")
        void shouldReturnSumOfTerminalStates() {
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementFailed();
            counts.incrementSkipped();

            assertEquals(4, counts.getProcessed());
        }
    }

    @Nested
    @DisplayName("isComplete()")
    class IsCompleteTests {

        @Test
        @DisplayName("Should be complete when all processed and total > 0")
        void shouldBeCompleteWhenAllProcessed() {
            counts.setTotal(3);
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementFailed();
            assertTrue(counts.isComplete());
        }

        @Test
        @DisplayName("Should not be complete when running > 0")
        void shouldNotBeCompleteWhenRunning() {
            counts.setTotal(2);
            counts.incrementCompleted();
            counts.incrementRunning();
            assertFalse(counts.isComplete());
        }

        @Test
        @DisplayName("Should not be complete when total is 0")
        void shouldNotBeCompleteWhenTotalIsZero() {
            assertFalse(counts.isComplete());
        }

        @Test
        @DisplayName("Should not be complete when not all processed")
        void shouldNotBeCompleteWhenNotAllProcessed() {
            counts.setTotal(3);
            counts.incrementCompleted();
            assertFalse(counts.isComplete());
        }
    }

    @Nested
    @DisplayName("hasCompleted()")
    class HasCompletedTests {

        @Test
        @DisplayName("Should return true when completed > 0")
        void shouldReturnTrueWhenCompleted() {
            counts.incrementCompleted();
            assertTrue(counts.hasCompleted());
        }

        @Test
        @DisplayName("Should return false when no completed")
        void shouldReturnFalseWhenNoCompleted() {
            assertFalse(counts.hasCompleted());
        }
    }

    @Nested
    @DisplayName("isAllSkipped()")
    class IsAllSkippedTests {

        @Test
        @DisplayName("Should return true when only skipped items processed")
        void shouldReturnTrueWhenOnlySkipped() {
            counts.incrementSkipped();
            counts.incrementSkipped();
            assertTrue(counts.isAllSkipped());
        }

        @Test
        @DisplayName("Should return false when some completed")
        void shouldReturnFalseWhenSomeCompleted() {
            counts.incrementSkipped();
            counts.incrementCompleted();
            assertFalse(counts.isAllSkipped());
        }

        @Test
        @DisplayName("Should return false when nothing processed")
        void shouldReturnFalseWhenNothingProcessed() {
            assertFalse(counts.isAllSkipped());
        }
    }

    @Nested
    @DisplayName("getAggregateStatus()")
    class GetAggregateStatusTests {

        @Test
        @DisplayName("Should return RUNNING when running > 0")
        void shouldReturnRunning() {
            counts.incrementRunning();
            assertEquals(NodeStatus.RUNNING, counts.getAggregateStatus());
        }

        @Test
        @DisplayName("Should return FAILED when failures exist (no running)")
        void shouldReturnFailed() {
            counts.incrementFailed();
            assertEquals(NodeStatus.FAILED, counts.getAggregateStatus());
        }

        @Test
        @DisplayName("Should return COMPLETED when only completed")
        void shouldReturnCompleted() {
            counts.incrementCompleted();
            assertEquals(NodeStatus.COMPLETED, counts.getAggregateStatus());
        }

        @Test
        @DisplayName("Should return SKIPPED when only skipped")
        void shouldReturnSkipped() {
            counts.incrementSkipped();
            assertEquals(NodeStatus.SKIPPED, counts.getAggregateStatus());
        }

        @Test
        @DisplayName("Should return PENDING when nothing processed")
        void shouldReturnPending() {
            assertEquals(NodeStatus.PENDING, counts.getAggregateStatus());
        }
    }

    @Nested
    @DisplayName("toMap()")
    class ToMapTests {

        @Test
        @DisplayName("Should convert to map correctly")
        void shouldConvertToMap() {
            StatusCounts sc = new StatusCounts(1, 2, 3, 4, 10);
            Map<String, Object> map = sc.toMap();

            assertEquals(1, map.get("running"));
            assertEquals(2, map.get("completed"));
            assertEquals(3, map.get("failed"));
            assertEquals(4, map.get("skipped"));
            assertEquals(10, map.get("total"));
        }
    }

    @Nested
    @DisplayName("fromMap()")
    class FromMapTests {

        @Test
        @DisplayName("Should create from map with new keys")
        void shouldCreateFromMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 1);
            map.put("completed", 2);
            map.put("failed", 3);
            map.put("skipped", 4);
            map.put("total", 10);

            StatusCounts sc = StatusCounts.fromMap(map);
            assertEquals(1, sc.getRunning());
            assertEquals(2, sc.getCompleted());
            assertEquals(3, sc.getFailed());
            assertEquals(4, sc.getSkipped());
            assertEquals(10, sc.getTotal());
        }

        @Test
        @DisplayName("Should create from map with legacy keys (backward compat)")
        void shouldCreateFromMapWithLegacyKeys() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 1);
            map.put("success", 2);
            map.put("failure", 3);
            map.put("skipped", 4);
            map.put("total", 10);

            StatusCounts sc = StatusCounts.fromMap(map);
            assertEquals(1, sc.getRunning());
            assertEquals(2, sc.getCompleted());
            assertEquals(3, sc.getFailed());
            assertEquals(4, sc.getSkipped());
            assertEquals(10, sc.getTotal());
        }

        @Test
        @DisplayName("Should return zeros for null map")
        void shouldReturnZerosForNull() {
            StatusCounts sc = StatusCounts.fromMap(null);
            assertEquals(0, sc.getRunning());
            assertEquals(0, sc.getCompleted());
        }

        @Test
        @DisplayName("Should handle string values in map")
        void shouldHandleStringValues() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", "1");
            map.put("completed", "2");

            StatusCounts sc = StatusCounts.fromMap(map);
            assertEquals(1, sc.getRunning());
            assertEquals(2, sc.getCompleted());
        }

        @Test
        @DisplayName("Should handle invalid string values")
        void shouldHandleInvalidStringValues() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", "invalid");

            StatusCounts sc = StatusCounts.fromMap(map);
            assertEquals(0, sc.getRunning());
        }
    }

    @Nested
    @DisplayName("snapshot()")
    class SnapshotTests {

        @Test
        @DisplayName("Should create independent copy")
        void shouldCreateIndependentCopy() {
            counts.incrementCompleted();
            counts.incrementRunning();
            counts.setTotal(2);

            StatusCounts snapshot = counts.snapshot();
            assertEquals(1, snapshot.getCompleted());
            assertEquals(1, snapshot.getRunning());
            assertEquals(2, snapshot.getTotal());

            // Modifying original should not affect snapshot
            counts.incrementCompleted();
            assertEquals(1, snapshot.getCompleted());
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should include all counts")
        void shouldIncludeAllCounts() {
            StatusCounts sc = new StatusCounts(1, 2, 3, 4, 10);
            String str = sc.toString();
            assertTrue(str.contains("running=1"));
            assertTrue(str.contains("completed=2"));
            assertTrue(str.contains("failed=3"));
            assertTrue(str.contains("skipped=4"));
            assertTrue(str.contains("total=10"));
        }
    }
}
