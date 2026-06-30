package com.apimarketplace.auth.domain;

/**
 * Lifecycle state of a {@link RewardRedemption}.
 *
 * <ul>
 *   <li>{@code GRANTED} terminal; an immediate redeem-time benefit (promo free-node).</li>
 *   <li>{@code PENDING} attributed, awaiting the redeemer's paid conversion.</li>
 *   <li>{@code QUALIFIED} converted (first paid invoice captured); reward held until
 *       {@code release_due_at}.</li>
 *   <li>{@code RELEASED} terminal; the held reward was granted to both parties.</li>
 *   <li>{@code CLAWED_BACK} terminal; a refund or dispute revoked the reward.</li>
 *   <li>{@code TRACK_ONLY} soft-cap overflow; recorded but not auto-released (awaits
 *       manual approval).</li>
 *   <li>{@code INELIGIBLE} reserved terminal state for a redemption that can never
 *       qualify. The current already-paid case is rejected at redeem time without
 *       persisting a row, so this is held for future use.</li>
 * </ul>
 */
public enum RewardStatus {
    GRANTED,
    PENDING,
    QUALIFIED,
    RELEASED,
    CLAWED_BACK,
    TRACK_ONLY,
    INELIGIBLE
}
