package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StatusCounts - Comprehensive Tests")
class StatusCountsComprehensiveTest {

    private StatusCounts counts;

    @BeforeEach
    void setUp() {
        counts = new StatusCounts();
    }

    // =========================================================================
    // 1. Constructor Tests
    // =========================================================================

    @Nested
    @DisplayName("Default constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("Should initialize all counters to zero")
        void shouldInitializeAllCountersToZero() {
            assertThat(counts.getRunning()).isZero();
            assertThat(counts.getCompleted()).isZero();
            assertThat(counts.getFailed()).isZero();
            assertThat(counts.getSkipped()).isZero();
            assertThat(counts.getTotal()).isZero();
        }

        @Test
        @DisplayName("Should have zero processed count")
        void shouldHaveZeroProcessed() {
            assertThat(counts.getProcessed()).isZero();
        }

        @Test
        @DisplayName("Should not be complete")
        void shouldNotBeComplete() {
            assertThat(counts.isComplete()).isFalse();
        }

        @Test
        @DisplayName("Should have PENDING aggregate status")
        void shouldHavePendingAggregateStatus() {
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.PENDING);
        }
    }

    @Nested
    @DisplayName("Parameterized constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("Should initialize with all provided values")
        void shouldInitializeWithProvidedValues() {
            StatusCounts sc = new StatusCounts(1, 2, 3, 4, 10);

            assertThat(sc.getRunning()).isEqualTo(1);
            assertThat(sc.getCompleted()).isEqualTo(2);
            assertThat(sc.getFailed()).isEqualTo(3);
            assertThat(sc.getSkipped()).isEqualTo(4);
            assertThat(sc.getTotal()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should accept all zeros")
        void shouldAcceptAllZeros() {
            StatusCounts sc = new StatusCounts(0, 0, 0, 0, 0);

            assertThat(sc.getRunning()).isZero();
            assertThat(sc.getCompleted()).isZero();
            assertThat(sc.getFailed()).isZero();
            assertThat(sc.getSkipped()).isZero();
            assertThat(sc.getTotal()).isZero();
        }

        @Test
        @DisplayName("Should accept negative values without throwing")
        void shouldAcceptNegativeValues() {
            StatusCounts sc = new StatusCounts(-1, -2, -3, -4, -10);

            assertThat(sc.getRunning()).isEqualTo(-1);
            assertThat(sc.getCompleted()).isEqualTo(-2);
            assertThat(sc.getFailed()).isEqualTo(-3);
            assertThat(sc.getSkipped()).isEqualTo(-4);
            assertThat(sc.getTotal()).isEqualTo(-10);
        }

        @Test
        @DisplayName("Should accept very large values")
        void shouldAcceptVeryLargeValues() {
            StatusCounts sc = new StatusCounts(
                    Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE
            );

            assertThat(sc.getRunning()).isEqualTo(Integer.MAX_VALUE);
            assertThat(sc.getCompleted()).isEqualTo(Integer.MAX_VALUE);
            assertThat(sc.getFailed()).isEqualTo(Integer.MAX_VALUE);
            assertThat(sc.getSkipped()).isEqualTo(Integer.MAX_VALUE);
            assertThat(sc.getTotal()).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("Should calculate processed correctly from constructor values")
        void shouldCalculateProcessedFromConstructorValues() {
            StatusCounts sc = new StatusCounts(2, 5, 3, 1, 11);

            assertThat(sc.getProcessed()).isEqualTo(9); // 5 + 3 + 1
        }
    }

    // =========================================================================
    // 2. Increment/Decrement Tests
    // =========================================================================

    @Nested
    @DisplayName("Increment and decrement methods")
    class IncrementDecrementTests {

        @Nested
        @DisplayName("Running counter")
        class RunningCounter {

            @Test
            @DisplayName("Should increment running from zero")
            void shouldIncrementFromZero() {
                counts.incrementRunning();
                assertThat(counts.getRunning()).isEqualTo(1);
            }

            @Test
            @DisplayName("Should increment running multiple times")
            void shouldIncrementMultipleTimes() {
                counts.incrementRunning();
                counts.incrementRunning();
                counts.incrementRunning();
                assertThat(counts.getRunning()).isEqualTo(3);
            }

            @Test
            @DisplayName("Should decrement running after increment")
            void shouldDecrementAfterIncrement() {
                counts.incrementRunning();
                counts.incrementRunning();
                counts.decrementRunning();
                assertThat(counts.getRunning()).isEqualTo(1);
            }

            @Test
            @DisplayName("Should decrement running below zero")
            void shouldDecrementBelowZero() {
                counts.decrementRunning();
                assertThat(counts.getRunning()).isEqualTo(-1);
            }
        }

        @Nested
        @DisplayName("Completed counter")
        class CompletedCounter {

            @Test
            @DisplayName("Should increment completed from zero")
            void shouldIncrementFromZero() {
                counts.incrementCompleted();
                assertThat(counts.getCompleted()).isEqualTo(1);
            }

            @Test
            @DisplayName("Should increment completed multiple times")
            void shouldIncrementMultipleTimes() {
                counts.incrementCompleted();
                counts.incrementCompleted();
                counts.incrementCompleted();
                counts.incrementCompleted();
                assertThat(counts.getCompleted()).isEqualTo(4);
            }

            @Test
            @DisplayName("Should decrement completed after increment")
            void shouldDecrementAfterIncrement() {
                counts.incrementCompleted();
                counts.decrementCompleted();
                assertThat(counts.getCompleted()).isZero();
            }

            @Test
            @DisplayName("Should decrement completed below zero")
            void shouldDecrementBelowZero() {
                counts.decrementCompleted();
                assertThat(counts.getCompleted()).isEqualTo(-1);
            }
        }

        @Nested
        @DisplayName("Failed counter")
        class FailedCounter {

            @Test
            @DisplayName("Should increment failed from zero")
            void shouldIncrementFromZero() {
                counts.incrementFailed();
                assertThat(counts.getFailed()).isEqualTo(1);
            }

            @Test
            @DisplayName("Should increment failed multiple times")
            void shouldIncrementMultipleTimes() {
                counts.incrementFailed();
                counts.incrementFailed();
                assertThat(counts.getFailed()).isEqualTo(2);
            }

            @Test
            @DisplayName("Should decrement failed after increment")
            void shouldDecrementAfterIncrement() {
                counts.incrementFailed();
                counts.incrementFailed();
                counts.decrementFailed();
                assertThat(counts.getFailed()).isEqualTo(1);
            }

            @Test
            @DisplayName("Should decrement failed below zero")
            void shouldDecrementBelowZero() {
                counts.decrementFailed();
                counts.decrementFailed();
                assertThat(counts.getFailed()).isEqualTo(-2);
            }
        }

        @Nested
        @DisplayName("Skipped counter")
        class SkippedCounter {

            @Test
            @DisplayName("Should increment skipped from zero")
            void shouldIncrementFromZero() {
                counts.incrementSkipped();
                assertThat(counts.getSkipped()).isEqualTo(1);
            }

            @Test
            @DisplayName("Should increment skipped multiple times")
            void shouldIncrementMultipleTimes() {
                counts.incrementSkipped();
                counts.incrementSkipped();
                counts.incrementSkipped();
                assertThat(counts.getSkipped()).isEqualTo(3);
            }

            @Test
            @DisplayName("Should decrement skipped after increment")
            void shouldDecrementAfterIncrement() {
                counts.incrementSkipped();
                counts.decrementSkipped();
                assertThat(counts.getSkipped()).isZero();
            }

            @Test
            @DisplayName("Should decrement skipped below zero")
            void shouldDecrementBelowZero() {
                counts.decrementSkipped();
                assertThat(counts.getSkipped()).isEqualTo(-1);
            }
        }

        @Nested
        @DisplayName("Total counter")
        class TotalCounter {

            @Test
            @DisplayName("Should set total to arbitrary value")
            void shouldSetTotal() {
                counts.setTotal(42);
                assertThat(counts.getTotal()).isEqualTo(42);
            }

            @Test
            @DisplayName("Should overwrite total when set multiple times")
            void shouldOverwriteTotal() {
                counts.setTotal(10);
                counts.setTotal(20);
                assertThat(counts.getTotal()).isEqualTo(20);
            }

            @Test
            @DisplayName("Should increment total from zero")
            void shouldIncrementTotalFromZero() {
                counts.incrementTotal();
                assertThat(counts.getTotal()).isEqualTo(1);
            }

            @Test
            @DisplayName("Should increment total after set")
            void shouldIncrementTotalAfterSet() {
                counts.setTotal(10);
                counts.incrementTotal();
                counts.incrementTotal();
                assertThat(counts.getTotal()).isEqualTo(12);
            }

            @Test
            @DisplayName("Should allow setting total to zero")
            void shouldAllowSettingToZero() {
                counts.setTotal(100);
                counts.setTotal(0);
                assertThat(counts.getTotal()).isZero();
            }

            @Test
            @DisplayName("Should allow setting total to negative value")
            void shouldAllowSettingToNegative() {
                counts.setTotal(-5);
                assertThat(counts.getTotal()).isEqualTo(-5);
            }
        }
    }

    // =========================================================================
    // 3. getProcessed() Tests
    // =========================================================================

    @Nested
    @DisplayName("getProcessed()")
    class GetProcessedTests {

        @Test
        @DisplayName("Should return zero when no terminal counts")
        void shouldReturnZeroWhenNoTerminalCounts() {
            counts.incrementRunning();
            counts.setTotal(5);
            assertThat(counts.getProcessed()).isZero();
        }

        @Test
        @DisplayName("Should return sum of completed, failed, and skipped")
        void shouldReturnSumOfTerminalCounts() {
            counts.incrementCompleted();  // 1
            counts.incrementCompleted();  // 2
            counts.incrementFailed();     // 1
            counts.incrementSkipped();    // 1

            assertThat(counts.getProcessed()).isEqualTo(4);
        }

        @Test
        @DisplayName("Should only include completed when others are zero")
        void shouldOnlyIncludeCompleted() {
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementCompleted();

            assertThat(counts.getProcessed()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should only include failed when others are zero")
        void shouldOnlyIncludeFailed() {
            counts.incrementFailed();
            counts.incrementFailed();

            assertThat(counts.getProcessed()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should only include skipped when others are zero")
        void shouldOnlyIncludeSkipped() {
            counts.incrementSkipped();

            assertThat(counts.getProcessed()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should not include running in processed count")
        void shouldNotIncludeRunning() {
            counts.incrementRunning();
            counts.incrementRunning();
            counts.incrementCompleted();

            assertThat(counts.getProcessed()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should handle negative values in processed calculation")
        void shouldHandleNegativeValues() {
            counts.incrementCompleted(); // 1
            counts.decrementFailed();    // -1

            assertThat(counts.getProcessed()).isZero(); // 1 + (-1) + 0
        }
    }

    // =========================================================================
    // 4. isComplete() Tests
    // =========================================================================

    @Nested
    @DisplayName("isComplete()")
    class IsCompleteTests {

        @Test
        @DisplayName("Should be complete when processed equals total and total > 0 and running == 0")
        void shouldBeCompleteWhenAllProcessed() {
            counts.setTotal(3);
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementCompleted();

            assertThat(counts.isComplete()).isTrue();
        }

        @Test
        @DisplayName("Should be complete with mixed terminal states summing to total")
        void shouldBeCompleteWithMixedTerminalStates() {
            counts.setTotal(4);
            counts.incrementCompleted();
            counts.incrementFailed();
            counts.incrementSkipped();
            counts.incrementCompleted();

            assertThat(counts.isComplete()).isTrue();
        }

        @Test
        @DisplayName("Should not be complete when running > 0 even if processed equals total")
        void shouldNotBeCompleteWhenRunning() {
            counts.setTotal(3);
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementRunning();

            assertThat(counts.isComplete()).isFalse();
        }

        @Test
        @DisplayName("Should not be complete when total is 0")
        void shouldNotBeCompleteWhenTotalIsZero() {
            assertThat(counts.isComplete()).isFalse();
        }

        @Test
        @DisplayName("Should not be complete when processed < total")
        void shouldNotBeCompleteWhenProcessedLessThanTotal() {
            counts.setTotal(5);
            counts.incrementCompleted();
            counts.incrementCompleted();

            assertThat(counts.isComplete()).isFalse();
        }

        @Test
        @DisplayName("Should not be complete with zero total even when all counters are zero")
        void shouldNotBeCompleteWithZeroTotalAllZeros() {
            StatusCounts sc = new StatusCounts(0, 0, 0, 0, 0);
            assertThat(sc.isComplete()).isFalse();
        }

        @Test
        @DisplayName("Should be complete with only failed summing to total")
        void shouldBeCompleteWithOnlyFailed() {
            counts.setTotal(2);
            counts.incrementFailed();
            counts.incrementFailed();

            assertThat(counts.isComplete()).isTrue();
        }

        @Test
        @DisplayName("Should be complete with only skipped summing to total")
        void shouldBeCompleteWithOnlySkipped() {
            counts.setTotal(2);
            counts.incrementSkipped();
            counts.incrementSkipped();

            assertThat(counts.isComplete()).isTrue();
        }

        @Test
        @DisplayName("Should not be complete when processed > total but running > 0")
        void shouldNotBeCompleteWhenProcessedExceedsTotalButRunning() {
            counts.setTotal(1);
            counts.incrementCompleted();
            counts.incrementCompleted(); // processed (2) > total (1)
            counts.incrementRunning();

            assertThat(counts.isComplete()).isFalse();
        }

        @Test
        @DisplayName("Should not be complete when processed exceeds total (running == 0 but processed != total)")
        void shouldNotBeCompleteWhenProcessedExceedsTotal() {
            counts.setTotal(1);
            counts.incrementCompleted();
            counts.incrementCompleted(); // processed=2, total=1

            // processed (2) != total (1), so isComplete should be false
            assertThat(counts.isComplete()).isFalse();
        }

        @Test
        @DisplayName("Should be complete with single item total")
        void shouldBeCompleteWithSingleItem() {
            counts.setTotal(1);
            counts.incrementCompleted();

            assertThat(counts.isComplete()).isTrue();
        }
    }

    // =========================================================================
    // 5. hasCompleted() Tests
    // =========================================================================

    @Nested
    @DisplayName("hasCompleted()")
    class HasCompletedTests {

        @Test
        @DisplayName("Should return true when completed > 0")
        void shouldReturnTrueWhenCompleted() {
            counts.incrementCompleted();
            assertThat(counts.hasCompleted()).isTrue();
        }

        @Test
        @DisplayName("Should return true when multiple completed")
        void shouldReturnTrueWhenMultipleCompleted() {
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementCompleted();
            assertThat(counts.hasCompleted()).isTrue();
        }

        @Test
        @DisplayName("Should return false when no completed")
        void shouldReturnFalseWhenNoCompleted() {
            assertThat(counts.hasCompleted()).isFalse();
        }

        @Test
        @DisplayName("Should return false when only failed and skipped")
        void shouldReturnFalseWhenOnlyFailedAndSkipped() {
            counts.incrementFailed();
            counts.incrementSkipped();
            assertThat(counts.hasCompleted()).isFalse();
        }

        @Test
        @DisplayName("Should return true when completed exists alongside other counters")
        void shouldReturnTrueWithMixedCounters() {
            counts.incrementRunning();
            counts.incrementFailed();
            counts.incrementCompleted();
            counts.incrementSkipped();
            assertThat(counts.hasCompleted()).isTrue();
        }

        @Test
        @DisplayName("Should return false after incrementing and then decrementing completed to zero")
        void shouldReturnFalseAfterDecrementToZero() {
            counts.incrementCompleted();
            counts.decrementCompleted();
            assertThat(counts.hasCompleted()).isFalse();
        }
    }

    // =========================================================================
    // 6. isAllSkipped() Tests
    // =========================================================================

    @Nested
    @DisplayName("isAllSkipped()")
    class IsAllSkippedTests {

        @Test
        @DisplayName("Should return true when only skipped items are processed")
        void shouldReturnTrueWhenOnlySkipped() {
            counts.incrementSkipped();
            counts.incrementSkipped();
            assertThat(counts.isAllSkipped()).isTrue();
        }

        @Test
        @DisplayName("Should return true with single skipped item")
        void shouldReturnTrueWithSingleSkipped() {
            counts.incrementSkipped();
            assertThat(counts.isAllSkipped()).isTrue();
        }

        @Test
        @DisplayName("Should return false when some completed items exist")
        void shouldReturnFalseWhenSomeCompleted() {
            counts.incrementSkipped();
            counts.incrementCompleted();
            assertThat(counts.isAllSkipped()).isFalse();
        }

        @Test
        @DisplayName("Should return false when some failed items exist")
        void shouldReturnFalseWhenSomeFailed() {
            counts.incrementSkipped();
            counts.incrementFailed();
            assertThat(counts.isAllSkipped()).isFalse();
        }

        @Test
        @DisplayName("Should return false when nothing is processed")
        void shouldReturnFalseWhenNothingProcessed() {
            assertThat(counts.isAllSkipped()).isFalse();
        }

        @Test
        @DisplayName("Should return false when nothing is processed but running exists")
        void shouldReturnFalseWithOnlyRunning() {
            counts.incrementRunning();
            assertThat(counts.isAllSkipped()).isFalse();
        }

        @Test
        @DisplayName("Should return true when skipped and running both exist (running not in processed)")
        void shouldReturnTrueWithSkippedAndRunning() {
            counts.incrementSkipped();
            counts.incrementRunning();
            // processed = 0 + 0 + 1 = 1, completed=0, failed=0 -> true
            assertThat(counts.isAllSkipped()).isTrue();
        }

        @Test
        @DisplayName("Should return false when all three terminal types have items")
        void shouldReturnFalseWithAllTerminalTypes() {
            counts.incrementCompleted();
            counts.incrementFailed();
            counts.incrementSkipped();
            assertThat(counts.isAllSkipped()).isFalse();
        }
    }

    // =========================================================================
    // 7. getAggregateStatus() Tests
    // =========================================================================

    @Nested
    @DisplayName("getAggregateStatus()")
    class GetAggregateStatusTests {

        @Test
        @DisplayName("Should return PENDING when all counters are zero")
        void shouldReturnPendingWhenAllZero() {
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.PENDING);
        }

        @Test
        @DisplayName("Should return RUNNING when running > 0 (highest priority)")
        void shouldReturnRunningWhenRunning() {
            counts.incrementRunning();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.RUNNING);
        }

        @Test
        @DisplayName("Should return RUNNING even when failed, completed, and skipped also exist")
        void shouldReturnRunningWithAllCounters() {
            counts.incrementRunning();
            counts.incrementFailed();
            counts.incrementCompleted();
            counts.incrementSkipped();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.RUNNING);
        }

        @Test
        @DisplayName("Should return FAILED when failed > 0 and running == 0")
        void shouldReturnFailedWhenNoRunning() {
            counts.incrementFailed();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("Should return FAILED even when completed and skipped also exist")
        void shouldReturnFailedWithCompletedAndSkipped() {
            counts.incrementFailed();
            counts.incrementCompleted();
            counts.incrementSkipped();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("Should return COMPLETED when completed > 0 and no running or failed")
        void shouldReturnCompletedWhenNoRunningOrFailed() {
            counts.incrementCompleted();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should return COMPLETED even when skipped also exists")
        void shouldReturnCompletedWithSkipped() {
            counts.incrementCompleted();
            counts.incrementSkipped();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should return SKIPPED when only skipped > 0")
        void shouldReturnSkippedWhenOnlySkipped() {
            counts.incrementSkipped();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.SKIPPED);
        }

        @Test
        @DisplayName("Priority order: RUNNING > FAILED > COMPLETED > SKIPPED > PENDING")
        void shouldFollowPriorityOrder() {
            // Start with PENDING
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.PENDING);

            // Add skipped -> SKIPPED
            counts.incrementSkipped();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.SKIPPED);

            // Add completed -> COMPLETED (higher priority than SKIPPED)
            counts.incrementCompleted();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.COMPLETED);

            // Add failed -> FAILED (higher priority than COMPLETED)
            counts.incrementFailed();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.FAILED);

            // Add running -> RUNNING (highest priority)
            counts.incrementRunning();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.RUNNING);
        }

        @Test
        @DisplayName("Should return PENDING when total > 0 but nothing processed or running")
        void shouldReturnPendingWithOnlyTotal() {
            counts.setTotal(10);
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.PENDING);
        }
    }

    // =========================================================================
    // 8. toMap() Tests
    // =========================================================================

    @Nested
    @DisplayName("toMap()")
    class ToMapTests {

        @Test
        @DisplayName("Should contain all five keys")
        void shouldContainAllKeys() {
            Map<String, Object> map = counts.toMap();

            assertThat(map).containsOnlyKeys("running", "completed", "failed", "skipped", "total");
        }

        @Test
        @DisplayName("Should return correct values for default constructor")
        void shouldReturnZeroValuesForDefault() {
            Map<String, Object> map = counts.toMap();

            assertThat(map).containsEntry("running", 0);
            assertThat(map).containsEntry("completed", 0);
            assertThat(map).containsEntry("failed", 0);
            assertThat(map).containsEntry("skipped", 0);
            assertThat(map).containsEntry("total", 0);
        }

        @Test
        @DisplayName("Should return correct values for parameterized constructor")
        void shouldReturnCorrectValues() {
            StatusCounts sc = new StatusCounts(1, 2, 3, 4, 10);
            Map<String, Object> map = sc.toMap();

            assertThat(map).containsEntry("running", 1);
            assertThat(map).containsEntry("completed", 2);
            assertThat(map).containsEntry("failed", 3);
            assertThat(map).containsEntry("skipped", 4);
            assertThat(map).containsEntry("total", 10);
        }

        @Test
        @DisplayName("Should return a LinkedHashMap preserving insertion order")
        void shouldReturnLinkedHashMap() {
            Map<String, Object> map = counts.toMap();

            assertThat(map).isInstanceOf(LinkedHashMap.class);

            List<String> keys = new ArrayList<>(map.keySet());
            assertThat(keys).containsExactly("running", "completed", "failed", "skipped", "total");
        }

        @Test
        @DisplayName("Should not use legacy key names (success/failure)")
        void shouldNotUseLegacyKeys() {
            StatusCounts sc = new StatusCounts(0, 5, 3, 0, 8);
            Map<String, Object> map = sc.toMap();

            assertThat(map).doesNotContainKey("success");
            assertThat(map).doesNotContainKey("failure");
        }

        @Test
        @DisplayName("Map should be independent from the StatusCounts object")
        void shouldBeIndependentFromSource() {
            StatusCounts sc = new StatusCounts(1, 2, 3, 4, 10);
            Map<String, Object> map = sc.toMap();

            sc.incrementRunning();
            // Map should still reflect old value
            assertThat(map.get("running")).isEqualTo(1);
        }
    }

    // =========================================================================
    // 9. fromMap() Tests
    // =========================================================================

    @Nested
    @DisplayName("fromMap()")
    class FromMapTests {

        @Test
        @DisplayName("Should return default StatusCounts for null map")
        void shouldReturnDefaultForNull() {
            StatusCounts sc = StatusCounts.fromMap(null);

            assertThat(sc.getRunning()).isZero();
            assertThat(sc.getCompleted()).isZero();
            assertThat(sc.getFailed()).isZero();
            assertThat(sc.getSkipped()).isZero();
            assertThat(sc.getTotal()).isZero();
        }

        @Test
        @DisplayName("Should return default StatusCounts for empty map")
        void shouldReturnDefaultForEmptyMap() {
            StatusCounts sc = StatusCounts.fromMap(new HashMap<>());

            assertThat(sc.getRunning()).isZero();
            assertThat(sc.getCompleted()).isZero();
            assertThat(sc.getFailed()).isZero();
            assertThat(sc.getSkipped()).isZero();
            assertThat(sc.getTotal()).isZero();
        }

        @Test
        @DisplayName("Should create from map with standard keys")
        void shouldCreateFromStandardKeys() {
            Map<String, Object> map = Map.of(
                    "running", 1,
                    "completed", 2,
                    "failed", 3,
                    "skipped", 4,
                    "total", 10
            );

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isEqualTo(1);
            assertThat(sc.getCompleted()).isEqualTo(2);
            assertThat(sc.getFailed()).isEqualTo(3);
            assertThat(sc.getSkipped()).isEqualTo(4);
            assertThat(sc.getTotal()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should fall back to 'success' key when 'completed' is absent")
        void shouldFallBackToSuccessKey() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 1);
            map.put("success", 5);
            map.put("failed", 2);
            map.put("skipped", 0);
            map.put("total", 8);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getCompleted()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should fall back to 'failure' key when 'failed' is absent")
        void shouldFallBackToFailureKey() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 0);
            map.put("completed", 3);
            map.put("failure", 2);
            map.put("skipped", 1);
            map.put("total", 6);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getFailed()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should prefer 'completed' over 'success' when both present and completed > 0")
        void shouldPreferCompletedOverSuccess() {
            Map<String, Object> map = new HashMap<>();
            map.put("completed", 10);
            map.put("success", 5);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getCompleted()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should prefer 'failed' over 'failure' when both present and failed > 0")
        void shouldPreferFailedOverFailure() {
            Map<String, Object> map = new HashMap<>();
            map.put("failed", 7);
            map.put("failure", 3);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getFailed()).isEqualTo(7);
        }

        @Test
        @DisplayName("Should fall back to 'success' when 'completed' is zero and 'success' is present")
        void shouldFallBackToSuccessWhenCompletedIsZero() {
            Map<String, Object> map = new HashMap<>();
            map.put("completed", 0);
            map.put("success", 8);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getCompleted()).isEqualTo(8);
        }

        @Test
        @DisplayName("Should fall back to 'failure' when 'failed' is zero and 'failure' is present")
        void shouldFallBackToFailureWhenFailedIsZero() {
            Map<String, Object> map = new HashMap<>();
            map.put("failed", 0);
            map.put("failure", 4);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getFailed()).isEqualTo(4);
        }

        @Test
        @DisplayName("Should handle String values in map")
        void shouldHandleStringValues() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", "3");
            map.put("completed", "7");
            map.put("failed", "2");
            map.put("skipped", "1");
            map.put("total", "13");

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isEqualTo(3);
            assertThat(sc.getCompleted()).isEqualTo(7);
            assertThat(sc.getFailed()).isEqualTo(2);
            assertThat(sc.getSkipped()).isEqualTo(1);
            assertThat(sc.getTotal()).isEqualTo(13);
        }

        @Test
        @DisplayName("Should handle Long values in map")
        void shouldHandleLongValues() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 1L);
            map.put("completed", 2L);
            map.put("failed", 3L);
            map.put("skipped", 4L);
            map.put("total", 10L);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isEqualTo(1);
            assertThat(sc.getCompleted()).isEqualTo(2);
            assertThat(sc.getFailed()).isEqualTo(3);
            assertThat(sc.getSkipped()).isEqualTo(4);
            assertThat(sc.getTotal()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should handle Double values in map")
        void shouldHandleDoubleValues() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 1.9);
            map.put("completed", 2.7);

            StatusCounts sc = StatusCounts.fromMap(map);

            // intValue() truncates
            assertThat(sc.getRunning()).isEqualTo(1);
            assertThat(sc.getCompleted()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should return zero for invalid string values")
        void shouldReturnZeroForInvalidStrings() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", "not_a_number");
            map.put("completed", "abc");
            map.put("failed", "");
            map.put("total", "xyz");

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isZero();
            assertThat(sc.getCompleted()).isZero();
            assertThat(sc.getFailed()).isZero();
            assertThat(sc.getTotal()).isZero();
        }

        @Test
        @DisplayName("Should return zero for null values in map")
        void shouldReturnZeroForNullValues() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", null);
            map.put("completed", null);
            map.put("failed", null);
            map.put("skipped", null);
            map.put("total", null);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isZero();
            assertThat(sc.getCompleted()).isZero();
            assertThat(sc.getFailed()).isZero();
            assertThat(sc.getSkipped()).isZero();
            assertThat(sc.getTotal()).isZero();
        }

        @Test
        @DisplayName("Should handle mixed types in map")
        void shouldHandleMixedTypes() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 1);
            map.put("completed", "2");
            map.put("failed", 3L);
            map.put("skipped", 4.0);
            map.put("total", 10);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isEqualTo(1);
            assertThat(sc.getCompleted()).isEqualTo(2);
            assertThat(sc.getFailed()).isEqualTo(3);
            assertThat(sc.getSkipped()).isEqualTo(4);
            assertThat(sc.getTotal()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should handle map with extra keys gracefully")
        void shouldIgnoreExtraKeys() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 1);
            map.put("completed", 2);
            map.put("failed", 0);
            map.put("skipped", 0);
            map.put("total", 3);
            map.put("extra_key", "should_be_ignored");
            map.put("another_extra", 999);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isEqualTo(1);
            assertThat(sc.getCompleted()).isEqualTo(2);
            assertThat(sc.getTotal()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should handle boolean values as non-number non-string (returns 0)")
        void shouldReturnZeroForBooleanValues() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", true);
            map.put("completed", false);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isZero();
            assertThat(sc.getCompleted()).isZero();
        }
    }

    // =========================================================================
    // 10. snapshot() Tests
    // =========================================================================

    @Nested
    @DisplayName("snapshot()")
    class SnapshotTests {

        @Test
        @DisplayName("Should create a copy with identical values")
        void shouldCreateIdenticalCopy() {
            counts.setTotal(5);
            counts.incrementRunning();
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementFailed();
            counts.incrementSkipped();

            StatusCounts snap = counts.snapshot();

            assertThat(snap.getRunning()).isEqualTo(counts.getRunning());
            assertThat(snap.getCompleted()).isEqualTo(counts.getCompleted());
            assertThat(snap.getFailed()).isEqualTo(counts.getFailed());
            assertThat(snap.getSkipped()).isEqualTo(counts.getSkipped());
            assertThat(snap.getTotal()).isEqualTo(counts.getTotal());
        }

        @Test
        @DisplayName("Should be independent: modifying original does not affect snapshot")
        void shouldBeIndependentFromOriginal() {
            counts.setTotal(3);
            counts.incrementCompleted();
            counts.incrementRunning();

            StatusCounts snap = counts.snapshot();

            counts.incrementCompleted();
            counts.incrementFailed();
            counts.decrementRunning();
            counts.setTotal(10);

            assertThat(snap.getCompleted()).isEqualTo(1);
            assertThat(snap.getRunning()).isEqualTo(1);
            assertThat(snap.getFailed()).isZero();
            assertThat(snap.getTotal()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should be independent: modifying snapshot does not affect original")
        void shouldBeIndependentFromSnapshot() {
            counts.setTotal(2);
            counts.incrementCompleted();
            counts.incrementFailed();

            StatusCounts snap = counts.snapshot();

            snap.incrementRunning();
            snap.incrementSkipped();
            snap.setTotal(100);

            assertThat(counts.getRunning()).isZero();
            assertThat(counts.getSkipped()).isZero();
            assertThat(counts.getTotal()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should create snapshot of default (all-zero) StatusCounts")
        void shouldSnapshotDefaultCounts() {
            StatusCounts snap = counts.snapshot();

            assertThat(snap.getRunning()).isZero();
            assertThat(snap.getCompleted()).isZero();
            assertThat(snap.getFailed()).isZero();
            assertThat(snap.getSkipped()).isZero();
            assertThat(snap.getTotal()).isZero();
        }

        @Test
        @DisplayName("Should snapshot preserve derived values (processed, isComplete, etc.)")
        void shouldPreserveDerivedValues() {
            counts.setTotal(3);
            counts.incrementCompleted();
            counts.incrementFailed();
            counts.incrementSkipped();

            StatusCounts snap = counts.snapshot();

            assertThat(snap.getProcessed()).isEqualTo(3);
            assertThat(snap.isComplete()).isTrue();
            assertThat(snap.getAggregateStatus()).isEqualTo(NodeStatus.FAILED);
        }
    }

    // =========================================================================
    // 11. Thread safety stress tests
    // =========================================================================

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Should handle concurrent increments of running counter correctly")
        void shouldHandleConcurrentRunningIncrements() throws Exception {
            int threadCount = 100;
            int incrementsPerThread = 1000;

            CompletableFuture<?>[] futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        for (int j = 0; j < incrementsPerThread; j++) {
                            counts.incrementRunning();
                        }
                    }))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            assertThat(counts.getRunning()).isEqualTo(threadCount * incrementsPerThread);
        }

        @Test
        @DisplayName("Should handle concurrent increments of all counters correctly")
        void shouldHandleConcurrentAllCounterIncrements() throws Exception {
            int threadCount = 50;
            int incrementsPerThread = 500;

            CompletableFuture<?>[] futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        for (int j = 0; j < incrementsPerThread; j++) {
                            counts.incrementRunning();
                            counts.incrementCompleted();
                            counts.incrementFailed();
                            counts.incrementSkipped();
                            counts.incrementTotal();
                        }
                    }))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            int expected = threadCount * incrementsPerThread;
            assertThat(counts.getRunning()).isEqualTo(expected);
            assertThat(counts.getCompleted()).isEqualTo(expected);
            assertThat(counts.getFailed()).isEqualTo(expected);
            assertThat(counts.getSkipped()).isEqualTo(expected);
            assertThat(counts.getTotal()).isEqualTo(expected);
        }

        @Test
        @DisplayName("Should handle concurrent increment and decrement pairs leaving net zero")
        void shouldHandleConcurrentIncrementDecrement() throws Exception {
            int threadCount = 100;
            int opsPerThread = 500;

            CompletableFuture<?>[] futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        for (int j = 0; j < opsPerThread; j++) {
                            counts.incrementCompleted();
                            counts.decrementCompleted();
                        }
                    }))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            assertThat(counts.getCompleted()).isZero();
        }

        @Test
        @DisplayName("Should handle concurrent snapshots without corrupted reads")
        void shouldHandleConcurrentSnapshots() throws Exception {
            counts.setTotal(10);
            counts.incrementCompleted();
            counts.incrementCompleted();

            int threadCount = 50;

            CompletableFuture<StatusCounts>[] futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        counts.incrementRunning();
                        StatusCounts snap = counts.snapshot();
                        counts.decrementRunning();
                        return snap;
                    }))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            // After all threads complete, running should be back to 0
            assertThat(counts.getRunning()).isZero();
            // Completed should remain unchanged
            assertThat(counts.getCompleted()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle concurrent mixed operations with executor service")
        void shouldHandleConcurrentMixedOperations() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(8);
            int operationsPerType = 1000;

            List<CompletableFuture<?>> futures = new ArrayList<>();

            // Increment running
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < operationsPerType; i++) {
                    counts.incrementRunning();
                }
            }, executor));

            // Increment completed
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < operationsPerType; i++) {
                    counts.incrementCompleted();
                }
            }, executor));

            // Increment failed
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < operationsPerType; i++) {
                    counts.incrementFailed();
                }
            }, executor));

            // Increment skipped
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = 0; i < operationsPerType; i++) {
                    counts.incrementSkipped();
                }
            }, executor));

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.SECONDS);

            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(counts.getRunning()).isEqualTo(operationsPerType);
            assertThat(counts.getCompleted()).isEqualTo(operationsPerType);
            assertThat(counts.getFailed()).isEqualTo(operationsPerType);
            assertThat(counts.getSkipped()).isEqualTo(operationsPerType);
        }
    }

    // =========================================================================
    // 12. Edge Cases
    // =========================================================================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle all counters at zero (completely empty)")
        void shouldHandleAllZeros() {
            StatusCounts sc = new StatusCounts(0, 0, 0, 0, 0);

            assertThat(sc.getProcessed()).isZero();
            assertThat(sc.isComplete()).isFalse();
            assertThat(sc.hasCompleted()).isFalse();
            assertThat(sc.isAllSkipped()).isFalse();
            assertThat(sc.getAggregateStatus()).isEqualTo(NodeStatus.PENDING);
        }

        @Test
        @DisplayName("Should handle very large counter values")
        void shouldHandleVeryLargeValues() {
            StatusCounts sc = new StatusCounts(0, 1_000_000, 500_000, 250_000, 1_750_000);

            assertThat(sc.getProcessed()).isEqualTo(1_750_000);
            assertThat(sc.isComplete()).isTrue();
            assertThat(sc.hasCompleted()).isTrue();
            assertThat(sc.isAllSkipped()).isFalse();
            // failed > 0 takes priority over completed in aggregate status
            assertThat(sc.getAggregateStatus()).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("Should handle only one counter being non-zero (running)")
        void shouldHandleSingleRunning() {
            StatusCounts sc = new StatusCounts(1, 0, 0, 0, 1);

            assertThat(sc.getProcessed()).isZero();
            assertThat(sc.isComplete()).isFalse();
            assertThat(sc.hasCompleted()).isFalse();
            assertThat(sc.isAllSkipped()).isFalse();
            assertThat(sc.getAggregateStatus()).isEqualTo(NodeStatus.RUNNING);
        }

        @Test
        @DisplayName("Should handle only one counter being non-zero (completed)")
        void shouldHandleSingleCompleted() {
            StatusCounts sc = new StatusCounts(0, 1, 0, 0, 1);

            assertThat(sc.getProcessed()).isEqualTo(1);
            assertThat(sc.isComplete()).isTrue();
            assertThat(sc.hasCompleted()).isTrue();
            assertThat(sc.isAllSkipped()).isFalse();
            assertThat(sc.getAggregateStatus()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should handle only one counter being non-zero (failed)")
        void shouldHandleSingleFailed() {
            StatusCounts sc = new StatusCounts(0, 0, 1, 0, 1);

            assertThat(sc.getProcessed()).isEqualTo(1);
            assertThat(sc.isComplete()).isTrue();
            assertThat(sc.hasCompleted()).isFalse();
            assertThat(sc.isAllSkipped()).isFalse();
            assertThat(sc.getAggregateStatus()).isEqualTo(NodeStatus.FAILED);
        }

        @Test
        @DisplayName("Should handle only one counter being non-zero (skipped)")
        void shouldHandleSingleSkipped() {
            StatusCounts sc = new StatusCounts(0, 0, 0, 1, 1);

            assertThat(sc.getProcessed()).isEqualTo(1);
            assertThat(sc.isComplete()).isTrue();
            assertThat(sc.hasCompleted()).isFalse();
            assertThat(sc.isAllSkipped()).isTrue();
            assertThat(sc.getAggregateStatus()).isEqualTo(NodeStatus.SKIPPED);
        }

        @Test
        @DisplayName("Should roundtrip through toMap/fromMap correctly")
        void shouldRoundtripThroughMapConversion() {
            StatusCounts original = new StatusCounts(2, 5, 3, 1, 11);
            Map<String, Object> map = original.toMap();
            StatusCounts restored = StatusCounts.fromMap(map);

            assertThat(restored.getRunning()).isEqualTo(original.getRunning());
            assertThat(restored.getCompleted()).isEqualTo(original.getCompleted());
            assertThat(restored.getFailed()).isEqualTo(original.getFailed());
            assertThat(restored.getSkipped()).isEqualTo(original.getSkipped());
            assertThat(restored.getTotal()).isEqualTo(original.getTotal());
        }

        @Test
        @DisplayName("Should roundtrip through snapshot correctly")
        void shouldRoundtripThroughSnapshot() {
            StatusCounts original = new StatusCounts(3, 7, 2, 4, 16);
            StatusCounts snapped = original.snapshot();

            assertThat(snapped.getRunning()).isEqualTo(original.getRunning());
            assertThat(snapped.getCompleted()).isEqualTo(original.getCompleted());
            assertThat(snapped.getFailed()).isEqualTo(original.getFailed());
            assertThat(snapped.getSkipped()).isEqualTo(original.getSkipped());
            assertThat(snapped.getTotal()).isEqualTo(original.getTotal());
            assertThat(snapped.getProcessed()).isEqualTo(original.getProcessed());
            assertThat(snapped.isComplete()).isEqualTo(original.isComplete());
            assertThat(snapped.getAggregateStatus()).isEqualTo(original.getAggregateStatus());
        }

        @Test
        @DisplayName("Should handle processed exceeding total gracefully")
        void shouldHandleProcessedExceedingTotal() {
            counts.setTotal(2);
            counts.incrementCompleted();
            counts.incrementCompleted();
            counts.incrementCompleted(); // processed=3, total=2

            assertThat(counts.getProcessed()).isEqualTo(3);
            assertThat(counts.isComplete()).isFalse(); // processed != total
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.COMPLETED);
        }

        @Test
        @DisplayName("Should handle total less than individual counters")
        void shouldHandleTotalLessThanCounters() {
            StatusCounts sc = new StatusCounts(0, 100, 50, 25, 10);

            assertThat(sc.getProcessed()).isEqualTo(175);
            assertThat(sc.isComplete()).isFalse(); // processed (175) != total (10)
        }

        @Test
        @DisplayName("toString should contain class name and all fields")
        void toStringShouldContainAllInfo() {
            StatusCounts sc = new StatusCounts(1, 2, 3, 4, 10);
            String str = sc.toString();

            assertThat(str).contains("StatusCounts");
            assertThat(str).contains("running=1");
            assertThat(str).contains("completed=2");
            assertThat(str).contains("failed=3");
            assertThat(str).contains("skipped=4");
            assertThat(str).contains("total=10");
        }

        @Test
        @DisplayName("toString for default constructor should show all zeros")
        void toStringDefaultShouldShowZeros() {
            String str = counts.toString();

            assertThat(str).contains("running=0");
            assertThat(str).contains("completed=0");
            assertThat(str).contains("failed=0");
            assertThat(str).contains("skipped=0");
            assertThat(str).contains("total=0");
        }

        @Test
        @DisplayName("Multiple snapshots should each be independent")
        void multipleSnapshotsShouldBeIndependent() {
            counts.setTotal(5);
            counts.incrementCompleted();

            StatusCounts snap1 = counts.snapshot();

            counts.incrementFailed();
            StatusCounts snap2 = counts.snapshot();

            counts.incrementRunning();
            StatusCounts snap3 = counts.snapshot();

            // snap1: completed=1, failed=0, running=0
            assertThat(snap1.getCompleted()).isEqualTo(1);
            assertThat(snap1.getFailed()).isZero();
            assertThat(snap1.getRunning()).isZero();

            // snap2: completed=1, failed=1, running=0
            assertThat(snap2.getCompleted()).isEqualTo(1);
            assertThat(snap2.getFailed()).isEqualTo(1);
            assertThat(snap2.getRunning()).isZero();

            // snap3: completed=1, failed=1, running=1
            assertThat(snap3.getCompleted()).isEqualTo(1);
            assertThat(snap3.getFailed()).isEqualTo(1);
            assertThat(snap3.getRunning()).isEqualTo(1);

            // Modifying snap1 should not affect snap2 or snap3
            snap1.incrementSkipped();
            assertThat(snap2.getSkipped()).isZero();
            assertThat(snap3.getSkipped()).isZero();
        }

        @Test
        @DisplayName("fromMap with only legacy keys and no modern keys should work")
        void fromMapWithOnlyLegacyKeys() {
            Map<String, Object> map = new HashMap<>();
            map.put("running", 1);
            map.put("success", 10);
            map.put("failure", 5);
            map.put("skipped", 3);
            map.put("total", 19);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isEqualTo(1);
            assertThat(sc.getCompleted()).isEqualTo(10);
            assertThat(sc.getFailed()).isEqualTo(5);
            assertThat(sc.getSkipped()).isEqualTo(3);
            assertThat(sc.getTotal()).isEqualTo(19);
        }

        @Test
        @DisplayName("fromMap with missing keys should default those to zero")
        void fromMapWithMissingKeys() {
            Map<String, Object> map = new HashMap<>();
            map.put("completed", 5);

            StatusCounts sc = StatusCounts.fromMap(map);

            assertThat(sc.getRunning()).isZero();
            assertThat(sc.getCompleted()).isEqualTo(5);
            assertThat(sc.getFailed()).isZero();
            assertThat(sc.getSkipped()).isZero();
            assertThat(sc.getTotal()).isZero();
        }

        @Test
        @DisplayName("Aggregate status transitions correctly as counters change")
        void aggregateStatusTransitionsCorrectly() {
            // PENDING -> SKIPPED -> COMPLETED -> FAILED -> RUNNING -> back down
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.PENDING);

            counts.incrementSkipped();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.SKIPPED);

            counts.incrementCompleted();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.COMPLETED);

            counts.incrementFailed();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.FAILED);

            counts.incrementRunning();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.RUNNING);

            // Remove running -> back to FAILED
            counts.decrementRunning();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.FAILED);

            // Remove failed -> back to COMPLETED
            counts.decrementFailed();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.COMPLETED);

            // Remove completed -> back to SKIPPED
            counts.decrementCompleted();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.SKIPPED);

            // Remove skipped -> back to PENDING
            counts.decrementSkipped();
            assertThat(counts.getAggregateStatus()).isEqualTo(NodeStatus.PENDING);
        }
    }
}
