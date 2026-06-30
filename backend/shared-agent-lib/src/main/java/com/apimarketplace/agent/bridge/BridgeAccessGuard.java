package com.apimarketplace.agent.bridge;

import com.apimarketplace.agent.factory.BridgeAvailabilityFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces per-user access control for CLI bridge providers.
 *
 * <p>CLI bridges (Claude Code, Codex, Gemini CLI, Mistral Vibe) run on a
 * SHARED subscription (the admin's Claude Pro / ChatGPT Plus account). Without
 * a gate, any user who selects such a model can drain the rate limit and
 * break the bridge for everyone - hence opt-in by default + per-bridge policy
 * table in auth-service (V118).
 *
 * <p>The guard is a thin wrapper around {@link BridgeAccessClient}: it
 * recognises whether a provider name is a bridge via
 * {@link BridgeAvailabilityFilter#BRIDGE_PROVIDER_TO_CLI_ID} (single source of
 * truth - no duplicated list) and short-circuits for non-bridge providers
 * (OpenAI, Anthropic, Mistral, Google, DeepSeek…) with a synthetic allow.
 *
 * <p>Call-site contract: invoke
 * {@link #enforce(String, String, String, boolean)} immediately before
 * dispatching the LLM call. On deny it throws {@link BridgeAccessDeniedException}
 * (a subtype of {@code LLMProviderException}) - the existing error path in
 * {@code AgentLoopService} / provider layer surfaces it as a 403/429.
 */
public class BridgeAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(BridgeAccessGuard.class);

    /** Matches {@code BridgeAccessDecision.REASON_GUARD_UNAVAILABLE}. Kept as a local constant
     *  so we don't cycle through the decision factory just to throw a typed denial. */
    private static final String REASON_GUARD_UNAVAILABLE = "guard_unavailable";

    private final BridgeAccessClient client;
    private final boolean failClosedWhenClientAbsent;

    /**
     * Default constructor: fail-CLOSED when the client is unwired. This is the
     * correct behaviour for the CE monolith and any deployment where bridges
     * are installed - a misconfigured bean wiring must NOT become a silent
     * bypass of the admin-subscription quota.
     */
    public BridgeAccessGuard(BridgeAccessClient client) {
        this(client, true);
    }

    /**
     * Explicit opt-out constructor for deployments that truly have no bridge
     * providers (cloud agent-service). Pass {@code false} to preserve the
     * pre-fail-closed "silently allow" behaviour for those environments only.
     *
     * @param client                       access client, may be null when
     *                                     {@code failClosedWhenClientAbsent=false}
     * @param failClosedWhenClientAbsent   true → a bridge dispatch with a null
     *                                     client throws {@code guard_unavailable};
     *                                     false → silently allow
     */
    public BridgeAccessGuard(BridgeAccessClient client, boolean failClosedWhenClientAbsent) {
        this.client = client;
        this.failClosedWhenClientAbsent = failClosedWhenClientAbsent;
    }

    /**
     * @return true iff {@code providerName} is one of the known CLI bridges.
     * Non-bridges (openai, anthropic, mistral, …) skip the auth-service round-trip.
     */
    public static boolean isBridgeProvider(String providerName) {
        if (providerName == null) return false;
        return BridgeAvailabilityFilter.BRIDGE_PROVIDER_TO_CLI_ID.containsKey(providerName.toLowerCase());
    }

    /**
     * Check access without counter side-effect. Use for pre-flight filtering
     * of the UI catalog (model picker) so counters reflect real dispatches only.
     */
    public BridgeAccessDecision check(String userId, String userRoles, String providerName) {
        if (!isBridgeProvider(providerName)) {
            return BridgeAccessDecision.allow(providerName, null);
        }
        if (client == null) {
            // No client wired - e.g. cloud agent-service where bridges are absent
            // anyway. Return allow so regular providers remain unaffected; if a
            // bridge call leaks into this code path it will still fail at the
            // CLI-availability layer.
            return BridgeAccessDecision.allow(providerName, null);
        }
        return client.check(userId, userRoles, providerName.toLowerCase(), false);
    }

    /**
     * Enforce access for an imminent dispatch. Increments today's counter on
     * allow, throws {@link BridgeAccessDeniedException} on deny.
     *
     * @param userId         caller's user id (Keycloak sub / provider id)
     * @param userRoles      comma-separated roles; at least {@code USER} or {@code ADMIN}
     * @param providerName   provider key (from LLM provider factory)
     * @param incrementUsage true → count this call against today's quota
     * @throws BridgeAccessDeniedException if access is denied
     */
    public void enforce(String userId,
                        String userRoles,
                        String providerName,
                        boolean incrementUsage) {
        if (!isBridgeProvider(providerName)) {
            return; // non-bridge providers are never gated by this guard
        }
        if (client == null) {
            if (failClosedWhenClientAbsent) {
                // The bean was wired but its client is null - in any deployment that
                // advertises a bridge provider, this must deny rather than silently
                // allow, otherwise a misconfig turns into an unbounded subscription drain.
                log.warn("Bridge access DENIED (guard_unavailable): no client wired for provider={} user={}",
                        providerName, userId);
                throw new BridgeAccessDeniedException(providerName, REASON_GUARD_UNAVAILABLE, null);
            }
            log.debug("BridgeAccessGuard: no client wired and opt-out mode is on, skipping check for provider={}",
                    providerName);
            return;
        }

        BridgeAccessDecision decision = client.check(
                userId, userRoles, providerName.toLowerCase(), incrementUsage);

        if (!decision.allowed()) {
            log.info("Bridge access DENIED: user={} provider={} reason={}",
                    userId, providerName, decision.reason());
            throw new BridgeAccessDeniedException(
                    providerName, decision.reason(), decision.remainingRequestsToday());
        }
        if (log.isDebugEnabled()) {
            log.debug("Bridge access ALLOWED: user={} provider={} remainingToday={}",
                    userId, providerName, decision.remainingRequestsToday());
        }
    }
}
