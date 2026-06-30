package com.apimarketplace.orchestrator.stepdata;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Maps the canonical {@code StatusType} values the frontend sends
 * ({@code StatusBadge.tsx#mapBackendStatusToStatusType}) back to the raw DB
 * {@code WorkflowStepDataEntity.status} values that should match.
 *
 * Three canonical values cover multiple raw values:
 *   - {@code completed} ⇐ {@code completed}, {@code success}
 *   - {@code failed}    ⇐ {@code failed}, {@code error}
 *   - {@code pending}   ⇐ {@code pending}, {@code ready}, {@code awaiting_signal},
 *                         {@code waiting_trigger}, {@code collecting}
 *
 * The {@code pending} bucket mirrors {@code mapBackendStatusToStatusType}'s
 * default-fallback bucket: any unknown raw status the frontend sees is shown
 * as {@code pending}, so a filter of {@code pending} must round-trip and not
 * silently drop those rows.
 *
 * Exposed as a tiny static helper so both {@code DetailedStepDataService} and
 * {@code WorkflowRunQueryController} can apply the same filter semantics.
 */
public final class StepStatusFilter {

    private StepStatusFilter() {}

    /**
     * @return {@code true} when {@code dbStatus} should be retained for a request
     * filtering on canonical {@code filterValue}. Returns {@code true} for null/blank
     * filters so callers can pass them straight through.
     */
    public static boolean matches(String dbStatus, String filterValue) {
        if (filterValue == null || filterValue.isBlank()) return true;
        if (dbStatus == null) return false;

        String canonical = filterValue.trim().toLowerCase(Locale.ROOT);
        String raw = dbStatus.trim().toLowerCase(Locale.ROOT);
        Set<String> accepted = expand(canonical);
        return accepted.contains(raw);
    }

    private static Set<String> expand(String canonical) {
        return switch (canonical) {
            case "completed" -> Set.of("completed", "success");
            case "failed"    -> Set.of("failed", "error");
            case "pending"   -> Set.of("pending", "ready", "awaiting_signal", "waiting_trigger", "collecting");
            default          -> Set.of(canonical);
        };
    }

    /**
     * Expand a canonical filter value to the list of raw DB status values that match it.
     * Returns an empty list when {@code filterValue} is null/blank - callers should treat
     * that as "no filter" and skip the IN clause server-side.
     *
     * <p>Used by repository queries that push the canonical→raw expansion into JPQL
     * (paginated step listings), so {@code Page.totalElements} reflects the filtered count
     * without materialising all rows for in-memory filtering.
     */
    public static List<String> expandToRawList(String filterValue) {
        if (filterValue == null || filterValue.isBlank()) {
            return List.of();
        }
        return List.copyOf(expand(filterValue.trim().toLowerCase(Locale.ROOT)));
    }
}
