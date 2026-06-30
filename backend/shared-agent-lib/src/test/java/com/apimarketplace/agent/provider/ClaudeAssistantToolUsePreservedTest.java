package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4a.6 (R29/R44) - Claude ASSISTANT {@code tool_use} preservation.
 *
 * <p>Claude's API requires that every replayed TOOL message reference a
 * {@code tool_use_id} emitted by the assistant earlier in the conversation.
 * If {@code ClaudeProvider.convertClaudeMessage} collapses the assistant
 * turn to a plain string, those ids are lost and the next turn hard-fails
 * with "tool_result refers to unknown tool_use_id". This test pins the
 * content-block shape the assistant message MUST have on replay.
 */
@DisplayName("ClaudeProvider - assistant tool_use blocks preserved on replay (Stage 4a.6)")
class ClaudeAssistantToolUsePreservedTest {

    private final ClaudeProvider provider = new ClaudeProvider();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildMessages(CompletionRequest request) {
        return (List<Map<String, Object>>) provider.buildRequestBody(request).get("messages");
    }

    @Test
    @DisplayName("assistant-with-toolCalls → content is a block list containing tool_use blocks")
    void assistantWithToolCallsEmitsToolUseBlocks() {
        ToolCall call = ToolCall.builder()
                .id("toolu_abc123")
                .toolName("get_weather")
                .arguments(Map.of("city", "Paris"))
                .index(0)
                .build();
        Message assistant = Message.assistantWithToolCalls("Looking up the weather now.", List.of(call));

        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .conversationHistory(List.of(
                        Message.user("what's the weather in Paris?"),
                        assistant,
                        Message.toolResult("toolu_abc123", "get_weather", "{\"temp\":18}")
                ))
                .userPrompt("and in Rome?")
                .build();

        List<Map<String, Object>> messages = buildMessages(req);
        Map<String, Object> assistantMsg = messages.get(1);
        assertThat(assistantMsg.get("role")).isEqualTo("assistant");

        Object content = assistantMsg.get("content");
        assertThat(content).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;

        // text block first, then tool_use block.
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).containsEntry("type", "text");
        assertThat(blocks.get(0)).containsEntry("text", "Looking up the weather now.");

