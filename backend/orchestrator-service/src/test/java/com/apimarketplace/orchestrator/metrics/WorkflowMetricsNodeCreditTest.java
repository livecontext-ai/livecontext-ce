package com.apimarketplace.orchestrator.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WorkflowMetrics - Node Credit Tracking")
class WorkflowMetricsNodeCreditTest {

    private MeterRegistry registry;
    private WorkflowMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new WorkflowMetrics(registry);
    }

    @Nested
    @DisplayName("extractStepTypePrefix")
    class ExtractStepTypePrefix {

        @Test
        @DisplayName("should extract prefix before first colon")
        void shouldExtractPrefix() {
            assertThat(WorkflowMetrics.extractStepTypePrefix("mcp:step1")).isEqualTo("mcp");
            assertThat(WorkflowMetrics.extractStepTypePrefix("agent:classify")).isEqualTo("agent");
            assertThat(WorkflowMetrics.extractStepTypePrefix("core:decision:if")).isEqualTo("core");
            assertThat(WorkflowMetrics.extractStepTypePrefix("trigger:webhook")).isEqualTo("trigger");
        }

        @Test
        @DisplayName("should return 'unknown' for nodeId without colon")
        void shouldReturnUnknownForNoColon() {
            assertThat(WorkflowMetrics.extractStepTypePrefix("nodewithoutcolon")).isEqualTo("unknown");
        }

        @Test
        @DisplayName("should return 'unknown' for null nodeId")
        void shouldReturnUnknownForNull() {
            assertThat(WorkflowMetrics.extractStepTypePrefix(null)).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("Per-run node credit accumulator")
    class RunNodeCreditAccumulator {

        @Test
        @DisplayName("should start at zero for unknown run")
        void shouldStartAtZero() {
            assertThat(metrics.getRunNodeCredits("unknown-run")).isEqualTo(0);
        }

        @Test
        @DisplayName("should increment on each recordRunNodeCredit call")
        void shouldIncrementOnRecord() {
            metrics.recordRunNodeCredit("run-1");
            metrics.recordRunNodeCredit("run-1");
            metrics.recordRunNodeCredit("run-1");

            assertThat(metrics.getRunNodeCredits("run-1")).isEqualTo(3);
        }

        @Test
        @DisplayName("should track runs independently")
        void shouldTrackRunsIndependently() {
            metrics.recordRunNodeCredit("run-a");
            metrics.recordRunNodeCredit("run-a");
            metrics.recordRunNodeCredit("run-b");

            assertThat(metrics.getRunNodeCredits("run-a")).isEqualTo(2);
            assertThat(metrics.getRunNodeCredits("run-b")).isEqualTo(1);
        }

        @Test
        @DisplayName("should clear accumulator on cleanupRun")
        void shouldClearOnCleanup() {
            metrics.recordRunNodeCredit("run-cleanup");
            metrics.recordRunNodeCredit("run-cleanup");
            assertThat(metrics.getRunNodeCredits("run-cleanup")).isEqualTo(2);

            metrics.cleanupRun("run-cleanup");

            assertThat(metrics.getRunNodeCredits("run-cleanup")).isEqualTo(0);
        }

        @Test
        @DisplayName("should handle null runId gracefully")
        void shouldHandleNullRunId() {
            metrics.recordRunNodeCredit(null);
            assertThat(metrics.getRunNodeCredits(null)).isEqualTo(0);
            metrics.cleanupRun(null); // should not throw
        }
    }

    @Nested
    @DisplayName("Prometheus counters")
    class PrometheusCounters {

        @Test
        @DisplayName("should increment total counter on recordNodeCreditConsumed")
        void shouldIncrementTotalCounter() {
            metrics.recordNodeCreditConsumed("mcp:step1");
            metrics.recordNodeCreditConsumed("agent:classify");

            double total = registry.counter(WorkflowMetrics.NODE_CREDITS_CONSUMED_TOTAL).count();
            assertThat(total).isEqualTo(2.0);
        }

        @Test
        @DisplayName("should increment by-type counter per step type")
        void shouldIncrementByTypeCounter() {
            metrics.recordNodeCreditConsumed("mcp:step1");
            metrics.recordNodeCreditConsumed("mcp:step2");
            metrics.recordNodeCreditConsumed("agent:classify");

            double mcpCount = registry.counter(
                    WorkflowMetrics.NODE_CREDITS_CONSUMED_TOTAL + "_by_type", "step_type", "mcp").count();
            double agentCount = registry.counter(
                    WorkflowMetrics.NODE_CREDITS_CONSUMED_TOTAL + "_by_type", "step_type", "agent").count();

            assertThat(mcpCount).isEqualTo(2.0);
            assertThat(agentCount).isEqualTo(1.0);
        }
    }
}
