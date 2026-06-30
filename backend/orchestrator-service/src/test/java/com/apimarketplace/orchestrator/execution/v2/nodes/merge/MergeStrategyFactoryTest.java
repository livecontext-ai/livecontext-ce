package com.apimarketplace.orchestrator.execution.v2.nodes.merge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MergeStrategyFactory Tests")
class MergeStrategyFactoryTest {

    private MergeStrategyFactory factory;

    @BeforeEach
    void setUp() {
        factory = new MergeStrategyFactory();
    }

    @Nested
    @DisplayName("getStrategy()")
    class GetStrategyTests {

        @Test
        @DisplayName("Should return Queue1To1Strategy for QUEUE_1_TO_1")
        void shouldReturnQueue1To1Strategy() {
            // When
            MergeStrategy strategy = factory.getStrategy("QUEUE_1_TO_1");

            // Then
            assertNotNull(strategy);
            assertTrue(strategy instanceof Queue1To1Strategy);
            assertEquals("QUEUE_1_TO_1", strategy.name());
        }

        @Test
        @DisplayName("Should return CombineAllStrategy for COMBINE_ALL")
        void shouldReturnCombineAllStrategy() {
            // When
            MergeStrategy strategy = factory.getStrategy("COMBINE_ALL");

            // Then
            assertNotNull(strategy);
            assertTrue(strategy instanceof CombineAllStrategy);
            assertEquals("COMBINE_ALL", strategy.name());
        }

        @Test
        @DisplayName("Should return FirstAvailableStrategy for FIRST_AVAILABLE")
        void shouldReturnFirstAvailableStrategy() {
            // When
            MergeStrategy strategy = factory.getStrategy("FIRST_AVAILABLE");

            // Then
            assertNotNull(strategy);
            assertTrue(strategy instanceof FirstAvailableStrategy);
            assertEquals("FIRST_AVAILABLE", strategy.name());
        }

        @Test
        @DisplayName("Should be case-insensitive")
        void shouldBeCaseInsensitive() {
            // When
            MergeStrategy lower = factory.getStrategy("queue_1_to_1");
            MergeStrategy upper = factory.getStrategy("QUEUE_1_TO_1");
            MergeStrategy mixed = factory.getStrategy("Queue_1_To_1");

            // Then
            assertNotNull(lower);
            assertNotNull(upper);
            assertNotNull(mixed);
            assertEquals(lower.name(), upper.name());
            assertEquals(upper.name(), mixed.name());
        }

        @Test
        @DisplayName("Should normalize hyphens to underscores")
        void shouldNormalizeHyphensToUnderscores() {
            // When
            MergeStrategy strategy = factory.getStrategy("queue-1-to-1");

            // Then
            assertNotNull(strategy);
            assertEquals("QUEUE_1_TO_1", strategy.name());
        }

        @Test
        @DisplayName("Should return default strategy for null input")
        void shouldReturnDefaultForNull() {
            // When
            MergeStrategy strategy = factory.getStrategy(null);

            // Then
            assertNotNull(strategy);
            assertEquals("QUEUE_1_TO_1", strategy.name());
        }

        @Test
        @DisplayName("Should return default strategy for blank input")
        void shouldReturnDefaultForBlank() {
            // When
            MergeStrategy strategy = factory.getStrategy("   ");

            // Then
            assertNotNull(strategy);
            assertEquals("QUEUE_1_TO_1", strategy.name());
        }

        @Test
        @DisplayName("Should return default strategy for empty input")
        void shouldReturnDefaultForEmpty() {
            // When
            MergeStrategy strategy = factory.getStrategy("");

            // Then
            assertNotNull(strategy);
            assertEquals("QUEUE_1_TO_1", strategy.name());
        }

        @Test
        @DisplayName("Should return default strategy for unknown strategy name")
        void shouldReturnDefaultForUnknown() {
            // When
            MergeStrategy strategy = factory.getStrategy("UNKNOWN_STRATEGY");

            // Then
            assertNotNull(strategy);
            assertEquals("QUEUE_1_TO_1", strategy.name());
        }
    }

    @Nested
    @DisplayName("getDefaultStrategy()")
    class GetDefaultStrategyTests {

        @Test
        @DisplayName("Should return Queue1To1Strategy as default")
        void shouldReturnQueue1To1AsDefault() {
            // When
            MergeStrategy strategy = factory.getDefaultStrategy();

            // Then
            assertNotNull(strategy);
            assertTrue(strategy instanceof Queue1To1Strategy);
            assertEquals("QUEUE_1_TO_1", strategy.name());
        }
    }

