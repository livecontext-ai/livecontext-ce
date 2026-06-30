package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility for atomically appending tool call batches to conversation history.
 *
 * OpenAI requires that an assistant message with tool_calls must be immediately
 * followed by ALL corresponding tool result messages, with no other messages
 * (system/user/assistant) interleaved.
 *
 * This class enforces that invariant and provides validation.
 *
 * @see <a href="https://platform.openai.com/docs/guides/function-calling">OpenAI Function Calling</a>
 */
@Slf4j
public class ToolCallBatchAppender {

    /**
     * Result of appending a tool call batch.
     */
    public record AppendResult(
        int messagesAdded,
        List<String> missingToolCallIds,
        boolean success
    ) {
        public static AppendResult success(int messagesAdded) {
            return new AppendResult(messagesAdded, List.of(), true);
        }

        public static AppendResult failure(List<String> missingIds) {
            return new AppendResult(0, missingIds, false);
        }
    }

    /**
     * Atomically append an assistant message with tool calls and all corresponding tool results.
     *
     * This method ensures:
     * 1. Assistant message with tool_calls is added first
     * 2. ALL tool results are added immediately after (no interleaving)
     * 3. Each tool_call_id has exactly one corresponding tool result
     *
     * @param messages The conversation history to append to
     * @param assistantContent The text content of the assistant message (can be empty)
     * @param toolCalls The tool calls made by the assistant
     * @param toolResults The results of executing those tool calls
     * @param contentTransformer Optional transformer for tool result content (e.g., truncation)
     * @return AppendResult indicating success or failure with details
     */
    public static AppendResult appendAtomically(
            List<Message> messages,
            String assistantContent,
            List<ToolCall> toolCalls,
            List<ToolResult> toolResults,
            ContentTransformer contentTransformer
    ) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.warn("[BATCH-APPEND] No tool calls to append");
            return AppendResult.success(0);
        }

        // Build set of expected tool_call_ids
        Set<String> expectedIds = toolCalls.stream()
            .map(ToolCall::id)
            .collect(Collectors.toSet());

        // Build map of tool_call_id -> ToolResult
        Map<String, ToolResult> resultMap = new HashMap<>();
        for (ToolResult result : toolResults) {
            if (result.toolCall() != null && result.toolCall().id() != null) {
                resultMap.put(result.toolCall().id(), result);
            }
        }

        // Check for missing results
        Set<String> missingIds = new HashSet<>(expectedIds);
        missingIds.removeAll(resultMap.keySet());

        if (!missingIds.isEmpty()) {
            log.error("[BATCH-APPEND] Missing tool results for tool_call_ids: {}", missingIds);
            // Even with missing results, we should add error placeholders to satisfy OpenAI
            for (String missingId : missingIds) {
                // Find the original tool call to get the name
                String toolName = toolCalls.stream()
                    .filter(tc -> tc.id().equals(missingId))
                    .map(ToolCall::toolName)
                    .findFirst()
                    .orElse("unknown");

                resultMap.put(missingId, ToolResult.builder()
                    .toolCall(new ToolCall(missingId, toolName, Map.of(), null))
                    .success(false)
                    .error("Tool execution failed or timed out - no result received")
                    .build());

                log.warn("[BATCH-APPEND] Added error placeholder for missing tool_call_id: {} ({})", missingId, toolName);
            }
        }

        // 1. Add assistant message with tool calls
        messages.add(Message.assistantWithToolCalls(
            assistantContent != null ? assistantContent : "",
            toolCalls
        ));

        // 2. Add ALL tool results in order of original tool calls (maintains determinism)
        int resultsAdded = 0;
        for (ToolCall toolCall : toolCalls) {
            ToolResult result = resultMap.get(toolCall.id());
            if (result != null) {
                String content = result.success()
                    ? result.content()
                    : "Error: " + result.error();

                // Apply content transformation if provided (e.g., truncation)
                if (contentTransformer != null) {
                    content = contentTransformer.transform(content, resultsAdded, toolResults.size());
                }

                messages.add(Message.toolResult(
                    toolCall.id(),
                    toolCall.toolName(),
                    content
                ));
                resultsAdded++;
            }
        }

        log.debug("[BATCH-APPEND] Added 1 assistant message + {} tool results", resultsAdded);
        return AppendResult.success(1 + resultsAdded);
    }

    /**
     * Simplified version without content transformation.
     */
    public static AppendResult appendAtomically(
            List<Message> messages,
            String assistantContent,
            List<ToolCall> toolCalls,
            List<ToolResult> toolResults
    ) {
        return appendAtomically(messages, assistantContent, toolCalls, toolResults, null);
    }

    /**
     * Functional interface for transforming tool result content.
     * Used for truncation of old results to manage context size.
     */
    @FunctionalInterface
    public interface ContentTransformer {
        /**
         * Transform the content of a tool result.
         *
         * @param content The original content
         * @param index The index of this result (0-based)
         * @param total The total number of results in this batch
         * @return The transformed content
         */
        String transform(String content, int index, int total);
    }

    /**
     * Validate that a message sequence satisfies OpenAI's tool call requirements.
     *
     * Rules:
     * 1. An assistant message with tool_calls must be immediately followed by
     *    tool messages responding to each tool_call_id
     * 2. No other message types can be interleaved between tool_calls and tool results
     * 3. A tool message without a preceding assistant tool_call is invalid
     *
     * @param messages The message sequence to validate
     * @return ValidationResult with details about any violations
     */
    public static ValidationResult validateSequence(List<Message> messages) {
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);

            if (msg.role() == Message.Role.ASSISTANT && msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                // Collect expected tool_call_ids
                Set<String> expectedIds = msg.toolCalls().stream()
                    .map(ToolCall::id)
                    .collect(Collectors.toSet());

                Set<String> foundIds = new HashSet<>();
                int j = i + 1;

                // Check that all following messages until we've covered all IDs are TOOL messages
                while (j < messages.size() && !expectedIds.equals(foundIds)) {
                    Message nextMsg = messages.get(j);

                    if (nextMsg.role() != Message.Role.TOOL) {
                        // Found a non-TOOL message before all tool_call_ids were covered
                        Set<String> missing = new HashSet<>(expectedIds);
                        missing.removeAll(foundIds);
                        errors.add(String.format(
                            "Message %d: Assistant has tool_calls %s but message %d is %s (not TOOL). Missing responses for: %s",
                            i, expectedIds, j, nextMsg.role(), missing
                        ));
                        break;
                    }

                    if (nextMsg.toolCallId() != null) {
                        if (!expectedIds.contains(nextMsg.toolCallId())) {
                            errors.add(String.format(
                                "Message %d: Tool result has tool_call_id '%s' which is not in the preceding assistant's tool_calls %s",
                                j, nextMsg.toolCallId(), expectedIds
                            ));
                        }
                        foundIds.add(nextMsg.toolCallId());
                    }
                    j++;
                }

                // Check if we reached end of messages without covering all IDs
                if (!expectedIds.equals(foundIds) && errors.isEmpty()) {
                    Set<String> missing = new HashSet<>(expectedIds);
                    missing.removeAll(foundIds);
                    errors.add(String.format(
                        "Message %d: Assistant has tool_calls but missing tool results for: %s",
                        i, missing
                    ));
                }
            }

            // Check for orphan tool messages
            if (msg.role() == Message.Role.TOOL) {
                boolean hasMatchingAssistant = false;
                for (int k = i - 1; k >= 0; k--) {
                    Message prev = messages.get(k);
                    if (prev.role() == Message.Role.ASSISTANT && prev.toolCalls() != null) {
                        hasMatchingAssistant = prev.toolCalls().stream()
                            .anyMatch(tc -> tc.id().equals(msg.toolCallId()));
                        break;
                    }
                    if (prev.role() != Message.Role.TOOL) {
                        break; // Hit a non-tool message, stop searching
                    }
                }
                if (!hasMatchingAssistant) {
                    errors.add(String.format(
                        "Message %d: Orphan tool message with tool_call_id '%s' - no matching assistant tool_call found",
                        i, msg.toolCallId()
                    ));
                }
            }
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Result of validating a message sequence.
     */
    public record ValidationResult(boolean valid, List<String> errors) {
        public void throwIfInvalid() {
            if (!valid) {
                throw new IllegalStateException(
                    "Invalid message sequence for OpenAI API:\n" + String.join("\n", errors)
                );
            }
        }
    }
}
