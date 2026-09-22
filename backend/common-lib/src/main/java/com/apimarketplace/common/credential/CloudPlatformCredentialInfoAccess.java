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
    default Optional<Map<String, Object>> fetchPlatformInfo(String integrationName, String apiToolId) {
        return fetchPlatformInfo(integrationName, apiToolId, null, null);
    }

    /**
     * The V428 variant, kept for every caller that has no notion of what a
     * call's own choices cost.
     */
    default Optional<Map<String, Object>> fetchPlatformInfo(String integrationName, String apiToolId,
                                                             String modelId, String quantity) {
        return fetchPlatformInfo(integrationName, apiToolId, modelId, quantity, null);
    }

    /**
     * V428 variant: names the generation model being quoted and the size of the
     * call. A generation is priced per MODEL, so without them the cloud can only
     * resolve an endpoint-wide row, which a seeded generation never has, and it
     * answers "nothing published" for a model the relay would execute and charge.
     *
     * @param modelId  generation model, or null for an ordinary endpoint
     * @param quantity PLATFORM measurement of the call, or null when unknown
     * @param priceMultiplier what the call's own CHOICES do to the published
     *        rate (a 1080p render, a reference image the provider charges to
     *        read), or null for a call at that rate. It travels because the
     *        cloud CHARGES it: the relay reads the factor back out of the body
     *        it executes, so a quote that omitted it would show the install one
     *        amount and bill another for the same request.
     */
    Optional<Map<String, Object>> fetchPlatformInfo(String integrationName, String apiToolId,
                                                     String modelId, String quantity,
                                                     String priceMultiplier);
}
