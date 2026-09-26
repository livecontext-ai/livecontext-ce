package com.apimarketplace.orchestrator.services.badge;

/**
 * Thematic group a badge belongs to. One family = one progression the user
 * climbs (e.g. every OPERATOR badge is the same metric at a higher threshold),
 * which is how the UI groups the grid and how the "next badge" hint is picked.
 *
 * <p>The frontend maps each family to an icon + accent colour; an unknown
 * family degrades to a neutral medal rather than breaking the page, so adding
 * one here before the frontend ships is safe.
 */
public enum BadgeFamily {
    /** Joined during an early cohort - one-shot, never progresses. */
    FOUNDER,
    /** How long the account has existed. */
    TENURE,
    /** Workflows authored. */
    BUILDER,
    /** Applications authored. */
    APP_MAKER,
    /** Executions launched. */
    OPERATOR,
    /** Executions that finished COMPLETED. */
    RELIABILITY,
    /** Distinct days with at least one execution. */
    CONSISTENCY,
    /** Executions started between midnight and 05:00 UTC. */
    NIGHT_OWL,
    /** Workflows pinned to a production version. */
    SHIPPER,
    /** Publications published to the marketplace. */
    PUBLISHER,
    /** Publications made PUBLIC (shared with everyone). */
    SHARER,
    /** Installs other people made of the user's publications. */
    POPULARITY,
    REACHABLE,
    MULTICHANNEL,
    REMOTE_CONTROL
}
