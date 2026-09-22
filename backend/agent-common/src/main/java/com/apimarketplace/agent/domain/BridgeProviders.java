package com.apimarketplace.agent.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Canonical names of the local-CLI "bridge" providers.
 *
 * <p>A bridge provider is NOT an API provider: it runs a CLI binary on the
 * bridge host (Claude Code, Codex, Gemini CLI, Mistral Vibe) instead of calling
 * a remote HTTP API with a key. That distinction is what makes an agent bound
 * to one of these providers a self-hosted concern, so the set is consumed well
 * outside the agent stack, e.g. by the marketplace to decide whether a
 * publication is Community-Edition exclusive.
 *
 * <p>This lives in {@code agent-common} (the lowest common module) so every
 * consumer reads the SAME list. {@code BridgeAllowlist} in
 * {@code shared-agent-lib} stays the authority for which MODELS each bridge
 * routes and re-exports this set as its {@code BRIDGE_PROVIDERS}; modules that
 * only need the provider NAMES depend on this class instead of pulling in the
 * whole agent runtime.
 */
public final class BridgeProviders {

    private BridgeProviders() {}

    /** The four bridges wired in the platform. */
    public static final Set<String> NAMES =
            Set.of("claude-code", "codex", "gemini-cli", "mistral-vibe");

    /**
     * True when {@code provider} names one of the local-CLI bridges. Null-safe;
     * comparison is case-insensitive and trims surrounding whitespace, because
     * the value often arrives from a persisted snapshot rather than from code.
     */
    public static boolean isBridgeProvider(String provider) {
        if (provider == null) {
            return false;
        }
        return NAMES.contains(provider.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * True iff a USER-FACING model list must hide {@code provider} from this caller on this
     * install.
     *
     * <p>The mirror image of {@code CeBlockedProviders}, and deliberately the opposite edition: a
     * bridge is a legitimate user choice on a SELF-HOSTED install, where the CLI is the operator's
     * own binary under their own login, and on the hosted product it is one for the platform's
     * ADMINISTRATORS only, because all four CLIs there share ONE operator subscription. Offering a
     * hosted user a provider they are spending someone else's subscription on is the whole
     * problem, and hiding it does not depend on the access policy: {@code auth.bridge_access_policy}
     * can be widened to every user by an admin, and the hosted product must not reopen on that. A
     * hosted admin keeps the bridges in every list; whether they may DISPATCH on one is still the
     * policy's answer, asked where the run starts.
     *
     * <p>Hosted users lose nothing, because a bridge is an EXECUTION target there, not an identity:
     * {@code agent.model_execution_links} routes a billed pair (say {@code anthropic/claude-fable-5})
     * onto a CLI at dispatch time. The user picks the billed model and the platform decides where it
     * runs, which is the arrangement this hiding restores.
     *
     * <p><b>Takes "is this install self-hosted", not an {@code auth.mode} string, on purpose.</b>
     * {@code auth.mode != "embedded"} would read a SELF_HOSTED_ENTERPRISE deployment (which runs
     * keycloak) as hosted and ban that operator from their own CLI. Callers pass
     * {@code AppEditionProvider.isSelfHosted()}, which covers CE_FREE and SELF_HOSTED_ENTERPRISE
     * alike. The frontend picker filter has no edition input at all: it reads the role only.
     *
     * <p><b>This answers "should it be SHOWN", never "is it valid".</b> Callers must keep it off
     * any path that validates or resolves a model, so the agents already configured on a bridge
     * stay valid and keep running. Which caller may apply it is not a detail: the nested model
     * catalogue is shared, and also feeds {@code ModelCatalogEnricher} (whose {@code provider.enum}
     * {@code NodeParamsValidator} enforces at WRITE time), the default resolution in
     * {@code SmartDefaultsEngine} and {@code ChatDispatchService}, and the cloud-only admin panel
     * that creates the execution links. An earlier version of this filter was applied to all of
     * them at once and made a classify node on a bridge unsaveable while emptying the panel that
     * points a billed pair at a CLI. Apply it where the consumer is a user-facing picker or a
     * genuinely public read, and nowhere else.
     *
     * @param selfHosted whether this install runs its own CLI, i.e.
     *                   {@code AppEditionProvider.isSelfHosted()}
     * @param admin      whether the caller holds the platform ADMIN role ({@code AdminRoleGuard
     *                   .isAdmin} on the {@code X-User-Roles} it presented); an anonymous read
     *                   passes {@code false}
     */
    public static boolean isHiddenFromUser(boolean selfHosted, boolean admin, String provider) {
        return !selfHosted && !admin && isBridgeProvider(provider);
    }
}
