package com.apimarketplace.agent.domain;

import java.util.Locale;

/**
 * Categorical reasoning-effort intent for the providers that honor it: the
 * CLI-backed bridges (claude-code, codex) and the direct Anthropic API.
 *
 * <p><strong>GENERATED FILE - do not edit by hand.</strong> Source of truth:
 * {@code shared/contracts/reasoning-effort.json}. Re-run
 * {@code node shared/contracts/scripts/generate-reasoning-effort.js} after editing the JSON.
 *
 * <p>Mapped to each consumer's concrete knob at the leaf: Codex
 * {@code -c model_reasoning_effort=<level>}, Claude Code the
 * {@code CLAUDE_CODE_EFFORT_LEVEL} env, and the direct Anthropic API
 * {@code output_config.effort} ({@code ClaudeProvider}, clamped per model).
 * The canonical {@link #wire()} value is the lowercase level string.
 * Unknown/unsupported levels are dropped or clamped at the leaf so the
 * consumer falls back to its own default. Precedence (per-conversation
 * override > per-agent > per-model default) is handled by the hand-written
 * {@code ReasoningEffortResolver}.
 */
public enum ReasoningEffort {
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX;

    /**
     * Tolerant parse: trims, upper-cases, and matches an enum constant. Returns
     * {@code null} for {@code null}, blank, or unrecognized input - callers
     * decide what null means (validation rejects it; the resolver skips it).
     */
    public static ReasoningEffort fromString(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return null;
        }
        for (ReasoningEffort level : values()) {
            if (level.name().equals(normalized)) {
                return level;
            }
        }
        return null;
    }

    /**
     * Whether {@code raw} parses to a known level. {@code null}/blank is treated
     * as "valid" here (it means "inherit / no override"); only a non-blank value
     * that fails to parse is invalid. Use at API/entity write boundaries.
     */
    public static boolean isValidOrBlank(String raw) {
        return raw == null || raw.trim().isEmpty() || fromString(raw) != null;
    }

    /** Canonical lowercase wire value the CLIs expect: {@code "minimal"}, {@code "high"}, … */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Comma-separated canonical wire values, for validation error messages.
     * Derived from the enum so the message can never drift from the contract.
     */
    public static String validValuesCsv() {
        StringBuilder sb = new StringBuilder();
        for (ReasoningEffort level : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(level.wire());
        }
        return sb.toString();
    }
}
