package com.apimarketplace.agent.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * The app surface a {@link ModelExecutionLinkEntity} applies to (CLOUD-only).
 *
 * <p>A link maps a billed {@code (provider, model)} pair to an execution target.
 * The {@code scope} narrows WHEN that routing applies, keyed on the run's logical
 * origin ({@code AgentExecutionRequestDto.source}, surfaced at the resolve
 * chokepoint by {@code AgentRemoteExecutionService.resolveActivitySource}):
 *
 * <ul>
 *   <li>{@link #ALL} - wildcard, applies to every surface (the default and the
 *       backward-compatible behaviour: every pre-scope link is an ALL row).</li>
 *   <li>{@link #CHAT} - interactive general chat (source {@code CHAT}, alias
 *       {@code CONVERSATION}).</li>
 *   <li>{@link #WORKFLOW} - a workflow agent node.</li>
 *   <li>{@link #WEBHOOK} / {@link #WIDGET} / {@link #SCHEDULE} / {@link #TASK} /
 *       {@link #TASK_REVIEW} - the remaining standalone-agent surfaces.</li>
 * </ul>
 *
 * <p>Resolution is exact-surface first, then a fallback to the {@link #ALL} row,
 * so a surface-specific link overrides the wildcard for just that surface while
 * every other surface keeps the {@link #ALL} route. Guardrail, classify and
 * sub-agent runs never reach the chokepoint, so no scope can target them.
 *
 * <p>Two consumers can only ever match the {@link #ALL} wildcard, never a
 * surface scope: the browser agent and the {@code json-completion} path (neither
 * carries an activity source). The CE cloud relay consults NO links at all,
 * including {@link #ALL}: a linked CE install asked for the billed pair and must
 * get exactly that provider's real API. Note also that DISABLING an
 * exact-surface row does not park that surface on the billed model - the surface
 * reverts to the {@link #ALL} route when one exists.
 */
public enum ModelExecutionLinkScope {
    ALL,
    CHAT,
    WORKFLOW,
    WEBHOOK,
    WIDGET,
    SCHEDULE,
    TASK,
    TASK_REVIEW;

    /**
     * Parse an admin-supplied scope token (case-insensitive). Blank/absent ⇒
     * {@link #ALL}. Throws on an unknown token so the controller maps it to 400.
     */
    public static ModelExecutionLinkScope parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("scope must be one of " + Arrays.toString(values()));
        }
    }

    /**
     * Map a runtime activity source (the value from
     * {@code AgentRemoteExecutionService.resolveActivitySource}: {@code CHAT},
     * {@code CONVERSATION}, {@code WORKFLOW}, {@code WEBHOOK}, {@code WIDGET},
     * {@code SCHEDULE}, {@code TASK}, {@code TASK_REVIEW}) to the surface scope it
     * represents, or {@code null} when it matches no specific surface (only an
     * {@link #ALL} link can then apply). {@code CONVERSATION} is an alias of
     * {@link #CHAT}; {@link #ALL} is never an activity source so it maps to
     * {@code null}.
     */
    public static ModelExecutionLinkScope fromActivitySource(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String normalized = source.trim().toUpperCase(Locale.ROOT);
        if ("CONVERSATION".equals(normalized)) {
            return CHAT;
        }
        try {
            ModelExecutionLinkScope scope = valueOf(normalized);
            return scope == ALL ? null : scope;
        } catch (IllegalArgumentException e) {
            // Unknown token: deliberate for non-surface producers (e.g. SUB_AGENT),
            // but also what a typo or an untagged NEW surface degrades to -
            // wildcard-only matching. Leave a trail so that degradation is diagnosable.
            LoggerHolder.LOG.debug("Activity source '{}' matches no execution-link surface; only ALL links can apply", source);
            return null;
        }
    }

    /** Holder defers logger init so the enum class-load stays trivial. */
    private static final class LoggerHolder {
        private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ModelExecutionLinkScope.class);
    }
}
