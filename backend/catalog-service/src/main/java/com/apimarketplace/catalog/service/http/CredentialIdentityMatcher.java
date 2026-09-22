package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.util.IconSlugNormalizer;

/**
 * Whether a credential's own identifiers say it belongs to the integration an
 * endpoint requires.
 *
 * <p>Extracted from {@code HttpExecutionService}, which still calls it and is
 * still the only place that RESOLVES a credential. It lives on its own because a
 * second reader appeared that has to ask the same question without executing
 * anything: the capability answer that tells a caller which of its accounts could
 * run an endpoint. A listing that matched more loosely, or more strictly, than the
 * executor would offer accounts the run then refuses, or hide ones it would have
 * accepted, and neither divergence is visible from either side.
 *
 * <p>The rules, unchanged: {@code integration} is system-set and decides whenever
 * it is present; the credential's LABEL is admitted only for a credential that
 * carries no integration at all, which is how the workflow-native connectors
 * (smtp, ssh, database) identify themselves.
 */
public final class CredentialIdentityMatcher {

    private CredentialIdentityMatcher() {
    }

    /**
     * How a requirement's credential name yields the integration it names. The
     * {@code -credential} suffix is how a requirement spells it, and the auth side
     * derives the integration from it the same way.
     */
    public static String integrationOfRequirement(String credentialName) {
        return credentialName == null ? null : credentialName.trim().replaceAll("-credential$", "");
    }

    /** A credential that carries no system-set integration at all. */
    public static boolean isBlankIdentifier(String value) {
        return value == null || value.isBlank();
    }

    /**
     * True when two credential identifiers name the same thing.
     *
     * <p>Collapsed to the canonical icon slug first, the same normalisation
     * the credential templates and the pickers are keyed on, so a difference
     * that is only punctuation ({@code stability-ai} against
     * {@code stabilityai}) does not read as a different provider.
     */
    public static boolean sameCredentialIdentity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return false;
        }
        String left = IconSlugNormalizer.normalizeForKey(a);
        String right = IconSlugNormalizer.normalizeForKey(b);
        return !left.isBlank() && left.equals(right);
    }

    /**
     * Whether a credential's own identifiers say it belongs to the integration this
     * endpoint requires.
     *
     * <p>One matcher, used by every way of choosing a credential (by id, by name,
     * and by the capability listing), because a path that matched more loosely than
     * the id path would be a way to reach a credential the id path exists to keep out.
     */
    public static boolean matchesRequirement(
            String integration, String requirement, String foundIntegration, String foundName) {
        return sameCredentialIdentity(integration, foundIntegration)
                || sameCredentialIdentity(requirement, foundIntegration)
                || (isBlankIdentifier(foundIntegration)
                        && sameCredentialIdentity(requirement, foundName));
    }
}
