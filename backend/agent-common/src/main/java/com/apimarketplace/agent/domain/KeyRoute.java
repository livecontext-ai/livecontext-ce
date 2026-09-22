package com.apimarketplace.agent.domain;

/**
 * Whose API key an LLM call runs on, decided ONCE per agent execution and pinned on the
 * loop context, so every consumer downstream (provider key selection, rate limiting,
 * pre-flight gate, debit, observability, sub-agents) reads the same answer.
 *
 * <p>{@code PLATFORM}: the platform's key serves the call, the user is billed platform
 * credits. {@code OWN_KEY}: the tenant's own saved key serves the call (their provider
 * bills the tokens). A {@code null} route on a request means "unpinned": the provider
 * resolves user-first by the request's tenant, which is the pre-pin behaviour.
 *
 * <p>Not to be confused with {@code CloudLlmSource.BYOK} (a CE install using its own
 * platform key instead of the cloud relay) nor with the per-model
 * {@code provider_kind='byok'} discriminator: both describe a DEPLOYMENT, this describes
 * a USER's execution.
 */
public enum KeyRoute {
    PLATFORM,
    OWN_KEY;

    /**
     * Key under which a parent execution hands its pin to the sub-agents it spawns, in the
     * same {@code credentials} map that already carries {@code __executionId__} and
     * {@code __taskId__} from parent to child. A child inherits the pin; it never re-resolves.
     */
    public static final String CREDENTIAL_KEY = "__keyRoute__";

    /**
     * The provider the stamped pin was resolved FOR. A pin is per (tenant, provider): a
     * tenant may hold an OpenAI key and no Anthropic key, so a child that runs on another
     * provider than its parent cannot take the parent's OWN_KEY at face value.
     */
    public static final String PROVIDER_CREDENTIAL_KEY = "__keyRouteProvider__";

    /**
     * What a child running on {@code childProvider} inherits from the parent's stamp:
     * <ul>
     *   <li>same provider (or no provider stamped): the parent's pin, verbatim;</li>
     *   <li>different provider: {@code null} = unpinned, whatever the parent's pin. A pin is
     *       per (tenant, provider): every reason that produces one today (a usable own key, no
     *       usable own key, a lookup failure) is about THAT provider, so it says nothing about
     *       the child's, and the child resolves for its own. If a tenant-level reason is ever
     *       added (a plan or workspace rule), it belongs in the resolver, which the child
     *       consults, not in this inheritance.</li>
     * </ul>
     * Bridge and relay executions are pinned PLATFORM but never stamp it, so a bridge hop
     * stays transparent for the children it spawns.
     */
    public static KeyRoute inheritFor(java.util.Map<String, Object> credentials, String childProvider) {
        KeyRoute parent = fromCredentials(credentials);
        if (parent == null) {
            return null;
        }
        Object stamped = credentials.get(PROVIDER_CREDENTIAL_KEY);
        if (!(stamped instanceof String parentProvider) || parentProvider.isBlank()
                || childProvider == null || parentProvider.equalsIgnoreCase(childProvider.trim())) {
            return parent;
        }
        return null;
    }

    /** Stamp {@code route} resolved for {@code provider} into {@code credentials} (both keys, or neither). */
    public static void stamp(java.util.Map<String, Object> credentials, KeyRoute route, String provider) {
        if (credentials == null) {
            return;
        }
        if (route == null) {
            credentials.remove(CREDENTIAL_KEY);
            credentials.remove(PROVIDER_CREDENTIAL_KEY);
            return;
        }
        credentials.put(CREDENTIAL_KEY, route.name());
        if (provider != null && !provider.isBlank()) {
            credentials.put(PROVIDER_CREDENTIAL_KEY, provider);
        } else {
            credentials.remove(PROVIDER_CREDENTIAL_KEY);
        }
    }

    /** The pin a parent stamped into {@code credentials}, or null when absent or unreadable. */
    public static KeyRoute fromCredentials(java.util.Map<String, Object> credentials) {
        if (credentials == null) {
            return null;
        }
        Object raw = credentials.get(CREDENTIAL_KEY);
        if (raw instanceof KeyRoute route) {
            return route;
        }
        if (raw instanceof String name && !name.isBlank()) {
            try {
                return KeyRoute.valueOf(name.trim());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
