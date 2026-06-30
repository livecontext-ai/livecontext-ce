package com.apimarketplace.agent.summary;

import java.util.List;
import java.util.Objects;

/**
 * Stage 5.2 - pure builder for the summariser's system + user prompts.
 *
 * <p>Separated from the service so the prompt shape is unit-testable
 * without standing up an LLM client. The service only wires this to a
 * provider; all semantics (what to keep, what to drop, what JSON shape
 * to demand back) live here.
 *
 * <p><b>Why a strict JSON schema in the system prompt.</b> The
 * summariser's output is consumed programmatically by
 * {@link ColdSummaryEnvelope}. A prose summary would re-introduce the
 * free-form-map problem the envelope was designed to solve. We spell the
 * expected keys out in the system prompt so Haiku / Sonnet / Gemini all
 * emit the same shape.
 */
public final class ColdSummarizerPromptBuilder {

    /**
     * Maximum character length for any single {@code decision} /
     * {@code error} / {@code intent} entry in the returned JSON.
     * Keeps pathological output from re-bloating the COLD zone we
     * just tried to compress. Mirrors Claude Code's
     * {@code COLD_SUMMARY_ENTRY_MAX_CHARS} budget.
     */
    public static final int MAX_ENTRY_CHARS = 400;

    static final String SYSTEM_PROMPT =
            """
            You are a conversation-summarisation specialist. Your job is to
            produce a **structured JSON summary** of the conversation turns
            I provide, covering only information that would still be useful
            if the raw turns were discarded.

            Output **ONLY** valid JSON matching this exact shape (no prose,
            no code fences):

            {
              "decisions":       [{"turn": <int>, "decision": "<text>"}, ...],
              "ids_resolved":    {"<name>": "<id>", ...},
              "errors_resolved": [{"error": "<text>", "resolution": "<text>"}, ...],
              "user_intents":    ["<text>", ...],
              "helped_actions":  ["<tool.action>", ...]
            }

            Rules:
              • Every text field: max %d characters, single line.
              • Omit a key entirely if its list/map would be empty.
              • `decisions`: include only choices that shaped the direction
                of the conversation (picked provider X over Y, dropped idea
                Z). Skip routine tool calls.
              • `ids_resolved`: only include IDs the user referenced by
                name and the agent resolved (e.g. "Jean's workflow" →
                "wf_abc123"). Skip auto-generated IDs the user never
                referenced.
              • `errors_resolved`: include only errors that were **fixed**
                in this window. Skip open errors.
              • `user_intents`: the user's goals, **as they stated them**.
                Don't paraphrase into product-speak.
              • `helped_actions`: dotted tool.action pairs the agent
                invoked to help (e.g. `agent.create`). Deduplicate.
              • Refuse to include raw secrets (API keys, tokens, PII).
                If you see one, replace with "<redacted>".
            """.formatted(MAX_ENTRY_CHARS);

    private ColdSummarizerPromptBuilder() {}

    /**
     * Build the user-side prompt: a chronologically-ordered dump of the
     * COLD-zone turns the summariser should compress.
     *
     * <p>{@code coldTurns} is expected to arrive oldest-first. Each
     * entry's {@link Turn#roleLabel()} becomes the line prefix; the text
     * body is passed through as-is (trust the caller's redaction).
     *
     * @param coldTurns turns in the COLD zone; must not be null or empty.
     * @return a single user-prompt string ready to hand to the
     *         summariser LLM.
     * @throws IllegalArgumentException if {@code coldTurns} is empty -
     *         a summariser call with no input would waste a token
     *         budget; caller must gate via {@link ColdSummaryGate}.
     */
    public static String buildUserPrompt(List<Turn> coldTurns) {
        Objects.requireNonNull(coldTurns, "coldTurns must not be null");
        if (coldTurns.isEmpty()) {
            throw new IllegalArgumentException(
                    "buildUserPrompt: coldTurns is empty - gate via ColdSummaryGate first");
        }
        StringBuilder sb = new StringBuilder(
                "Summarise these conversation turns into the JSON shape described in the system prompt.\n\n");
        for (Turn t : coldTurns) {
            sb.append('[').append(t.turnIndex()).append("] ")
              .append(t.roleLabel()).append(": ")
              .append(t.body() == null ? "" : t.body())
              .append('\n');
        }
        return sb.toString();
    }

    /**
     * Returns the pinned system prompt. Exposed as a getter (not a
     * constant) so tests can assert the contents and so a future
     * swap-for-translation wouldn't require a source edit.
     */
    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Minimal turn carrier - the caller projects its domain Message
     * into this shape. Keeps the builder agnostic of which service
     * owns the message entity.
     */
    public record Turn(int turnIndex, String roleLabel, String body) {
        public Turn {
            Objects.requireNonNull(roleLabel, "roleLabel must not be null");
        }
    }
}
