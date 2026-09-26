package com.apimarketplace.orchestrator.services.badge;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full, immutable list of badges the platform can award.
 *
 * <p>Kept in code rather than in a table on purpose: a badge is a product
 * decision that ships together with the frontend artwork and the translated
 * name for the same {@link BadgeDefinition#code()}. Storing definitions in the
 * database would let the three drift apart with no compile-time signal, and
 * every new badge would need a migration. {@code orchestrator.user_badges}
 * therefore records only the unlock EVENT.
 *
 * <p>Ordering matters: the list is served to the UI as-is, so families stay
 * grouped and tiers climb inside each family.
 */
public final class BadgeCatalog {

    /**
     * The end of the early-adopter window. Cohort badges measure how many days
     * BEFORE this date the account was created, which folds "joined early" into
     * the same {@code value >= threshold} rule as every other badge. Moving this
     * constant would silently re-scope every cohort badge, so it is fixed for
     * good; a later cohort gets its own constant and its own badges.
     */
    public static final LocalDate JOIN_CUTOFF = LocalDate.of(2027, 1, 1);

    private static final List<BadgeDefinition> DEFINITIONS = List.of(
            // --- Origin: a single, ungraded trophy for the accounts that existed
            // before 2027. Threshold 1 = at least one day left until JOIN_CUTOFF,
            // i.e. created in 2026 or earlier. There are deliberately no lesser
            // grades here: you either were there or you were not, and the window
            // closes by itself on 2027-01-01 - nobody can earn it afterwards.
            def("founder_2026", BadgeFamily.FOUNDER, BadgeTier.DIAMOND,  BadgeMetric.DAYS_UNTIL_JOIN_CUTOFF, 1),

            // --- Tenure
            def("tenure_30",   BadgeFamily.TENURE, BadgeTier.BRONZE,   BadgeMetric.MEMBER_DAYS, 30),
            def("tenure_100",  BadgeFamily.TENURE, BadgeTier.SILVER,   BadgeMetric.MEMBER_DAYS, 100),
            def("tenure_730",  BadgeFamily.TENURE, BadgeTier.GOLD,     BadgeMetric.MEMBER_DAYS, 730),
            def("tenure_1095", BadgeFamily.TENURE, BadgeTier.PLATINUM, BadgeMetric.MEMBER_DAYS, 1095),
            def("tenure_1825", BadgeFamily.TENURE, BadgeTier.DIAMOND,  BadgeMetric.MEMBER_DAYS, 1825),

            // --- Workflows authored
            def("builder_1",   BadgeFamily.BUILDER, BadgeTier.BRONZE,   BadgeMetric.WORKFLOWS_CREATED, 1),
            def("builder_5",   BadgeFamily.BUILDER, BadgeTier.BRONZE,   BadgeMetric.WORKFLOWS_CREATED, 5),
            def("builder_10",  BadgeFamily.BUILDER, BadgeTier.SILVER,   BadgeMetric.WORKFLOWS_CREATED, 10),
            def("builder_50",  BadgeFamily.BUILDER, BadgeTier.GOLD,     BadgeMetric.WORKFLOWS_CREATED, 50),
            def("builder_100", BadgeFamily.BUILDER, BadgeTier.PLATINUM, BadgeMetric.WORKFLOWS_CREATED, 100),
            def("builder_300", BadgeFamily.BUILDER, BadgeTier.DIAMOND,  BadgeMetric.WORKFLOWS_CREATED, 300),

            // --- Applications authored
            def("appmaker_1",  BadgeFamily.APP_MAKER, BadgeTier.BRONZE,   BadgeMetric.APPLICATIONS_CREATED, 1),
            def("appmaker_3",  BadgeFamily.APP_MAKER, BadgeTier.SILVER,   BadgeMetric.APPLICATIONS_CREATED, 3),
            def("appmaker_25", BadgeFamily.APP_MAKER, BadgeTier.GOLD,     BadgeMetric.APPLICATIONS_CREATED, 25),
            def("appmaker_60", BadgeFamily.APP_MAKER, BadgeTier.PLATINUM, BadgeMetric.APPLICATIONS_CREATED, 60),

            // --- Executions launched
            def("operator_1",       BadgeFamily.OPERATOR, BadgeTier.BRONZE,   BadgeMetric.RUNS_LAUNCHED, 1),
            def("operator_10",      BadgeFamily.OPERATOR, BadgeTier.BRONZE,   BadgeMetric.RUNS_LAUNCHED, 10),
            def("operator_100",     BadgeFamily.OPERATOR, BadgeTier.SILVER,   BadgeMetric.RUNS_LAUNCHED, 100),
            def("operator_5000",    BadgeFamily.OPERATOR, BadgeTier.GOLD,     BadgeMetric.RUNS_LAUNCHED, 5_000),
            def("operator_50000",   BadgeFamily.OPERATOR, BadgeTier.PLATINUM, BadgeMetric.RUNS_LAUNCHED, 50_000),
            def("operator_1000000", BadgeFamily.OPERATOR, BadgeTier.DIAMOND,  BadgeMetric.RUNS_LAUNCHED, 1_000_000),

            // --- Successful executions
            def("reliability_25",    BadgeFamily.RELIABILITY, BadgeTier.BRONZE,   BadgeMetric.RUNS_COMPLETED, 25),
            def("reliability_250",   BadgeFamily.RELIABILITY, BadgeTier.SILVER,   BadgeMetric.RUNS_COMPLETED, 250),
            def("reliability_10000", BadgeFamily.RELIABILITY, BadgeTier.GOLD,     BadgeMetric.RUNS_COMPLETED, 10_000),
            def("reliability_50000", BadgeFamily.RELIABILITY, BadgeTier.PLATINUM, BadgeMetric.RUNS_COMPLETED, 50_000),

            // --- Days with activity
            def("consistency_3",   BadgeFamily.CONSISTENCY, BadgeTier.BRONZE,   BadgeMetric.ACTIVE_DAYS, 3),
            def("consistency_10",  BadgeFamily.CONSISTENCY, BadgeTier.SILVER,   BadgeMetric.ACTIVE_DAYS, 10),
            def("consistency_90",  BadgeFamily.CONSISTENCY, BadgeTier.GOLD,     BadgeMetric.ACTIVE_DAYS, 90),
            def("consistency_250", BadgeFamily.CONSISTENCY, BadgeTier.PLATINUM, BadgeMetric.ACTIVE_DAYS, 250),
            def("consistency_750", BadgeFamily.CONSISTENCY, BadgeTier.DIAMOND,  BadgeMetric.ACTIVE_DAYS, 750),

            // --- Executions started overnight (00:00-05:00 UTC)
            def("nightowl_1",   BadgeFamily.NIGHT_OWL, BadgeTier.BRONZE,   BadgeMetric.NIGHT_LAUNCHES, 1),
            def("nightowl_25",  BadgeFamily.NIGHT_OWL, BadgeTier.SILVER,   BadgeMetric.NIGHT_LAUNCHES, 25),
            def("nightowl_500", BadgeFamily.NIGHT_OWL, BadgeTier.GOLD,     BadgeMetric.NIGHT_LAUNCHES, 500),

            // --- Pinned to production
            def("shipper_1",  BadgeFamily.SHIPPER, BadgeTier.BRONZE,   BadgeMetric.WORKFLOWS_PINNED, 1),
            def("shipper_5",  BadgeFamily.SHIPPER, BadgeTier.SILVER,   BadgeMetric.WORKFLOWS_PINNED, 5),
            def("shipper_50", BadgeFamily.SHIPPER, BadgeTier.GOLD,     BadgeMetric.WORKFLOWS_PINNED, 50),

            // --- Published to the marketplace
            def("publisher_1",  BadgeFamily.PUBLISHER, BadgeTier.BRONZE,   BadgeMetric.PUBLICATIONS_PUBLISHED, 1),
            def("publisher_3",  BadgeFamily.PUBLISHER, BadgeTier.SILVER,   BadgeMetric.PUBLICATIONS_PUBLISHED, 3),
            def("publisher_25", BadgeFamily.PUBLISHER, BadgeTier.GOLD,     BadgeMetric.PUBLICATIONS_PUBLISHED, 25),
            def("publisher_60", BadgeFamily.PUBLISHER, BadgeTier.PLATINUM, BadgeMetric.PUBLICATIONS_PUBLISHED, 60),

            // --- Shared publicly
            def("sharer_1",  BadgeFamily.SHARER, BadgeTier.BRONZE,   BadgeMetric.PUBLICATIONS_PUBLIC, 1),
            def("sharer_5",  BadgeFamily.SHARER, BadgeTier.SILVER,   BadgeMetric.PUBLICATIONS_PUBLIC, 5),
            def("sharer_40", BadgeFamily.SHARER, BadgeTier.GOLD,     BadgeMetric.PUBLICATIONS_PUBLIC, 40),

            // --- Installs earned from other people
            def("popularity_1",     BadgeFamily.POPULARITY, BadgeTier.BRONZE,   BadgeMetric.PUBLICATION_USES, 1),
            def("popularity_10",    BadgeFamily.POPULARITY, BadgeTier.SILVER,   BadgeMetric.PUBLICATION_USES, 10),
            def("popularity_500",   BadgeFamily.POPULARITY, BadgeTier.GOLD,     BadgeMetric.PUBLICATION_USES, 500),
            def("popularity_5000",  BadgeFamily.POPULARITY, BadgeTier.PLATINUM, BadgeMetric.PUBLICATION_USES, 5_000),
            def("popularity_50000", BadgeFamily.POPULARITY, BadgeTier.DIAMOND,  BadgeMetric.PUBLICATION_USES, 50_000),

            // --- Reachable outside the app: destinations that really received a message
            def("reachable_1", BadgeFamily.REACHABLE, BadgeTier.BRONZE, BadgeMetric.CHANNELS_CONNECTED, 1),
            def("reachable_5", BadgeFamily.REACHABLE, BadgeTier.SILVER, BadgeMetric.CHANNELS_CONNECTED, 5),

            // --- Several chat services
            def("multichannel_2", BadgeFamily.MULTICHANNEL, BadgeTier.SILVER, BadgeMetric.CHANNEL_SERVICES, 2),
            def("multichannel_4", BadgeFamily.MULTICHANNEL, BadgeTier.GOLD,   BadgeMetric.CHANNEL_SERVICES, 4),

            // --- Decisions made from a chat
            def("remote_1",    BadgeFamily.REMOTE_CONTROL, BadgeTier.BRONZE,   BadgeMetric.REMOTE_DECISIONS, 1),
            def("remote_25",   BadgeFamily.REMOTE_CONTROL, BadgeTier.SILVER,   BadgeMetric.REMOTE_DECISIONS, 25),
            def("remote_250",  BadgeFamily.REMOTE_CONTROL, BadgeTier.GOLD,     BadgeMetric.REMOTE_DECISIONS, 250),
            def("remote_2500", BadgeFamily.REMOTE_CONTROL, BadgeTier.PLATINUM, BadgeMetric.REMOTE_DECISIONS, 2_500)
    );

    private static final Map<String, BadgeDefinition> BY_CODE = indexByCode();

    private BadgeCatalog() {}

    private static BadgeDefinition def(String code, BadgeFamily family, BadgeTier tier,
                                       BadgeMetric metric, long threshold) {
        return new BadgeDefinition(code, family, tier, metric, threshold);
    }

    private static Map<String, BadgeDefinition> indexByCode() {
        Map<String, BadgeDefinition> map = new LinkedHashMap<>(DEFINITIONS.size());
        for (BadgeDefinition d : DEFINITIONS) {
            BadgeDefinition previous = map.put(d.code(), d);
            if (previous != null) {
                // A duplicate code makes unlock rows ambiguous (two badges, one
                // persisted key). Fail at class-init rather than ship it.
                throw new IllegalStateException("Duplicate badge code in catalog: " + d.code());
            }
        }
        return Map.copyOf(map);
    }

    /** Every badge, in display order (grouped by family, tiers ascending). */
    public static List<BadgeDefinition> all() {
        return DEFINITIONS;
    }

    /**
     * Look up one definition, or {@code null} when the code is unknown. Callers
     * reading persisted rows MUST tolerate null: a badge retired from the
     * catalog leaves its unlock rows behind, and those simply stop rendering
     * instead of breaking the page.
     */
    public static BadgeDefinition byCode(String code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /**
     * Days from {@code createdAt} until {@link #JOIN_CUTOFF}, clamped at 0.
     * Shared by the stats collector and its tests so the cohort thresholds are
     * interpreted in exactly one place.
     */
    public static long daysUntilJoinCutoff(Instant createdAt) {
        if (createdAt == null) return 0;
        long days = ChronoUnit.DAYS.between(
                createdAt.atZone(ZoneOffset.UTC).toLocalDate(), JOIN_CUTOFF);
        return Math.max(0, days);
    }
}
