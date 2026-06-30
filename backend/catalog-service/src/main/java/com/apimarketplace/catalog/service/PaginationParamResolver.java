package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.ToolContextService.ToolContext.ParamMeta;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolve the cursor and page-size parameter names from a tool's input schema
 * for use in {@code nextAction.params.parameters} pagination hints.
 *
 * <p>Two ordered priority lists (cursor candidates, size candidates) matched
 * <b>exactly, case-insensitively</b> against parameter names. A separate
 * {@code endsWith} blocklist suppresses non-canonical aliases that might
 * shadow priority names.
 *
 * <p>Audit-driven design (v3 + v4):
 * <ul>
 *   <li>Exact-name match (not substring) - eliminates the v2 false positive
 *       where {@code page} matched {@code pageSize}.</li>
 *   <li>Tier-1 SaaS conventions covered: Apify ({@code offset}), Slack
 *       ({@code cursor}), Drive ({@code pageToken}), Notion ({@code start_cursor}),
 *       Stripe ({@code starting_after}), Twitter ({@code pagination_token}),
 *       AWS S3 ({@code ContinuationToken}), Microsoft Graph ({@code top}).</li>
 *   <li>{@code endsWith} blocklist (not substring) - defense-in-depth, since
 *       canonical false positives are already prevented by exact-name match.</li>
 * </ul>
 */
@Component
public class PaginationParamResolver {

    /** Cursor candidates, in priority order. Earlier wins. */
    static final List<String> CURSOR_PRIORITY = List.of(
            "pageToken", "page_token", "nextPageToken", "next_page_token",
            "cursor", "next_cursor", "nextCursor",
            "start_cursor", "startCursor",
            "pagination_token", "paginationToken",
            "continuation", "continuationToken",
            "start_after", "startAfter",
            "starting_after", "startingAfter",
            "ending_before", "endingBefore",
            "offset", "skip",
            "start",
            "page", "pageNumber", "page_number"
    );

    /** Page-size candidates, in priority order. */
    static final List<String> SIZE_PRIORITY = List.of(
            "limit", "pageSize", "page_size", "perPage", "per_page",
            "max_results", "maxResults",
            "MaxKeys", "maxKeys",
            "top",
            "count", "take"
    );

    /** Defensive endsWith blocklist - applied to BOTH cursor and size resolution. */
    static final List<String> BLOCKLIST_SUFFIXES = List.of(
            "_size", "_count", "_max", "_total", "_id", "_at", "_date", "_time"
    );

    /** Resolve cursor parameter name from {@code params}. */
    public Optional<String> resolveCursor(List<ParamMeta> params) {
        return resolveByPriority(params, CURSOR_PRIORITY);
    }

    /** Resolve page-size parameter name from {@code params}. */
    public Optional<String> resolveSize(List<ParamMeta> params) {
        return resolveByPriority(params, SIZE_PRIORITY);
    }

    private Optional<String> resolveByPriority(List<ParamMeta> params, List<String> priority) {
        if (params == null || params.isEmpty()) {
            return Optional.empty();
        }
        // For each priority candidate, scan params case-insensitively.
        for (String candidate : priority) {
            for (ParamMeta p : params) {
                if (p.name() == null) continue;
                if (!p.name().equalsIgnoreCase(candidate)) continue;
                if (isBlocked(p.name())) continue;
                // Match found - return the actual parameter name (preserving its case)
                return Optional.of(p.name());
            }
        }
        return Optional.empty();
    }

    /** True iff the name ends with any blocklist suffix (case-insensitive). */
    static boolean isBlocked(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : BLOCKLIST_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }
}
