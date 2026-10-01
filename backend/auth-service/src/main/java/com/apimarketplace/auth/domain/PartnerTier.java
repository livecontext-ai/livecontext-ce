package com.apimarketplace.auth.domain;

/**
 * A partner's tier (V556). Declared in ascending order: a tier only ever moves up, so the
 * ordinal is the rank ({@link #isAbove}).
 */
public enum PartnerTier {
    SILVER, GOLD, PLATINUM;

    public boolean isAbove(PartnerTier other) {
        return other == null || ordinal() > other.ordinal();
    }

    /** The next tier up, or {@code null} at the top. */
    public PartnerTier next() {
        PartnerTier[] all = values();
        return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
    }
}
