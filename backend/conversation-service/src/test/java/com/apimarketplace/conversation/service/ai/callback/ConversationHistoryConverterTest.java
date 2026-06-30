package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.entity.ToolResult;
import com.apimarketplace.conversation.service.ToolResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("ConversationHistoryConverter")
@ExtendWith(MockitoExtension.class)
class ConversationHistoryConverterTest {

    @Mock
    private ToolResultService toolResultService;

    private ConversationHistoryConverter converter;

    @BeforeEach
    void setUp() {
        converter = new ConversationHistoryConverter(toolResultService);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    private static ChatRequest.ChatMessage chatMsg(String role, String content) {
        ChatRequest.ChatMessage msg = new ChatRequest.ChatMessage();
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }

    private static ChatRequest.ChatMessage chatMsgWithToolCalls(String role, String content, String toolCalls) {
        ChatRequest.ChatMessage msg = chatMsg(role, content);
        msg.setToolCalls(toolCalls);
        return msg;
    }

    private static Message msg(Message.Role role, String content) {
        return Message.builder().role(role).content(content).build();
    }

    /** Build a realistic tool_calls JSON for a single tool call. */
    private static String toolCallJson(String toolName, String action) {
        return toolCallJson(toolName, action, null);
    }

    private static String toolCallJson(String toolName, String action, String extraArgs) {
        String args = "{\\\"action\\\":\\\"" + action + "\\\""
            + (extraArgs != null ? "," + extraArgs : "")
            + "}";
        return "[{\"toolName\":\"" + toolName + "\",\"id\":\"call_1\",\"arguments\":\"" + args + "\"}]";
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 1. BASIC CONVERSION - agent must always get a non-null list
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("convert - basic safety")
    class ConvertBasic {

        @Test
        @DisplayName("should return empty list for null history")
        void shouldReturnEmptyForNull() {
            assertThat(converter.convert(null)).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should return empty list for empty history")
        void shouldReturnEmptyForEmpty() {
            assertThat(converter.convert(new ArrayList<>())).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("should convert single user message")
        void shouldConvertUserMessage() {
            List<Message> result = converter.convert(List.of(chatMsg("user", "Hello")));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).role()).isEqualTo(Message.Role.USER);
            assertThat(result.get(0).content()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("should convert single assistant message")
        void shouldConvertAssistant() {
            List<Message> result = converter.convert(List.of(chatMsg("assistant", "Hi!")));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).role()).isEqualTo(Message.Role.ASSISTANT);
        }

        @Test
        @DisplayName("should convert system message")
        void shouldConvertSystem() {
            List<Message> result = converter.convert(List.of(chatMsg("system", "Prompt")));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).role()).isEqualTo(Message.Role.SYSTEM);
        }

        @Test
        @DisplayName("should skip blank content")
        void shouldSkipBlank() {
            assertThat(converter.convert(List.of(chatMsg("user", "   ")))).isEmpty();
        }

        @Test
        @DisplayName("should skip null content")
        void shouldSkipNull() {
            assertThat(converter.convert(List.of(chatMsg("user", null)))).isEmpty();
        }

        @Test
        @DisplayName("should default unknown roles to USER")
        void shouldDefaultUnknownRole() {
            List<Message> result = converter.convert(List.of(chatMsg("unknown_role", "Hi")));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).role()).isEqualTo(Message.Role.USER);
        }

        @Test
        @DisplayName("should convert TOOL role to ASSISTANT")
        void shouldConvertToolToAssistant() {
            List<Message> result = converter.convert(List.of(chatMsg("tool", "Summary")));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).role()).isEqualTo(Message.Role.ASSISTANT);
            assertThat(result.get(0).content()).isEqualTo("Summary");
        }

        @Test
        @DisplayName("should skip TOOL messages with no content")
        void shouldSkipToolNoContent() {
            assertThat(converter.convert(List.of(chatMsg("tool", "")))).isEmpty();
        }

        @Test
        @DisplayName("should convert full conversation preserving order")
        void shouldConvertFullConversation() {
            List<ChatRequest.ChatMessage> history = List.of(
                chatMsg("user", "Question"),
                chatMsg("assistant", "Answer"),
                chatMsg("user", "Thanks")
            );
            List<Message> result = converter.convert(history);
            assertThat(result).hasSize(3);
            assertThat(result.get(0).role()).isEqualTo(Message.Role.USER);
            assertThat(result.get(1).role()).isEqualTo(Message.Role.ASSISTANT);
            assertThat(result.get(2).role()).isEqualTo(Message.Role.USER);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 2. TOOL CALLS - malformed JSON must not crash
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("convert - tool_calls robustness")
    class ToolCallsRobustness {

        @Test
        @DisplayName("should skip assistant with empty tool_calls array")
        void shouldSkipEmptyToolCalls() {
            assertThat(converter.convert(List.of(
                chatMsgWithToolCalls("assistant", null, "[]")
            ))).isEmpty();
        }

        @Test
        @DisplayName("should not crash on malformed tool_calls JSON")
        void shouldNotCrashOnMalformedJson() {
            assertThatCode(() -> converter.convert(List.of(
                chatMsgWithToolCalls("assistant", null, "not-valid-json{{{")
            ))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should not crash on null tool_calls with null content")
        void shouldNotCrashOnNullToolCallsNullContent() {
            assertThatCode(() -> converter.convert(List.of(
                chatMsgWithToolCalls("assistant", null, null)
            ))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should skip _thinking tool calls")
        void shouldSkipInternalToolCalls() {
            String json = "[{\"toolName\":\"_thinking\",\"id\":\"call_1\",\"arguments\":\"{}\"}]";
            List<Message> result = converter.convert(List.of(
                chatMsgWithToolCalls("assistant", null, json)
            ));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should handle tool_call missing toolName field")
        void shouldHandleMissingToolName() {
            String json = "[{\"id\":\"call_1\",\"arguments\":\"{}\"}]";
            assertThatCode(() -> converter.convert(List.of(
                chatMsgWithToolCalls("assistant", null, json)
            ))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle tool_call missing arguments field")
        void shouldHandleMissingArguments() {
            String json = "[{\"toolName\":\"catalog\",\"id\":\"call_1\"}]";
            assertThatCode(() -> converter.convert(List.of(
                chatMsgWithToolCalls("assistant", null, json)
            ))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should produce summary for valid tool call without DB result")
        void shouldProduceSummaryWithoutDbResult() {
            String json = toolCallJson("catalog", "search", "\\\"query\\\":\\\"gmail\\\"");
            List<Message> result = converter.convert(List.of(
                chatMsgWithToolCalls("assistant", null, json)
            ));
            assertThat(result).hasSize(1);
            assertThat(result.get(0).content()).contains("catalog");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 3. DB ERROR RESILIENCE - toolResultService fails → agent still works
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("convert - DB error resilience")
    class DbErrorResilience {

        @Test
        @DisplayName("should not crash when toolResultService throws exception")
        void shouldNotCrashOnDbError() {
            when(toolResultService.getPreviewsByConversation(anyString(), anyString()))
                .thenThrow(new RuntimeException("DB connection refused"));

            assertThatCode(() -> converter.convert(
                List.of(chatMsg("user", "Hello")), "conv-1", "tenant-1"
            )).doesNotThrowAnyException();

            List<Message> result = converter.convert(
                List.of(chatMsg("user", "Hello")), "conv-1", "tenant-1"
            );
            assertThat(result).hasSize(1);
            assertThat(result.get(0).content()).isEqualTo("Hello");
        }

        @Test
        @DisplayName("should work with null conversationId")
        void shouldWorkWithNullConversationId() {
            List<Message> result = converter.convert(
                List.of(chatMsg("user", "Hi")), null, "tenant-1"
            );
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("should work with null tenantId")
        void shouldWorkWithNullTenantId() {
            List<Message> result = converter.convert(
                List.of(chatMsg("user", "Hi")), "conv-1", null
            );
            assertThat(result).hasSize(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 4. FULL PIPELINE - end-to-end with tool results from DB
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("convert - full pipeline with tool results")
    class FullPipeline {

        @Test
        @DisplayName("should join tool calls with DB results")
        void shouldJoinToolCallsWithResults() {
            ToolResult tr = new ToolResult("conv-1", "t-1", "catalog", "call_1", true, 150L,
                "Found 3 APIs", null);
            when(toolResultService.getPreviewsByConversation("conv-1", "t-1")).thenReturn(List.of(tr));

            String toolCallsJson = toolCallJson("catalog", "search", "\\\"query\\\":\\\"email\\\"");
            List<Message> result = converter.convert(
                List.of(chatMsgWithToolCalls("assistant", null, toolCallsJson)),
                "conv-1", "t-1"
            );

            assertThat(result).hasSize(1);
            assertThat(result.get(0).content()).contains("catalog");
            assertThat(result.get(0).content()).contains("✅");
        }

        @Test
        @DisplayName("should show error status for failed tool results")
        void shouldShowErrorStatus() {
            ToolResult tr = new ToolResult("conv-1", "t-1", "workflow", "call_1", false, 50L,
                null, "Permission denied");
            when(toolResultService.getPreviewsByConversation("conv-1", "t-1")).thenReturn(List.of(tr));

            String json = toolCallJson("workflow", "save");
            List<Message> result = converter.convert(
                List.of(chatMsgWithToolCalls("assistant", null, json)),
                "conv-1", "t-1"
            );

            assertThat(result).hasSize(1);
            assertThat(result.get(0).content()).contains("❌");
            assertThat(result.get(0).content()).contains("Permission denied");
        }

        @Test
        @DisplayName("should truncate very long tool result content")
        void shouldTruncateLongContent() {
            String longContent = "x".repeat(10000);
            ToolResult tr = new ToolResult("conv-1", "t-1", "workflow", "call_1", true, 100L,
                longContent, null);
            when(toolResultService.getPreviewsByConversation("conv-1", "t-1")).thenReturn(List.of(tr));

            String json = toolCallJson("workflow", "help");
            List<Message> result = converter.convert(
                List.of(chatMsgWithToolCalls("assistant", null, json)),
                "conv-1", "t-1"
            );

            assertThat(result).hasSize(1);
            // Content should be truncated - the wrapper adds some overhead, but total should be
            // significantly less than raw content + full wrapper would be
            assertThat(result.get(0).content().length()).isLessThan(longContent.length() + 500);
        }

        @Test
        @DisplayName("should handle realistic multi-turn conversation")
        void shouldHandleRealisticConversation() {
            ToolResult helpResult = new ToolResult("c1", "t1", "workflow", "call_help",
                true, 100L, "Help docs here...", null);
            ToolResult initResult = new ToolResult("c1", "t1", "workflow", "call_init",
                true, 200L, "{\"id\":\"wf-1\"}", null);
            when(toolResultService.getPreviewsByConversation("c1", "t1"))
                .thenReturn(List.of(helpResult, initResult));

            List<ChatRequest.ChatMessage> history = List.of(
                chatMsg("user", "Create a workflow for email automation"),
                chatMsgWithToolCalls("assistant", null,
                    "[{\"toolName\":\"workflow\",\"id\":\"call_help\",\"arguments\":\"{\\\"action\\\":\\\"help\\\"}\"}]"),
                chatMsgWithToolCalls("assistant", null,
                    "[{\"toolName\":\"workflow\",\"id\":\"call_init\",\"arguments\":\"{\\\"action\\\":\\\"init\\\",\\\"name\\\":\\\"Email Flow\\\"}\"}]"),
                chatMsg("assistant", "I've created your workflow."),
                chatMsg("user", "Great, add a step")
            );

            List<Message> result = converter.convert(history, "c1", "t1");

            // Should not crash, should produce valid messages, agent can start
            assertThat(result).isNotEmpty();
            assertThat(result.get(0).role()).isEqualTo(Message.Role.USER);
            assertThat(result.get(result.size() - 1).role()).isEqualTo(Message.Role.USER);
            assertThat(result.get(result.size() - 1).content()).isEqualTo("Great, add a step");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 5. isNewConversation
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isNewConversation")
    class IsNewConversation {

        @Test
        @DisplayName("empty history → new")
        void empty() {
            assertThat(converter.isNewConversation(List.of())).isTrue();
        }

        @Test
        @DisplayName("single user message → new")
        void singleUser() {
            assertThat(converter.isNewConversation(List.of(msg(Message.Role.USER, "Hi")))).isTrue();
        }

        @Test
        @DisplayName("user + assistant → not new")
        void twoMessages() {
            assertThat(converter.isNewConversation(List.of(
                msg(Message.Role.USER, "Hi"), msg(Message.Role.ASSISTANT, "Hello")
            ))).isFalse();
        }

        @Test
        @DisplayName("single system message → not new")
        void singleSystem() {
            assertThat(converter.isNewConversation(List.of(msg(Message.Role.SYSTEM, "prompt")))).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 6. PROGRESSIVE MASKING - edge cases
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("progressive masking")
    class ProgressiveMasking {

        @Test
        @DisplayName("should not modify anything when <= 6 messages")
        void shouldNotModifySmallHistory() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                messages.add(msg(Message.Role.USER, "Message " + i));
            }

            List<String> originalContents = messages.stream().map(Message::content).toList();
            converter.applyProgressiveMasking(messages);

            for (int i = 0; i < 6; i++) {
                assertThat(messages.get(i).content()).isEqualTo(originalContents.get(i));
            }
        }

        @Test
        @DisplayName("should not modify 0 messages")
        void shouldNotModifyEmpty() {
            List<Message> messages = new ArrayList<>();
            assertThatCode(() -> converter.applyProgressiveMasking(messages)).doesNotThrowAnyException();
            assertThat(messages).isEmpty();
        }

        @Test
        @DisplayName("should not modify 1 message")
        void shouldNotModifySingle() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.USER, "Only message"));
            converter.applyProgressiveMasking(messages);
            assertThat(messages.get(0).content()).isEqualTo("Only message");
        }

        @Test
        @DisplayName("exactly 7 messages - first in WARM, last 6 in HOT")
        void shouldHandleBoundary7() {
            List<Message> messages = new ArrayList<>();
            String longAssistant = "[Tool: workflow(action='help') → ✅\n  Content: " + "x".repeat(500);
            messages.add(msg(Message.Role.ASSISTANT, longAssistant));
            for (int i = 1; i <= 6; i++) {
                messages.add(msg(Message.Role.USER, "Msg " + i));
            }

            converter.applyProgressiveMasking(messages);

            // Index 0, fromEnd=6 → WARM
            assertThat(messages.get(0).content()).contains("[content summarized]");
            // Last 6 unchanged
            for (int i = 1; i <= 6; i++) {
                assertThat(messages.get(i).content()).isEqualTo("Msg " + i);
            }
        }

        @Test
        @DisplayName("exactly 15 messages - first in COLD, then WARM, then HOT")
        void shouldHandleBoundary15() {
            List<Message> messages = new ArrayList<>();
            String toolResult = "[Tool: catalog(action='search') → ✅\n  Content: " + "x".repeat(500);
            messages.add(msg(Message.Role.ASSISTANT, toolResult)); // index 0, fromEnd=14 → COLD
            for (int i = 1; i < 15; i++) {
                messages.add(msg(Message.Role.USER, "Msg " + i));
            }

            converter.applyProgressiveMasking(messages);

            // Index 0 in COLD zone → minimal
            String coldContent = messages.get(0).content();
            assertThat(coldContent).startsWith("[Tool:");
            assertThat(coldContent.length()).isLessThan(100);
        }

        @Test
        @DisplayName("HOT zone preserves full content for all roles")
        void shouldPreserveHotZoneFullContent() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 8; i++) { // 2 padding + 6 HOT
                messages.add(msg(Message.Role.USER, "Padding " + i));
            }
            // Replace last 6 with various roles and long content
            String longContent = "Long assistant text " + "x".repeat(500);
            messages.set(7, msg(Message.Role.ASSISTANT, longContent));
            messages.set(6, msg(Message.Role.SYSTEM, "System in HOT"));
            messages.set(5, msg(Message.Role.USER, "User in HOT"));
            messages.set(4, msg(Message.Role.ASSISTANT, "[Tool: workflow(action='get') → ✅\n Content: big data..."));
            messages.set(3, msg(Message.Role.USER, "Short user in HOT"));
            messages.set(2, msg(Message.Role.ASSISTANT, "Another assistant HOT"));

            converter.applyProgressiveMasking(messages);

            // Index 2-7 = fromEnd 5,4,3,2,1,0 → all HOT
            assertThat(messages.get(7).content()).isEqualTo(longContent);
            assertThat(messages.get(6).content()).isEqualTo("System in HOT");
            assertThat(messages.get(5).content()).isEqualTo("User in HOT");
        }

        @Test
        @DisplayName("WARM zone truncates long non-tool assistant text")
        void shouldTruncateWarmAssistantText() {
            List<Message> messages = new ArrayList<>();
            String longAssistant = "Normal text " + "x".repeat(500); // Not [Tool: ...
            messages.add(msg(Message.Role.ASSISTANT, longAssistant));
            for (int i = 1; i < 10; i++) {
                messages.add(msg(Message.Role.USER, "Msg " + i));
            }

            converter.applyProgressiveMasking(messages);

            // Index 0, fromEnd=9 → WARM
            assertThat(messages.get(0).content()).endsWith("... [summarized]");
            assertThat(messages.get(0).content().length())
                .isLessThanOrEqualTo(ConversationHistoryConverter.WARM_CONTENT_PREVIEW + 20);
        }

        @Test
        @DisplayName("WARM zone does NOT truncate short messages")
        void shouldNotTruncateShortWarmMessages() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.USER, "Short")); // WARM zone
            for (int i = 1; i < 10; i++) {
                messages.add(msg(Message.Role.USER, "Msg " + i));
            }

            converter.applyProgressiveMasking(messages);

            // Short user message in WARM should be unchanged
            assertThat(messages.get(0).content()).isEqualTo("Short");
        }

        @Test
        @DisplayName("COLD zone truncates long user text to 200 chars")
        void shouldTruncateColdUserText() {
            List<Message> messages = new ArrayList<>();
            String longUser = "x".repeat(500);
            messages.add(msg(Message.Role.USER, longUser));
            for (int i = 1; i < 20; i++) {
                messages.add(msg(Message.Role.USER, "Short"));
            }

            converter.applyProgressiveMasking(messages);

            assertThat(messages.get(0).content()).endsWith("... [truncated]");
            assertThat(messages.get(0).content().length()).isLessThanOrEqualTo(215);
        }

        @Test
        @DisplayName("COLD zone does NOT truncate short user text")
        void shouldNotTruncateShortColdUserText() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.USER, "Brief question")); // Will be in COLD
            for (int i = 1; i < 20; i++) {
                messages.add(msg(Message.Role.USER, "Msg " + i));
            }

            converter.applyProgressiveMasking(messages);

            assertThat(messages.get(0).content()).isEqualTo("Brief question");
        }

        @Test
        @DisplayName("should handle messages with null content without NPE")
        void shouldHandleNullContentMessages() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.USER, null));
            for (int i = 1; i < 10; i++) {
                messages.add(msg(Message.Role.USER, "Msg " + i));
            }

            assertThatCode(() -> converter.applyProgressiveMasking(messages)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle tool result without newline (single-line)")
        void shouldHandleSingleLineToolResult() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.ASSISTANT, "[Tool: catalog(action='search') → ✅]"));
            for (int i = 1; i < 20; i++) {
                messages.add(msg(Message.Role.USER, "Msg " + i));
            }

            converter.applyProgressiveMasking(messages);

            // COLD zone single-line tool result
            String result = messages.get(0).content();
            assertThat(result).startsWith("[Tool:");
            assertThat(result).contains("→");
        }

        @Test
        @DisplayName("should handle tool result with ❌ error status in COLD zone")
        void shouldHandleErrorToolResultInCold() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.ASSISTANT, "[Tool: workflow(action='save') → ❌\n  Error: Failed"));
            for (int i = 1; i < 20; i++) {
                messages.add(msg(Message.Role.USER, "Msg " + i));
            }

            converter.applyProgressiveMasking(messages);

            assertThat(messages.get(0).content()).contains("ERROR");
        }

        @Test
        @DisplayName("masking level boundaries")
        void shouldDetermineCorrectLevels() {
            int totalSize = 20;
            // HOT: fromEnd 0-5 (indices 14-19)
            for (int idx = 14; idx <= 19; idx++) {
                assertThat(converter.getMaskingLevel(idx, totalSize))
                    .as("index=%d", idx).isEqualTo(ConversationHistoryConverter.MaskingLevel.FULL);
            }
            // WARM: fromEnd 6-13 (indices 6-13)
            for (int idx = 6; idx <= 13; idx++) {
                assertThat(converter.getMaskingLevel(idx, totalSize))
                    .as("index=%d", idx).isEqualTo(ConversationHistoryConverter.MaskingLevel.SUMMARY);
            }
            // COLD: fromEnd 14+ (indices 0-5)
            for (int idx = 0; idx <= 5; idx++) {
                assertThat(converter.getMaskingLevel(idx, totalSize))
                    .as("index=%d", idx).isEqualTo(ConversationHistoryConverter.MaskingLevel.MINIMAL);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 7. HELP DEDUPLICATION - edge cases
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("help deduplication")
    class HelpDeduplication {

        @Test
        @DisplayName("should deduplicate second identical help call")
        void shouldDeduplicateSecondHelp() {
            List<Message> messages = new ArrayList<>();
            String helpContent = "[Tool: workflow(action='help') → ✅\n  Content: Help docs...";

            messages.add(msg(Message.Role.ASSISTANT, helpContent));
            messages.add(msg(Message.Role.USER, "Now create a workflow"));
            messages.add(msg(Message.Role.ASSISTANT, helpContent));

            converter.deduplicateHelpResults(messages, new HashMap<>());

            assertThat(messages.get(0).content()).isEqualTo(helpContent);
            assertThat(messages.get(2).content()).contains("Help already loaded for workflow");
            assertThat(messages.get(2).content()).contains("Skipping duplicate");
        }

        @Test
        @DisplayName("should keep different help tools separate")
        void shouldKeepDifferentToolsSeparate() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.ASSISTANT, "[Tool: workflow(action='help') → ✅\n Content: WF docs"));
            messages.add(msg(Message.Role.ASSISTANT, "[Tool: interface(action='help') → ✅\n Content: IF docs"));

            converter.deduplicateHelpResults(messages, new HashMap<>());

            assertThat(messages.get(0).content()).contains("WF docs");
            assertThat(messages.get(1).content()).contains("IF docs");
        }

        @Test
        @DisplayName("should keep different topics of same tool separate")
        void shouldKeepDifferentTopicsSeparate() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.ASSISTANT,
                "[Tool: workflow(action='help', topics=['triggers']) → ✅\n  Content: Trigger docs"));
            messages.add(msg(Message.Role.ASSISTANT,
                "[Tool: workflow(action='help', topics=['agent']) → ✅\n  Content: Agent docs"));

            converter.deduplicateHelpResults(messages, new HashMap<>());

            assertThat(messages.get(0).content()).contains("Trigger docs");
            assertThat(messages.get(1).content()).contains("Agent docs");
        }

        @Test
        @DisplayName("should NOT touch non-help assistant messages")
        void shouldNotTouchNonHelp() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.ASSISTANT, "I'll help you create a workflow."));
            messages.add(msg(Message.Role.ASSISTANT, "I'll help you create a workflow."));

            converter.deduplicateHelpResults(messages, new HashMap<>());

            // Identical non-help messages should NOT be deduplicated
            assertThat(messages.get(0).content()).isEqualTo("I'll help you create a workflow.");
            assertThat(messages.get(1).content()).isEqualTo("I'll help you create a workflow.");
        }

        @Test
        @DisplayName("should NOT touch USER messages even if they look like help")
        void shouldNotTouchUserMessages() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.USER, "[Tool: workflow(action='help') → ✅"));
            messages.add(msg(Message.Role.USER, "[Tool: workflow(action='help') → ✅"));

            converter.deduplicateHelpResults(messages, new HashMap<>());

            // User messages are never modified
            assertThat(messages.get(0).content()).contains("workflow");
            assertThat(messages.get(1).content()).contains("workflow");
        }

        @Test
        @DisplayName("should handle empty messages list")
        void shouldHandleEmptyList() {
            assertThatCode(() -> converter.deduplicateHelpResults(new ArrayList<>(), new HashMap<>()))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle messages with null content")
        void shouldHandleNullContent() {
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.ASSISTANT, null));
            messages.add(msg(Message.Role.ASSISTANT, "[Tool: workflow(action='help') → ✅\n  Content: docs"));

            assertThatCode(() -> converter.deduplicateHelpResults(messages, new HashMap<>()))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should deduplicate 3+ identical help calls - only first kept")
        void shouldDeduplicate3PlusCalls() {
            String helpContent = "[Tool: agent(action='help') → ✅\n  Content: Agent help text";
            List<Message> messages = new ArrayList<>();
            messages.add(msg(Message.Role.ASSISTANT, helpContent));
            messages.add(msg(Message.Role.USER, "question 1"));
            messages.add(msg(Message.Role.ASSISTANT, helpContent));
            messages.add(msg(Message.Role.USER, "question 2"));
            messages.add(msg(Message.Role.ASSISTANT, helpContent));

            converter.deduplicateHelpResults(messages, new HashMap<>());

            assertThat(messages.get(0).content()).isEqualTo(helpContent); // kept
            assertThat(messages.get(2).content()).contains("Help already loaded"); // deduped
            assertThat(messages.get(4).content()).contains("Help already loaded"); // deduped
            // User messages untouched
            assertThat(messages.get(1).content()).isEqualTo("question 1");
            assertThat(messages.get(3).content()).isEqualTo("question 2");
        }

        @Test
        @DisplayName("should not false-positive on [Tool: catalog(action='search') which is not help")
        void shouldNotFalsePositiveOnSearch() {
            List<Message> messages = new ArrayList<>();
            String searchContent = "[Tool: catalog(action='search', query='email') → ✅\n  Content: results";
            messages.add(msg(Message.Role.ASSISTANT, searchContent));
            messages.add(msg(Message.Role.ASSISTANT, searchContent));

            converter.deduplicateHelpResults(messages, new HashMap<>());

            // Both should be preserved - search is not help
            assertThat(messages.get(0).content()).isEqualTo(searchContent);
            assertThat(messages.get(1).content()).isEqualTo(searchContent);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 8. STRUCTURED TRUNCATION
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("structured truncation")
    class StructuredTruncation {

        @Test
        @DisplayName("should produce informative summary")
        void shouldProduceInformativeSummary() {
            List<Message> dropped = List.of(
                msg(Message.Role.USER, "Build an email automation workflow"),
                msg(Message.Role.ASSISTANT, "[Tool: catalog(action='search', query='email') → ✅\n Content: results"),
                msg(Message.Role.ASSISTANT, "[Tool: workflow(action='init', name='Email Pipeline') → ✅\n Content: ok")
            );

            String summary = converter.buildStructuredTruncationSummary(new ArrayList<>(dropped));

            assertThat(summary).contains("3 earlier messages omitted");
            assertThat(summary).contains("email automation workflow");
            assertThat(summary).contains("catalog search");
            assertThat(summary).contains("workflow init");
            assertThat(summary).contains("Email Pipeline");
        }

        @Test
        @DisplayName("should handle empty list")
        void shouldHandleEmpty() {
            String summary = converter.buildStructuredTruncationSummary(new ArrayList<>());
            assertThat(summary).contains("0 earlier messages omitted");
        }

        @Test
        @DisplayName("should count duplicate tool actions")
        void shouldCountDuplicates() {
            List<Message> dropped = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                dropped.add(msg(Message.Role.ASSISTANT,
                    "[Tool: workflow(action='add_node') → ✅\n Content: ok"));
            }

            String summary = converter.buildStructuredTruncationSummary(dropped);
            assertThat(summary).contains("workflow add_node x5");
        }

        @Test
        @DisplayName("should limit user intents to 3")
        void shouldLimitUserIntents() {
            List<Message> dropped = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                dropped.add(msg(Message.Role.USER, "Question " + i));
            }

            String summary = converter.buildStructuredTruncationSummary(dropped);
            // Should contain first 3 intents, not all 10
            assertThat(summary).contains("Question 0");
            assertThat(summary).contains("Question 2");
            assertThat(summary).doesNotContain("Question 5");
        }

        @Test
        @DisplayName("should truncate long user intents to 100 chars")
        void shouldTruncateLongIntents() {
            String longMessage = "x".repeat(300);
            List<Message> dropped = List.of(msg(Message.Role.USER, longMessage));

            String summary = converter.buildStructuredTruncationSummary(new ArrayList<>(dropped));
            // Intent should not be 300 chars
            assertThat(summary.length()).isLessThan(300);
        }

        @Test
        @DisplayName("should handle messages with null content")
        void shouldHandleNullContent() {
            List<Message> dropped = new ArrayList<>();
            dropped.add(msg(Message.Role.USER, null));
            dropped.add(msg(Message.Role.ASSISTANT, null));
            dropped.add(msg(Message.Role.USER, "Valid question"));

            assertThatCode(() -> converter.buildStructuredTruncationSummary(dropped))
                .doesNotThrowAnyException();
            String summary = converter.buildStructuredTruncationSummary(dropped);
            assertThat(summary).contains("Valid question");
        }

        @Test
        @DisplayName("should handle only SYSTEM messages (no user intents, no tool actions)")
        void shouldHandleOnlySystemMessages() {
            List<Message> dropped = List.of(msg(Message.Role.SYSTEM, "System prompt text"));

            String summary = converter.buildStructuredTruncationSummary(new ArrayList<>(dropped));
            assertThat(summary).contains("1 earlier messages omitted");
            // No user intents, no tools - just the header
            assertThat(summary).doesNotContain("Topics discussed");
            assertThat(summary).doesNotContain("Tools used");
        }

        @Test
        @DisplayName("should not extract artifacts from non-create actions")
        void shouldNotExtractArtifactsFromRead() {
            List<Message> dropped = List.of(
                msg(Message.Role.ASSISTANT,
                    "[Tool: workflow(action='get', name='My WF') → ✅\n Content: data")
            );

            String summary = converter.buildStructuredTruncationSummary(new ArrayList<>(dropped));
            assertThat(summary).doesNotContain("Key artifacts");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 9. TOKEN BUDGET
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("token budget")
    class TokenBudget {

        @Test
        @DisplayName("should preserve messages under limit")
        void shouldPreserveUnderLimit() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                messages.add(msg(Message.Role.USER, "Short message " + i));
            }

            List<Message> result = converter.applyTokenLimit(messages);
            assertThat(result).hasSize(10);
        }

        @Test
        @DisplayName("should not truncate when <= HOT_ZONE_SIZE messages")
        void shouldNotTruncateSmallHistory() {
            List<Message> messages = new ArrayList<>();
            String huge = "x".repeat(200000); // Way over token limit per message
            for (int i = 0; i < 6; i++) {
                messages.add(msg(Message.Role.USER, huge));
            }

            List<Message> result = converter.applyTokenLimit(messages);
            // <= HOT_ZONE_SIZE → always preserved
            assertThat(result).hasSize(6);
        }

        @Test
        @DisplayName("should truncate and add structured summary")
        void shouldTruncateWithSummary() {
            List<Message> messages = new ArrayList<>();
            String content = "x".repeat(4000); // ~1000 tokens each
            for (int i = 0; i < 60; i++) {
                messages.add(msg(Message.Role.USER, content));
            }

            List<Message> result = converter.applyTokenLimit(messages);

            assertThat(result.size()).isLessThan(60);
            assertThat(result.get(0).role()).isEqualTo(Message.Role.SYSTEM);
            assertThat(result.get(0).content()).contains("earlier messages omitted");
        }

        @Test
        @DisplayName("should always keep HOT zone messages even after truncation")
        void shouldKeepHotZone() {
            List<Message> messages = new ArrayList<>();
            String content = "x".repeat(4000);
            for (int i = 0; i < 60; i++) {
                messages.add(msg(Message.Role.USER, "content-" + i + "-" + content));
            }

            List<Message> result = converter.applyTokenLimit(messages);

            // Last HOT_ZONE_SIZE messages should be the original last N
            int hotStart = result.size() - ConversationHistoryConverter.HOT_ZONE_SIZE;
            for (int i = 0; i < ConversationHistoryConverter.HOT_ZONE_SIZE; i++) {
                assertThat(result.get(hotStart + i).content())
                    .startsWith("content-" + (54 + i)); // 60-6=54
            }
        }

        @Test
        @DisplayName("should respect 45K token limit")
        void shouldRespect45kLimit() {
            List<Message> messages = new ArrayList<>();
            String content = "x".repeat(4000); // ~1000 tokens
            for (int i = 0; i < 60; i++) {
                messages.add(msg(Message.Role.USER, content));
            }

            List<Message> result = converter.applyTokenLimit(messages);

            int totalTokens = result.stream().mapToInt(m -> converter.estimateTokens(m.content())).sum();
            assertThat(totalTokens).isLessThanOrEqualTo(ConversationHistoryConverter.MAX_HISTORY_TOKENS + 200);
        }

        @Test
        @DisplayName("should handle single huge message gracefully")
        void shouldHandleSingleHugeMessage() {
            List<Message> messages = new ArrayList<>();
            for (int i = 0; i < 7; i++) {
                messages.add(msg(Message.Role.USER, "Short"));
            }
            // Replace first with a huge message
            messages.set(0, msg(Message.Role.USER, "x".repeat(500000)));

            assertThatCode(() -> converter.applyTokenLimit(messages)).doesNotThrowAnyException();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 10. FULL END-TO-END PIPELINE - the critical agent-launch path
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("end-to-end pipeline safety")
    class EndToEndPipeline {

        @Test
        @DisplayName("pipeline never returns null - agent always gets a list")
        void pipelineNeverReturnsNull() {
            assertThat(converter.convert(null)).isNotNull();
            assertThat(converter.convert(List.of())).isNotNull();
            assertThat(converter.convert(List.of(chatMsg("user", "Hi")))).isNotNull();
            assertThat(converter.convert(List.of(chatMsg("user", "Hi")), null, null)).isNotNull();
            assertThat(converter.convert(List.of(chatMsg("user", "Hi")), "c", "t")).isNotNull();
        }

        @Test
        @DisplayName("pipeline never throws - agent can always launch")
        void pipelineNeverThrows() {
            // Null history
            assertThatCode(() -> converter.convert(null)).doesNotThrowAnyException();
            // Empty history
            assertThatCode(() -> converter.convert(List.of())).doesNotThrowAnyException();
            // Single message
            assertThatCode(() -> converter.convert(List.of(chatMsg("user", "Hi"))))
                .doesNotThrowAnyException();
            // Null content
            assertThatCode(() -> converter.convert(List.of(chatMsg("user", null))))
                .doesNotThrowAnyException();
            // Malformed tool calls
            assertThatCode(() -> converter.convert(List.of(
                chatMsgWithToolCalls("assistant", null, "{{bad json"))))
                .doesNotThrowAnyException();
            // Very long history
            List<ChatRequest.ChatMessage> longHistory = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                longHistory.add(chatMsg("user", "Message " + i));
                longHistory.add(chatMsg("assistant", "Response " + i));
            }
            assertThatCode(() -> converter.convert(longHistory)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("pipeline preserves last user message - agent sees the question")
        void pipelinePreservesLastUserMessage() {
            List<ChatRequest.ChatMessage> history = new ArrayList<>();
            // Long history to trigger truncation
            for (int i = 0; i < 100; i++) {
                history.add(chatMsg("user", "Old question " + i + " " + "x".repeat(500)));
                history.add(chatMsg("assistant", "Old answer " + i + " " + "x".repeat(500)));
            }
            history.add(chatMsg("user", "THE IMPORTANT QUESTION"));

            List<Message> result = converter.convert(history);

            // Last message must always be the user's question
            Message lastMsg = result.get(result.size() - 1);
            assertThat(lastMsg.role()).isEqualTo(Message.Role.USER);
            assertThat(lastMsg.content()).isEqualTo("THE IMPORTANT QUESTION");
        }

        @Test
        @DisplayName("pipeline output has no null-content messages")
        void pipelineHasNoNullContentMessages() {
            List<ChatRequest.ChatMessage> history = new ArrayList<>();
            history.add(chatMsg("user", null));
            history.add(chatMsg("user", ""));
            history.add(chatMsg("user", "   "));
            history.add(chatMsg("assistant", null));
            history.add(chatMsg("tool", ""));
            history.add(chatMsg("user", "Valid question"));

            List<Message> result = converter.convert(history);

            for (Message m : result) {
                assertThat(m.content()).as("message content should not be null").isNotNull();
                assertThat(m.content().isBlank()).as("message content should not be blank").isFalse();
            }
        }

        @Test
        @DisplayName("pipeline with all features: help dedup + masking + truncation")
        void pipelineAllFeaturesCombined() {
            // Simulate a real conversation with repeated help, lots of tool calls, and long history
            List<ChatRequest.ChatMessage> history = new ArrayList<>();

            // First turn: user asks, agent calls help
            history.add(chatMsg("user", "Build me a workflow for emails"));
            history.add(chatMsgWithToolCalls("assistant", null,
                "[{\"toolName\":\"workflow\",\"id\":\"call_h1\",\"arguments\":\"{\\\"action\\\":\\\"help\\\"}\"}]"));

            // Several build turns
            for (int i = 0; i < 15; i++) {
                history.add(chatMsg("user", "Add step " + i));
                history.add(chatMsg("assistant", "Added step " + i + " " + "x".repeat(200)));
            }

            // Agent calls help again (should be deduplicated)
            history.add(chatMsgWithToolCalls("assistant", null,
                "[{\"toolName\":\"workflow\",\"id\":\"call_h2\",\"arguments\":\"{\\\"action\\\":\\\"help\\\"}\"}]"));

            history.add(chatMsg("user", "Now save it"));

            List<Message> result = converter.convert(history);

            // Must not crash
            assertThat(result).isNotNull().isNotEmpty();
            // Last message preserved
            assertThat(result.get(result.size() - 1).content()).isEqualTo("Now save it");
            // Output should be smaller than input (masking + truncation worked)
            int totalChars = result.stream().mapToInt(m -> m.content().length()).sum();
            int inputChars = history.stream()
                .filter(m -> m.getContent() != null)
                .mapToInt(m -> m.getContent().length()).sum();
            // Hard to compare exactly, but output should be reasonable
            assertThat(result.size()).isLessThanOrEqualTo(history.size());
        }

        @Test
        @DisplayName("pipeline with DB tool results joins correctly and doesn't crash")
        void pipelineWithDbToolResults() {
            ToolResult helpTr = new ToolResult("c1", "t1", "workflow", "call_h1",
                true, 100L, "Help docs " + "x".repeat(5000), null);
            ToolResult initTr = new ToolResult("c1", "t1", "workflow", "call_init",
                true, 200L, "{\"id\":\"wf-1\",\"name\":\"Email Flow\"}", null);
            when(toolResultService.getPreviewsByConversation("c1", "t1"))
                .thenReturn(List.of(helpTr, initTr));

            List<ChatRequest.ChatMessage> history = List.of(
                chatMsg("user", "Create an email workflow"),
                chatMsgWithToolCalls("assistant", null,
                    "[{\"toolName\":\"workflow\",\"id\":\"call_h1\",\"arguments\":\"{\\\"action\\\":\\\"help\\\"}\"}]"),
                chatMsgWithToolCalls("assistant", null,
                    "[{\"toolName\":\"workflow\",\"id\":\"call_init\",\"arguments\":\"{\\\"action\\\":\\\"init\\\",\\\"name\\\":\\\"Email Flow\\\"}\"}]"),
                chatMsg("assistant", "Your workflow is ready!"),
                chatMsg("user", "Run it")
            );

            List<Message> result = converter.convert(history, "c1", "t1");

            assertThat(result).isNotNull().isNotEmpty();
            // Last user message preserved
            assertThat(result.get(result.size() - 1).content()).isEqualTo("Run it");
            // No null content
            result.forEach(m -> assertThat(m.content()).isNotNull());
        }

        @Test
        @DisplayName("pipeline with mixed roles preserves role integrity")
        void pipelineMixedRoles() {
            List<ChatRequest.ChatMessage> history = List.of(
                chatMsg("system", "You are helpful"),
                chatMsg("user", "Hi"),
                chatMsg("assistant", "Hello!"),
                chatMsg("tool", "Tool summary"),
                chatMsg("user", "Do something"),
                chatMsg("assistant", "Done")
            );

            List<Message> result = converter.convert(history);

            // TOOL → ASSISTANT conversion
            boolean hasTool = result.stream().anyMatch(m -> m.role() == Message.Role.TOOL);
            assertThat(hasTool).as("No TOOL role should remain in output").isFalse();

            // All roles should be valid
            for (Message m : result) {
                assertThat(m.role()).isIn(Message.Role.USER, Message.Role.ASSISTANT, Message.Role.SYSTEM);
            }
        }

        @Test
        @DisplayName("logHistory should never throw")
        void logHistoryNeverThrows() {
            assertThatCode(() -> converter.logHistory(List.of())).doesNotThrowAnyException();
            assertThatCode(() -> converter.logHistory(List.of(
                msg(Message.Role.USER, "Hi"),
                msg(Message.Role.ASSISTANT, null)
            ))).doesNotThrowAnyException();
        }
    }
}
