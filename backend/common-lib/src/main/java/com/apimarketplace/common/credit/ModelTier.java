package com.apimarketplace.common.credit;

import java.math.BigDecimal;

/**
 * A model's price class, derived from its OUTPUT price per 1M tokens with one deterministic
 * rule shared by every consumer: the catalog feed parsers (agent-service), the own-key flat
 * fee per turn (auth-service), the admin UI badge and the Python sync script all agree on
 * these four bands. Change a threshold here and in {@code scripts/models/sync_openrouter.py}
 * together.
 *
 * <p>{@link #UNKNOWN} is the answer for a model with no known price: never throw, a sync or a
 * bill must degrade, not fail.
 */
public enum ModelTier {
    BUDGET("budget"),
    MID("mid"),
    HIGH("high"),
    TOP("top"),
    UNKNOWN("unknown");

    /** Thresholds on USD per 1M output tokens. */
    public static final BigDecimal TOP_MIN = new BigDecimal("15.00");
    public static final BigDecimal HIGH_MIN = new BigDecimal("5.00");
    public static final BigDecimal MID_MIN = new BigDecimal("1.50");

    private final String key;

    ModelTier(String key) {
        this.key = key;
    }

    /** The lowercase key stored on catalog rows and shown in the UI ({@code "budget"}, {@code "top"}...). */
    public String key() {
        return key;
    }

    public static ModelTier classify(BigDecimal outputPricePerMillion) {
        if (outputPricePerMillion == null) return UNKNOWN;
        if (outputPricePerMillion.compareTo(TOP_MIN) >= 0) return TOP;
        if (outputPricePerMillion.compareTo(HIGH_MIN) >= 0) return HIGH;
        if (outputPricePerMillion.compareTo(MID_MIN) >= 0) return MID;
        return BUDGET;
    }

    /** The tier for a stored key ({@code "top"}), {@link #UNKNOWN} for anything else. */
    public static ModelTier fromKey(String key) {
        if (key == null) return UNKNOWN;
        for (ModelTier tier : values()) {
            if (tier.key.equalsIgnoreCase(key.trim())) return tier;
        }
        return UNKNOWN;
    }
}
