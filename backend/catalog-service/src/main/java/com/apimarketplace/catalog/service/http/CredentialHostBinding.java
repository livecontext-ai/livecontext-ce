package com.apimarketplace.catalog.service.http;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * The one narrow case where a user-registered API may use a credential key owned by a shipped
 * integration (LC-002, CASA readiness): every request of the call must go over https to a host the
 * shipped integration itself targets.
 *
 * <p>Set by {@code ApiService} for the duration of one tool execution, enforced by
 * {@code HttpExecutionService.validatedTarget} on the FINAL validated URL of every outbound request,
 * so path/query/credential substitution, the refresh retry, async polling and streaming are all
 * held to it. A request that leaves the bound hosts is refused before any byte is sent.
 *
 * <p>Thread-confined: a tool execution runs its requests on the calling thread.
 */
public final class CredentialHostBinding {

    /**
     * @param credentialKey the foreign key the call is allowed to use
     * @param exactHosts    concrete (non-templated) base-URL hosts of the owning shipped integration
     * @param hostSuffixes  its declared {@code allowedUrlHostSuffixes}, minus any suffix a templated
     *                      base URL of the same integration lives under (tenant-controlled hosts)
     */
    public record Binding(String credentialKey, Set<String> exactHosts, Set<String> hostSuffixes) {
        public boolean permits(URI uri) {
            if (uri == null || uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || uri.getRawUserInfo() != null) {
                return false;
            }
            String h = host.toLowerCase(Locale.ROOT);
            while (h.endsWith(".")) {
                h = h.substring(0, h.length() - 1);
            }
            if (exactHosts.contains(h)) {
                return true;
            }
            for (String suffix : hostSuffixes) {
                if (h.equals(suffix) || h.endsWith("." + suffix)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private CredentialHostBinding() {
    }

    public static void set(Binding binding) {
        CURRENT.set(binding);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Binding current() {
        return CURRENT.get();
    }

    /** @throws IllegalArgumentException when a binding is active and the URL leaves it */
    public static void enforce(String url) {
        Binding binding = CURRENT.get();
        if (binding == null) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (RuntimeException e) {
            uri = null;
        }
        if (!binding.permits(uri)) {
            throw new IllegalArgumentException("credential_key_conflict: the credential '"
                    + binding.credentialKey() + "' belongs to a built-in integration and may only be sent over "
                    + "https to that integration's own hosts; this request targets "
                    + (uri == null || uri.getHost() == null ? "an unparseable URL" : uri.getHost()));
        }
    }
}
