package com.apimarketplace.orchestrator.tools.utility;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WaitToolsProvider} - the blocking sleep tool.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitToolsProvider - wait tool (sleep)")
class WaitToolsProviderTest {

    private static final int MAX_SECONDS = 300;

    @Mock AgentCancellationProbe cancellationProbe;

    private WaitToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new WaitToolsProvider(cancellationProbe, MAX_SECONDS);
        // Shrink the cancellation-poll slice so sleep tests run fast.
        provider.sliceMs = 5L;
    }

    private ToolExecutionResult execute(Map<String, Object> params) {
        return provider.execute("wait", params, ToolExecutionContext.of("tenant-1"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    @Nested
    @DisplayName("tool definition")
    class Definition {

        @Test
        @DisplayName("exposes a single 'wait' tool in the UTILITY category")
        void exposesWaitTool() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.UTILITY);
            assertThat(provider.getTools()).hasSize(1);
            AgentToolDefinition def = provider.getTools().get(0);
            assertThat(def.name()).isEqualTo("wait");
            assertThat(def.requiredParameters()).containsExactly("action");
        }

        @Test
        @DisplayName("declares a per-tool timeout above the max sleep so the loop cannot kill a legal sleep")
        void timeoutCoversMaxSleep() {
            AgentToolDefinition def = provider.getTools().get(0);
            assertThat(def.timeoutMs()).isGreaterThan(MAX_SECONDS * 1000L);
        }

        /**
         * The 240s production default is what keeps a single blocking sleep under
         * the 5-minute silence watchdogs (bridge inactivity kill + agent loop
         * inactivity watchdog). Tests construct the provider with an explicit max,
         * so without this pin a regression raising the annotation default past the
         * watchdog window would sail through the whole suite green.
         */
        @Test
        @DisplayName("production default wait.max-seconds stays 240 (under the 5-min silence watchdogs)")
        void productionDefaultMaxStaysUnderSilenceWatchdogs() throws Exception {
            var ctor = WaitToolsProvider.class.getConstructor(AgentCancellationProbe.class, int.class);
            var value = ctor.getParameters()[1]
                    .getAnnotation(org.springframework.beans.factory.annotation.Value.class);
            assertThat(value).isNotNull();
            assertThat(value.value()).isEqualTo("${wait.max-seconds:240}");
        }
    }

    @Nested
    @DisplayName("action dispatch & validation")
    class Dispatch {

        @Test
        @DisplayName("unknown tool name -> TOOL_NOT_FOUND")
        void unknownToolName() {
            ToolExecutionResult r = provider.execute("nap", Map.of("action", "sleep"),
                    ToolExecutionContext.of("tenant-1"));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("missing action -> MISSING_PARAMETER listing valid actions")
        void missingAction() {
            ToolExecutionResult r = execute(Map.of());
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("sleep").contains("help");
        }

        @Test
        @DisplayName("invalid action -> VALIDATION_ERROR listing valid actions")
        void invalidAction() {
            ToolExecutionResult r = execute(Map.of("action", "snooze"));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("sleep");
        }

        @Test
        @DisplayName("help returns actions, constraints and examples without sleeping")
        void helpPayload() {
            ToolExecutionResult r = execute(Map.of("action", "help"));
            assertThat(r.success()).isTrue();
            assertThat(data(r)).containsKeys("description", "actions", "when_to_use", "constraints", "examples");
            verifyNoInteractions(cancellationProbe);
        }
    }

    @Nested
    @DisplayName("sleep - parameter validation")
    class SleepValidation {

        @Test
        @DisplayName("missing seconds -> MISSING_PARAMETER naming the 1..max range")
        void missingSeconds() {
            ToolExecutionResult r = execute(Map.of("action", "sleep"));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            assertThat(r.error()).contains("1-" + MAX_SECONDS);
        }

        @Test
        @DisplayName("seconds=0 -> INVALID_PARAMETER_VALUE")
        void zeroSeconds() {
            ToolExecutionResult r = execute(Map.of("action", "sleep", "seconds", 0));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }

        @Test
        @DisplayName("seconds above the max -> INVALID_PARAMETER_VALUE pointing at wait_run for runs")
        void aboveMax() {
            ToolExecutionResult r = execute(Map.of("action", "sleep", "seconds", MAX_SECONDS + 1));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(r.error()).contains("wait_run");
        }

        @Test
        @DisplayName("non-numeric seconds -> INVALID_PARAMETER_VALUE echoing the raw value")
        void nonNumericSeconds() {
            ToolExecutionResult r = execute(Map.of("action", "sleep", "seconds", "soon"));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(r.error()).contains("'soon'");
        }

        @Test
        @DisplayName("fractional seconds -> INVALID_PARAMETER_VALUE (whole seconds only)")
        void fractionalSeconds() {
            ToolExecutionResult r = execute(Map.of("action", "sleep", "seconds", 1.5d));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }
    }

    @Nested
    @DisplayName("sleep - execution")
    class SleepExecution {

        @Test
        @DisplayName("completes the requested duration and reports completed status")
        void completesSleep() {
            when(cancellationProbe.isCallerCancelled(any())).thenReturn(false);

            long start = System.currentTimeMillis();
            ToolExecutionResult r = execute(Map.of("action", "sleep", "seconds", 1));
            long elapsed = System.currentTimeMillis() - start;

            assertThat(r.success()).isTrue();
            assertThat(data(r).get("status")).isEqualTo("completed");
            assertThat(data(r).get("requested_seconds")).isEqualTo(1);
            assertThat(data(r).get("slept_seconds")).isEqualTo(1L);
            assertThat(elapsed).isGreaterThanOrEqualTo(950L);
        }

        @Test
        @DisplayName("accepts a numeric string for seconds")
        void numericStringSeconds() {
            when(cancellationProbe.isCallerCancelled(any())).thenReturn(false);
            ToolExecutionResult r = execute(Map.of("action", "sleep", "seconds", "1"));
            assertThat(r.success()).isTrue();
            assertThat(data(r).get("requested_seconds")).isEqualTo(1);
        }

        @Test
        @DisplayName("caller stop releases the sleep within a slice, reporting cancelled + wrap-up note")
        void cancelledMidSleep() {
            when(cancellationProbe.isCallerCancelled(any())).thenReturn(true);

            long start = System.currentTimeMillis();
            // Max-length sleep: only the cancel can end this fast.
            ToolExecutionResult r = execute(Map.of("action", "sleep", "seconds", MAX_SECONDS));
            long elapsed = System.currentTimeMillis() - start;

            assertThat(r.success()).isTrue();
            assertThat(data(r).get("status")).isEqualTo("cancelled");
            assertThat((String) data(r).get("note")).contains("stopped");
            assertThat((Long) data(r).get("slept_seconds")).isLessThan(MAX_SECONDS);
            assertThat(elapsed).isLessThan(5_000L);
        }

        @Test
        @DisplayName("null params map on sleep dispatch is a missing action, not an NPE")
        void nullParams() {
            ToolExecutionResult r = provider.execute("wait", null, ToolExecutionContext.of("tenant-1"));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }
    }

    @Nested
    @DisplayName("seconds parsing edge cases")
    class ParsingEdgeCases {

        @Test
        @DisplayName("whole double (2.0) is accepted as 2")
        void wholeDouble() {
            Map<String, Object> params = new HashMap<>();
            params.put("action", "sleep");
            params.put("seconds", 2.0d);
            when(cancellationProbe.isCallerCancelled(any())).thenReturn(true); // return fast
            ToolExecutionResult r = execute(params);
            assertThat(r.success()).isTrue();
            assertThat(data(r).get("requested_seconds")).isEqualTo(2);
        }

        @Test
        @DisplayName("long out of int range -> rejected with the ORIGINAL value in the message, not a wrapped sentinel")
        void hugeLong() {
            ToolExecutionResult r = execute(Map.of("action", "sleep", "seconds", Long.MAX_VALUE));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(r.error()).contains(String.valueOf(Long.MAX_VALUE)).doesNotContain("-2147483648");
        }
    }

    @Nested
    @DisplayName("interruption")
    class Interruption {

        @Test
        @DisplayName("thread interrupt mid-sleep -> EXECUTION_FAILED and the interrupt flag is restored")
        void interruptMidSleep() throws Exception {
            when(cancellationProbe.isCallerCancelled(any())).thenReturn(false);
            java.util.concurrent.atomic.AtomicReference<ToolExecutionResult> resultRef = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicBoolean flagRestored = new java.util.concurrent.atomic.AtomicBoolean(false);

            Thread worker = new Thread(() -> {
                resultRef.set(execute(Map.of("action", "sleep", "seconds", MAX_SECONDS)));
                flagRestored.set(Thread.currentThread().isInterrupted());
            });
            worker.start();
            Thread.sleep(100);
            worker.interrupt();
            worker.join(5_000);

            assertThat(worker.isAlive()).isFalse();
            ToolExecutionResult r = resultRef.get();
            assertThat(r).isNotNull();
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            assertThat(r.error()).containsIgnoringCase("interrupted");
            assertThat(flagRestored).as("Thread.currentThread().interrupt() must be restored").isTrue();
        }
    }
}