        Map<String, Object> toolUse = blocks.get(1);
        assertThat(toolUse).containsEntry("type", "tool_use");
        assertThat(toolUse).containsEntry("id", "toolu_abc123");
        assertThat(toolUse).containsEntry("name", "get_weather");
        assertThat(toolUse).containsEntry("input", Map.of("city", "Paris"));
    }

    @Test
    @DisplayName("assistant text-only (no toolCalls) keeps the plain-string content path")
    void assistantWithoutToolCallsStaysPlainString() {
        Message assistant = Message.assistant("just thinking out loud");

        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .conversationHistory(List.of(
                        Message.user("start"),
                        assistant
                ))
                .userPrompt("go on")
                .build();

        List<Map<String, Object>> messages = buildMessages(req);
        // The last-history cache-marker promotes this block to a list - that's a
        // separate concern. Use the earlier-history path (index 1 is last; we
        // want a non-last assistant to confirm the base case).
        //
        // Simpler: add another turn so the assistant is NOT the last history msg.
        CompletionRequest reqMid = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .conversationHistory(List.of(
                        Message.user("start"),
                        assistant,
                        Message.user("another")
                ))
                .userPrompt("go on")
                .build();
        List<Map<String, Object>> mid = buildMessages(reqMid);
        assertThat(mid.get(1).get("role")).isEqualTo("assistant");
        assertThat(mid.get(1).get("content")).isEqualTo("just thinking out loud");
    }

    @Test
    @DisplayName("assistant with blank content but tool calls → omits empty text block")
    void assistantBlankContentSkipsTextBlock() {
        ToolCall call = ToolCall.builder()
                .id("toolu_silent")
                .toolName("noop")
                .arguments(Map.of())
                .index(0)
                .build();
        // Claude will sometimes emit only tool_use with no text - replay must
        // NOT inject {"type":"text","text":""}, Anthropic rejects blank text.
        Message assistant = Message.assistantWithToolCalls("", List.of(call));

        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .conversationHistory(List.of(
                        Message.user("do something"),
                        assistant,
                        Message.toolResult("toolu_silent", "noop", "ok")
                ))
                .userPrompt("ok?")
                .build();

        List<Map<String, Object>> messages = buildMessages(req);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) messages.get(1).get("content");
        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).containsEntry("type", "tool_use");
        assertThat(blocks.get(0)).containsEntry("id", "toolu_silent");
    }

    @Test
    @DisplayName("parallel tool calls on one assistant turn → all tool_use blocks preserved in order")
    void parallelToolCallsPreserved() {
        ToolCall a = ToolCall.builder().id("t1").toolName("lookup_user").arguments(Map.of("id", 1)).index(0).build();
        ToolCall b = ToolCall.builder().id("t2").toolName("lookup_order").arguments(Map.of("id", 42)).index(1).build();
        ToolCall c = ToolCall.builder().id("t3").toolName("lookup_tenant").arguments(Map.of("id", 7)).index(2).build();
        Message assistant = Message.assistantWithToolCalls("parallel fetch", List.of(a, b, c));

        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .conversationHistory(List.of(
                        Message.user("fetch all"),
                        assistant,
                        Message.toolResult("t1", "lookup_user", "{}"),
                        Message.toolResult("t2", "lookup_order", "{}"),
                        Message.toolResult("t3", "lookup_tenant", "{}")
                ))
                .userPrompt("ok?")
                .build();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) buildMessages(req).get(1).get("content");
        // text + 3 tool_use, in order.
        assertThat(blocks).hasSize(4);
        assertThat(blocks.get(0).get("type")).isEqualTo("text");
        assertThat(blocks.get(1)).containsEntry("id", "t1").containsEntry("name", "lookup_user");
        assertThat(blocks.get(2)).containsEntry("id", "t2").containsEntry("name", "lookup_order");
        assertThat(blocks.get(3)).containsEntry("id", "t3").containsEntry("name", "lookup_tenant");
    }

    @Test
    @DisplayName("assistant tool_use uses Claude shape, NOT OpenAI's {id, type:function, function:{…}}")
    void rejectsOpenAIShape() {
        ToolCall call = ToolCall.builder()
                .id("toolu_shape_check")
                .toolName("probe")
                .arguments(Map.of("k", "v"))
                .index(0)
                .build();
        Message assistant = Message.assistantWithToolCalls(null, List.of(call));

        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .conversationHistory(List.of(
                        Message.user("probe"),
                        assistant,
                        Message.toolResult("toolu_shape_check", "probe", "done")
                ))
                .userPrompt("?")
                .build();

        Map<String, Object> assistantMsg = buildMessages(req).get(1);

        // The OpenAI-shaped escape hatch on AbstractLLMProvider would have
        // stamped a top-level "tool_calls" key on the message map - Claude
        // rejects that field at the request level.
        assertThat(assistantMsg).doesNotContainKey("tool_calls");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) assistantMsg.get("content");
        Map<String, Object> toolUse = blocks.get(0);
        // Claude native fields:
        assertThat(toolUse).containsKeys("type", "id", "name", "input");
        // OpenAI-shaped fields must NOT leak through:
        assertThat(toolUse).doesNotContainKey("function");
        assertThat(toolUse).doesNotContainKey("arguments");
        // input must be a Map (structured object), not a JSON-encoded string.
        assertThat(toolUse.get("input")).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("null arguments map is normalised to an empty object - Anthropic requires 'input' present")
    void nullArgumentsBecomesEmptyObject() {
        ToolCall call = ToolCall.builder()
                .id("toolu_no_args")
                .toolName("ping")
                .arguments(null)
                .index(0)
                .build();
        Message assistant = Message.assistantWithToolCalls("ping!", List.of(call));

        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .conversationHistory(List.of(
                        Message.user("ping please"),
                        assistant,
                        Message.toolResult("toolu_no_args", "ping", "pong")
                ))
                .userPrompt("?")
                .build();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) buildMessages(req).get(1).get("content");
        Map<String, Object> toolUse = blocks.get(1);
        assertThat(toolUse).containsEntry("input", Map.of());
    }

    @Test
    @DisplayName("last-history cache marker rides on the final tool_use block when assistant is last")
    void cacheControlAppliedToLastToolUseBlock() {
        // Stage 1a.1 marks the last history message's final content block with
        // cache_control=ephemeral. When the last history is an assistant with
        // tool_use blocks, the marker MUST land on the final tool_use block -
        // otherwise the cache prefix excludes the tool call and invalidates
        // on the very next turn.
        ToolCall call = ToolCall.builder()
                .id("toolu_cached")
                .toolName("probe")
                .arguments(Map.of("k", "v"))
                .index(0)
                .build();
        Message assistant = Message.assistantWithToolCalls("probing", List.of(call));

        CompletionRequest req = CompletionRequest.builder()
                .model("claude-sonnet-4-5")
                .conversationHistory(List.of(
                        Message.user("kickoff"),
                        assistant
                ))
                .userPrompt("follow-up")
                .build();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) buildMessages(req).get(1).get("content");
        Map<String, Object> lastBlock = blocks.get(blocks.size() - 1);
        assertThat(lastBlock).containsEntry("type", "tool_use");
        assertThat(lastBlock.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
        // Earlier blocks must NOT be marked - only the boundary is.
        for (int i = 0; i < blocks.size() - 1; i++) {
            assertThat(blocks.get(i))
                    .as("block %d must not be marked", i)
                    .doesNotContainKey("cache_control");
        }
    }
}
