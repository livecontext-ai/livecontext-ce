package com.apimarketplace.agent.compaction;

import java.util.Set;

/**
 * Stage 3.7.4 - action-level allow/deny lists for client-side COLD
 * masking. Complements the facade-level
 * {@link ContextCompactionTools} (Stage 1b.2), which drives Anthropic's
 * server-side {@code clear_tool_uses_20250919} edit at a coarser
 * granularity.
 *
 * <p><b>Why two granularities.</b> Anthropic's knob accepts
 * <em>tool names</em> only (facades such as {@code catalog}). If one
 * action on a facade is sensitive, the whole facade must be kept. Our
 * client-side masker, running in the Gemini / OpenAI / Ollama path,
 * has no such restriction - it can preview-collapse {@code
 * catalog.search} while leaving {@code catalog.execute} untouched. The
 * extra granularity is worth it: {@code catalog} is one of the biggest
 * tool-result payload producers, and blanket-excluding it (the
 * Claude-side stance) costs real tokens on the other providers.
 *
 * <p><b>Never-mask invariant</b> (U9 / {@code
 * NeverMaskSecretsInvariantTest}). Any {@code (tool, action)} pair
 * present in {@link #NEVER_MASK_ACTIONS} stays full-length in every
 * zone, including COLD. These carry either credentials, publish
 * side-effects, or user-visible receipts whose silent truncation would
 * change semantics. The pair {@code catalog.execute} is the canonical
 * example: request bodies routinely contain {@code api_key},
 * {@code access_token}, or PII that later turns read back verbatim -
 * rewriting the stored input into a preview would return wrong data
 * on replay.
 *
 * <p><b>Not exhaustive by design.</b> A newly-registered action is
 * safe by default: it doesn't enter {@link #COMPACTABLE_ACTIONS} until
 * explicitly audited. Same fail-safe posture as the Stage 1b.2
 * facade-level lists.
 */
public final class CompactionActionAllowlist {

    /**
     * {@code "tool.action"} pairs whose tool-result {@code content}
     * may be preview-collapsed in the COLD zone. These are read-only
     * exploratory surfaces - their results duplicate information the
     * agent can re-derive by calling {@code get_tool_result(id=…)}
     * when deep history becomes relevant again.
     */
    public static final Set<String> COMPACTABLE_ACTIONS = Set.of(
            "catalog.search",
            "web_search.search",
            "web_search.fetch",
            "workflow.list",
            "workflow.help",
            "agent.list",
            "skill.list",
            "interface.list",
            "table.query_rows"
    );

    /**
     * {@code "tool.action"} pairs that MUST stay full-length even in
     * the COLD zone. Credentials, publish side-effects,
     * catalog.execute (which may carry secrets in request bodies),
     * and user-visible receipts whose silent truncation would change
     * semantics.
     */
    public static final Set<String> NEVER_MASK_ACTIONS = Set.of(
            "credential.create",
            "credential.get",
            "credential.update",
            "credential.delete",
            "publish.publish",
            "publish.unpublish",
            "catalog.execute"
    );

    private CompactionActionAllowlist() {}

    /**
     * Is {@code toolName.actionName} safe to preview-collapse in the
     * COLD zone? Returns {@code false} for unknown pairs - same
     * fail-safe posture as {@link ContextCompactionTools}: unknown ⇒
     * preserve full payload.
     *
     * @param toolName   facade name (e.g. {@code "catalog"}). Never
     *                   {@code null}; blank → not compactable.
     * @param actionName action within the facade (e.g. {@code
     *                   "search"}). {@code null} or blank → not
     *                   compactable (we don't guess).
     */
    public static boolean isCompactable(String toolName, String actionName) {
        if (toolName == null || toolName.isBlank()) return false;
        if (actionName == null || actionName.isBlank()) return false;
        return COMPACTABLE_ACTIONS.contains(toolName + "." + actionName);
    }

    /**
     * Must {@code toolName.actionName} stay full-length even in the
     * COLD zone? The inverse of {@link #isCompactable} - a {@code
     * true} here is an <em>explicit</em> never-mask marker; absence
     * from {@link #COMPACTABLE_ACTIONS} already suffices today, but
     * listing the dangerous pairs keeps intent readable and triggers
     * the invariant test ({@code NeverMaskSecretsInvariantTest}) on
     * accidental widening.
     */
    public static boolean isNeverMask(String toolName, String actionName) {
        if (toolName == null || toolName.isBlank()) return false;
        if (actionName == null || actionName.isBlank()) return false;
        return NEVER_MASK_ACTIONS.contains(toolName + "." + actionName);
    }
}
