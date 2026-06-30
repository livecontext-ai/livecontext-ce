package com.apimarketplace.common.recentactivity;

/**
 * Resource kind discriminator for a {@link RecentActivityItemDto} row.
 *
 * <p>Mirrors the 6 filter chips on {@code /app/activity} tab:
 * Workflow / Application / Interface / Agent / Skill / Table.
 *
 * <p>{@code APPLICATION} and {@code WORKFLOW} both come from the same
 * underlying {@code workflows} table - the orchestrator-side branch of the
 * aggregator emits one or the other based on {@code WorkflowEntity.WorkflowType}
 * (mirrors {@code ActiveAutomationsService:196-198}).
 *
 * <p>{@code TABLE} is the user-facing label for {@code data_sources} rows.
 * Keeping the user-facing word here, not the schema name, so the frontend
 * filter chip + i18n key stay in lockstep.
 *
 * <p><b>Freshness semantic per kind diverges</b> - Activity surfaces a row when
 * its {@code updated_at} advances, but the mechanism that bumps each column
 * differs (DB trigger / @PreUpdate / JPQL SET clause / async touch). The
 * truth table lives at the read site for discoverability: see the class-level
 * javadoc of {@code RecentActivityAggregatorService} in orchestrator-service.
 *
 * <p>Notable divergence: {@code SKILL} bumps only on config edit (system-prompt
 * content has no per-use runtime event); the other 5 kinds bump on user-visible
 * activity (run, action fire, row CRUD).
 */
public enum ResourceKind {
    WORKFLOW,
    APPLICATION,
    INTERFACE,
    AGENT,
    SKILL,
    TABLE
}
