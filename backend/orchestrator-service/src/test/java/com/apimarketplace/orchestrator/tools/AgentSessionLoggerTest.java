package com.apimarketplace.orchestrator.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for AgentSessionLogger.
 */
@DisplayName("AgentSessionLogger")
class AgentSessionLoggerTest {

    private AgentSessionLogger logger;

    @BeforeEach
    void setUp() {
        logger = new AgentSessionLogger();
    }

    @Nested
    @DisplayName("ToolCall record")
    class ToolCallTests {

        @Test
        @DisplayName("Should format compact for success")
        void shouldFormatCompactForSuccess() {
            AgentSessionLogger.ToolCall call = new AgentSessionLogger.ToolCall(
                "call-1", Instant.now(), "workflow",
                Map.of("action", "add_node"), Map.of("status", "success"),
                true, null, 150L);

            String formatted = call.formatCompact();

            assertThat(formatted).contains("workflow");
            assertThat(formatted).contains("(150ms)");
            assertThat(formatted).doesNotContain("ERROR");
        }

        @Test
        @DisplayName("Should format compact for failure")
        void shouldFormatCompactForFailure() {
            AgentSessionLogger.ToolCall call = new AgentSessionLogger.ToolCall(
                "call-2", Instant.now(), "tool_name",
                Map.of(), null,
                false, "something went wrong", 0L);

            String formatted = call.formatCompact();

            assertThat(formatted).contains("ERROR");
            assertThat(formatted).contains("something went wrong");
        }

        @Test
        @DisplayName("Should handle null parameters")
        void shouldHandleNullParameters() {
            AgentSessionLogger.ToolCall call = new AgentSessionLogger.ToolCall(
                "call-3", Instant.now(), "test",
                null, "result",
                true, null, 0L);

            String formatted = call.formatCompact();

            assertThat(formatted).contains("test()");
        }
    }

    @Nested
    @DisplayName("ConversationLog")
    class ConversationLogTests {

        @Test
        @DisplayName("Should store conversation details")
        void shouldStoreDetails() {
            AgentSessionLogger.ConversationLog log =
                new AgentSessionLogger.ConversationLog("conv-1", "Hello");

            assertThat(log.getConversationId()).isEqualTo("conv-1");
            assertThat(log.getUserMessage()).isEqualTo("Hello");
            assertThat(log.getStartTime()).isNotNull();
            assertThat(log.getToolCalls()).isEmpty();
        }

        @Test
        @DisplayName("Should track end time")
        void shouldTrackEndTime() {
            AgentSessionLogger.ConversationLog log =
                new AgentSessionLogger.ConversationLog("conv-1", "Hello");

            Instant endTime = Instant.now();
            log.setEndTime(endTime);
            assertThat(log.getEndTime()).isEqualTo(endTime);
        }
    }

    @Nested
    @DisplayName("Conversation lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("Should start and end conversation")
        void shouldStartAndEnd() {
            logger.startConversation("conv-1", "Build a workflow");
            assertThat(logger.getActiveConversations()).contains("conv-1");

            logger.endConversation("conv-1", "completed");
            // Conversation should still be in logs (not cleared)
            assertThat(logger.getActiveConversations()).contains("conv-1");
        }

        @Test
        @DisplayName("Should clear conversation")
        void shouldClearConversation() {
            logger.startConversation("conv-2", "Test");
            logger.clearConversation("conv-2");

            assertThat(logger.getActiveConversations()).doesNotContain("conv-2");
        }

        @Test
        @DisplayName("Should handle ending non-existent conversation")
        void shouldHandleEndNonExistent() {
            // Should not throw
            logger.endConversation("nonexistent", "error");
        }
    }

    @Nested
    @DisplayName("Tool call logging")
    class ToolCallLoggingTests {

        @Test
        @DisplayName("Should log tool call and return callId")
        void shouldLogToolCall() {
            logger.startConversation("conv-3", "Test");

            String callId = logger.logToolCall("conv-3", "workflow",
                Map.of("action", "add_node"));

            assertThat(callId).isNotNull().hasSize(8);
        }

        @Test
        @DisplayName("Should log tool result")
        void shouldLogToolResult() {
            logger.startConversation("conv-4", "Test");
            String callId = logger.logToolCall("conv-4", "tool1", Map.of());

            logger.logToolResult("conv-4", callId, "tool1", Map.of(),
                Map.of("status", "success"), true, null);

            // Verify tool call was recorded
            String report = logger.getConversationReport("conv-4");
            assertThat(report).contains("tool1");
        }

        @Test
        @DisplayName("Should log complete tool execution")
        void shouldLogCompleteExecution() {
            logger.startConversation("conv-5", "Test");

            logger.logToolExecution("conv-5", "catalog",
                Map.of("query", "gmail"), Map.of("count", 5),
                true, null, 200L);

            String report = logger.getConversationReport("conv-5");
            assertThat(report).contains("catalog");
            assertThat(report).contains("1 total");
        }
    }

    @Nested
    @DisplayName("Report generation")
    class ReportTests {

        @Test
        @DisplayName("Should return no-logs message for unknown conversation")
        void shouldReturnNoLogsForUnknown() {
            String report = logger.getConversationReport("unknown");
            assertThat(report).contains("No logs for conversation");
        }

        @Test
        @DisplayName("Should include conversation details in report")
        void shouldIncludeConversationDetails() {
            logger.startConversation("conv-6", "Build a workflow for email");
            logger.endConversation("conv-6", "completed");

            String report = logger.getConversationReport("conv-6");

            assertThat(report).contains("AGENT SESSION REPORT");
            assertThat(report).contains("conv-6");
            assertThat(report).contains("Build a workflow for email");
            assertThat(report).contains("completed");
        }

        @Test
        @DisplayName("Should include tool usage summary")
        void shouldIncludeToolUsageSummary() {
            logger.startConversation("conv-7", "Test");
            logger.logToolExecution("conv-7", "workflow",
                Map.of(), Map.of(), true, null, 100L);
            logger.logToolExecution("conv-7", "workflow",
                Map.of(), Map.of(), true, null, 50L);
            logger.logToolExecution("conv-7", "catalog",
                Map.of(), null, false, "not found", 30L);
            logger.endConversation("conv-7", "done");

            String report = logger.getConversationReport("conv-7");

            assertThat(report).contains("TOOL USAGE SUMMARY");
            assertThat(report).contains("workflow");
            assertThat(report).contains("catalog");
        }
    }
}
