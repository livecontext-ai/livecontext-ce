package com.apimarketplace.interfaces.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Closed projection for the recent-activity feed: the four columns
 * {@code /api/internal/interfaces/recent-activity} actually emits, and nothing else.
 *
 * <p><b>Why a projection and not the entity.</b> The endpoint builds a
 * {@code RecentActivityItemDto} out of id / name / updatedAt / tenantId, but it used to load
 * 50 full {@link com.apimarketplace.interfaces.domain.InterfaceEntity} rows to read them,
 * dragging in the three template columns ({@code html_template}, {@code css_template},
 * {@code js_template}) for every row.
 *
 * <p><b>This is the bandwidth half of a two-part fix, not the crash fix.</b> The 500 those rows
 * produced on 2026-09-18 came from the {@code @Lob} annotation that used to sit on those three
 * fields: it made Hibernate read TEXT columns as PostgreSQL large objects, which are readable
 * only inside a transaction. That annotation is gone (see the note on {@code InterfaceEntity}),
 * so widening this query back to the entity would no longer 500 - it would merely ship 50 page
 * templates across the wire to produce a list of names. Keep the projection for that reason,
 * and rely on the entity mapping for the correctness one. Same shape as
 * {@link InterfaceListView}, which already excludes those columns for the paged list.
 */
public interface InterfaceRecentActivityView {

    UUID getId();

    String getName();

    Instant getUpdatedAt();

    /** Owner within the active scope - surfaced as the activity item's actor. */
    String getTenantId();
}
