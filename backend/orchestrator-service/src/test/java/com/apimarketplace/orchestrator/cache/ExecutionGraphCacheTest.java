package com.apimarketplace.orchestrator.cache;

import com.apimarketplace.orchestrator.config.RedisCacheConfig;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionGraph;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * Tests for ExecutionGraphCache (Caffeine-backed).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ExecutionGraphCache")
class ExecutionGraphCacheTest {

    @Mock
    private RedisCacheConfig cacheConfig;

    private ExecutionGraphCache cache;

    @BeforeEach
    void setUp() {
        when(cacheConfig.getExecutionGraphTtl()).thenReturn(Duration.ofMinutes(30));
        cache = new ExecutionGraphCache(cacheConfig);
    }

    @Nested
    @DisplayName("invalidate")
    class InvalidateTests {

        @Test
        @DisplayName("Should not throw when invalidating")
        void shouldNotThrowWhenInvalidating() {
            WorkflowPlan plan = mock(WorkflowPlan.class);
            when(plan.getId()).thenReturn("plan-123");

            assertDoesNotThrow(() -> cache.invalidate(plan));
        }
    }

    @Nested
    @DisplayName("clear")
    class ClearTests {

        @Test
        @DisplayName("Should clear all entries without error")
        void shouldClearAllEntries() {
            assertDoesNotThrow(() -> cache.clear());
        }
    }

    @Nested
    @DisplayName("getStats")
    class GetStatsTests {

        @Test
        @DisplayName("Should return stats with zero entries initially")
        void shouldReturnZeroEntriesInitially() {
            ExecutionGraphCache.CacheStats stats = cache.getStats();

            assertThat(stats.totalEntries()).isEqualTo(0);
            assertThat(stats.getActiveEntries()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should calculate usage percentage correctly")
        void shouldCalculateUsagePercentage() {
            ExecutionGraphCache.CacheStats stats = new ExecutionGraphCache.CacheStats(50, 0, 100);
            assertThat(stats.getUsagePercentage()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("Should return 0 usage when maxSize is 0 or negative")
        void shouldReturnZeroUsageWhenNoMaxSize() {
            ExecutionGraphCache.CacheStats stats = new ExecutionGraphCache.CacheStats(50, 0, 0);
            assertThat(stats.getUsagePercentage()).isEqualTo(0.0);

            ExecutionGraphCache.CacheStats statsNeg = new ExecutionGraphCache.CacheStats(50, 0, -1);
            assertThat(statsNeg.getUsagePercentage()).isEqualTo(0.0);
        }
    }
}
