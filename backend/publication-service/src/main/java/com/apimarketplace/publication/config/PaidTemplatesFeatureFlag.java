package com.apimarketplace.publication.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Defense-in-depth gate for paid template publications.
 *
 * <p>While the billing pipeline (payouts, VAT, refund) is not production-ready,
 * the marketplace publish wizards grey the price input client-side AND the
 * backend rejects any direct-curl attempt to set {@code creditsPerUse > 0}
 * on a new publish or update. Existing paid publications are grandfathered
 * (DB rows untouched) so already-acquired users are not retroactively
 * affected.
 *
 * <p>Property: {@code marketplace.paid-templates.enabled} (default {@code false}).
 * Mirror env var (frontend): {@code NEXT_PUBLIC_PAID_TEMPLATES}.
 *
 * <p>The static holder pattern mirrors {@link SharedLinkPlanLimits} - keeps
 * the constructor signatures of consuming services stable so test fixtures
 * don't need to be updated when the flag flips.
 */
@Configuration
public class PaidTemplatesFeatureFlag {

    private static final Logger logger = LoggerFactory.getLogger(PaidTemplatesFeatureFlag.class);

    /** Default-false: paid templates stay disabled until the operator opts in. */
    private static volatile boolean enabled = false;

    @Value("${marketplace.paid-templates.enabled:false}")
    private boolean configuredEnabled;

    @PostConstruct
    void init() {
        enabled = configuredEnabled;
        logger.info("Paid template publications: {} (creditsPerUse > 0 on new publishes is {}allowed)",
                enabled ? "ENABLED" : "DISABLED",
                enabled ? "" : "NOT ");
    }

    /** True when publishers may set a non-zero credit price on new publications. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Test-only escape hatch. Production code MUST go through Spring. */
    static void setEnabledForTest(boolean value) {
        enabled = value;
    }
}
