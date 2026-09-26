package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.services.agent.GuardrailRuleEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentAsyncCompletionService guardrail merge")
class AgentAsyncCompletionGuardrailMergeTest {

    @Test
    @DisplayName("the deterministic verdict decided before the enqueue overrides a model that passed the content")
    void precomputedViolationFailsTheNode() {
        var outcome = GuardrailRuleEvaluator.evaluate(List.of(
            Map.of("id", "kw", "type", "keyword_filter", "action", "block",
                "config", Map.of("keywordsExpression", "refund")),
            Map.of("id", "tox", "type", "toxic_language", "action", "block", "config", Map.of())),
            "I want a refund", v -> v);
        Map<String, Object> output = new HashMap<>(Map.of(
            "passed", true, "violations", List.of(), "details", Map.of(), "sanitized", "I want a refund",
            "response", "Content passed all checks"));

        AgentAsyncCompletionService.mergeGuardrailPrecomputed(output, outcome.toMap());

        assertThat(output).containsEntry("passed", false);
        assertThat((List<Object>) output.get("violations")).containsExactly("kw");
        assertThat(output).as("the worker's summary counted only the model's rules").doesNotContainKey("response");
    }

    @Test
    @DisplayName("a sanitize rule's redaction is applied to the model's sanitized text")
    void sanitizeAppliedOnModelText() {
        var outcome = GuardrailRuleEvaluator.evaluate(List.of(
            Map.of("id", "pii", "type", "pii_detection", "action", "sanitize",
                "config", Map.of("piiTypes", List.of("email"))),
            Map.of("id", "tox", "type", "toxic_language", "action", "flag", "config", Map.of())),
            "write to jane@example.com", v -> v);
        Map<String, Object> output = new HashMap<>(Map.of(
            "passed", true, "violations", List.of(), "details", Map.of(), "sanitized", "write to jane@example.com"));

        AgentAsyncCompletionService.mergeGuardrailPrecomputed(output, outcome.toMap());

        assertThat(output).as("the sanitized email is still a violation").containsEntry("passed", false);
        assertThat(output.get("sanitized")).isEqualTo("write to [REDACTED]");
    }
}
