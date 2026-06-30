package com.apimarketplace.agent.summary;

import com.apimarketplace.agent.summary.ColdSummarizerPromptBuilder.Turn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5.2 - pin the summariser prompt shape. The JSON schema
 * instruction is the contract the summariser LLM must follow; drift
 * here means the envelope reader starts rejecting rows in production.
 */
@DisplayName("ColdSummarizerPromptBuilder - system prompt + user prompt shape (Stage 5.2)")
class ColdSummarizerPromptBuilderTest {

    // ---- system prompt ----

    @Test
    @DisplayName("system prompt enumerates every required envelope key")
    void systemPromptListsAllEnvelopeKeys() {
        String sys = ColdSummarizerPromptBuilder.systemPrompt();
        // Every key on the envelope side must appear in the prompt's
        // schema block. If someone adds a field to ColdSummaryEnvelope
        // without updating the prompt, the summariser stops emitting
        // that field and the round-trip silently loses data.
        assertThat(sys)
                .contains("\"decisions\"")
                .contains("\"ids_resolved\"")
                .contains("\"errors_resolved\"")
                .contains("\"user_intents\"")
                .contains("\"helped_actions\"");
    }

    @Test
    @DisplayName("system prompt forbids prose / code fences around the JSON")
    void systemPromptForbidsProseAndFences() {
        String sys = ColdSummarizerPromptBuilder.systemPrompt();
        assertThat(sys)
                .as("must tell the model to emit JSON only - not fenced, not explained")
                .containsIgnoringCase("ONLY")
                .containsIgnoringCase("no prose")
                .containsIgnoringCase("no code fences");
    }

    @Test
    @DisplayName("system prompt documents the secret-redaction rule")
    void systemPromptHasRedactionRule() {
        // Never-mask invariant also applies at the summariser boundary
        // - if the COLD zone briefly contained a leaked key, the
        // summary must not persist it. Pin that the prompt tells the
        // model to scrub.
        assertThat(ColdSummarizerPromptBuilder.systemPrompt())
                .containsIgnoringCase("redacted")
                .containsIgnoringCase("api key");
    }

    @Test
    @DisplayName("system prompt pins MAX_ENTRY_CHARS value so a refactor trips review")
    void systemPromptEmbedsMaxEntryChars() {
        // The cap is part of the prompt - embed the literal so a
        // future change to 200 or 1000 flashes on review.
        assertThat(ColdSummarizerPromptBuilder.MAX_ENTRY_CHARS).isEqualTo(400);
        assertThat(ColdSummarizerPromptBuilder.systemPrompt()).contains("400");
    }

    // ---- user prompt ----

    @Test
    @DisplayName("user prompt lists turns chronologically with [idx] role: body shape")
    void userPromptFormat() {
        String prompt = ColdSummarizerPromptBuilder.buildUserPrompt(List.of(
                new Turn(1, "USER",      "hi"),
                new Turn(2, "ASSISTANT", "hello, how can I help?"),
                new Turn(3, "USER",      "build a CRM")
        ));
        assertThat(prompt)
                .contains("[1] USER: hi")
                .contains("[2] ASSISTANT: hello, how can I help?")
                .contains("[3] USER: build a CRM");
        // Check ordering: 1 must appear before 2, 2 before 3.
        int p1 = prompt.indexOf("[1]");
        int p2 = prompt.indexOf("[2]");
        int p3 = prompt.indexOf("[3]");
        assertThat(p1).isLessThan(p2);
        assertThat(p2).isLessThan(p3);
    }

    @Test
    @DisplayName("user prompt begins with an instruction referencing the system prompt's JSON shape")
    void userPromptReferencesSystemShape() {
        // Defence-in-depth: repeat the "match the JSON shape" cue in
        // the user turn so the model can't miss it even on short
        // contexts.
        String prompt = ColdSummarizerPromptBuilder.buildUserPrompt(List.of(
                new Turn(1, "USER", "x")
        ));
        assertThat(prompt)
                .startsWith("Summarise these conversation turns")
                .contains("JSON shape");
    }

    @Test
    @DisplayName("user prompt handles null body entries as empty string (defensive)")
    void userPromptNullBody() {
        // The projector that turns Message into Turn might emit a null
        // body for a tool-use turn with no textual content. Pin the
        // defensive "" so we don't trip the prompt with 'null'.
        String prompt = ColdSummarizerPromptBuilder.buildUserPrompt(List.of(
                new Turn(1, "USER", null)
        ));
        assertThat(prompt).contains("[1] USER: ");
        assertThat(prompt).doesNotContain("null");
    }

    @Test
    @DisplayName("empty coldTurns throws - caller must gate via ColdSummaryGate first")
    void emptyColdTurnsThrows() {
        // Summariser calls cost tokens. An empty list means the caller
        // bypassed the gate. Pin the refusal so this can never sneak
        // through (the alert would be a credit-consumption anomaly
        // otherwise).
        assertThatThrownBy(() -> ColdSummarizerPromptBuilder.buildUserPrompt(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ColdSummaryGate");
    }

    @Test
    @DisplayName("null coldTurns throws NPE (record-style contract)")
    void nullColdTurnsThrows() {
        assertThatThrownBy(() -> ColdSummarizerPromptBuilder.buildUserPrompt(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Turn rejects null role (record invariant)")
    void turnRoleRequired() {
        assertThatThrownBy(() -> new Turn(0, null, "body"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("roleLabel");
    }
}
