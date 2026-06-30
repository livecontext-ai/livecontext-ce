package com.apimarketplace.agent.logging;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for DefaultAgentLogger - structured agent execution logging.
 */
@DisplayName("DefaultAgentLogger")
class DefaultAgentLoggerTest {

    private DefaultAgentLogger logger;

    @BeforeEach
    void setUp() {
        logger = new DefaultAgentLogger();
    }

    @Nested
    @DisplayName("logExecutionStart()")
    class LogExecutionStartTests {

        @Test
        @DisplayName("should create RunLog for the run")
        void shouldCreateRunLog() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            assertThat(runLog).isNotNull();
            assertThat(runLog.getRunId()).isEqualTo("run-1");
            assertThat(runLog.getUserPrompt()).isEqualTo("Hello");
            assertThat(runLog.getProvider()).isEqualTo("openai");
            assertThat(runLog.getModel()).isEqualTo("gpt-4");
        }

        @Test
        @DisplayName("should add EXECUTION_START log entry")
        void shouldAddStartEntry() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            assertThat(runLog.getEntries()).hasSize(1);
            assertThat(runLog.getEntries().get(0).type()).isEqualTo("EXECUTION_START");
            assertThat(runLog.getEntries().get(0).success()).isTrue();
        }

        @Test
        @DisplayName("should handle null userPrompt")
        void shouldHandleNullPrompt() {
            logger.logExecutionStart("run-1", null, "openai", "gpt-4");

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            assertThat(runLog).isNotNull();
        }
    }

    @Nested
    @DisplayName("logExecutionEnd()")
    class LogExecutionEndTests {

        @Test
        @DisplayName("should add EXECUTION_END entry to existing run")
        void shouldAddEndEntry() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");
            logger.logExecutionEnd("run-1", true, 3, 5, 1500, "COMPLETED");

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            assertThat(runLog.getEntries()).hasSize(2);
            assertThat(runLog.getEntries().get(1).type()).isEqualTo("EXECUTION_END");
            assertThat(runLog.getEntries().get(1).durationMs()).isEqualTo(1500);
        }

        @Test
        @DisplayName("should not fail when run does not exist")
        void shouldNotFailForMissingRun() {
            // Should not throw
            logger.logExecutionEnd("unknown-run", false, 0, 0, 0, "ERROR");
        }
    }

    @Nested
    @DisplayName("logToolCallStart()")
    class LogToolCallStartTests {

        @Test
        @DisplayName("should add TOOL_CALL entry")
        void shouldAddToolCallEntry() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");

            ToolCall toolCall = ToolCall.builder()
                    .id("tc-1")
                    .toolName("search")
                    .arguments(Map.of("query", "test"))
                    .build();

            logger.logToolCallStart("run-1", toolCall);

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            assertThat(runLog.getEntries()).hasSize(2);
            assertThat(runLog.getEntries().get(1).type()).isEqualTo("TOOL_CALL");
            assertThat(runLog.getEntries().get(1).toolName()).isEqualTo("search");
        }
    }

    @Nested
    @DisplayName("logToolCallEnd()")
    class LogToolCallEndTests {

        @Test
        @DisplayName("should add TOOL_RESULT entry for success")
        void shouldAddSuccessResult() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");

            ToolCall toolCall = ToolCall.builder()
                    .id("tc-1")
                    .toolName("search")
                    .arguments(Map.of())
                    .build();

            ToolResult result = ToolResult.success(toolCall, "Found 5 items");

            logger.logToolCallEnd("run-1", toolCall, result, 250);

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            DefaultAgentLogger.LogEntry entry = runLog.getEntries().get(1);
            assertThat(entry.type()).isEqualTo("TOOL_RESULT");
            assertThat(entry.success()).isTrue();
            assertThat(entry.durationMs()).isEqualTo(250);
        }

        @Test
        @DisplayName("should add TOOL_RESULT entry for failure")
        void shouldAddFailureResult() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");

            ToolCall toolCall = ToolCall.builder()
                    .id("tc-1")
                    .toolName("search")
                    .arguments(Map.of())
                    .build();

            ToolResult result = ToolResult.failure(toolCall, "Connection timeout");

            logger.logToolCallEnd("run-1", toolCall, result, 5000);

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            DefaultAgentLogger.LogEntry entry = runLog.getEntries().get(1);
            assertThat(entry.success()).isFalse();
            assertThat(entry.error()).isEqualTo("Connection timeout");
        }
    }

    @Nested
    @DisplayName("logIteration()")
    class LogIterationTests {

        @Test
        @DisplayName("should add ITERATION entry")
        void shouldAddIterationEntry() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");
            logger.logIteration("run-1", 1, 3);

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            assertThat(runLog.getEntries()).hasSize(2);
            assertThat(runLog.getEntries().get(1).type()).isEqualTo("ITERATION");
        }
    }

    @Nested
    @DisplayName("logError()")
    class LogErrorTests {

        @Test
        @DisplayName("should add ERROR entry")
        void shouldAddErrorEntry() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");
            logger.logError("run-1", "Something went wrong", new RuntimeException("boom"));

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            assertThat(runLog.getEntries()).hasSize(2);
            assertThat(runLog.getEntries().get(1).type()).isEqualTo("ERROR");
            assertThat(runLog.getEntries().get(1).success()).isFalse();
            assertThat(runLog.getEntries().get(1).error()).isEqualTo("Something went wrong");
        }

        @Test
        @DisplayName("should handle null throwable")
        void shouldHandleNullThrowable() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");
            logger.logError("run-1", "Error occurred", null);

            DefaultAgentLogger.RunLog runLog = logger.getRunLog("run-1");
            assertThat(runLog.getEntries()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("getReport()")
    class GetReportTests {

        @Test
        @DisplayName("should return formatted report for valid run")
        void shouldReturnFormattedReport() {
            logger.logExecutionStart("run-1", "Find emails", "openai", "gpt-4");
            logger.logIteration("run-1", 1, 2);
            logger.logExecutionEnd("run-1", true, 1, 2, 500, "COMPLETED");

            String report = logger.getReport("run-1");

            assertThat(report).contains("AGENT EXECUTION REPORT");
            assertThat(report).contains("run-1");
            assertThat(report).contains("openai");
            assertThat(report).contains("gpt-4");
            assertThat(report).contains("Find emails");
            assertThat(report).contains("EXECUTION LOG");
        }

        @Test
        @DisplayName("should return error message for unknown run")
        void shouldReturnErrorForUnknownRun() {
            String report = logger.getReport("unknown");
            assertThat(report).contains("No logs for run: unknown");
        }
    }

    @Nested
    @DisplayName("clearRunLog()")
    class ClearRunLogTests {

        @Test
        @DisplayName("should remove run log")
        void shouldRemoveRunLog() {
            logger.logExecutionStart("run-1", "Hello", "openai", "gpt-4");
            assertThat(logger.getRunLog("run-1")).isNotNull();

            logger.clearRunLog("run-1");
            assertThat(logger.getRunLog("run-1")).isNull();
        }

        @Test
        @DisplayName("should not fail for non-existent run")
        void shouldNotFailForNonExistent() {
            logger.clearRunLog("non-existent");
            // Should not throw
        }
    }

    @Nested
    @DisplayName("LogEntry.format()")
    class LogEntryFormatTests {

        @Test
        @DisplayName("should format TOOL_CALL entry")
        void shouldFormatToolCall() {
            DefaultAgentLogger.LogEntry entry = new DefaultAgentLogger.LogEntry(
                    Instant.now(), "TOOL_CALL", "search", Map.of("query", "test"),
                    null, true, null, 0, Map.of()
            );

            String formatted = entry.format();
            assertThat(formatted).contains("search");
            assertThat(formatted).contains("query");
        }

        @Test
        @DisplayName("should format TOOL_RESULT success entry")
        void shouldFormatToolResultSuccess() {
            DefaultAgentLogger.LogEntry entry = new DefaultAgentLogger.LogEntry(
                    Instant.now(), "TOOL_RESULT", "search", null,
                    "Found items", true, null, 150, Map.of()
            );

            String formatted = entry.format();
            assertThat(formatted).contains("Found items");
            assertThat(formatted).contains("150ms");
        }

        @Test
        @DisplayName("should format TOOL_RESULT failure entry")
        void shouldFormatToolResultFailure() {
            DefaultAgentLogger.LogEntry entry = new DefaultAgentLogger.LogEntry(
                    Instant.now(), "TOOL_RESULT", "search", null,
                    null, false, "Timeout", 5000, Map.of()
            );

            String formatted = entry.format();
            assertThat(formatted).contains("ERROR: Timeout");
            assertThat(formatted).contains("5000ms");
        }

        @Test
        @DisplayName("should format ITERATION entry")
        void shouldFormatIteration() {
            DefaultAgentLogger.LogEntry entry = new DefaultAgentLogger.LogEntry(
                    Instant.now(), "ITERATION", null, null,
                    null, true, null, 0, Map.of("iteration", 3, "toolCalls", 2)
            );

            String formatted = entry.format();
            assertThat(formatted).contains("#3");
            assertThat(formatted).contains("2 tool calls");
        }

        @Test
        @DisplayName("should format ERROR entry")
        void shouldFormatError() {
            DefaultAgentLogger.LogEntry entry = new DefaultAgentLogger.LogEntry(
                    Instant.now(), "ERROR", null, null,
                    null, false, "Connection failed", 0, Map.of()
            );

            String formatted = entry.format();
            assertThat(formatted).contains("Connection failed");
        }

        @Test
        @DisplayName("should format empty args as empty string")
        void shouldFormatEmptyArgs() {
            DefaultAgentLogger.LogEntry entry = new DefaultAgentLogger.LogEntry(
                    Instant.now(), "TOOL_CALL", "test", Map.of(),
                    null, true, null, 0, Map.of()
            );

            String formatted = entry.format();
            assertThat(formatted).contains("test()");
        }

        @Test
        @DisplayName("should handle null result in TOOL_RESULT")
        void shouldHandleNullResult() {
            DefaultAgentLogger.LogEntry entry = new DefaultAgentLogger.LogEntry(
                    Instant.now(), "TOOL_RESULT", "test", null,
                    null, true, null, 100, Map.of()
            );

            String formatted = entry.format();
            assertThat(formatted).contains("OK");
        }
    }

    @Nested
    @DisplayName("RunLog")
    class RunLogTests {

        @Test
        @DisplayName("should store run metadata")
        void shouldStoreMetadata() {
            DefaultAgentLogger.RunLog runLog = new DefaultAgentLogger.RunLog("run-123");

            runLog.setUserPrompt("Find all users");
            runLog.setProvider("anthropic");
            runLog.setModel("claude-3");

            assertThat(runLog.getRunId()).isEqualTo("run-123");
            assertThat(runLog.getUserPrompt()).isEqualTo("Find all users");
            assertThat(runLog.getProvider()).isEqualTo("anthropic");
            assertThat(runLog.getModel()).isEqualTo("claude-3");
            assertThat(runLog.getStartTime()).isNotNull();
            assertThat(runLog.getEntries()).isEmpty();
        }
    }

    @Nested
    @DisplayName("AgentLogger.NOOP")
    class NoopLoggerTests {

        @Test
        @DisplayName("NOOP logger should not throw on any method call")
        void noopShouldNotThrow() {
            AgentLogger noop = AgentLogger.NOOP;

            ToolCall toolCall = ToolCall.builder()
                    .id("tc-1")
                    .toolName("test")
                    .arguments(Map.of())
                    .build();

            ToolResult result = ToolResult.success(toolCall, "ok");

            // None of these should throw
            noop.logExecutionStart("run", "hello", "openai", "gpt-4");
            noop.logExecutionEnd("run", true, 1, 1, 100, "COMPLETED");
            noop.logToolCallStart("run", toolCall);
            noop.logToolCallEnd("run", toolCall, result, 50);
            noop.logIteration("run", 1, 1);
            noop.logError("run", "error", new RuntimeException("test"));
        }
    }
}