    @Nested
    @DisplayName("registerStrategy()")
    class RegisterStrategyTests {

        @Test
        @DisplayName("Should register custom strategy")
        void shouldRegisterCustomStrategy() {
            // Given
            MergeStrategy customStrategy = new CustomTestStrategy();

            // When
            factory.registerStrategy(customStrategy);
            MergeStrategy retrieved = factory.getStrategy("CUSTOM_TEST");

            // Then
            assertNotNull(retrieved);
            assertEquals("CUSTOM_TEST", retrieved.name());
        }

        @Test
        @DisplayName("Should override existing strategy")
        void shouldOverrideExistingStrategy() {
            // Given
            MergeStrategy customQueue = new MergeStrategy() {
                @Override
                public String name() {
                    return "QUEUE_1_TO_1";
                }

                @Override
                public boolean canMerge(java.util.List<String> sourceNodeIds,
                        com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context) {
                    return true;
                }

                @Override
                public java.util.Map<String, Object> merge(java.util.List<String> sourceNodeIds,
                        com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context) {
                    return java.util.Map.of("custom", true);
                }
            };

            // When
            factory.registerStrategy(customQueue);
            MergeStrategy retrieved = factory.getStrategy("QUEUE_1_TO_1");

            // Then
            assertNotNull(retrieved);
            assertEquals("QUEUE_1_TO_1", retrieved.name());
            // The custom implementation should be returned
            assertFalse(retrieved instanceof Queue1To1Strategy);
        }
    }

    @Nested
    @DisplayName("getAvailableStrategies()")
    class GetAvailableStrategiesTests {

        @Test
        @DisplayName("Should return all default strategies")
        void shouldReturnAllDefaultStrategies() {
            // When
            Set<String> strategies = factory.getAvailableStrategies();

            // Then
            assertNotNull(strategies);
            assertTrue(strategies.contains("QUEUE_1_TO_1"));
            assertTrue(strategies.contains("COMBINE_ALL"));
            assertTrue(strategies.contains("FIRST_AVAILABLE"));
        }

        @Test
        @DisplayName("Should return at least 3 strategies")
        void shouldReturnAtLeast3Strategies() {
            // When
            Set<String> strategies = factory.getAvailableStrategies();

            // Then
            assertTrue(strategies.size() >= 3);
        }

        @Test
        @DisplayName("Should include custom registered strategies")
        void shouldIncludeCustomStrategies() {
            // Given
            factory.registerStrategy(new CustomTestStrategy());

            // When
            Set<String> strategies = factory.getAvailableStrategies();

            // Then
            assertTrue(strategies.contains("CUSTOM_TEST"));
        }

        @Test
        @DisplayName("Should return immutable set")
        void shouldReturnImmutableSet() {
            // When
            Set<String> strategies = factory.getAvailableStrategies();

            // Then
            assertThrows(UnsupportedOperationException.class, () -> strategies.add("NEW_STRATEGY"));
        }
    }

    @Nested
    @DisplayName("Strategy Caching")
    class StrategyCachingTests {

        @Test
        @DisplayName("Should return same instance for same strategy name")
        void shouldReturnSameInstanceForSameName() {
            // When
            MergeStrategy first = factory.getStrategy("QUEUE_1_TO_1");
            MergeStrategy second = factory.getStrategy("QUEUE_1_TO_1");

            // Then
            assertSame(first, second);
        }

        @Test
        @DisplayName("Should return same instance for different case")
        void shouldReturnSameInstanceForDifferentCase() {
            // When
            MergeStrategy upper = factory.getStrategy("COMBINE_ALL");
            MergeStrategy lower = factory.getStrategy("combine_all");

            // Then
            assertSame(upper, lower);
        }
    }

    // Helper class for testing custom strategy registration
    private static class CustomTestStrategy implements MergeStrategy {
        @Override
        public String name() {
            return "CUSTOM_TEST";
        }

        @Override
        public boolean canMerge(java.util.List<String> sourceNodeIds,
                com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context) {
            return true;
        }

        @Override
        public java.util.Map<String, Object> merge(java.util.List<String> sourceNodeIds,
                com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext context) {
            return java.util.Map.of("strategy", "CUSTOM_TEST");
        }
    }
}
