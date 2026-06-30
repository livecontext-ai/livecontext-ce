package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for LoopDetector - detects repetitive tool calls.
 */
@DisplayName("LoopDetector")
class LoopDetectorTest {

    private LoopDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LoopDetector();
    }

    private ToolCall createCall(String name, Map<String, Object> args) {
        return ToolCall.builder().id("tc-1").toolName(name).arguments(args).build();
    }

    private ToolCall createCall(String name) {
        return createCall(name, Map.of());
    }

    @Nested
    @DisplayName("Identical call detection (recordToolCall)")
    class IdenticalCallDetectionTests {

        @Test
        @DisplayName("first call should return OK")
        void firstCallShouldReturnOk() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            assertThat(detector.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.OK);
        }

        @Test
        @DisplayName("calls below warn threshold should return OK")
        void belowWarnShouldReturnOk() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            for (int i = 0; i < 4; i++) {
                assertThat(detector.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.OK);
            }
        }

        @Test
        @DisplayName("call at warn threshold (5) should return WARN")
        void atWarnThresholdShouldReturnWarn() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            for (int i = 0; i < 4; i++) {
                detector.recordToolCall(call);
            }
            assertThat(detector.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.WARN);
        }

        @Test
        @DisplayName("call at stop threshold (15) should return STOP")
        void atStopThresholdShouldReturnStop() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            for (int i = 0; i < 14; i++) {
                detector.recordToolCall(call);
            }
            assertThat(detector.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.STOP);
        }

        @Test
        @DisplayName("different arguments should be tracked separately")
        void differentArgsShouldBeSeparate() {
            ToolCall call1 = createCall("search", Map.of("q", "test1"));
            ToolCall call2 = createCall("search", Map.of("q", "test2"));

            for (int i = 0; i < 4; i++) {
                assertThat(detector.recordToolCall(call1)).isEqualTo(LoopDetector.DetectionResult.OK);
                assertThat(detector.recordToolCall(call2)).isEqualTo(LoopDetector.DetectionResult.OK);
            }
        }

        @Test
        @DisplayName("different tool names should be tracked separately")
        void differentToolNamesShouldBeSeparate() {
            ToolCall call1 = createCall("search", Map.of("q", "test"));
            ToolCall call2 = createCall("execute", Map.of("q", "test"));

            for (int i = 0; i < 4; i++) {
                assertThat(detector.recordToolCall(call1)).isEqualTo(LoopDetector.DetectionResult.OK);
                assertThat(detector.recordToolCall(call2)).isEqualTo(LoopDetector.DetectionResult.OK);
            }
        }
    }

    @Nested
    @DisplayName("Result recording (recordResult)")
    class RecordResultTests {

        @Test
        @DisplayName("first result should not be duplicate")
        void firstResultShouldNotBeDuplicate() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            assertThat(detector.recordResult(call, "result1")).isFalse();
        }

        @Test
        @DisplayName("same result for same call should be duplicate")
        void sameResultShouldBeDuplicate() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            detector.recordResult(call, "result1");
            assertThat(detector.recordResult(call, "result1")).isTrue();
        }

        @Test
        @DisplayName("different result for same call should not be duplicate")
        void differentResultShouldNotBeDuplicate() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            detector.recordResult(call, "result1");
            assertThat(detector.recordResult(call, "result2")).isFalse();
        }
    }

    @Nested
    @DisplayName("Consecutive call detection")
    class ConsecutiveCallDetectionTests {

        @Test
        @DisplayName("first consecutive call should return OK")
        void firstCallShouldReturnOk() {
            assertThat(detector.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.OK);
        }

        @Test
        @DisplayName("15th call should return REMINDER")
        void at15ShouldReturnReminder() {
            for (int i = 0; i < 14; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.REMINDER);
        }

        @Test
        @DisplayName("25th call should return STRONG_RECOMMENDATION")
        void at25ShouldReturnStrong() {
            for (int i = 0; i < 24; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.STRONG_RECOMMENDATION);
        }

        @Test
        @DisplayName("35th call should return FINAL_WARNING")
        void at35ShouldReturnFinal() {
            for (int i = 0; i < 34; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.FINAL_WARNING);
        }

        @Test
        @DisplayName("40th call should return STOP")
        void at40ShouldReturnStop() {
            for (int i = 0; i < 39; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.STOP);
        }

        @Test
        @DisplayName("getTotalConsecutiveCalls should track count")
        void shouldTrackCount() {
            assertThat(detector.getTotalConsecutiveCalls()).isEqualTo(0);
            detector.recordConsecutiveCall();
            detector.recordConsecutiveCall();
            assertThat(detector.getTotalConsecutiveCalls()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Message generation")
    class MessageGenerationTests {

        @Test
        @DisplayName("generateWarningMessage should include tool name and count")
        void shouldGenerateWarningMessage() {
            ToolCall call = createCall("search");
            String msg = detector.generateWarningMessage(call, 6);

            assertThat(msg).contains("search");
            assertThat(msg).contains("6x");
            assertThat(msg).contains("WARNING");
        }

        @Test
        @DisplayName("generateStopMessage should include tool name and count")
        void shouldGenerateStopMessage() {
            ToolCall call = createCall("search");
            String msg = detector.generateStopMessage(call, 15);

            assertThat(msg).contains("search");
            assertThat(msg).contains("15x");
            assertThat(msg).contains("STOP");
        }

        @Test
        @DisplayName("generateConsecutiveMessage for REMINDER should mention automation")
        void reminderMessageShouldMentionWorkflow() {
            for (int i = 0; i < 15; i++) {
                detector.recordConsecutiveCall();
            }
            String msg = detector.generateConsecutiveMessage(LoopDetector.ConsecutiveResult.REMINDER);
            assertThat(msg).contains("workflow");
        }

        @Test
        @DisplayName("generateConsecutiveMessage for OK should return null")
        void okMessageShouldReturnNull() {
            String msg = detector.generateConsecutiveMessage(LoopDetector.ConsecutiveResult.OK);
            assertThat(msg).isNull();
        }
    }

    @Nested
    @DisplayName("Reset and state management")
    class ResetTests {

        @Test
        @DisplayName("reset should clear all state")
        void resetShouldClearState() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            for (int i = 0; i < 6; i++) {
                detector.recordToolCall(call);
                detector.recordConsecutiveCall();
            }

            detector.reset();

            assertThat(detector.getCallCount(call)).isEqualTo(0);
            assertThat(detector.getTotalConsecutiveCalls()).isEqualTo(0);
            assertThat(detector.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.OK);
        }

        @Test
        @DisplayName("getCallCount should return count for specific signature")
        void getCallCountShouldReturnCount() {
            ToolCall call = createCall("search", Map.of("q", "test"));
            assertThat(detector.getCallCount(call)).isEqualTo(0);

            detector.recordToolCall(call);
            detector.recordToolCall(call);

            assertThat(detector.getCallCount(call)).isEqualTo(2);
        }
    }

    // V100: per-agent configurable thresholds
    @Nested
    @DisplayName("Configurable thresholds (V100 per-agent overrides)")
    class ConfigurableThresholdsTests {

        @Test
        @DisplayName("Custom identical stop threshold triggers STOP at that count")
        void customIdenticalStopTriggersAtCount() {
            LoopDetector custom = new LoopDetector(4, 40);
            ToolCall call = createCall("search", Map.of("q", "test"));

            // 3 calls below stop (identicalStop=4): OK or WARN (warn ≈ ceil(4/3) = 2)
            assertThat(custom.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.OK);
            assertThat(custom.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.WARN); // count=2, warn threshold
            assertThat(custom.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.WARN); // count=3
            assertThat(custom.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.STOP); // count=4 = stop
        }

        @Test
        @DisplayName("Custom consecutive stop threshold triggers STOP at that count")
        void customConsecutiveStopTriggersAtCount() {
            LoopDetector custom = new LoopDetector(15, 8);

            for (int i = 0; i < 7; i++) {
                custom.recordConsecutiveCall();
            }
            // 8th call = STOP (consecutiveStop=8)
            assertThat(custom.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.STOP);
        }

        @Test
        @DisplayName("Intermediate thresholds scale proportionally from consecutive stop")
        void intermediateThresholdsScale() {
            // consecutiveStop=80: final=ceil(80*7/8)=70, strong=ceil(80*5/8)=50, reminder=ceil(80*3/8)=30
            LoopDetector custom = new LoopDetector(15, 80);

            // at 30 → REMINDER
            for (int i = 0; i < 29; i++) custom.recordConsecutiveCall();
            assertThat(custom.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.REMINDER);

            // at 50 → STRONG
            for (int i = 0; i < 19; i++) custom.recordConsecutiveCall();
            assertThat(custom.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.STRONG_RECOMMENDATION);

            // at 70 → FINAL_WARNING
            for (int i = 0; i < 19; i++) custom.recordConsecutiveCall();
            assertThat(custom.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.FINAL_WARNING);

            // at 80 → STOP
            for (int i = 0; i < 9; i++) custom.recordConsecutiveCall();
            assertThat(custom.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.STOP);
        }

        @Test
        @DisplayName("Default constructor matches DEFAULT_* constants (no behavior change)")
        void defaultConstructorMatchesHistoricalBehavior() {
            LoopDetector defaultDetector = new LoopDetector();
            LoopDetector explicitDefault = new LoopDetector(
                LoopDetector.DEFAULT_STOP_THRESHOLD,
                LoopDetector.DEFAULT_CONSECUTIVE_STOP);

            ToolCall call = createCall("search", Map.of("q", "test"));
            // 5th identical call → WARN on both
            for (int i = 0; i < 4; i++) {
                defaultDetector.recordToolCall(call);
                explicitDefault.recordToolCall(call);
            }
            assertThat(defaultDetector.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.WARN);
            assertThat(explicitDefault.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.WARN);
        }

        @Test
        @DisplayName("identicalStop below 2 throws IllegalArgumentException")
        void rejectsTooLowIdenticalStop() {
            assertThatThrownBy(() -> new LoopDetector(1, 40))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identicalStop must be >= 2");
        }

        @Test
        @DisplayName("consecutiveStop below 4 throws IllegalArgumentException")
        void rejectsTooLowConsecutiveStop() {
            assertThatThrownBy(() -> new LoopDetector(15, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consecutiveStop must be >= 4");
        }

        @Test
        @DisplayName("Minimum valid thresholds (2 identical / 4 consecutive) are accepted")
        void minimumValidThresholdsAccepted() {
            LoopDetector min = new LoopDetector(2, 4);
            ToolCall call = createCall("search", Map.of("q", "test"));

            // 2nd identical call → STOP (warn = ceil(2/3) = 1 but min 2, so warn=2=stop; fired as STOP)
            assertThat(min.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.OK);
            assertThat(min.recordToolCall(call)).isEqualTo(LoopDetector.DetectionResult.STOP);

            // 4 consecutive calls → STOP
            LoopDetector min2 = new LoopDetector(2, 4);
            for (int i = 0; i < 3; i++) min2.recordConsecutiveCall();
            assertThat(min2.recordConsecutiveCall()).isEqualTo(LoopDetector.ConsecutiveResult.STOP);
        }
    }
}
