package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.service.ToolContextService.ToolContext.ParamMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PaginationParamResolver}.
 *
 * <p>Walks the audit-supplied real-API matrix to lock the heuristic against
 * regression. Tier-1 SaaS conventions covered: Apify, Slack, Drive, Notion,
 * GitHub, Twitter, AWS S3, Stripe, Microsoft Graph.
 */
@DisplayName("PaginationParamResolver")
class PaginationParamResolverTest {

    private PaginationParamResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PaginationParamResolver();
    }

    private static List<ParamMeta> params(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> new ParamMeta(n, ""))
                .toList();
    }

    // ---- exact-match priority + canonical wins ------------------------------

    @Test
    @DisplayName("cursorResolvedExactMatchOffset")
    void cursorResolvedExactMatchOffset() {
        assertEquals(Optional.of("offset"),
                resolver.resolveCursor(params("dataset_id", "offset", "limit", "clean")));
    }

    @Test
    @DisplayName("cursorResolvedPageTokenWinsOverPage")
    void cursorResolvedPageTokenWinsOverPage() {
        // pageToken is earlier in priority than page.
        assertEquals(Optional.of("pageToken"),
                resolver.resolveCursor(params("page", "pageToken", "q")));
    }

    // ---- false-positive guards (regression for substring bug) ---------------

    @Test
    @DisplayName("cursorRejectsPageSize - substring `page` must NOT match `pageSize`")
    void cursorRejectsPageSize() {
        // Only pageSize present, no canonical cursor. Resolver must miss.
        assertEquals(Optional.empty(),
                resolver.resolveCursor(params("dataset_id", "pageSize")));
    }

    @Test
    @DisplayName("cursorRejectsStartTimeAndStartDate - substring `start` must NOT match `start_time`/`start_date`")
    void cursorRejectsStartTimeAndStartDate() {
        assertEquals(Optional.empty(),
                resolver.resolveCursor(params("startTime", "start_date", "id")));
    }

    @Test
    @DisplayName("cursorRejectsCustomerIdEvenIfNamedCursor - _id endsWith blocklist guards")
    void cursorRejectsCursorIdViaBlocklist() {
        // cursor_id contains the priority `cursor` substring, but exact match
        // requires "cursor" to equal "cursor_id" - false. Then even if a future
        // priority entry added "cursor_id", the _id blocklist catches it.
        assertEquals(Optional.empty(),
                resolver.resolveCursor(params("cursor_id", "limit")));
    }

    // ---- v4 priority list additions (Tier-1 SaaS) ---------------------------

    @Test
    @DisplayName("cursorResolvesNotionStartCursor")
    void cursorResolvesNotionStartCursor() {
        assertEquals(Optional.of("start_cursor"),
                resolver.resolveCursor(params("database_id", "start_cursor", "page_size")));
    }

    @Test
    @DisplayName("cursorResolvesStripeStartingAfter")
    void cursorResolvesStripeStartingAfter() {
        assertEquals(Optional.of("starting_after"),
                resolver.resolveCursor(params("customer", "created", "ending_before", "starting_after", "limit")));
    }

    @Test
    @DisplayName("cursorResolvesS3ContinuationTokenCaseInsensitive")
    void cursorResolvesS3ContinuationTokenCaseInsensitive() {
        // S3 uses PascalCase wire names. Match must be case-insensitive.
        assertEquals(Optional.of("ContinuationToken"),
                resolver.resolveCursor(params("Bucket", "ContinuationToken", "MaxKeys", "StartAfter")));
    }

    @Test
    @DisplayName("cursorResolvesTwitterPaginationToken")
    void cursorResolvesTwitterPaginationToken() {
        assertEquals(Optional.of("pagination_token"),
                resolver.resolveCursor(params("pagination_token", "max_results")));
    }

    // ---- size resolution -----------------------------------------------------

    @Test
    @DisplayName("sizeResolvedExactMatchLimit")
    void sizeResolvedExactMatchLimit() {
        assertEquals(Optional.of("limit"),
                resolver.resolveSize(params("offset", "limit")));
    }

    @Test
    @DisplayName("sizeResolvesTwitterMaxResults")
    void sizeResolvesTwitterMaxResults() {
        assertEquals(Optional.of("max_results"),
                resolver.resolveSize(params("pagination_token", "max_results")));
    }

    @Test
    @DisplayName("sizeResolvesS3MaxKeys")
    void sizeResolvesS3MaxKeys() {
        assertEquals(Optional.of("MaxKeys"),
                resolver.resolveSize(params("Bucket", "ContinuationToken", "MaxKeys")));
    }

    @Test
    @DisplayName("sizeResolvesGraphTop")
    void sizeResolvesGraphTop() {
        assertEquals(Optional.of("top"),
                resolver.resolveSize(params("top", "filter", "select")));
    }

    @Test
    @DisplayName("sizeRejectedWhenAbsent - Notion has page_size only")
    void sizeRejectedWhenAbsentApifyLikeButOnlyCursor() {
        // Tool with cursor only, no canonical size param.
        assertEquals(Optional.empty(),
                resolver.resolveSize(params("offset", "filter")));
    }

    // ---- isBlocked unit -----------------------------------------------------

    @Test
    @DisplayName("isBlockedDetectsSuffixesCaseInsensitive")
    void isBlockedDetectsSuffixesCaseInsensitive() {
        assertTrue(PaginationParamResolver.isBlocked("user_id"));
        assertTrue(PaginationParamResolver.isBlocked("created_at"));
        assertTrue(PaginationParamResolver.isBlocked("DUE_DATE"));
        assertTrue(PaginationParamResolver.isBlocked("token_count"));
        assertFalse(PaginationParamResolver.isBlocked("offset"));
        assertFalse(PaginationParamResolver.isBlocked("cursor"));
    }
}
