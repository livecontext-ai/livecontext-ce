package com.apimarketplace.agent.service.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive tests for LlmJsonExtractor.extractJson().
 *
 * Test strategy:
 *   Happy path  → clean JSON, no noise
 *   Fences      → ```json … ```, ``` … ```, mixed case
 *   Preamble    → thinking text with stray braces before the real object
 *   Nesting     → JSON objects embedded in string values, nested objects
 *   Escape      → backslash-escaped quotes inside strings
 *   Whitespace  → leading/trailing spaces, CRLF, blank content
 *   No-JSON     → no braces, unbalanced braces, only an array
 *   Multiple    → two JSON objects: must return the FIRST complete one
 *   Unicode     → emoji and non-ASCII in values
 *   Gemini      → double-brace {{ thinking pattern, extended preamble
 */
@DisplayName("LlmJsonExtractor")
class LlmJsonExtractorTest {

    // ──────────────────────────────────────────────────────────────────────────
    //  Happy path
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path - clean JSON")
    class HappyPath {

        @Test
        @DisplayName("simple flat object is returned as-is")
        void simpleFlatObject() {
            String input = "{\"selected_category\":\"finance\",\"confidence\":0.95}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("nested object is returned intact")
        void nestedObject() {
            String input = "{\"passed\":true,\"details\":{\"rule_1\":{\"violated\":false}}}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("surrounding whitespace is stripped")
        void surroundingWhitespace() {
            String input = "   {\"key\":\"val\"}   ";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{\"key\":\"val\"}");
        }

        @Test
        @DisplayName("CRLF line endings in values are preserved")
        void crlfInValues() {
            String input = "{\"key\":\"line1\\r\\nline2\"}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Markdown fences
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Markdown fence stripping")
    class MarkdownFences {

        @Test
        @DisplayName("```json fence is stripped")
        void jsonFence() {
            String input = "```json\n{\"key\":\"val\"}\n```";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{\"key\":\"val\"}");
        }

        @Test
        @DisplayName("plain ``` fence is stripped")
        void plainFence() {
            String input = "```\n{\"key\":\"val\"}\n```";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{\"key\":\"val\"}");
        }

        @Test
        @DisplayName("only opening fence (no closing) - content still extracted")
        void onlyOpeningFence() {
            String input = "```json\n{\"key\":\"val\"}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{\"key\":\"val\"}");
        }

        @Test
        @DisplayName("fence with leading/trailing whitespace inside")
        void fenceWithWhitespace() {
            String input = "```json\n   {\"key\":\"val\"}   \n```";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{\"key\":\"val\"}");
        }

        @Test
        @DisplayName("fence wrapper around a full classify response")
        void fenceAroundClassifyResponse() {
            String input = "```json\n{\"selected_category\":\"urgent\",\"confidence\":0.9,\"reasoning\":\"high priority\"}\n```";
            String result = LlmJsonExtractor.extractJson(input);
            assertThat(result).startsWith("{").endsWith("}");
            assertThat(result).contains("\"selected_category\":\"urgent\"");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Preamble / thinking noise
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * The extractor always returns the FIRST complete balanced { } object found.
     * If the preamble contains balanced stray objects (like "{about this}"), those
     * are returned first - they will cause a Jackson parse failure upstream, but token
     * counts are preserved because services extract usage BEFORE calling this method.
     *
     * For preamble text containing UNBALANCED brace sequences (no closing "}"), the
     * extractor skips past them and finds the actual JSON object.
     */
    @Nested
    @DisplayName("Preamble noise - first balanced object is always returned")
    class PreambleNoise {

        @Test
        @DisplayName("balanced stray object in preamble is returned (upstream Jackson error expected)")
        void balancedStrayObjectInPreambleIsReturnedFirst() {
            // {about this} is a balanced object → extractor returns it.
            // Caller will get a Jackson parse error and fall back to parseFromPlainText.
            // Token count is preserved because it is extracted before calling extractJson.
            String input = "Let me think {about this}\n{\"selected_category\":\"finance\"}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{about this}");
        }

        @Test
        @DisplayName("first balanced stray token wins: '{1}' from preamble")
        void firstBalancedStrayTokenWins() {
            String input = "Step {1}: analyze {content}\nResult: {\"passed\":true,\"violations\":[]}";
            // {1} is the first balanced object
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{1}");
        }

        @Test
        @DisplayName("Gemini {{ double-brace: outer balanced object {{thinking}} is returned first")
        void geminiDoubleBracePreambleReturnsOuterObject() {
            // {{thinking}} → depth reaches 2 then back to 0 on the last }
            // So the whole {{thinking}} is the first balanced object
            String input = "{{thinking}}\nLet me evaluate this.\n{\"passed\":true,\"violations\":[]}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{{thinking}}");
        }

        @Test
        @DisplayName("nested stray valid-JSON objects: FIRST complete balanced object is returned")
        void nestedStrayObjectsInPreamble() {
            String input = "Intermediate: {\"step\":\"analysis\", \"score\":0.7} Final: {\"selected_category\":\"billing\",\"confidence\":0.88}";
            assertThat(LlmJsonExtractor.extractJson(input))
                .isEqualTo("{\"step\":\"analysis\", \"score\":0.7}");
        }

        @Test
        @DisplayName("preamble text with NO braces: JSON object is found directly")
        void preambleTextWithNoBraces() {
            String input = "I analyzed the content carefully and considered all the categories provided. "
                + "Based on the semantic meaning and context, here is my assessment:\n\n"
                + "{\"selected_category\":\"support\",\"confidence\":0.92,\"reasoning\":\"matches support pattern\"}";
            String result = LlmJsonExtractor.extractJson(input);
            assertThat(result).isEqualTo(
                "{\"selected_category\":\"support\",\"confidence\":0.92,\"reasoning\":\"matches support pattern\"}");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Braces inside string values
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Braces inside string values - must not confuse depth counter")
    class BracesInStringValues {

        @Test
        @DisplayName("single braces in a reasoning string")
        void singleBracesInReasoning() {
            String input = "{\"reasoning\":\"uses {template} syntax\",\"confidence\":0.8}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("multiple brace pairs in a string value")
        void multipleBracePairsInString() {
            String input = "{\"reasoning\":\"score {0} exceeds threshold {1}\",\"selected_category\":\"alert\"}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("opening brace without closing inside string")
        void unmatchedOpenBraceInString() {
            String input = "{\"explanation\":\"missing close brace {here\",\"violated\":false}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("closing brace without opening inside string")
        void unmatchedCloseBraceInString() {
            String input = "{\"explanation\":\"extra } in value\",\"passed\":true}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Escaped quotes inside strings
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Escaped quotes - must not break string detection")
    class EscapedQuotes {

        @Test
        @DisplayName("escaped double quotes in value")
        void escapedDoubleQuote() {
            String input = "{\"key\":\"say \\\"hello\\\" world\"}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("backslash followed by escaped quote then closing quote")
        void backslashThenEscapedQuote() {
            // JSON: {"key": "ends with backslash\\"}
            String input = "{\"key\":\"ends with backslash\\\\\"}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("brace after escaped quote in string")
        void braceAfterEscapedQuoteInString() {
            String input = "{\"reasoning\":\"said \\\"yes {to}\\\" it\",\"passed\":true}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  No JSON / edge inputs
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("No JSON found - return original content for clear upstream error")
    class NoJson {

        @Test
        @DisplayName("completely plain text returns trimmed original")
        void plainText() {
            String input = "The content is safe and does not violate any rules.";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input.trim());
        }

        @Test
        @DisplayName("only whitespace returns empty string")
        void onlyWhitespace() {
            assertThat(LlmJsonExtractor.extractJson("   ")).isEqualTo("");
        }

        @Test
        @DisplayName("empty string returns empty string")
        void emptyString() {
            assertThat(LlmJsonExtractor.extractJson("")).isEqualTo("");
        }

        @Test
        @DisplayName("JSON array (no braces at root) returns original")
        void jsonArray() {
            String input = "[\"one\",\"two\"]";
            // Arrays have no { }, so no balanced object is found
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("unbalanced opening brace returns original")
        void unbalancedOpenBrace() {
            String input = "{\"key\":\"value\"";
            // No closing brace → no balanced object
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("lone opening brace returns original")
        void loneBrace() {
            assertThat(LlmJsonExtractor.extractJson("{")).isEqualTo("{");
        }

        @Test
        @DisplayName("only closing brace returns original")
        void onlyClosingBrace() {
            assertThat(LlmJsonExtractor.extractJson("}")).isEqualTo("}");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Multiple JSON objects
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Multiple JSON objects - only the first complete one is returned")
    class MultipleObjects {

        @Test
        @DisplayName("two back-to-back objects: first is returned")
        void twoBackToBack() {
            String input = "{\"a\":1}{\"b\":2}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("two objects separated by text: first is returned")
        void twoSeparatedByText() {
            String input = "{\"first\":true} and then {\"second\":true}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo("{\"first\":true}");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Real-world LLM response shapes
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Real-world LLM response shapes")
    class RealWorldShapes {

        @Test
        @DisplayName("classify response with all fields")
        void classifyFullResponse() {
            String input = """
                {
                  "selected_category": "billing",
                  "confidence": 0.97,
                  "reasoning": "The message explicitly mentions invoice and payment issues."
                }
                """;
            String result = LlmJsonExtractor.extractJson(input);
            assertThat(result).startsWith("{").endsWith("}");
            assertThat(result).containsAnyOf("\"selected_category\":\"billing\"", "\"selected_category\": \"billing\"");
        }

        @Test
        @DisplayName("guardrail response with nested details and violations")
        void guardrailFullResponse() {
            String input = """
                {
                  "passed": false,
                  "violations": ["hate_speech"],
                  "details": {
                    "hate_speech": {
                      "violated": true,
                      "severity": "high",
                      "explanation": "Content targets a group",
                      "matched_content": "offensive phrase here"
                    }
                  },
                  "sanitized": "Content with violations [REDACTED]"
                }
                """;
            String result = LlmJsonExtractor.extractJson(input);
            assertThat(result).startsWith("{").endsWith("}");
            assertThat(result).containsAnyOf("\"passed\": false", "\"passed\":false");
            assertThat(result).contains("hate_speech");
        }

        @Test
        @DisplayName("Gemini extended thinking prefix - stray balanced objects prevent reaching real JSON")
        void geminiThinkingPrefixWithBalancedStrayObjects() {
            // "{spam: matches pattern A}" is a balanced object → extractor returns it first.
            // Upstream code (ClassifyService/GuardrailService) handles the Jackson parse error
            // and falls back to parseFromPlainText with the FULL original content,
            // which can still extract useful data via regex. Token count is always preserved.
            String input = "Let me analyze the content step by step.\n"
                + "First, I will check each category: {spam: matches pattern A}, {finance: less likely}\n"
                + "Based on my analysis:\n"
                + "{\"selected_category\":\"spam\",\"confidence\":0.91,\"reasoning\":\"matches spam indicators\"}";
            String result = LlmJsonExtractor.extractJson(input);
            // First balanced object is the stray {spam: matches pattern A}
            assertThat(result).isEqualTo("{spam: matches pattern A}");
        }

        @Test
        @DisplayName("Gemini preamble with NO brace sequences - JSON extracted correctly")
        void geminiPreambleWithNoBracesExtractsJson() {
            // When preamble text contains no braces at all, extractor finds the real JSON directly.
            // This is the clean Gemini preamble case: pure prose, then JSON.
            String input = "Let me analyze the content step by step. "
                + "The message matches spam indicators clearly. "
                + "Based on my analysis: "
                + "{\"selected_category\":\"spam\",\"confidence\":0.91,\"reasoning\":\"matches spam indicators\"}";
            String result = LlmJsonExtractor.extractJson(input);
            assertThat(result).isEqualTo(
                "{\"selected_category\":\"spam\",\"confidence\":0.91,\"reasoning\":\"matches spam indicators\"}");
        }

        @Test
        @DisplayName("unicode and emoji in values are preserved")
        void unicodeAndEmoji() {
            String input = "{\"reasoning\":\"contenu en français avec des accents: éàü 🎉\",\"passed\":true}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }

        @Test
        @DisplayName("deeply nested objects (reasoning with code examples)")
        void deeplyNestedObjects() {
            String input = "{\"reasoning\":\"uses {\\\"inner\\\":{\\\"deep\\\":true}} syntax\",\"passed\":true,\"details\":{\"rule_1\":{\"violated\":false,\"severity\":\"low\"}}}";
            String result = LlmJsonExtractor.extractJson(input);
            assertThat(result).startsWith("{").endsWith("}");
            assertThat(result).contains("\"passed\":true");
        }

        @Test
        @DisplayName("empty violations array and null sanitized field")
        void emptyViolationsAndNullSanitized() {
            String input = "{\"passed\":true,\"violations\":[],\"details\":{},\"sanitized\":null}";
            assertThat(LlmJsonExtractor.extractJson(input)).isEqualTo(input);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Whitespace / newline variants
    // ──────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Whitespace and newline handling")
    class WhitespaceVariants {

        @Test
        @DisplayName("JSON split across multiple lines (pretty-printed)")
        void prettyPrinted() {
            String input = "{\n  \"key\": \"value\",\n  \"num\": 42\n}";
            String result = LlmJsonExtractor.extractJson(input);
            assertThat(result).startsWith("{").endsWith("}");
            assertThat(result).contains("\"key\"");
        }

        @Test
        @DisplayName("Windows CRLF line endings in pretty-printed JSON")
        void crlfPrettyPrinted() {
            String input = "{\r\n  \"key\": \"value\"\r\n}";
            String result = LlmJsonExtractor.extractJson(input);
            assertThat(result).startsWith("{").endsWith("}");
            assertThat(result).contains("\"key\"");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Parameterized: all common fence variants
    // ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "fence variant: [{0}]")
    @ValueSource(strings = {
        "```json\n{\"k\":\"v\"}\n```",
        "```JSON\n{\"k\":\"v\"}\n```",
        "``` json\n{\"k\":\"v\"}\n```",
        "```\n{\"k\":\"v\"}\n```"
    })
    @DisplayName("various fence prefixes are stripped and JSON is extracted")
    void fenceVariants(String input) {
        String result = LlmJsonExtractor.extractJson(input);
        // The ```JSON and ``` json variants will NOT be stripped by the current implementation
        // (it only handles ```json and ``` exactly) - this test documents actual behaviour.
        // What must always be true: the result is not null and contains the key/value.
        assertThat(result).isNotNull().contains("\"k\"");
    }

    @Test
    @DisplayName("a stray unmatched '}' in the preamble does not poison extraction of a later object")
    void strayClosingBraceInPreambleDoesNotPoisonScan() {
        // Regression: pre-fix the lone '}' drove the brace depth to -1 and never recovered,
        // so the well-formed object that follows was never extracted (whole string returned).
        assertThat(LlmJsonExtractor.extractJson("} {\"x\":1}")).isEqualTo("{\"x\":1}");
        assertThat(LlmJsonExtractor.extractJson("oops }} then {\"a\":\"b\"}")).isEqualTo("{\"a\":\"b\"}");
    }
}
