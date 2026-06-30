package com.apimarketplace.auth.domain;

/**
 * How a code's redemption cap is enforced. The cap is optional: conversion-gating
 * plus the hold are the primary economic protection, not a count limit.
 *
 * <ul>
 *   <li>{@code NONE} uncapped (the referral default).</li>
 *   <li>{@code GLOBAL} a single total-redemption cap across all redeemers
 *       (the legacy promo {@code max_redemptions}).</li>
 *   <li>{@code PER_OWNER_SOFT} past {@code cap_limit} a redemption is not blocked;
 *       it is recorded as {@code TRACK_ONLY} and held for manual approval (the
 *       releaser skips it).</li>
 * </ul>
 */
public enum CapScope {
    NONE,
    GLOBAL,
    PER_OWNER_SOFT
}
