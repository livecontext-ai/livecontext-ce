package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.AttachmentType;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.MessageAttachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.1 - Anthropic prompt-cache breakpoint #3 of 4 on the messages array.
 * The last message of {@code conversationHistory} is the sliding-window boundary:
 * marking it with {@code cache_control: ephemeral} makes the entire history up to
 * and including that message a cache HIT on the next turn, because the next turn
 * appends AFTER it. The new {@code userPrompt} is explicitly NOT marked - it is
 * the changing suffix.
 */
@DisplayName("ClaudeProvider - cache_control on last history message (breakpoint #3)")
class ClaudeProviderMessagesCacheTest {

    private final ClaudeProvider provider = new ClaudeProvider();

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildMessages(CompletionRequest request) {
        return (List<Map<String, Object>>) provider.buildRequestBody(request).get("messages");
    }

    @Test
    @DisplayName("multi-turn: last history message text-content is promoted to block-list with cache_control")
    void lastHistoryTextMessageIsWrappedAndMarked() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .conversationHistory(List.of(
                Message.user("first question"),
                Message.assistant("first answer"),
                Message.user("second question"),
                Message.assistant("second answer - this is the stable prefix")
            ))
            .userPrompt("a third question arrives")
            .build();

        List<Map<String, Object>> messages = buildMessages(req);
        assertThat(messages).hasSize(5);

        // Last history message (index 3) must be marked.
        Map<String, Object> lastHistory = messages.get(3);
        assertThat(lastHistory.get("role")).isEqualTo("assistant");
        Object content = lastHistory.get("content");
        assertThat(content).isInstanceOf(List.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
        assertThat(blocks).hasSize(1);
        Map<String, Object> block = blocks.get(0);
        assertThat(block.get("type")).isEqualTo("text");
        assertThat(block.get("text")).isEqualTo("second answer - this is the stable prefix");
        assertThat(block.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));

        // The newly-appended userPrompt (index 4) must NOT be marked - it is the
        // changing suffix that invalidates on every turn.
        Map<String, Object> newUserTurn = messages.get(4);
        assertThat(newUserTurn.get("role")).isEqualTo("user");
        assertThat(newUserTurn.get("content")).isEqualTo("a third question arrives");
        assertThat(newUserTurn).doesNotContainKey("cache_control");
    }

    @Test
    @DisplayName("non-last history messages are untouched - only the final boundary is marked")
    void earlierHistoryMessagesAreNotMarked() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .conversationHistory(List.of(
                Message.user("u1"),
                Message.assistant("a1"),
                Message.user("u2"),
                Message.assistant("a2")
            ))
            .userPrompt("u3")
            .build();

        List<Map<String, Object>> messages = buildMessages(req);
        // index 0..2 (u1, a1, u2) must keep plain-string content, no marker.
        for (int i = 0; i < 3; i++) {
            assertThat(messages.get(i).get("content"))
                .as("message %d must stay as plain string", i)
                .isInstanceOf(String.class);
        }
    }

    @Test
    @DisplayName("history-only (no userPrompt) → no marker is placed, no cache prefix committed")
    void historyOnlyDoesNotMark() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .conversationHistory(List.of(
                Message.user("u1"),
                Message.assistant("a1")
            ))
            // No userPrompt - agent is being asked to continue without new input.
            // Marking here would pin a prefix to a message that is itself the one
            // being mutated on the next turn (anti-pattern - no cache benefit).
            .build();

        List<Map<String, Object>> messages = buildMessages(req);
        assertThat(messages).hasSize(2);
        // Both messages keep plain-string content.
        assertThat(messages.get(0).get("content")).isEqualTo("u1");
        assertThat(messages.get(1).get("content")).isEqualTo("a1");
    }

    @Test
    @DisplayName("userPrompt only (no history) → no marker anywhere")
    void userPromptOnlyDoesNotMark() {
        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .userPrompt("hi")
            .build();

        List<Map<String, Object>> messages = buildMessages(req);
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("content")).isEqualTo("hi");
        assertThat(messages.get(0)).doesNotContainKey("cache_control");
    }

    @Test
    @DisplayName("multimodal last message: cache_control goes on the LAST content block, not the first")
    void multimodalLastMessageMarksLastBlock() {
        MessageAttachment img = MessageAttachment.builder()
            .type(AttachmentType.IMAGE)
            .mimeType("image/png")
            .fileName("diagram.png")
            .data(new byte[100])
            .build();

        Message userWithImage = Message.builder()
            .role(Message.Role.USER)
            .content("what does this diagram show?")
            .attachments(List.of(img))
            .build();

        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .conversationHistory(List.of(userWithImage))
            .userPrompt("follow-up question")
            .build();

        List<Map<String, Object>> messages = buildMessages(req);
        Map<String, Object> lastHistory = messages.get(0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) lastHistory.get("content");
        // Attachment added first, text added last (per buildMultimodalContent).
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0)).doesNotContainKey("cache_control");
        // The last block (text) must carry the marker so the attachment bytes are
        // part of the cached prefix.
        Map<String, Object> lastBlock = blocks.get(blocks.size() - 1);
        assertThat(lastBlock.get("type")).isEqualTo("text");
        assertThat(lastBlock.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
    }

    @Test
    @DisplayName("tool-result message as last history: cache_control rides on the tool_result block")
    void toolResultLastMessageIsMarked() {
        Message toolResult = Message.builder()
            .role(Message.Role.TOOL)
            .content("{\"status\":\"ok\"}")
            .toolCallId("call_42")
            .build();

        CompletionRequest req = CompletionRequest.builder()
            .model("claude-sonnet-4-5")
            .conversationHistory(List.of(Message.user("call the tool"), toolResult))
            .userPrompt("what did the tool return?")
            .build();

        List<Map<String, Object>> messages = buildMessages(req);
        Map<String, Object> lastHistory = messages.get(1);
        assertThat(lastHistory.get("role")).isEqualTo("user"); // Claude maps TOOL → user

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) lastHistory.get("content");
        assertThat(blocks).hasSize(1);
        Map<String, Object> block = blocks.get(0);
        assertThat(block.get("type")).isEqualTo("tool_result");
        assertThat(block.get("tool_use_id")).isEqualTo("call_42");
        assertThat(block.get("cache_control")).isEqualTo(Map.of("type", "ephemeral"));
    }
}
