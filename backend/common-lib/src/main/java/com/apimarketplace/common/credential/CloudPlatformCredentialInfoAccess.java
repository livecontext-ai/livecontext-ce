package com.apimarketplace.common.credential;

import java.util.Map;
import java.util.Optional;

/**
 * Lets auth-service's platform-credential public-info endpoint ask whether the CLOUD offers a
 * platform credential for an integration when the local install has none - the consultation half
 * of the CE catalog credential relay (the builder's user/platform toggle is driven by this info).
 *
 * <p>Mirrors the {@code CloudPlanAccess} pattern: the only implementation is a CE-side adapter
 * (shared-agent-lib, {@code marketplace.mode=remote}) that resolves the install's cloud link and
 * fetches the cloud's platform-credential public info over it. In the cloud deployment no bean
 * exists, so the endpoint keeps its current local-only behaviour unchanged - this interface is
 * inert on the cloud side by construction.
 */
public interface CloudPlatformCredentialInfoAccess {

    /**
     * @param integrationName the platform-credential integration name (e.g. {@code "gmail"})
     * @param apiToolId       optional tool UUID (as string) for per-endpoint pricing resolution;
     *                        may be null or blank
     * @return the cloud's platform-info payload ({@code integrationName}, {@code available},
     *         {@code platformCredentialId}, {@code hasPricing}, {@code markupCredits},
     *         {@code subscriptionActive}, {@code relayEligible}), present ONLY when the install
     *         is cloud-linked, the catalog source toggle is CLOUD, and the cloud answered;
     *         empty otherwise (unlinked, BYOK catalog source, or any transport failure) so the
     *         caller falls back to the local not-found response
     */
    Optional<Map<String, Object>> fetchPlatformInfo(String integrationName, String apiToolId);
}
