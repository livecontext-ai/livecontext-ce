package com.apimarketplace.conversation.service.ai.callback;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("ToolCallClassifier")
class ToolCallClassifierTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    // CLASSIFY
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("classify")
    class Classify {

        @Test
        @DisplayName("should classify help calls for all CRUD tools")
        void shouldClassifyHelp() {
            for (String tool : new String[]{"workflow", "interface", "agent", "skill", "table", "application", "catalog"}) {
                assertThat(ToolCallClassifier.classify(tool, "{\"action\":\"help\"}"))
                    .as("tool=%s", tool)
                    .isEqualTo(ToolCallClassifier.ToolCategory.HELP);
            }
        }

        @Test
        @DisplayName("should classify help with topics")
        void shouldClassifyHelpWithTopics() {
            assertThat(ToolCallClassifier.classify("workflow", "{\"action\":\"help\",\"topics\":[\"triggers\"]}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.HELP);
        }

        @Test
        @DisplayName("should classify search calls")
        void shouldClassifySearch() {
            assertThat(ToolCallClassifier.classify("catalog", "{\"action\":\"search\",\"query\":\"gmail\"}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.SEARCH);
            assertThat(ToolCallClassifier.classify("web_search", "{\"action\":\"search\",\"query\":\"test\"}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.SEARCH);
        }

        @Test
        @DisplayName("should classify all read actions")
        void shouldClassifyRead() {
            String[] readActions = {"get", "list", "get_plan", "fetch", "describe", "runs", "response_schema"};
            for (String action : readActions) {
                assertThat(ToolCallClassifier.classify("workflow", "{\"action\":\"" + action + "\"}"))
                    .as("action=%s", action)
                    .isEqualTo(ToolCallClassifier.ToolCategory.READ);
            }
        }

        @Test
        @DisplayName("should classify all mutation actions")
        void shouldClassifyMutation() {
            String[] mutationActions = {"create", "update", "delete", "save", "set_plan", "init",
                "add_node", "connect", "remove_node", "disconnect", "publish", "load"};
            for (String action : mutationActions) {
                assertThat(ToolCallClassifier.classify("workflow", "{\"action\":\"" + action + "\"}"))
                    .as("action=%s", action)
                    .isEqualTo(ToolCallClassifier.ToolCategory.MUTATION);
            }
        }

        @Test
        @DisplayName("should classify execution calls")
        void shouldClassifyExecution() {
            assertThat(ToolCallClassifier.classify("catalog", "{\"action\":\"execute\",\"tool_id\":\"gmail_send\"}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.EXECUTION);
            assertThat(ToolCallClassifier.classify("workflow", "{\"action\":\"run\"}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.EXECUTION);
            assertThat(ToolCallClassifier.classify("application", "{\"action\":\"acquire\",\"id\":\"app1\"}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.EXECUTION);
        }

        @Test
        @DisplayName("should classify unknown actions as OTHER")
        void shouldClassifyOther() {
            assertThat(ToolCallClassifier.classify("unknown_tool", "{\"action\":\"weird\"}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.OTHER);
        }

        // ── Robustness ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("should handle null toolName gracefully")
        void shouldHandleNullToolName() {
            assertThatCode(() -> ToolCallClassifier.classify(null, null)).doesNotThrowAnyException();
            assertThat(ToolCallClassifier.classify(null, null)).isEqualTo(ToolCallClassifier.ToolCategory.OTHER);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "{}", "not-json", "{\"foo\":\"bar\"}", "[]"})
        @DisplayName("should never throw on malformed/missing arguments")
        void shouldNeverThrowOnBadArgs(String args) {
            assertThatCode(() -> ToolCallClassifier.classify("catalog", args)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle deeply nested/huge JSON without exception")
        void shouldHandleLargeJson() {
            String huge = "{\"action\":\"search\",\"data\":\"" + "x".repeat(50000) + "\"}";
            assertThatCode(() -> ToolCallClassifier.classify("catalog", huge)).doesNotThrowAnyException();
            assertThat(ToolCallClassifier.classify("catalog", huge)).isEqualTo(ToolCallClassifier.ToolCategory.SEARCH);
        }

        @Test
        @DisplayName("should handle action with extra whitespace in JSON")
        void shouldHandleExtraWhitespace() {
            // Jackson handles this fine, but let's confirm
            assertThat(ToolCallClassifier.classify("tool", "{ \"action\" : \"help\" }"))
                .isEqualTo(ToolCallClassifier.ToolCategory.HELP);
        }

        @Test
        @DisplayName("should return OTHER when no action field exists")
        void shouldReturnOtherWhenNoAction() {
            assertThat(ToolCallClassifier.classify("catalog", "{\"query\":\"gmail\"}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.OTHER);
        }

        @Test
        @DisplayName("should return OTHER when action is numeric")
        void shouldReturnOtherForNumericAction() {
            assertThat(ToolCallClassifier.classify("tool", "{\"action\":123}"))
                .isEqualTo(ToolCallClassifier.ToolCategory.OTHER);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELP DEDUP KEY
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("helpDedupKey")
    class HelpDedupKey {

        @Test
        @DisplayName("should produce consistent keys without topics")
        void shouldProduceKeyWithoutTopics() {
            assertThat(ToolCallClassifier.helpDedupKey("interface", "{\"action\":\"help\"}"))
                .isEqualTo("interface:help");
            assertThat(ToolCallClassifier.helpDedupKey("agent", "{\"action\":\"help\"}"))
                .isEqualTo("agent:help");
        }

        @Test
        @DisplayName("should include topics in key when present")
        void shouldIncludeTopics() {
            assertThat(ToolCallClassifier.helpDedupKey("workflow", "{\"action\":\"help\",\"topics\":[\"triggers\",\"agent\"]}"))
                .isEqualTo("workflow:help:triggers,agent");
        }

        @Test
        @DisplayName("should return same key for same inputs (idempotent)")
        void shouldBeIdempotent() {
            String args = "{\"action\":\"help\",\"topics\":[\"triggers\"]}";
            String key1 = ToolCallClassifier.helpDedupKey("workflow", args);
            String key2 = ToolCallClassifier.helpDedupKey("workflow", args);
            assertThat(key1).isEqualTo(key2);
        }

        @Test
        @DisplayName("should handle null tool name")
        void shouldHandleNullToolName() {
            assertThatCode(() -> ToolCallClassifier.helpDedupKey(null, "{}")).doesNotThrowAnyException();
            assertThat(ToolCallClassifier.helpDedupKey(null, "{}")).isEqualTo("unknown:help");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("should handle null/empty args without exception")
        void shouldHandleNullEmptyArgs(String args) {
            assertThatCode(() -> ToolCallClassifier.helpDedupKey("tool", args)).doesNotThrowAnyException();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // IS HELP CALL
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isHelpCall")
    class IsHelpCall {

        @Test
        @DisplayName("should detect help action on various tools")
        void shouldDetectHelpAction() {
            for (String tool : new String[]{"workflow", "interface", "agent", "skill", "table"}) {
                assertThat(ToolCallClassifier.isHelpCall(tool, "{\"action\":\"help\"}"))
                    .as("tool=%s", tool).isTrue();
            }
        }

        @Test
        @DisplayName("should detect help with topics")
        void shouldDetectHelpWithTopics() {
            assertThat(ToolCallClassifier.isHelpCall("workflow", "{\"action\":\"help\",\"topics\":[\"triggers\"]}")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-help actions")
        void shouldRejectNonHelp() {
            assertThat(ToolCallClassifier.isHelpCall("catalog", "{\"action\":\"search\"}")).isFalse();
            assertThat(ToolCallClassifier.isHelpCall("workflow", "{\"action\":\"create\"}")).isFalse();
            assertThat(ToolCallClassifier.isHelpCall("interface", "{\"action\":\"update\"}")).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "not-json", "[]", "{}"})
        @DisplayName("should never throw on bad args - always returns false")
        void shouldNeverThrowOnBadArgs(String args) {
            assertThatCode(() -> ToolCallClassifier.isHelpCall("tool", args)).doesNotThrowAnyException();
            assertThat(ToolCallClassifier.isHelpCall("tool", args)).isFalse();
        }

        @Test
        @DisplayName("should handle null toolName")
        void shouldHandleNullToolName() {
            assertThatCode(() -> ToolCallClassifier.isHelpCall(null, "{\"action\":\"help\"}")).doesNotThrowAnyException();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EXTRACT OUTCOME SUMMARY
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("extractOutcomeSummary")
    class ExtractOutcomeSummary {

        @Test
        @DisplayName("should summarize help calls")
        void shouldSummarizeHelp() {
            String summary = ToolCallClassifier.extractOutcomeSummary(
                "workflow", "{\"action\":\"help\",\"topics\":[\"triggers\",\"agent\"]}", "long content...", true);
            assertThat(summary).isEqualTo("workflow help [triggers,agent]: OK");
        }

        @Test
        @DisplayName("should summarize search with count")
        void shouldSummarizeSearch() {
            String resultContent = "[{\"name\":\"gmail\"},{\"name\":\"slack\"},{\"name\":\"stripe\"}]";
            String summary = ToolCallClassifier.extractOutcomeSummary(
                "catalog", "{\"action\":\"search\",\"query\":\"email\"}", resultContent, true);
            assertThat(summary).isEqualTo("catalog search 'email': 3 results");
        }

        @Test
        @DisplayName("should summarize create with ID")
        void shouldSummarizeCreate() {
            String resultContent = "{\"id\":\"abc-123\",\"name\":\"Dashboard\"}";
            String summary = ToolCallClassifier.extractOutcomeSummary(
                "interface", "{\"action\":\"create\",\"name\":\"Dashboard\"}", resultContent, true);
            assertThat(summary).isEqualTo("interface create 'Dashboard': id=abc-123");
        }

        @Test
        @DisplayName("should summarize errors with preview")
        void shouldSummarizeError() {
            String summary = ToolCallClassifier.extractOutcomeSummary(
                "workflow", "{\"action\":\"save\"}", "Permission denied", false);
            assertThat(summary).startsWith("workflow save → ERROR:");
            assertThat(summary).contains("Permission denied");
        }

        @Test
        @DisplayName("should summarize update/save")
        void shouldSummarizeUpdateSave() {
            assertThat(ToolCallClassifier.extractOutcomeSummary("interface", "{\"action\":\"update\"}", "OK", true))
                .isEqualTo("interface update: OK");
            assertThat(ToolCallClassifier.extractOutcomeSummary("workflow", "{\"action\":\"save\"}", "OK", true))
                .isEqualTo("workflow save: OK");
        }

        @Test
        @DisplayName("should summarize execution")
        void shouldSummarizeExecution() {
            assertThat(ToolCallClassifier.extractOutcomeSummary("catalog", "{\"action\":\"execute\"}", "done", true))
                .isEqualTo("catalog execute: OK");
        }

        // ── Robustness ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("should handle null toolName")
        void shouldHandleNullToolName() {
            assertThatCode(() -> ToolCallClassifier.extractOutcomeSummary(null, "{\"action\":\"search\"}", "r", true))
                .doesNotThrowAnyException();
            assertThat(ToolCallClassifier.extractOutcomeSummary(null, "{\"action\":\"search\"}", "r", true))
                .contains("unknown");
        }

        @Test
        @DisplayName("should handle null result content")
        void shouldHandleNullResultContent() {
            assertThatCode(() -> ToolCallClassifier.extractOutcomeSummary("catalog", "{\"action\":\"search\"}", null, true))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle null arguments")
        void shouldHandleNullArguments() {
            assertThatCode(() -> ToolCallClassifier.extractOutcomeSummary("tool", null, "result", true))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle all-null inputs")
        void shouldHandleAllNull() {
            assertThatCode(() -> ToolCallClassifier.extractOutcomeSummary(null, null, null, false))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("should handle empty result content for search")
        void shouldHandleEmptyResultForSearch() {
            String summary = ToolCallClassifier.extractOutcomeSummary("catalog", "{\"action\":\"search\",\"query\":\"x\"}", "", true);
            assertThat(summary).contains("0 results");
        }

        @Test
        @DisplayName("should handle very long error message without crash")
        void shouldHandleLongErrorMessage() {
            String longError = "x".repeat(10000);
            assertThatCode(() -> ToolCallClassifier.extractOutcomeSummary("tool", "{\"action\":\"save\"}", longError, false))
                .doesNotThrowAnyException();
            String summary = ToolCallClassifier.extractOutcomeSummary("tool", "{\"action\":\"save\"}", longError, false);
            assertThat(summary.length()).isLessThan(200); // Should be truncated, not 10K chars
        }

        @Test
        @DisplayName("should produce summary for init action using label field")
        void shouldUseAlternateNameFields() {
            String result = "{\"id\":\"w-1\"}";
            String summary = ToolCallClassifier.extractOutcomeSummary(
                "workflow", "{\"action\":\"init\",\"label\":\"My Flow\"}", result, true);
            assertThat(summary).contains("My Flow");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EXTRACT KEY PARAMS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("extractKeyParams")
    class ExtractKeyParams {

        @Test
        @DisplayName("should extract action")
        void shouldExtractAction() {
            assertThat(ToolCallClassifier.extractKeyParams("catalog", "{\"action\":\"search\"}"))
                .contains("action='search'");
        }

        @Test
        @DisplayName("should extract action + query")
        void shouldExtractQuery() {
            String params = ToolCallClassifier.extractKeyParams("catalog", "{\"action\":\"search\",\"query\":\"gmail\"}");
            assertThat(params).contains("action='search'").contains("query='gmail'");
        }

        @Test
        @DisplayName("should extract action + id")
        void shouldExtractId() {
            String params = ToolCallClassifier.extractKeyParams("workflow", "{\"action\":\"get\",\"id\":\"abc-123\"}");
            assertThat(params).contains("action='get'").contains("id='abc-123'");
        }

        @Test
        @DisplayName("should extract tool_id")
        void shouldExtractToolId() {
            String params = ToolCallClassifier.extractKeyParams("catalog", "{\"action\":\"execute\",\"tool_id\":\"gmail_send\"}");
            assertThat(params).contains("tool_id='gmail_send'");
        }

        @Test
        @DisplayName("should truncate long values")
        void shouldTruncateLong() {
            String longQuery = "a".repeat(50);
            String params = ToolCallClassifier.extractKeyParams("catalog", "{\"action\":\"search\",\"query\":\"" + longQuery + "\"}");
            assertThat(params).contains("...'");
            assertThat(params.length()).isLessThan(150);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "not-json", "{}"})
        @DisplayName("should never throw on bad args")
        void shouldNeverThrowOnBadArgs(String args) {
            assertThatCode(() -> ToolCallClassifier.extractKeyParams("tool", args)).doesNotThrowAnyException();
        }
    }
}
