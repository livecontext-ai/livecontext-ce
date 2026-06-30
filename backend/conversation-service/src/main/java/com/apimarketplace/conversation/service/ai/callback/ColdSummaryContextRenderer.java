package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.agent.summary.ColdSummaryEnvelope;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Stage 5.5 - renders the persisted COLD compaction summary
 * ({@code conversation.conversations.summary_cold}) into a compact, clearly
 * labelled system-prompt block so the LLM can recall what happened in turns
 * that have since been masked out of the HOT/WARM history window.
 *
 * <p><b>Why a static utility (not a {@code @Component}).</b> The renderer is a
 * pure {@code Map -> String} transform with no collaborators, so injecting it
 * would only add construction churn to {@link AgentContextBuilder}'s twelve
 * existing constructor dependencies (and every test that news it up). It is
 * called from exactly one place - block 6 of the layered system prompt - which
 * is the single layer BOTH chat paths share (direct-API via
 * {@code AgentLoopContext.systemBlocks}, bridge/CLI via the flattened
 * {@code systemPrompt} string). Centralising here means both paths recall the
 * same distilled context without per-path wiring.
 *
 * <p><b>Robustness contract.</b> The input is the raw JSONB {@code Map} exactly
 * as Jackson deserialised the {@code jsonb} column - NOT a
 * {@link com.apimarketplace.agent.summary.ColdSummaryEnvelope}, whose
 * {@code @JsonCreator} would throw on a legacy/partial row missing
 * {@code generated_at} or {@code model}. Every field is read defensively with
 * {@code instanceof} guards (mirroring
 * {@code ConversationMapper#buildCompactionMarker}); a null, empty, or
 * malformed summary yields {@code ""} so a bad COLD row can never break a main
 * turn. Short conversations have a {@code null} {@code summary_cold} and so this
 * naturally renders nothing - block 6 stays empty and the call is a no-op.
 *
 * <p>Output is bounded: per-list entry caps plus a hard total-character ceiling
 * keep a runaway summary from inflating the prompt.
 */
final class ColdSummaryContextRenderer {

    private ColdSummaryContextRenderer() {
    }

    // Per-section entry caps - generous enough to preserve recall, tight enough
    // that a pathological summary can't dominate the prompt.
    private static final int MAX_INTENTS = 12;
    private static final int MAX_DECISIONS = 20;
    private static final int MAX_IDS = 40;
    private static final int MAX_ERRORS = 15;
    private static final int MAX_HELPED = 20;

    /** Hard ceiling on a single rendered line. */
    private static final int MAX_LINE_CHARS = 300;
    /** Hard ceiling on the whole block - a final guard against a runaway row. */
    private static final int MAX_TOTAL_CHARS = 6000;

    /** Pre-compiled once: collapse any whitespace run so one entry stays one line. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final String HEADER =
        "[DURABLE CONTEXT - distilled summary of earlier turns]\n"
        + "This conversation was compacted: earlier turns are no longer shown in full. "
        + "The summary below is an authoritative recollection of what already happened - "
        + "trust it for prior decisions, resolved IDs, and completed actions, and do NOT "
        + "redo work or re-ask for information already captured here.";

    /**
     * Status-aware variant: when the stored envelope is flagged
     * {@code status="stale"} (the COLD zone shrank under it, or the user
     * corrected course and no regeneration has landed yet), the summary is
     * still worth recalling - losing it entirely would be worse - but it
     * must not carry the authoritative "trust it / do NOT redo work"
     * framing. Absent or unrecognised statuses keep the authoritative
     * header (rows written before the status field existed are active by
     * definition).
     */
    private static final String HEADER_STALE =
        "[DURABLE CONTEXT - distilled summary of earlier turns - MAY BE PARTIALLY OUTDATED]\n"
        + "This conversation was compacted: earlier turns are no longer shown in full. "
        + "The summary below reflects an earlier state of the conversation and may no "
        + "longer be fully accurate (the conversation has since changed course). Treat it "
        + "as background context: where it conflicts with the visible recent turns, the "
        + "recent turns win, and verify before relying on it for decisions or IDs.";

    /**
     * Render the COLD summary block, or {@code ""} when there is nothing useful
     * to inject. Never throws.
     *
     * @param summaryCold the raw {@code summary_cold} JSONB map (may be null)
     * @return a bounded system-prompt block, or {@code ""}
     */
    static String render(Map<String, Object> summaryCold) {
        if (summaryCold == null || summaryCold.isEmpty()) {
            return "";
        }
        try {
            StringBuilder body = new StringBuilder();

            appendStringList(body, "User intents", summaryCold.get("user_intents"), MAX_INTENTS);
            appendDecisions(body, summaryCold.get("decisions"));
            appendIds(body, summaryCold.get("ids_resolved"));
            appendErrors(body, summaryCold.get("errors_resolved"));
            appendStringList(body, "Actions already completed", summaryCold.get("helped_actions"), MAX_HELPED);

            if (body.length() == 0) {
                // Summary present but carried no usable content (all sections
                // empty/malformed) - nothing worth injecting.
                return "";
            }

            String header = ColdSummaryEnvelope.STATUS_STALE.equals(summaryCold.get("status"))
                ? HEADER_STALE : HEADER;
            StringBuilder out = new StringBuilder(header.length() + body.length() + 32);
            out.append(header);
            String coverage = renderCoverage(summaryCold.get("turns_covered"));
            if (!coverage.isEmpty()) {
                out.append(' ').append(coverage);
            }
            out.append('\n').append(body);

            String result = out.toString().stripTrailing();
            if (result.length() > MAX_TOTAL_CHARS) {
                result = result.substring(0, MAX_TOTAL_CHARS).stripTrailing()
                    + "\n… (summary truncated)";
            }
            return result;
        } catch (Exception e) {
            // Defence in depth: a malformed row must never break a main turn.
            return "";
        }
    }

    /** "(covers turns 1-14)" - omitted when turns_covered is absent/unusable. */
    private static String renderCoverage(Object turnsRaw) {
        if (!(turnsRaw instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Integer min = null;
        Integer max = null;
        for (Object o : list) {
            if (o instanceof Number n) {
                int v = n.intValue();
                if (min == null || v < min) min = v;
                if (max == null || v > max) max = v;
            }
        }
        if (min == null) {
            return "";
        }
        return min.equals(max)
            ? "(covers turn " + min + ")"
            : "(covers turns " + min + "-" + max + ")";
    }

    private static void appendStringList(StringBuilder body, String label, Object raw, int cap) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        StringBuilder section = new StringBuilder();
        int count = 0;
        for (Object o : list) {
            if (count >= cap) break;
            String text = asLine(o);
            if (text == null) continue;
            section.append("  - ").append(text).append('\n');
            count++;
        }
        if (count > 0) {
            body.append('\n').append(label).append(":\n").append(section);
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendDecisions(StringBuilder body, Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        StringBuilder section = new StringBuilder();
        int count = 0;
        for (Object o : list) {
            if (count >= MAX_DECISIONS) break;
            if (!(o instanceof Map<?, ?> map)) continue;
            String text = asLine(((Map<String, Object>) map).get("decision"));
            if (text == null) continue;
            Object turn = ((Map<String, Object>) map).get("turn");
            if (turn instanceof Number n) {
                section.append("  - (turn ").append(n.intValue()).append(") ").append(text).append('\n');
            } else {
                section.append("  - ").append(text).append('\n');
            }
            count++;
        }
        if (count > 0) {
            body.append("\nKey decisions:\n").append(section);
        }
    }

    private static void appendIds(StringBuilder body, Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return;
        }
        StringBuilder section = new StringBuilder();
        int count = 0;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (count >= MAX_IDS) break;
            String key = asLine(e.getKey());
            String val = asLine(e.getValue());
            if (key == null || val == null) continue;
            section.append("  - ").append(key).append(": ").append(val).append('\n');
            count++;
        }
        if (count > 0) {
            body.append("\nResolved identifiers (name → value):\n").append(section);
        }
    }

    @SuppressWarnings("unchecked")
    private static void appendErrors(StringBuilder body, Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        StringBuilder section = new StringBuilder();
        int count = 0;
        for (Object o : list) {
            if (count >= MAX_ERRORS) break;
            if (!(o instanceof Map<?, ?> map)) continue;
            String error = asLine(((Map<String, Object>) map).get("error"));
            String resolution = asLine(((Map<String, Object>) map).get("resolution"));
            if (error == null && resolution == null) continue;
            section.append("  - ")
                .append(error == null ? "(unknown error)" : error)
                .append(" → ")
                .append(resolution == null ? "(no resolution recorded)" : resolution)
                .append('\n');
            count++;
        }
        if (count > 0) {
            body.append("\nResolved errors:\n").append(section);
        }
    }

    /**
     * Coerce an arbitrary JSON scalar to a single trimmed, length-capped line,
     * or {@code null} when it carries no usable text.
     */
    private static String asLine(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) {
            return null;
        }
        // Collapse newlines so one entry stays one line.
        text = WHITESPACE.matcher(text).replaceAll(" ");
        if (text.length() > MAX_LINE_CHARS) {
            text = text.substring(0, MAX_LINE_CHARS).stripTrailing() + "…";
        }
        return text;
    }
}
