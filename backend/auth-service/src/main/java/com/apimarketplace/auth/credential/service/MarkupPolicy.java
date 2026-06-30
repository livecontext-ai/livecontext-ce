package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.AuthType;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Central enforcement point for markup business rules.
 * Kept as a small, pure component so the same rules apply whether markup is
 * being written (pricing-version publish) or read (per-call resolve). The DB
 * CHECK constraints enforce the same invariants as a last line of defense;
 * this class exists so the service layer can fail fast with a clear error
 * instead of bubbling up an opaque constraint-violation SQLException.
 */
@Component
public class MarkupPolicy {

    /**
     * Reject configurations that cannot legally exist.
     *
     * <p>OAuth2 credentials never carry markup - they represent a delegated user
     * auth flow where billing the platform owner for the user's own OAuth quota
     * makes no sense. Negative markup / negative call limits are obviously
     * nonsense and are blocked here before they reach the DB.
     *
     * <p>A null {@code markup} is allowed and means "no API-wide default -
     * per-tool overrides are the sole source of pricing". Callers that pass
     * null must still ensure at least one per-tool override exists before
     * publishing; this policy does not see the overrides, so that invariant
     * is enforced one level up in the pricing service.
     */
    public void validateMarkupConfig(AuthType authType, BigDecimal markup, int maxCallsPerRun) {
        if (markup != null && markup.signum() < 0) {
            throw new IllegalArgumentException(
                    "defaultMarkupCredits must be >= 0 (got " + markup + ")");
        }
        if (maxCallsPerRun < 0) {
            throw new IllegalArgumentException(
                    "maxCallsPerRun must be >= 0 (got " + maxCallsPerRun + ")");
        }
        if (authType == AuthType.OAUTH2
                && ((markup != null && markup.signum() != 0) || maxCallsPerRun != 0)) {
            throw new IllegalArgumentException(
                    "OAuth2 credentials cannot carry markup (markup=" + markup
                            + ", maxCallsPerRun=" + maxCallsPerRun + ")");
        }
    }

    /**
     * Resolve the effective per-call markup for a given tool under a pricing version.
     *
     * <p>Per-tool override wins over the version-wide default. A null version
     * (credential not yet priced) yields {@link BigDecimal#ZERO} so billing
     * defaults to "no markup" rather than "fail closed" - the rate-cap / flag
     * gate upstream is the actual on/off switch.
     */
    public BigDecimal resolveEffectiveMarkup(PlatformCredentialPricingVersion version,
                                              Optional<PricingVersionEntry> perTool) {
        if (version == null) {
            return BigDecimal.ZERO;
        }
        if (perTool != null && perTool.isPresent()) {
            BigDecimal rate = perTool.get().getMarkupCredits();
            return rate != null ? rate : BigDecimal.ZERO;
        }
        BigDecimal def = version.getDefaultMarkupCredits();
        return def != null ? def : BigDecimal.ZERO;
    }

    /**
     * Projected markup cost for a budget pre-check.
     *
     * <p>Used to reserve budget up-front: {@code perCallMarkup × min(remainingCalls, maxPerRun)}.
     * {@code remainingCalls} is the caller's estimate of how many more debits will
     * happen in the run; we clamp by {@code maxPerRun} so a runaway loop cannot
     * be projected past the configured cap. Zero- or negative-input cases
     * degrade to {@link BigDecimal#ZERO}.
     */
    public BigDecimal projectedMarkup(BigDecimal perCallMarkup, int remainingCalls, int maxPerRun) {
        if (perCallMarkup == null || perCallMarkup.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (remainingCalls <= 0 || maxPerRun <= 0) {
            return BigDecimal.ZERO;
        }
        int effective = Math.min(remainingCalls, maxPerRun);
        return perCallMarkup.multiply(BigDecimal.valueOf(effective));
    }
}
