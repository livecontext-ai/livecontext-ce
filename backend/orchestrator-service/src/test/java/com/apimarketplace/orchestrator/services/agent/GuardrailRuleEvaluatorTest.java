package com.apimarketplace.orchestrator.services.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("GuardrailRuleEvaluator")
class GuardrailRuleEvaluatorTest {

    /** Identity resolver: a configured value is its own value. */
    private static final UnaryOperator<Object> LITERAL = v -> v;

    private static Map<String, Object> rule(String id, String type, String action, Map<String, Object> config) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", id);
        rule.put("type", type);
        if (action != null) rule.put("action", action);
        if (config != null) rule.put("config", config);
        return rule;
    }

    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]);
        return map;
    }

    private static GuardrailRuleEvaluator.Verdict only(GuardrailRuleEvaluator.Outcome outcome) {
        assertThat(outcome.verdicts()).hasSize(1);
        assertThat(outcome.llmRules()).isEmpty();
        return outcome.verdicts().get(0);
    }

    @Nested
    @DisplayName("keyword_filter")
    class KeywordFilter {

        @Test
        @DisplayName("block mode: a listed keyword as a whole word, any case, is a violation")
        void blockModeHit() {
            var v = only(GuardrailRuleEvaluator.evaluate(List.of(rule("kw", "keyword_filter", "block",
                config("keywordsExpression", "refund, chargeback"))), "I want a REFUND now", LITERAL));
            assertThat(v.violated()).isTrue();
            assertThat(v.matchedContent()).isEqualTo("refund");
        }

        @Test
        @DisplayName("block mode: a keyword inside another word does not match")
        void blockModeWholeWordMiss() {
            var v = only(GuardrailRuleEvaluator.evaluate(List.of(rule("kw", "keyword_filter", "block",
                config("keywordsExpression", "hack"))), "shackles and whackamole", LITERAL));
            assertThat(v.violated()).isFalse();
        }

        @Test
        @DisplayName("allow mode: violated when none of the keywords appears")
        void allowMode() {
            var miss = only(GuardrailRuleEvaluator.evaluate(List.of(rule("kw", "keyword_filter", "block",
                config("keywordsExpression", "invoice", "mode", "allow"))), "hello there", LITERAL));
            var hit = only(GuardrailRuleEvaluator.evaluate(List.of(rule("kw", "keyword_filter", "block",
                config("keywordsExpression", "invoice", "mode", "allow"))), "your invoice", LITERAL));
            assertThat(miss.violated()).isTrue();
            assertThat(hit.violated()).isFalse();
        }

        @Test
        @DisplayName("the keyword list is resolved through the node's resolver, and a resolved LIST is accepted")
        void templateResolvedKeywords() {
            UnaryOperator<Object> resolver = v -> "{{core:cfg.output.words}}".equals(v) ? List.of("spam", "scam") : v;
            var v = only(GuardrailRuleEvaluator.evaluate(List.of(rule("kw", "keyword_filter", "block",
                config("keywordsExpression", "{{core:cfg.output.words}}"))), "obvious scam", resolver));
            assertThat(v.violated()).isTrue();
            assertThat(v.matchedContent()).isEqualTo("scam");
        }

        @Test
        @DisplayName("a keyword reference that resolves to nothing fails the node, naming the rule")
        void emptyKeywordsFail() {
            assertThatThrownBy(() -> GuardrailRuleEvaluator.evaluate(List.of(rule("kw", "keyword_filter", "block",
                config("keywordsExpression", "{{core:missing.output.x}}"))), "text", v -> null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'kw'")
                .hasMessageContaining("keyword_filter");
        }
    }

    @Nested
    @DisplayName("regex_pattern")
    class RegexPattern {

        @Test
        @DisplayName("require mode (default): violated when the content does not match")
        void requireMode() {
            var bad = only(GuardrailRuleEvaluator.evaluate(List.of(rule("rx", "regex_pattern", null,
                config("pattern", "^[a-z]+$"))), "abc123", LITERAL));
            var good = only(GuardrailRuleEvaluator.evaluate(List.of(rule("rx", "regex_pattern", null,
                config("pattern", "^[a-z]+$"))), "abc", LITERAL));
            assertThat(bad.violated()).isTrue();
            assertThat(good.violated()).isFalse();
        }

        @Test
        @DisplayName("block mode: violated when the content contains a match, and reports it")
        void blockMode() {
            var v = only(GuardrailRuleEvaluator.evaluate(List.of(rule("rx", "regex_pattern", "block",
                config("pattern", "ORD-\\d{4}", "mode", "block"))), "see ORD-1234", LITERAL));
            assertThat(v.violated()).isTrue();
            assertThat(v.matchedContent()).isEqualTo("ORD-1234");
        }

        @Test
        @DisplayName("a pattern that does not compile fails the node")
        void invalidPattern() {
            assertThatThrownBy(() -> GuardrailRuleEvaluator.evaluate(List.of(rule("rx", "regex_pattern", null,
                config("pattern", "(unclosed"))), "x", LITERAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not compile");
        }

        @Test
        @DisplayName("a catastrophically backtracking pattern is stopped by the time limit and fails the node")
        void catastrophicBacktrackingStops() throws Exception {
            // A modern JDK short-circuits the textbook catastrophic patterns, so the budget is
            // exercised directly: a zero budget over a long scan must abort, not run to the end.
            GuardrailRuleEvaluator.TimeLimitedCharSequence limited =
                new GuardrailRuleEvaluator.TimeLimitedCharSequence("a".repeat(200_000), 0);
            Thread.sleep(2);
            assertThatThrownBy(() -> java.util.regex.Pattern.compile("x").matcher(limited).find())
                .isInstanceOf(GuardrailRuleEvaluator.RegexTimeoutException.class);
        }

        @Test
        @DisplayName("a budget that is not spent lets an ordinary pattern run to its answer")
        void unspentBudgetDoesNotInterfere() {
            GuardrailRuleEvaluator.TimeLimitedCharSequence limited =
                new GuardrailRuleEvaluator.TimeLimitedCharSequence("hello world", 60_000);
            assertThat(java.util.regex.Pattern.compile("world").matcher(limited).find()).isTrue();
        }
    }

    @Nested
    @DisplayName("length_check")
    class LengthCheck {

        @Test
        @DisplayName("counts code points and enforces min and max")
        void bounds() {
            var tooShort = only(GuardrailRuleEvaluator.evaluate(List.of(rule("len", "length_check", null,
                config("minLength", 5, "maxLength", 10))), "abc", LITERAL));
            var ok = only(GuardrailRuleEvaluator.evaluate(List.of(rule("len", "length_check", null,
                config("minLength", 5, "maxLength", 10))), "😀😀😀😀😀", LITERAL));
            var tooLong = only(GuardrailRuleEvaluator.evaluate(List.of(rule("len", "length_check", null,
                config("maxLength", "3"))), "abcd", LITERAL));
            assertThat(tooShort.violated()).isTrue();
            assertThat(ok.violated()).as("five emoji are five characters, not ten UTF-16 units").isFalse();
            assertThat(tooLong.violated()).isTrue();
        }

        @Test
        @DisplayName("a non-numeric bound fails the node")
        void nonNumeric() {
            assertThatThrownBy(() -> GuardrailRuleEvaluator.evaluate(List.of(rule("len", "length_check", null,
                config("maxLength", "lots"))), "x", LITERAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a number");
        }
    }

    @Nested
    @DisplayName("pii_detection")
    class PiiDetection {

        @Test
        @DisplayName("detects an email and a Luhn-valid card number; a random 16-digit number is not a card")
        void emailAndCard() {
            var hit = only(GuardrailRuleEvaluator.evaluate(List.of(rule("pii", "pii_detection", "block",
                config("piiTypes", List.of("email", "credit_card")))),
                "mail jane.doe@example.com card 4111 1111 1111 1111", LITERAL));
            var notCard = only(GuardrailRuleEvaluator.evaluate(List.of(rule("pii", "pii_detection", "block",
                config("piiTypes", List.of("credit_card")))), "order 1234 5678 9012 3456", LITERAL));
            assertThat(hit.violated()).isTrue();
            assertThat(hit.explanation()).contains("email").contains("credit_card");
            assertThat(notCard.violated()).isFalse();
        }

        @Test
        @DisplayName("an address is left to the model under its own id")
        void addressGoesToModel() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(rule("pii", "pii_detection", "block",
                config("piiTypes", List.of("email", "address")))), "hello", LITERAL);
            assertThat(outcome.verdicts()).hasSize(1);
            assertThat(outcome.llmRules()).extracting(GuardrailRuleEvaluator.LlmRule::id).containsExactly("pii-address");
        }
    }

    @Nested
    @DisplayName("custom and competitor_mention")
    class CustomAndCompetitor {

        @Test
        @DisplayName("custom: #input is the content, false means violated")
        void customExpression() {
            var v = only(GuardrailRuleEvaluator.evaluate(List.of(rule("c", "custom", null,
                config("expression", "#length(#input) > 10"))), "short", LITERAL));
            assertThat(v.violated()).isTrue();
        }

        @Test
        @DisplayName("custom: an expression that does not return true/false fails the node")
        void customNonBoolean() {
            assertThatThrownBy(() -> GuardrailRuleEvaluator.evaluate(List.of(rule("c", "custom", null,
                config("expression", "#length(#input)"))), "short", LITERAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must return true or false");
        }

        @Test
        @DisplayName("competitor_mention: a listed name as a whole word is a violation")
        void competitor() {
            var v = only(GuardrailRuleEvaluator.evaluate(List.of(rule("cm", "competitor_mention", null,
                config("topicsExpression", "Acme, Globex"))), "unlike acme we ship fast", LITERAL));
            assertThat(v.violated()).isTrue();
            assertThat(v.matchedContent()).isEqualTo("Acme");
        }
    }

    @Nested
    @DisplayName("which rules go to the model")
    class ModelRules {

        @Test
        @DisplayName("a description-only rule, even of a deterministic type, is judged by the model with its description")
        void descriptionOnlyStaysWithModel() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("spam", "keyword_filter", "flag", config("description", "Block spam messages"))), "x", LITERAL);
            assertThat(outcome.verdicts()).isEmpty();
            assertThat(outcome.llmRules()).singleElement()
                .satisfies(r -> assertThat(r.description()).isEqualTo("Block spam messages"));
        }

        @Test
        @DisplayName("a judgement type without description gets one built from its type and resolved topics")
        void synthesizedDescription() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("t", "topic_restriction", "block", config("topicsExpression", "politics, war"))), "x", LITERAL);
            assertThat(outcome.llmRules()).singleElement()
                .satisfies(r -> assertThat(r.description()).contains("politics").contains("war"));
        }

        @Test
        @DisplayName("a generic rule-N id takes the rule's type, as the model has always seen it")
        void genericIdTakesType() {
            Map<String, Object> r = new HashMap<>();
            r.put("id", "rule-0");
            r.put("type", "toxicity");
            assertThat(GuardrailRuleEvaluator.ruleId(r)).isEqualTo("toxicity");
        }

        @Test
        @DisplayName("no rule at all still needs the model (its instructions may be in the prompt)")
        void noRulesNeedsModel() {
            assertThat(GuardrailRuleEvaluator.evaluate(List.of(), "x", LITERAL).needsLlm()).isTrue();
        }
    }

    @Nested
    @DisplayName("actions and merge")
    class Actions {

        private GuardrailRuleEvaluator.Outcome keywordHit(String action) {
            return GuardrailRuleEvaluator.evaluate(List.of(rule("kw", "keyword_filter", action,
                config("keywordsExpression", "secret"))), "the secret code", LITERAL);
        }

        @Test
        @DisplayName("regression: ANY violated rule fails the node, whatever its action (flag no longer disarms a guardrail)")
        void anyViolationFails() {
            // Imported rules are stamped action=flag; if flag let content pass, every such
            // guardrail would silently route violations to its pass port.
            for (String action : new String[]{"block", null, "flag", "sanitize"}) {
                var merged = GuardrailRuleEvaluator.merge(keywordHit(action), null, null, null, null);
                assertThat(merged.passed()).as("action " + action).isFalse();
                assertThat(merged.violations()).containsExactly("kw");
            }
            assertThat(GuardrailRuleEvaluator.merge(keywordHit("sanitize"), null, null, null, null).sanitized())
                .as("sanitize still redacts the match").isEqualTo("the [REDACTED] code");
            assertThat(GuardrailRuleEvaluator.merge(keywordHit("flag"), null, null, null, null).sanitized())
                .as("flag redacts nothing").isEqualTo("the secret code");
        }

        @Test
        @DisplayName("merge with the model: any model violation fails, whatever the rule's action; details carry type")
        @SuppressWarnings("unchecked")
        void mergeWithModel() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("kw", "keyword_filter", "sanitize", config("keywordsExpression", "secret")),
                rule("tox", "toxic_language", "flag", config())), "the secret code", LITERAL);
            var merged = GuardrailRuleEvaluator.merge(outcome, false, List.of("tox"),
                Map.of("tox", Map.of("violated", true, "severity", "high")), "the secret code");
            assertThat(merged.passed()).isFalse();
            assertThat(merged.violations()).containsExactlyInAnyOrder("kw", "tox");
            assertThat((Map<String, Object>) merged.details().get("tox")).containsEntry("type", "toxic_language");
            assertThat(merged.sanitized()).as("deterministic redaction applied on the model's text too")
                .isEqualTo("the [REDACTED] code");

            var clean = GuardrailRuleEvaluator.evaluate(List.of(rule("tox", "toxic_language", "flag", config())),
                "fine", LITERAL);
            assertThat(GuardrailRuleEvaluator.merge(clean, true, List.of(), Map.of(), null).passed()).isTrue();
            assertThat(GuardrailRuleEvaluator.merge(clean, false, List.of(), Map.of(), null).passed())
                .as("a failed model verdict with no id still fails").isFalse();
        }

        @Test
        @DisplayName("the outcome survives the async queue round trip (toMap / fromMap)")
        void roundTrip() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("kw", "keyword_filter", "sanitize", config("keywordsExpression", "secret")),
                rule("tox", "toxic_language", "block", config())), "the secret code", LITERAL);
            var back = GuardrailRuleEvaluator.Outcome.fromMap(outcome.toMap());
            assertThat(back.verdicts()).hasSize(1);
            assertThat(back.llmRules()).extracting(GuardrailRuleEvaluator.LlmRule::id).containsExactly("tox");
            assertThat(GuardrailRuleEvaluator.merge(back, true, List.of(), Map.of(), "the secret code").sanitized())
                .isEqualTo("the [REDACTED] code");
        }

        @Test
        @DisplayName("a model-judged sanitize rule asks the model to redact")
        void llmActionRedact() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(rule("tox", "toxic_language", "sanitize", config())),
                "x", LITERAL);
            assertThat(outcome.llmAction("flag")).isEqualTo("redact");
            assertThat(outcome.llmAction("block")).isEqualTo("block");
        }
    }

    @Nested
    @DisplayName("rules written before typed rules were enforced")
    class LegacyAndBlankRules {

        @Test
        @DisplayName("regression: a keyword_filter whose keywords field holds its own description is judged by the model")
        void legacyKeywordDescriptionGoesToModel() {
            // The canvas importer copied a {ruleId: description} rule's description into
            // keywordsExpression. Read as a list, "Block spam messages" never appears and the
            // guardrail passed everything without asking the model.
            Map<String, Object> rule = rule("spam", "keyword_filter", "block",
                config("keywordsExpression", "Block spam messages", "mode", "block"));
            rule.put("description", "Block spam messages");

            GuardrailRuleEvaluator.Outcome outcome = GuardrailRuleEvaluator.evaluate(List.of(rule), "buy now!!!", LITERAL);

            assertThat(outcome.verdicts()).isEmpty();
            assertThat(outcome.llmRules()).extracting(GuardrailRuleEvaluator.LlmRule::description)
                .containsExactly("Block spam messages");
        }

        @Test
        @DisplayName("regression: a competitor_mention whose names field holds config.description is judged by the model")
        void legacyTopicsDescriptionGoesToModel() {
            GuardrailRuleEvaluator.Outcome outcome = GuardrailRuleEvaluator.evaluate(List.of(rule("rivals",
                "competitor_mention", null,
                config("topicsExpression", "Do not name competitors", "description", "Do not name competitors"))),
                "we beat Acme", LITERAL);

            assertThat(outcome.verdicts()).isEmpty();
            assertThat(outcome.llmRules()).hasSize(1);
        }

        @Test
        @DisplayName("a real keyword list next to a different description is still checked exactly")
        void realListIsChecked() {
            Map<String, Object> rule = rule("kw", "keyword_filter", "block", config("keywordsExpression", "refund"));
            rule.put("description", "No refund talk");

            GuardrailRuleEvaluator.Outcome outcome = GuardrailRuleEvaluator.evaluate(List.of(rule), "a refund", LITERAL);

            assertThat(only(outcome).violated()).isTrue();
        }

        @Test
        @DisplayName("regression: a length_check carrying the old importer defaults (1..10000) and a description is judged by the model")
        void legacyLengthDefaultsGoToModel() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("len", "length_check", "flag", config("description", "Max 200 chars", "minLength", 1, "maxLength", 10000))),
                "x".repeat(500), LITERAL);
            assertThat(outcome.verdicts()).isEmpty();
            assertThat(outcome.llmRules()).extracting(GuardrailRuleEvaluator.LlmRule::description)
                .containsExactly("Max 200 chars");
        }

        @Test
        @DisplayName("a length_check with author-set bounds next to a description is still checked exactly")
        void authoredLengthIsChecked() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("len", "length_check", "block", config("description", "Max 200 chars", "minLength", 1, "maxLength", 200))),
                "x".repeat(500), LITERAL);
            assertThat(outcome.verdicts()).hasSize(1);
            assertThat(outcome.verdicts().get(0).violated()).isTrue();
        }

        @Test
        @DisplayName("regression: a pii_detection carrying the old importer defaults and a description is judged by the model")
        void legacyPiiDefaultsGoToModel() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("pii", "pii_detection", "flag", config("description", "No postal addresses",
                    "piiTypes", List.of("email", "phone", "credit_card")))),
                "write to bob@example.com", LITERAL);
            assertThat(outcome.verdicts()).isEmpty();
            assertThat(outcome.llmRules()).extracting(GuardrailRuleEvaluator.LlmRule::description)
                .containsExactly("No postal addresses");
        }

        @Test
        @DisplayName("a pii_detection with author-chosen types next to a description is still checked exactly")
        void authoredPiiIsChecked() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("pii", "pii_detection", "block", config("description", "No emails", "piiTypes", List.of("email")))),
                "write to bob@example.com", LITERAL);
            assertThat(outcome.verdicts()).hasSize(1);
            assertThat(outcome.verdicts().get(0).violated()).isTrue();
        }

        @Test
        @DisplayName("a blank deterministic rule reaches the model with its type's description, not a placeholder")
        void blankRuleGetsTypeDescription() {
            var outcome = GuardrailRuleEvaluator.evaluate(List.of(
                rule("kw", "keyword_filter", "block", config("keywordsExpression", ""))), "x", LITERAL);
            assertThat(outcome.llmRules()).extracting(GuardrailRuleEvaluator.LlmRule::description)
                .containsExactly("The content must not contain blocked or forbidden words.");
        }

        @Test
        @DisplayName("a rule left blank in the form goes to the model instead of failing the node")
        void blankConfigGoesToModel() {
            GuardrailRuleEvaluator.Outcome outcome = GuardrailRuleEvaluator.evaluate(List.of(
                    rule("kw", "keyword_filter", null, config("keywordsExpression", "  ")),
                    rule("rx", "regex_pattern", null, config("pattern", "")),
                    rule("cx", "custom", null, config("expression", "")),
                    rule("len", "length_check", null, config("minLength", "", "maxLength", " "))),
                "anything", LITERAL);

            assertThat(outcome.verdicts()).isEmpty();
            assertThat(outcome.llmRules()).extracting(GuardrailRuleEvaluator.LlmRule::id)
                .containsExactly("kw", "rx", "cx", "len");
        }
    }

    @Nested
    @DisplayName("pii_detection shapes")
    class PiiShapes {

        @Test
        @DisplayName("regression: a bare run of digits (an order id) is not a phone number")
        void orderIdIsNotAPhone() {
            GuardrailRuleEvaluator.Verdict verdict = only(GuardrailRuleEvaluator.evaluate(List.of(rule("pii",
                "pii_detection", "block", config("piiTypes", List.of("phone")))), "order 4839201756 shipped", LITERAL));

            assertThat(verdict.violated()).isFalse();
        }

        @Test
        @DisplayName("a phone written with + or separators is detected")
        void shapedPhonesAreDetected() {
            for (String text : List.of("call +33612345678", "call 06 12 34 56 78", "call (415) 555-0132")) {
                GuardrailRuleEvaluator.Verdict verdict = only(GuardrailRuleEvaluator.evaluate(List.of(rule("pii",
                    "pii_detection", "block", config("piiTypes", List.of("phone")))), text, LITERAL));
                assertThat(verdict.violated()).as(text).isTrue();
            }
        }

        @Test
        @DisplayName("pii_detection with no piiTypes checks email, phone, ssn and credit card without the model")
        void noTypesChecksEveryPatternType() {
            GuardrailRuleEvaluator.Outcome outcome = GuardrailRuleEvaluator.evaluate(List.of(rule("pii",
                "pii_detection", "block", config())), "write to a@b.io", LITERAL);

            assertThat(only(outcome).violated()).isTrue();
        }

        @Test
        @DisplayName("a pii_detection whose config is only a description stays with the model")
        void descriptionOnlyPiiGoesToModel() {
            GuardrailRuleEvaluator.Outcome outcome = GuardrailRuleEvaluator.evaluate(List.of(rule("pii",
                "pii_detection", null, config("description", "No personal data"))), "a@b.io", LITERAL);

            assertThat(outcome.verdicts()).isEmpty();
            assertThat(outcome.llmRules()).hasSize(1);
        }
    }
}
