package com.apimarketplace.conversation.service.ai.schema;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Stage 4a.3 - decides whether a given {@code (toolName, action)} pair must
 * receive the {@link SchemaMode#FULL} schema even when the model tier would
 * otherwise route it to {@link SchemaMode#SLIM}.
 *
 * <p><b>Resolution order</b> (first hit wins):
 * <ol>
 *   <li><b>Whole-tool always-full set</b> - tool name is in
 *   {@link JitExclusionProperties#getAlwaysFullTools()}. Used for tools
 *   where every action is sensitive (e.g. {@code request_credential}).</li>
 *   <li><b>Per-action always-full set</b> - {@code "tool:action"} (both
 *   lowercased) is in
 *   {@link JitExclusionProperties#getAlwaysFullToolActions()}. Used to pin
 *   individual marketplace/credential actions
 *   (e.g. {@code agent:publish}, {@code credential:rotate}).</li>
 *   <li><b>Positive-list regex</b> - the tool name OR the action string
 *   matches {@link JitExclusionProperties#getPositiveListPattern()}. Catches
 *   newly-added actions before anyone remembers to update the manual list
 *   (e.g. a future {@code invoice(action='charge')} auto-routes to FULL via
 *   the {@code charge} keyword).</li>
 * </ol>
 *
 * <p><b>Fail-safe posture.</b> If the configured regex is malformed, the
 * policy logs a warning at startup and <b>treats every call as excluded</b>
 * ({@link #isExcluded} returns {@code true}). Wrong guess toward FULL just
 * wastes tokens; wrong guess toward SLIM risks the LLM calling a sensitive
 * action with fabricated parameters. This is deliberate.
 *
 * <p><b>Stateless after startup.</b> Regex compiled once in
 * {@link #init()}; every {@link #isExcluded} call is hash-set lookups plus
 * a pre-compiled {@link Pattern#matcher} - O(1) per check modulo regex
 * length. Safe to call on every turn's tool-prefix build.
 *
 * <p><b>Not wired to {@link SchemaSlimmer} directly.</b> The policy is
 * consulted by the tools-prefix builder at a later stage alongside
 * {@link ModelTierMapper}; the caller decides whether to run
 * {@link SchemaSlimmer#minimize}. Policy stays pure and the exclusion
 * vocabulary lives in one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaSlimExclusionPolicy {

    private final JitExclusionProperties properties;

    /**
     * Pre-compiled positive-list regex. {@code null} means the configured
     * pattern failed to compile - the policy falls back to "always excluded"
     * in {@link #isExcluded}.
     */
    private Pattern positiveListPattern;

    /**
     * Set to {@code true} if the startup pattern compile failed; makes
     * {@link #isExcluded} return {@code true} unconditionally. Kept
     * separate from {@code positiveListPattern == null} because a valid
     * config could have an empty pattern (opt-out of regex but keep the
     * manual list), whereas a failed-compile config must fail safe.
     */
    private boolean patternCompileFailed = false;

    @PostConstruct
    void init() {
        // Reset state so a second manual call to init() (e.g. from tests) starts clean
        // rather than carrying stale patternCompileFailed from a prior run.
        this.positiveListPattern = null;
        this.patternCompileFailed = false;

        String raw = properties.getPositiveListPattern();
        if (raw == null || raw.isBlank()) {
            // Explicit opt-out: only manual list is consulted. Fine.
            return;
        }
        try {
            this.positiveListPattern = Pattern.compile(raw);
        } catch (Exception e) {
            log.warn("SchemaSlimExclusionPolicy: positive-list regex failed to compile ({}) - failing safe to ALWAYS-EXCLUDED",
                    e.getMessage());
            this.patternCompileFailed = true;
        }
    }

    /**
     * Whole-tool exclusion check - returns {@code true} if <em>any</em>
     * call to this tool must use the full schema. Useful for the tools-prefix
     * builder that decides per-tool whether to slim the whole definition or
     * leave it intact.
     *
     * <p><b>Does not consult per-action entries.</b> A tool present only in
     * {@link JitExclusionProperties#getAlwaysFullToolActions()} returns
     * {@code false} here - callers that need per-action resolution must use
     * {@link #isExcluded(String, String)} directly.
     *
     * @param toolName exact tool name as registered (not normalised);
     *                 {@code null} → {@code false} (nothing to look up)
     */
    public boolean isToolAlwaysFull(String toolName) {
        if (patternCompileFailed) return true;
        if (toolName == null) return false;
        if (properties.getAlwaysFullTools().contains(toolName)) return true;
        if (positiveListPattern != null && positiveListPattern.matcher(toolName).find()) {
            return true;
        }
        return false;
    }

    /**
     * Per-action exclusion check - returns {@code true} if this
     * {@code (toolName, action)} pair must use the full schema. Called for
     * every action on a tool that's not in {@link #isToolAlwaysFull}.
     *
     * <p>{@code null} or blank {@code action} → falls back to the whole-tool
     * check (a tool without an action is effectively a single action).
     *
     * @param toolName exact tool name as registered; {@code null} → {@code false}
     * @param action   action enum value (e.g. {@code "publish"}, {@code "rotate"})
     */
    public boolean isExcluded(String toolName, String action) {
        if (patternCompileFailed) return true;
        if (toolName == null) return false;

        // 1. Whole-tool always-full wins.
        if (properties.getAlwaysFullTools().contains(toolName)) return true;

        // 2. Action-specific lookup.
        if (action != null && !action.isBlank()) {
            String key = toolName.toLowerCase(Locale.ROOT) + ":" + action.toLowerCase(Locale.ROOT);
            if (properties.getAlwaysFullToolActions().contains(key)) return true;
        }

        // 3. Positive-list regex - tested against the tool name AND the action
        // string separately so either hit forces FULL. Matches "substring"
        // semantics (Matcher#find, not #matches) so the \b anchors in the
        // default pattern behave as word boundaries inside longer identifiers.
        if (positiveListPattern != null) {
            if (positiveListPattern.matcher(toolName).find()) return true;
            if (action != null && !action.isBlank() && positiveListPattern.matcher(action).find()) return true;
        }

        return false;
    }
}
