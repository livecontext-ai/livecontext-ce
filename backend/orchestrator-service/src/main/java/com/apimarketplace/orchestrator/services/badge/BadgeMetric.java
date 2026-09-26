package com.apimarketplace.orchestrator.services.badge;

/**
 * The measurable quantity a badge unlocks on. Every metric resolves to a
 * single {@code long} in {@link BadgeStats}, and a badge unlocks when that
 * value is {@code >=} its {@link BadgeDefinition#threshold()} - one rule for
 * the whole catalog, including the cohort badges (see
 * {@link #DAYS_UNTIL_JOIN_CUTOFF}).
 */
public enum BadgeMetric {

    /**
     * Days between the account's creation and 2027-01-01, clamped at 0 for
     * later sign-ups. Expressing the cohort badges as "how early did you
     * join" keeps the whole catalog on the single {@code value >= threshold}
     * rule instead of adding a second, date-shaped unlock path.
     */
    DAYS_UNTIL_JOIN_CUTOFF,

    /** Days since the account was created. */
    MEMBER_DAYS,

    /** Workflows authored by the user (acquired marketplace copies excluded). */
    WORKFLOWS_CREATED,

    /** Applications authored by the user (acquired copies excluded). */
    APPLICATIONS_CREATED,

    /** Executions launched: epoch fires, floored at one per run. */
    RUNS_LAUNCHED,

    /** Runs whose final status is COMPLETED. */
    RUNS_COMPLETED,

    /** Distinct UTC days on which at least one execution started. */
    ACTIVE_DAYS,

    /** Executions started between 00:00 and 05:00 UTC. */
    NIGHT_LAUNCHES,

    /** Workflows carrying a pinned production version. */
    WORKFLOWS_PINNED,

    /** Publications the user has published (any visibility). */
    PUBLICATIONS_PUBLISHED,

    /** Publications the user published with PUBLIC visibility. */
    PUBLICATIONS_PUBLIC,

    /** Total installs across the user's publications. */
    PUBLICATION_USES,

    /** Chat destinations the user connected that received their test message. */
    CHANNELS_CONNECTED,

    /** Distinct chat services (Telegram, Slack, ...) among those destinations. */
    CHANNEL_SERVICES,

    /**
     * Decisions made from a chat instead of the app: an agent's permission or question answered
     * with a button or a reply, or a workflow approval decided on its channel message.
     */
    REMOTE_DECISIONS
}
