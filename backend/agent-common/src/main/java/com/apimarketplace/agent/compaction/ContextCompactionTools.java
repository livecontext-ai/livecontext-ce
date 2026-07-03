package com.apimarketplace.agent.compaction;

import java.util.Set;

/**
 * Stage 1b.2 - canonical allowlist / exclude-list for long-context
 * compaction, shared between Claude server-side
 * {@code clear_tool_uses_20250919} (this stage) and the pending Stage 3
 * client-side COLD masking for other providers.
 *
 * <p><b>Why share.</b> Both features answer the same question - "which
 * tools' stored {@code tool_use.input} payloads can we safely discard
 * from deep history?" - and they must agree. A tool whose input is
 * preserved client-side but discarded server-side (or vice versa) would
 * mean identical conversations produce different context budgets on
 * different providers. Keeping the decision in one constant keeps
 * behaviour symmetric across the Claude and non-Claude code paths.
 *
 * <p><b>Tool names are unified-facade bare names</b> ({@code web_search},
 * {@code table}, {@code application}, …) - the same strings that appear
 * in the {@code tools[].name} field the provider emits. Actions are NOT
 * encoded here: the Claude API knob {@code clear_tool_inputs} is
 * per-tool, not per-action. A tool whose facade carries at least one
 * sensitive action (e.g. {@code workflow.publish},
 * {@code catalog.execute}) is excluded entirely from the allowlist -
 * we lose coverage on that facade's benign actions, but avoid clearing
 * inputs that downstream turns may still need to consult.
 *
 * <p><b>NEVER_MASK_TOOLS</b> goes into Anthropic's {@code exclude_tools}
 * parameter. The distinction with {@link #COMPACTABLE_TOOLS}: a tool
 * <em>absent</em> from {@code COMPACTABLE_TOOLS} is simply not cleared
 * by default; a tool in {@code NEVER_MASK_TOOLS} is an explicit
 * "even if a later change tries to include this, don't" marker. Keeping
 * both lists lets the clear_tool_uses edit specify the tools it
 * <em>will</em> clear <em>and</em> the ones it must <em>never</em>
 * clear, so a future refactor that accidentally widens
 * {@code COMPACTABLE_TOOLS} still won't clear credentials.
 *
 * <p><b>Not exhaustive by design.</b> This list reflects the tools that
 * exist today and whose semantics we've audited. A new tool added to
 * the registry is safe by default - it doesn't enter
 * {@code COMPACTABLE_TOOLS} until an explicit audit adds it. Same
 * fail-safe posture as {@code SchemaSlimExclusionPolicy}: unknown ⇒
 * preserve the full payload.
 */
public final class ContextCompactionTools {

    /**
     * Tool names whose stored {@code tool_use.input} payloads are safe
     * to clear from history once the conversation crosses the 180k
     * context-management trigger. These are read-only / exploratory
     * surfaces whose input arguments duplicate information already in
     * the user's messages or the response payload.
     */
    public static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "web_search",
            "skill",
            "interface",
            "table",
            "application"
    );

    /**
     * Tool names that must never have their inputs cleared - either the
     * input carries a credential, or a downstream turn reads the exact
     * input back (e.g. {@code catalog.execute} request bodies feeding
     * into response interpretation), or the tool has user-visible
     * publish / rotate / approve actions whose silent truncation would
     * change semantics. Explicit marker; absence from
     * {@link #COMPACTABLE_TOOLS} would already suffice today, but
     * listing these here keeps intent readable and fires the Claude
     * API's own guard on accidental widening.
     */
    public static final Set<String> NEVER_MASK_TOOLS = Set.of(
            "credential",
            // Legacy routing alias of `credential` (pre-rename sessions).
            "request_credential",
            "catalog",
            "workflow",
            "agent",
            "get_tool_result",
            "set_conversation_title"
    );

    private ContextCompactionTools() {}
}
