package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GuardrailCreator typed rules")
class GuardrailCreatorTypedRulesTest {

    private static String parse(List<?> rules, List<Map<String, Object>> out) {
        return GuardrailCreator.parseTypedRules(rules, out);
    }

    @Test
    @DisplayName("a valid typed array is normalised: default action block, generated unique ids, config kept")
    void validArray() {
        List<Map<String, Object>> out = new ArrayList<>();
        String error = parse(List.of(
            Map.of("type", "keyword_filter", "config", Map.of("keywordsExpression", "refund")),
            Map.of("type", "toxic_language", "action", "flag")), out);

        assertThat(error).isNull();
        assertThat(out).extracting(r -> r.get("id")).containsExactly("keyword_filter_1", "toxic_language_2");
        assertThat(out.get(0)).containsEntry("action", "block");
        assertThat(out.get(1)).containsEntry("action", "flag");
    }

    @Test
    @DisplayName("an unknown type, an unknown action and a missing config field are refused with the rule index")
    void refusals() {
        assertThat(parse(List.of(Map.of("type", "spam")), new ArrayList<>())).contains("rules[0].type");
        assertThat(parse(List.of(Map.of("type", "toxic_language", "action", "redact")), new ArrayList<>()))
            .contains("rules[0].action");
        assertThat(parse(List.of(Map.of("type", "keyword_filter", "config", Map.of())), new ArrayList<>()))
            .contains("keywordsExpression");
        assertThat(parse(List.of(Map.of("type", "length_check", "config", Map.of())), new ArrayList<>()))
            .contains("minLength");
    }

    @Test
    @DisplayName("a regex that does not compile is refused at build time; a templated one is left to the run")
    void regexCompiles() {
        assertThat(parse(List.of(Map.of("type", "regex_pattern", "config", Map.of("pattern", "(oops"))), new ArrayList<>()))
            .contains("does not compile");
        assertThat(parse(List.of(Map.of("type", "regex_pattern",
            "config", Map.of("pattern", "{{core:cfg.output.pattern}}"))), new ArrayList<>())).isNull();
    }

    @Test
    @DisplayName("duplicate ids are refused")
    void duplicateIds() {
        assertThat(parse(List.of(
            Map.of("id", "a", "type", "toxic_language"),
            Map.of("id", "a", "type", "prompt_injection")), new ArrayList<>())).contains("used twice");
    }
}
