package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolCallBatchAppender ensuring OpenAI message sequence invariants.
 *
 * Key invariant: An assistant message with tool_calls must be immediately followed
 * by ALL corresponding tool messages, with no other messages interleaved.
 */
class ToolCallBatchAppenderTest {

    @Nested
    @DisplayName("validateSequence()")
    class ValidateSequenceTests {

        @Test
        @DisplayName("Valid sequence: assistant with tool_calls followed by all tool results")
        void validSequence_allToolResultsPresent() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Hello"));
            messages.add(Message.assistantWithToolCalls("", List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1),
                new ToolCall("call_3", "tool_c", Map.of(), 2)
            )));
            messages.add(Message.toolResult("call_1", "tool_a", "result 1"));
            messages.add(Message.toolResult("call_2", "tool_b", "result 2"));
            messages.add(Message.toolResult("call_3", "tool_c", "result 3"));
            messages.add(Message.assistant("Done!"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertTrue(result.valid(), "Sequence should be valid: " + result.errors());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("Invalid sequence: missing tool result")
        void invalidSequence_missingToolResult() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Hello"));
            messages.add(Message.assistantWithToolCalls("", List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1),
                new ToolCall("call_3", "tool_c", Map.of(), 2)
            )));
            messages.add(Message.toolResult("call_1", "tool_a", "result 1"));
            // Missing call_2 and call_3!
            messages.add(Message.assistant("Done!"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertFalse(result.valid(), "Sequence should be invalid");
            assertFalse(result.errors().isEmpty());
            assertTrue(result.errors().get(0).contains("call_2") || result.errors().get(0).contains("call_3"),
                "Error should mention missing tool_call_ids");
        }

        @Test
        @DisplayName("Invalid sequence: system message interleaved between tool results")
        void invalidSequence_systemMessageInterleaved() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Hello"));
            messages.add(Message.assistantWithToolCalls("", List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1)
            )));
            messages.add(Message.toolResult("call_1", "tool_a", "result 1"));
            messages.add(Message.system("Some system message")); // INVALID!
            messages.add(Message.toolResult("call_2", "tool_b", "result 2"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertFalse(result.valid(), "Sequence should be invalid due to interleaved system message");
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("call_2")),
                "Error should mention missing call_2");
        }

        @Test
        @DisplayName("Invalid sequence: user message interleaved between tool results")
        void invalidSequence_userMessageInterleaved() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.assistantWithToolCalls("", List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1)
            )));
            messages.add(Message.toolResult("call_1", "tool_a", "result 1"));
            messages.add(Message.user("Wait!")); // INVALID!
            messages.add(Message.toolResult("call_2", "tool_b", "result 2"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertFalse(result.valid(), "Sequence should be invalid due to interleaved user message");
        }

        @Test
        @DisplayName("Valid sequence: multiple tool call batches")
        void validSequence_multipleToolCallBatches() {
            List<Message> messages = new ArrayList<>();
            // First batch
            messages.add(Message.user("Do task 1"));
            messages.add(Message.assistantWithToolCalls("", List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0)
            )));
            messages.add(Message.toolResult("call_1", "tool_a", "result 1"));

            // Second batch
            messages.add(Message.assistantWithToolCalls("Now task 2", List.of(
                new ToolCall("call_2", "tool_b", Map.of(), 0),
                new ToolCall("call_3", "tool_c", Map.of(), 1)
            )));
            messages.add(Message.toolResult("call_2", "tool_b", "result 2"));
            messages.add(Message.toolResult("call_3", "tool_c", "result 3"));

            messages.add(Message.assistant("All done!"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertTrue(result.valid(), "Sequence should be valid: " + result.errors());
        }

        @Test
        @DisplayName("Valid sequence: tool results can be in any order within batch")
        void validSequence_toolResultsAnyOrder() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.assistantWithToolCalls("", List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1),
                new ToolCall("call_3", "tool_c", Map.of(), 2)
            )));
            // Results in different order than calls
            messages.add(Message.toolResult("call_3", "tool_c", "result 3"));
            messages.add(Message.toolResult("call_1", "tool_a", "result 1"));
            messages.add(Message.toolResult("call_2", "tool_b", "result 2"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertTrue(result.valid(), "Sequence should be valid regardless of tool result order: " + result.errors());
        }

        @Test
        @DisplayName("Valid sequence: empty conversation")
        void validSequence_emptyConversation() {
            List<Message> messages = new ArrayList<>();

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Valid sequence: no tool calls")
        void validSequence_noToolCalls() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Hello"));
            messages.add(Message.assistant("Hi there!"));
            messages.add(Message.user("How are you?"));
            messages.add(Message.assistant("I'm good!"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertTrue(result.valid());
        }

        @Test
        @DisplayName("Invalid sequence: orphan tool message without preceding assistant")
        void invalidSequence_orphanToolMessage() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Hello"));
            messages.add(Message.toolResult("call_orphan", "tool_x", "some result")); // No assistant before!

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertFalse(result.valid(), "Sequence should be invalid due to orphan tool message");
            assertTrue(result.errors().stream().anyMatch(e -> e.contains("orphan") || e.contains("Orphan")),
                "Error should mention orphan tool message");
        }
    }

    @Nested
    @DisplayName("appendAtomically()")
    class AppendAtomicallyTests {

        @Test
        @DisplayName("Append single tool call and result")
        void appendSingleToolCall() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Hello"));

            ToolCall toolCall = new ToolCall("call_1", "my_tool", Map.of("arg", "value"), 0);
            ToolResult toolResult = ToolResult.success(toolCall, "Tool executed successfully");

            var result = ToolCallBatchAppender.appendAtomically(
                messages,
                "Let me call a tool",
                List.of(toolCall),
                List.of(toolResult)
            );

            assertTrue(result.success());
            assertEquals(2, result.messagesAdded()); // 1 assistant + 1 tool
            assertEquals(3, messages.size()); // user + assistant + tool

            // Verify sequence is valid
            var validation = ToolCallBatchAppender.validateSequence(messages);
            assertTrue(validation.valid(), "Appended sequence should be valid: " + validation.errors());
        }

        @Test
        @DisplayName("Append multiple parallel tool calls")
        void appendMultipleToolCalls() {
            List<Message> messages = new ArrayList<>();

            List<ToolCall> toolCalls = List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1),
                new ToolCall("call_3", "tool_c", Map.of(), 2)
            );

            List<ToolResult> toolResults = List.of(
                ToolResult.success(toolCalls.get(0), "Result A"),
                ToolResult.success(toolCalls.get(1), "Result B"),
                ToolResult.success(toolCalls.get(2), "Result C")
            );

            var result = ToolCallBatchAppender.appendAtomically(
                messages,
                "",
                toolCalls,
                toolResults
            );

            assertTrue(result.success());
            assertEquals(4, result.messagesAdded()); // 1 assistant + 3 tools
            assertEquals(4, messages.size());

            // Verify sequence is valid
            var validation = ToolCallBatchAppender.validateSequence(messages);
            assertTrue(validation.valid(), "Appended sequence should be valid: " + validation.errors());
        }

        @Test
        @DisplayName("Append with content transformer (truncation)")
        void appendWithContentTransformer() {
            List<Message> messages = new ArrayList<>();

            List<ToolCall> toolCalls = List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1)
            );

            List<ToolResult> toolResults = List.of(
                ToolResult.success(toolCalls.get(0), "Very long result that should be truncated"),
                ToolResult.success(toolCalls.get(1), "Recent result kept intact")
            );

            // Transformer that truncates old results
            ToolCallBatchAppender.ContentTransformer truncator = (content, index, total) -> {
                boolean isRecent = (total - index) <= 1;
                return isRecent ? content : content.substring(0, Math.min(10, content.length())) + "...";
            };

            var result = ToolCallBatchAppender.appendAtomically(
                messages,
                "",
                toolCalls,
                toolResults,
                truncator
            );

            assertTrue(result.success());

            // First result should be truncated
            Message firstTool = messages.get(1);
            assertTrue(firstTool.content().endsWith("..."), "First result should be truncated");

            // Second result should be intact
            Message secondTool = messages.get(2);
            assertEquals("Recent result kept intact", secondTool.content());
        }

        @Test
        @DisplayName("Append with missing tool result creates error placeholder")
        void appendWithMissingResult() {
            List<Message> messages = new ArrayList<>();

            List<ToolCall> toolCalls = List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1),
                new ToolCall("call_3", "tool_c", Map.of(), 2)
            );

            // Only 2 results - missing call_2
            List<ToolResult> toolResults = List.of(
                ToolResult.success(toolCalls.get(0), "Result A"),
                ToolResult.success(toolCalls.get(2), "Result C")
            );

            var result = ToolCallBatchAppender.appendAtomically(
                messages,
                "",
                toolCalls,
                toolResults
            );

            // Should still succeed - missing results get error placeholders
            assertTrue(result.success());
            assertEquals(4, result.messagesAdded()); // 1 assistant + 3 tools (including placeholder)

            // Verify sequence is valid (error placeholder should satisfy OpenAI)
            var validation = ToolCallBatchAppender.validateSequence(messages);
            assertTrue(validation.valid(), "Sequence with placeholder should be valid: " + validation.errors());

            // Verify the placeholder has error content
            Message call2Message = messages.stream()
                .filter(m -> m.role() == Message.Role.TOOL && "call_2".equals(m.toolCallId()))
                .findFirst()
                .orElseThrow();
            assertTrue(call2Message.content().toLowerCase().contains("error") ||
                       call2Message.content().toLowerCase().contains("failed"),
                "Placeholder should indicate error");
        }

        @Test
        @DisplayName("Append with failed tool result")
        void appendWithFailedResult() {
            List<Message> messages = new ArrayList<>();

            ToolCall toolCall = new ToolCall("call_1", "my_tool", Map.of(), 0);
            ToolResult toolResult = ToolResult.failure(toolCall, "Connection timeout");

            var result = ToolCallBatchAppender.appendAtomically(
                messages,
                "Calling tool",
                List.of(toolCall),
                List.of(toolResult)
            );

            assertTrue(result.success());

            Message toolMessage = messages.get(1);
            assertTrue(toolMessage.content().contains("Connection timeout"),
                "Tool message should contain error");
        }
    }

    @Nested
    @DisplayName("ValidationResult.throwIfInvalid()")
    class ThrowIfInvalidTests {

        @Test
        @DisplayName("Does not throw for valid sequence")
        void doesNotThrowForValid() {
            var result = new ToolCallBatchAppender.ValidationResult(true, List.of());

            assertDoesNotThrow(result::throwIfInvalid);
        }

        @Test
        @DisplayName("Throws for invalid sequence")
        void throwsForInvalid() {
            var result = new ToolCallBatchAppender.ValidationResult(false, List.of("Error 1", "Error 2"));

            var exception = assertThrows(IllegalStateException.class, result::throwIfInvalid);
            assertTrue(exception.getMessage().contains("Error 1"));
            assertTrue(exception.getMessage().contains("Error 2"));
        }
    }
}
