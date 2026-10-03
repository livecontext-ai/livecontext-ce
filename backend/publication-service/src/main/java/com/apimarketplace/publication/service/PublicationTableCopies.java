package com.apimarketplace.publication.service;

import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.TableCopyException;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The one way a publication snapshot copies a table's rows (table, interface, workflow,
 * application and agent listings).
 *
 * <p>For a PUBLISH the copy is strict: a copy that could not be completed (a failed page, a
 * missing confirmation, a stalled paging) refuses the publish with a retryable
 * {@link PublicationValidationException#TABLE_COPY_FAILED}, so a transient failure never ships
 * an empty table; a table that IS empty copies as empty. The copied rows are checked against the
 * row budget right away, with the table's real id, so an oversized table is refused before
 * anything else is built or copied.
 *
 * <p>The moderation view rebuilds the same snapshots to compare them with the stored ones. It runs
 * inside {@link #forReview}, where a copy is lenient (empty on failure, as it always was) and the
 * budget is not enforced: a reviewer must still see what the source looks like.
 *
 * <p>The mode travels on the thread, rather than as a parameter, because the same builders
 * (resource strategies, workflow enrichment, sub-workflows, agent snapshots) serve both callers
 * several frames down. Outside any scope a copy is strict: that is the safe default.
 */
public final class PublicationTableCopies {

    private record Scope(boolean review, PublicationSnapshotBudget.Listing listing) {}

    private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();

    private PublicationTableCopies() {
    }

    /** Run a moderation-view rebuild: copies are lenient and unbudgeted. */
    public static <T> T forReview(Supplier<T> body) {
        return within(new Scope(true, null), body);
    }

    /**
     * Run a publish whose listing type must decide the refusal code for every table it copies,
     * including the ones reached through shared builders (an agent's embedded workflows).
     */
    public static <T> T forListing(PublicationSnapshotBudget.Listing listing, Supplier<T> body) {
        return within(new Scope(false, listing), body);
    }

    private static <T> T within(Scope scope, Supplier<T> body) {
        Scope previous = SCOPE.get();
        SCOPE.set(scope);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(previous);
            }
        }
    }

    /**
     * The rows of one table for a publication snapshot (RESTRICTED rows left out by
     * datasource-service). See the class comment for the strict and the review behaviour.
     *
     * @param listing the listing type when no {@link #forListing} scope says otherwise
     * @param budget  the shared budget, or null for the default limits (unit constructions)
     */
    public static List<DataSourceItemDto> copy(DataSourceClient client, PublicationSnapshotBudget budget,
                                               PublicationSnapshotBudget.Listing listing,
                                               Long dataSourceId, String tableName,
                                               String tenantId, String organizationId) {
        Scope scope = SCOPE.get();
        if (scope != null && scope.review()) {
            return client.getAllItems(dataSourceId, tenantId, organizationId);
        }
        List<DataSourceItemDto> rows;
        try {
            rows = client.copyAllItems(dataSourceId, tenantId, organizationId);
        } catch (TableCopyException e) {
            throw copyFailed(dataSourceId, tableName, e);
        }
        PublicationSnapshotBudget.Listing effective = scope != null && scope.listing() != null ? scope.listing() : listing;
        (budget != null ? budget : PublicationSnapshotBudget.defaults(null))
                .assertTableRows(effective, dataSourceId != null ? dataSourceId.toString() : null, tableName,
                        rows != null ? rows.size() : 0);
        return rows != null ? rows : List.of();
    }

    private static PublicationValidationException copyFailed(Long dataSourceId, String tableName, TableCopyException e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("retryable", true);
        if (dataSourceId != null) details.put("tableId", dataSourceId.toString());
        if (tableName != null) details.put("tableName", tableName);
        String label = tableName != null ? "'" + tableName + "'" : String.valueOf(dataSourceId);
        return new PublicationValidationException(PublicationValidationException.TABLE_COPY_FAILED,
                "The rows of table " + label + " (id " + dataSourceId + ") could not be read just now, so nothing "
                        + "was published. This is temporary: publish again in a moment.",
                details);
    }
}
