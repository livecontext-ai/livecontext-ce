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
    Integer inactivityTimeout,

    /**
     * Per-agent tool-authorization requirement (V299), resolved here and DELIBERATELY NOT
     * carried down.
     *
     * <p>{@code TRUE} means "ask permission for a sensitive action wherever this agent runs",
     * and {@code ToolAuthorizationScope.isCardRaised} does honour it: the credential
     * {@code __requireToolAuthorization__} forces the card back on at every exit, including
     * inside a workflow node. {@code AgentNode} could set it the way it sets
     * {@link #inactivityTimeout}, in one line, and must not.
     *
     * <p>An agent running inside a workflow node executes with NO stream id (the node's stream
     * channel rides on the request payload), and {@code parkForAuthorization} returns at its
     * {@code streamId == null} guard before it reaches the chat delivery. Setting the flag there
     * would refuse every sensitive action while asking nobody, anywhere: a run stopped by a
     * question that was never put. Carrying it becomes correct once those executions have a
     * conversation and a stream to park on, and not before. See the project docs, "Two
     * contexts are deliberately NOT covered".
     *
     * <p>Until then this component is the agent's stated intent travelling as far as it can go.
     * {@code AgentNodeRuntimeOverridesTest} pins that it goes no further, so the one-line
     * "fix" trips a test that explains why.
     */
    Boolean requireToolAuthorization) {

    /**
     * Back-compat constructor (no requireToolAuthorization) for existing call and test
     * sites, the same shape {@code AgentConfig} uses when a per-agent field is appended.
     */
    public AgentRuntimeOverrides(Integer executionTimeout, Integer loopIdenticalStop,
                                 Integer loopConsecutiveStop, String reasoningEffort,
                                 Integer inactivityTimeout) {
        this(executionTimeout, loopIdenticalStop, loopConsecutiveStop, reasoningEffort,
             inactivityTimeout, null);
    }

    public static final AgentRuntimeOverrides EMPTY =
        new AgentRuntimeOverrides(null, null, null, null, null, null);

    /** True when this agent must be asked about sensitive actions wherever it runs. */
    public boolean requiresToolAuthorization() {
        return Boolean.TRUE.equals(requireToolAuthorization);
    }
}
