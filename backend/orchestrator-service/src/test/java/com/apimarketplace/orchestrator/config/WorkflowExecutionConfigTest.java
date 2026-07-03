package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WorkflowExecutionConfig.
 */
@DisplayName("WorkflowExecutionConfig")
class WorkflowExecutionConfigTest {

    private WorkflowExecutionConfig config;

    @BeforeEach
    void setUp() {
        config = new WorkflowExecutionConfig();
    }

    @Nested
    @DisplayName("default values")
    class DefaultValueTests {

        @Test
        @DisplayName("Should have correct default thread pool size")
        void shouldHaveDefaultThreadPoolSize() {
            assertThat(config.getThreadPoolSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should have correct default step timeout")
        void shouldHaveDefaultStepTimeout() {
            assertThat(config.getStepTimeoutMs()).isEqualTo(60000);
        }

        @Test
        @DisplayName("Should have correct default workflow timeout")
        void shouldHaveDefaultWorkflowTimeout() {
            assertThat(config.getWorkflowTimeoutMs()).isEqualTo(3600000);
        }

        @Test
        @DisplayName("Should have correct default max retry attempts")
        void shouldHaveDefaultMaxRetryAttempts() {
            assertThat(config.getMaxRetryAttempts()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should have correct default retry delay")
        void shouldHaveDefaultRetryDelay() {
            assertThat(config.getRetryDelayMs()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Should have correct default retry backoff multiplier")
        void shouldHaveDefaultRetryBackoffMultiplier() {
            assertThat(config.getRetryBackoffMultiplier()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("Should have correct default loop iterations")
        void shouldHaveDefaultLoopIterations() {
            assertThat(config.getDefaultMaxIterations()).isEqualTo(10);
            assertThat(config.getMaxAllowedIterations()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should have correct default loop strategy")
        void shouldHaveDefaultLoopStrategy() {
            assertThat(config.getDefaultLoopStrategy()).isEqualTo("continue-anyway");
        }

        @Test
        @DisplayName("Should have correct default max complexity score")
        void shouldHaveDefaultMaxComplexityScore() {
            assertThat(config.getMaxComplexityScore()).isEqualTo(1000);
        }

        @Test
        @DisplayName("Should have correct default max steps count")
        void shouldHaveDefaultMaxStepsCount() {
            assertThat(config.getMaxStepsCount()).isEqualTo(100);
        }

        @Test
        @DisplayName("Should have correct default max edges count")
        void shouldHaveDefaultMaxEdgesCount() {
            assertThat(config.getMaxEdgesCount()).isEqualTo(200);
        }

        @Test
        @DisplayName("Default max execution minutes is 125 - must exceed the 7200s agent timeout contract (zombie threshold = this + 2 min grace)")
        void shouldHaveDefaultMaxExecutionMinutes() {
            assertThat(config.getMaxExecutionMinutes()).isEqualTo(125);
            // A default at or below 120 min would let the zombie scan fail HEALTHY
            // max-length agent runs (executionTimeout/inactivityTimeout valid to 7200s).
            assertThat(config.getMaxExecutionMinutes() * 60).isGreaterThan(7200L);
        }
    }

    @Nested
    @DisplayName("resolveWorkflowTimeoutMs")
    class ResolveWorkflowTimeoutMsTests {

        @Test
        @DisplayName("Should use maxExecutionMinutes when positive")
        void shouldUseMaxExecutionMinutesWhenPositive() {
            config.setMaxExecutionMinutes(5);

            long resolved = config.resolveWorkflowTimeoutMs();

            assertThat(resolved).isEqualTo(TimeUnit.MINUTES.toMillis(5));
        }

        @Test
        @DisplayName("Should fallback to workflowTimeoutMs when maxExecutionMinutes is 0")
        void shouldFallbackWhenMaxExecutionMinutesIsZero() {
            config.setMaxExecutionMinutes(0);
            config.setWorkflowTimeoutMs(60000);

            long resolved = config.resolveWorkflowTimeoutMs();

            assertThat(resolved).isEqualTo(60000);
        }

        @Test
        @DisplayName("Should calculate correct milliseconds from minutes")
        void shouldCalculateCorrectMilliseconds() {
            config.setMaxExecutionMinutes(10);

            assertThat(config.resolveWorkflowTimeoutMs()).isEqualTo(600000);
        }
    }

    @Nested
    @DisplayName("isEnableMockData")
    class IsEnableMockDataTests {

        @Test
        @DisplayName("Should return enableMockData when explicitly set")
        void shouldReturnExplicitValue() {
            config.setEnableMockData(false);
            assertThat(config.isEnableMockData()).isFalse();

            config.setEnableMockData(true);
            assertThat(config.isEnableMockData()).isTrue();
        }

        @Test
        @DisplayName("Should return null when enableMockData not set")
        void shouldReturnNullWhenNotSet() {
            assertThat(config.getEnableMockData()).isNull();
        }
    }

    @Nested
    @DisplayName("setters and getters")
    class SettersAndGettersTests {

        @Test
        @DisplayName("Should set and get all configuration values")
        void shouldSetAndGetAllValues() {
            config.setThreadPoolSize(50);
            config.setMaxConcurrentSteps(10);
            config.setMaxConcurrentLevels(5);
            config.setStreamingBatchIntervalMs(500);
            config.setTriggerBatchSize(100);
            config.setMaxDatasourceItems(10000);
            config.setMaxItemQueueSize(2000);
            config.setItemWorkerPoolSize(16);

            assertThat(config.getThreadPoolSize()).isEqualTo(50);
            assertThat(config.getMaxConcurrentSteps()).isEqualTo(10);
            assertThat(config.getMaxConcurrentLevels()).isEqualTo(5);
            assertThat(config.getStreamingBatchIntervalMs()).isEqualTo(500);
            assertThat(config.getTriggerBatchSize()).isEqualTo(100);
            assertThat(config.getMaxDatasourceItems()).isEqualTo(10000);
            assertThat(config.getMaxItemQueueSize()).isEqualTo(2000);
            assertThat(config.getItemWorkerPoolSize()).isEqualTo(16);
        }
    }
}
