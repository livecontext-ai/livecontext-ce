package com.apimarketplace.orchestrator.services.agent;

/**
 * Sidecar record carrying the per-agent runtime overrides that don't fit on
 * the workflow {@code Agent} record (kept stable to avoid touching the 50+
 * positional test sites). Populated by {@link AgentConfigResolver#resolve}
 * from the {@code AgentDto} returned by agent-service, attached to the
 * {@code AgentNode} via setter so the node reads them without re-fetching
 * the DTO.
 *
 * <p>All fields are nullable. {@code null} means "no override" - the
 * downstream loop falls back to the platform default
 * ({@code AgentDefaultsConfig}) just as it did before this record existed.
 */
public record AgentRuntimeOverrides(
    Integer executionTimeout,
    Integer loopIdenticalStop,
    Integer loopConsecutiveStop,
    /**
     * Per-agent reasoning-effort override for CLI/bridge providers
     * ({@code minimal|low|medium|high|xhigh}). {@code null} ⇒ no agent-level
     * override; the per-model admin default (resolved downstream in
     * agent-service) or the CLI's own default applies.
     */
    String reasoningEffort,

    /**
     * Per-agent inactivity watchdog window in seconds. {@code null} ⇒ no override (the loop uses the
     * platform 5-minute default); {@code 0} ⇒ disabled; {@code 10-7200} ⇒ custom. Carried down to
     * agent-service / the bridge via the {@code __inactivityTimeoutSeconds__} credential set by
     * {@code AgentNode}, so it never had to be threaded through the positional execution DTO.
     */
    Integer inactivityTimeout) {

    public static final AgentRuntimeOverrides EMPTY = new AgentRuntimeOverrides(null, null, null, null, null);
}
