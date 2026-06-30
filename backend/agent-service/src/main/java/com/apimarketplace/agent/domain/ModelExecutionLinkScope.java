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
            return null;
        }
    }
}
