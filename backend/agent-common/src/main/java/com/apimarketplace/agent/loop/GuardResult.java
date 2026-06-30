package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.AgentStopReason;

/**
 * Outcome of a {@link PreIterationGuard#check(IterationContext)} call.
 *
 * <p>Immutable. Returned by every guard. When {@link #proceed()} is {@code false},
 * the loop stops and {@link #stopReason()} is propagated to the run.</p>
 *
 * @param proceed       {@code true} → loop continues, {@code false} → loop stops
 * @param stopReason    reason to record on the run; {@code null} when proceed is true
 * @param scope         optional scope label for metadata (e.g. {@code "tenant"}, {@code "agent"})
 * @param denialReason  optional human-readable explanation, surfaced to logs and metrics
 */
public record GuardResult(
    boolean proceed,
    AgentStopReason stopReason,
    String scope,
    String denialReason
) {
    private static final GuardResult ALLOW = new GuardResult(true, null, null, null);

    /** Singleton allow result. */
    public static GuardResult allow() {
        return ALLOW;
    }

    /**
     * Build a deny result.
     *
     * @param reason       the stop reason to record (must not be null)
     * @param scope        scope tag for metadata; may be null
     * @param denialReason human-readable explanation; may be null
     */
    public static GuardResult deny(AgentStopReason reason, String scope, String denialReason) {
        if (reason == null) {
            throw new IllegalArgumentException("stopReason is required when denying");
        }
        return new GuardResult(false, reason, scope, denialReason);
    }
}
