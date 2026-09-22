package com.apimarketplace.auth.service;

import com.apimarketplace.common.credit.ModelTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * What the platform charges for ONE agent turn that ran on the tenant's OWN provider key.
 *
 * <p>The provider bills the tokens to the user directly, so the platform bills the work it
 * actually does (orchestration, tools, storage, streaming) as a flat fee per turn, keyed on
 * the model's price band.
 *
 * <p><b>A flat fee prices the AVERAGE turn of its tier, so it is not the whole rule.</b> On the
 * two cheap tiers about a quarter of real turns are shorter than their own fee, which would
 * make bringing your own key cost MORE than not bringing it, on top of what the provider
 * charges you. {@code CreditService} therefore debits {@code min(fee, consumption)}: this class
 * sets a CEILING, not a price. Declared in {@code application.yml}
 * ({@code billing.own-key.credits-per-turn.*}), pinned per value in {@code values-prod.yaml}
 * and guarded by {@code EconomicsConfigPinTest} like every other margin lever.
 *
 * <p>The defaults below exist only for a context that loads neither file; they are kept equal
 * to the shipped ladder so an install can never be billed off a number nobody chose.
 */
@Component
public class OwnKeyTurnPricing {

    private final BigDecimal budget;
    private final BigDecimal mid;
    private final BigDecimal high;
    private final BigDecimal top;
    private final BigDecimal unknown;

    public OwnKeyTurnPricing(
            @Value("${billing.own-key.credits-per-turn.budget:1}") BigDecimal budget,
            @Value("${billing.own-key.credits-per-turn.mid:2}") BigDecimal mid,
            @Value("${billing.own-key.credits-per-turn.high:5}") BigDecimal high,
            @Value("${billing.own-key.credits-per-turn.top:10}") BigDecimal top,
            @Value("${billing.own-key.credits-per-turn.unknown:2}") BigDecimal unknown) {
        this.budget = nonNegative(budget, "budget");
        this.mid = nonNegative(mid, "mid");
        this.high = nonNegative(high, "high");
        this.top = nonNegative(top, "top");
        this.unknown = nonNegative(unknown, "unknown");
    }

    /** Credits the platform charges for one turn on a model of {@code tier}, on the tenant's own key. */
    public BigDecimal creditsPerTurn(ModelTier tier) {
        return switch (tier == null ? ModelTier.UNKNOWN : tier) {
            case BUDGET -> budget;
            case MID -> mid;
            case HIGH -> high;
            case TOP -> top;
            case UNKNOWN -> unknown;
        };
    }

    private static BigDecimal nonNegative(BigDecimal value, String tier) {
        if (value == null) {
            throw new IllegalArgumentException("billing.own-key.credits-per-turn." + tier + " must be set");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException("billing.own-key.credits-per-turn." + tier + " must be >= 0");
        }
        return value;
    }
}
