package com.apimarketplace.publication.service;

import com.apimarketplace.publication.dto.MarketplaceQueryFilter;
import com.apimarketplace.publication.dto.PublicationListItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for PublicationListQueryService.
 * Uses lenient mocking to avoid UnfinishedStubbing cascades
 * when multiple Query mocks share the same EntityManager.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PublicationListQueryService")
class PublicationListQueryServiceTest {

    @Mock private EntityManager em;
    @Mock private Query dataQuery;
    @Mock private Query countQuery;

    private PublicationListQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new PublicationListQueryService();
        Field emField = PublicationListQueryService.class.getDeclaredField("em");
        emField.setAccessible(true);
        emField.set(service, em);

        // Default stubs for Query fluent methods (lenient - not all tests use all of them)
        when(dataQuery.setFirstResult(anyInt())).thenReturn(dataQuery);
        when(dataQuery.setMaxResults(anyInt())).thenReturn(dataQuery);
        when(dataQuery.setParameter(anyString(), any())).thenReturn(dataQuery);
        when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
    }

    /**
     * Build a mock native query result row with all columns matching SELECT_COLUMNS.
     */
    private Object[] buildRow(UUID pubId, UUID workflowId, String title, UUID categoryId) {
        Instant now = Instant.now();
        return new Object[]{
                pubId,                          // 0  id
                "WORKFLOW",                     // 1  publication_type
                workflowId,                     // 2  workflow_id
                null,                           // 3  agent_config_id
                title,                          // 4  title
                "A description",                // 5  description
                UUID.randomUUID(),              // 6  showcase_interface_id
                "run_20260115_abc",             // 7  showcase_run_id
                "APPLICATION",                  // 8  display_mode
                10,                             // 9  credits_per_use
                "pub-1",                        // 10 publisher_id
                "John Doe",                     // 11 publisher_name
                "john@example.com",             // 12 publisher_email
                "https://avatar.url",           // 13 publisher_avatar_url
                "ACTIVE",                       // 14 status
                "PUBLIC",                       // 15 visibility
                "ORG",                          // 16 owner_type
                "org-1",                        // 17 owner_id
                42,                             // 18 use_count
                420,                            // 19 total_credits_earned
                3,                              // 20 plan_version
                "[{\"icon\":\"zap\"}]",         // 21 node_icons
                0,                              // 22 agent_count
                0,                              // 23 skill_count
                2,                              // 24 interface_count
                1,                              // 25 datasource_count
                0,                              // 26 workflow_count
                4.5,                            // 27 average_rating
                12,                             // 28 review_count
                Timestamp.from(now),            // 29 published_at
                Timestamp.from(now),            // 30 updated_at
                categoryId,                     // 31 cat_id
                categoryId != null ? "automation" : null,  // 32 cat_slug
                categoryId != null ? "Automation" : null,  // 33 cat_name
                categoryId != null ? "zap" : null,         // 34 cat_icon_slug
                categoryId != null ? "#8b5cf6" : null,     // 35 cat_color
                UUID.randomUUID(),              // 36 project_id
                null,                           // 37 rejection_reason
                null,                           // 38 agent_avatar_url
                null,                           // 39 agent_model_provider
                null,                           // 40 agent_model_name
                "resource-1",                   // 41 resource_id
                "a-description",                // 42 public_slug
                "john-doe",                     // 43 publisher_handle
                false,                          // 44 ce_exclusive
                null,                           // 45 ce_exclusive_features (jsonb CAST to TEXT)
                false,                          // 46 studio
                "[\"mcp:gmail\"]"               // 47 node_types (jsonb CAST to TEXT)
        };
    }

    private void stubForPaginatedQuery(List<Object[]> rows, long totalCount) {
        doReturn(dataQuery).doReturn(countQuery).when(em).createNativeQuery(anyString());
        when(dataQuery.getResultList()).thenReturn(rows);
        when(countQuery.getSingleResult()).thenReturn(totalCount);
    }

    private void stubForSimpleQuery(List<Object[]> rows) {
        when(em.createNativeQuery(anyString())).thenReturn(dataQuery);
        when(dataQuery.getResultList()).thenReturn(rows);
    }

    @Nested
    @DisplayName("findMarketplacePublications")
    class FindMarketplace {

        @Test
        @DisplayName("should return paginated results without planSnapshot")
        void paginatedResults() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            UUID catId = UUID.randomUUID();

            stubForPaginatedQuery(List.<Object[]>of(buildRow(pubId, wfId, "My Workflow", catId)), 1L);

            Page<PublicationListItem> page = service.findMarketplacePublications(0, 20);

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent()).hasSize(1);

            PublicationListItem item = page.getContent().get(0);
            assertThat(item.id()).isEqualTo(pubId);
            assertThat(item.workflowId()).isEqualTo(wfId);
            assertThat(item.title()).isEqualTo("My Workflow");
            assertThat(item.status()).isEqualTo("ACTIVE");
            assertThat(item.categorySlug()).isEqualTo("automation");
            // Pin the positional SELECT↔mapRow↔record contract for the owner columns added at
            // indices 16/17 - an off-by-one there would silently misalign every later column.
            assertThat(item.ownerType()).isEqualTo("ORG");
            assertThat(item.ownerId()).isEqualTo("org-1");
            // V413 - the two public-SEO columns. They used to be the LAST entries of
            // SELECT_COLUMNS, which is what made this assertion a position check; they are not any
            // more (ce_exclusive, ce_exclusive_features and studio come after them), so the
            // positional guarantee now lives in PublicationListSelectMappingInvariantTest, which
            // compares the whole SELECT list against mapRow. What is left here is the ordinary
            // question of whether these two fields carry their values.
            assertThat(item.publicSlug()).isEqualTo("a-description");
            assertThat(item.publisherHandle()).isEqualTo("john-doe");
            assertThat(item.toResponseMap())
                    .containsEntry("publicSlug", "a-description")
                    .containsEntry("publisherHandle", "john-doe");
        }

        @Test
        @DisplayName("should return empty page when no results")
        void emptyPage() {
            stubForPaginatedQuery(List.of(), 0L);

            Page<PublicationListItem> page = service.findMarketplacePublications(0, 10);

            assertThat(page.getTotalElements()).isZero();
            assertThat(page.getContent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Marketplace browse ordering (most-liked first)")
    class MarketplacePopularityOrdering {

        /**
         * Captures every SQL string handed to the EntityManager, so a test can
         * assert on the data query while ignoring the COUNT companion.
         */
        private List<String> captureSql(Runnable call) {
            List<String> sql = new java.util.ArrayList<>();
            doAnswer(inv -> {
                sql.add(inv.getArgument(0));
                return sql.size() == 1 ? dataQuery : countQuery;
            }).when(em).createNativeQuery(anyString());
            when(dataQuery.getResultList()).thenReturn(List.of());
            when(countQuery.getSingleResult()).thenReturn(0L);

            call.run();
            return sql;
        }

        private void assertOrdersByPopularity(String dataSql) {
            // Favorites are the heaviest term and are counted per publication.
            assertThat(dataSql)
                    .contains("3 * (SELECT COUNT(*) FROM user_publication_favorites f WHERE f.publication_id = p.id)")
                    .contains("2 * COALESCE(p.use_count, 0)")
                    // Rating MASS (avg x count), not the bare average: one 5-star
                    // vote must not outrank a 4.5 backed by twenty.
                    .contains("0.4 * COALESCE(p.average_rating, 0) * COALESCE(p.review_count, 0)")
                    .contains("DESC, p.published_at DESC");
            // Regression guard: pre-fix this was a plain chronological feed.
            assertThat(dataSql).doesNotContain("ORDER BY p.published_at DESC");
        }

        @Test
        @DisplayName("findMarketplacePublications orders by the popularity score, not by published_at")
        void marketplaceOrdersByPopularity() {
            List<String> sql = captureSql(() -> service.findMarketplacePublications(0, 10));

            assertOrdersByPopularity(sql.get(0));
        }

        @Test
        @DisplayName("Category-filtered marketplace keeps the same popularity ordering")
        void categoryMarketplaceOrdersByPopularity() {
            List<String> sql = captureSql(() -> service.findMarketplacePublicationsByCategory("finance", 0, 10));

            assertOrdersByPopularity(sql.get(0));
        }

        @Test
        @DisplayName("Agent marketplace keeps the same popularity ordering")
        void agentMarketplaceOrdersByPopularity() {
            List<String> sql = captureSql(() -> service.findMarketplaceAgentPublications(0, 10));

            assertOrdersByPopularity(sql.get(0));
        }

        @Test
        @DisplayName("Per-type marketplace keeps the same popularity ordering")
        void typedMarketplaceOrdersByPopularity() {
            List<String> sql = captureSql(() -> service.findMarketplaceByType("SKILL", 0, 10));

            assertOrdersByPopularity(sql.get(0));
        }

        @Test
        @DisplayName("The COUNT companion query stays free of the ORDER BY (Postgres would reject the non-grouped published_at, and ordering a count is pointless anyway)")
        void countQueryHasNoOrderBy() {
            List<String> sql = captureSql(() -> service.findMarketplacePublications(0, 10));

            assertThat(sql).hasSize(2);
            assertThat(sql.get(1))
                    .startsWith("SELECT COUNT(*)")
                    .doesNotContain("ORDER BY");
        }

        @Test
        @DisplayName("A publication with no engagement at all still sorts by recency (graceful degradation to the previous behaviour)")
        void tieBreaksOnRecency() {
            List<String> sql = captureSql(() -> service.findMarketplacePublications(0, 10));

            // Every zero-engagement row scores 0, so published_at is what actually
            // orders the (currently large) untouched tail.
            assertThat(sql.get(0)).endsWith("p.published_at DESC\n");
        }
    }

    /**
     * The marketplace grid used to fetch one popularity-ordered page and apply
     * type / sort / rating / date / price in the browser. Each of these pins one
     * refinement to the SQL, which is what makes it a filter over the CATALOGUE
     * instead of over whatever that page happened to contain.
     */
    @Nested
    @DisplayName("Marketplace refinements are answered by SQL, not by the client")
    class MarketplaceRefinements {

        private final List<String> sql = new java.util.ArrayList<>();

        /** Capture both SQL strings (data query first, COUNT companion second). */
        private void captureSql(Runnable call) {
            doAnswer(inv -> {
                sql.add(inv.getArgument(0));
                return sql.size() == 1 ? dataQuery : countQuery;
            }).when(em).createNativeQuery(anyString());
            when(dataQuery.getResultList()).thenReturn(List.of());
            when(countQuery.getSingleResult()).thenReturn(0L);
            call.run();
        }

        private String dataSql() {
            return sql.get(0);
        }

        private String countSql() {
            return sql.get(1);
        }

        private void run(MarketplaceQueryFilter filter) {
            captureSql(() -> service.findMarketplacePublications(filter, 0, 24));
        }

        @Test
        @DisplayName("A type filter becomes a display_mode predicate instead of a client-side pass over one page")
        void typeFilterReachesSql() {
            run(MarketplaceQueryFilter.fromRequest(null, "AGENT", null, null, null, null, null));

            assertThat(dataSql()).contains("AND p.display_mode = :displayMode");
            verify(dataQuery).setParameter("displayMode", "AGENT");
        }

        @Test
        @DisplayName("sort=recent orders by published_at in SQL - the fix for a new publication being unreachable")
        void recentSortReachesSql() {
            run(MarketplaceQueryFilter.fromRequest(null, null, "recent", null, null, null, null));

            // The reported bug: a freshly published app scores 0 on popularity
            // (no installs, favorites or reviews) so it sorted last, past the
            // single page the client held - and "Recent", being a client-side
            // re-sort of that page, could never bring it back. Only an ORDER BY
            // in the query can.
            assertThat(dataSql()).contains("ORDER BY p.published_at DESC");
            assertThat(dataSql()).doesNotContain("user_publication_favorites");
        }

        @Test
        @DisplayName("sort=installs orders by use_count in SQL")
        void installsSortReachesSql() {
            run(MarketplaceQueryFilter.fromRequest(null, null, "installs", null, null, null, null));

            assertThat(dataSql()).contains("ORDER BY p.use_count DESC, p.published_at DESC");
        }

        @Test
        @DisplayName("sort=rating ranks unrated LAST rather than as a 0 average")
        void ratingSortPutsUnratedLast() {
            run(MarketplaceQueryFilter.fromRequest(null, null, "rating", null, null, null, null));

            assertThat(dataSql())
                    .contains("CASE WHEN p.review_count > 0 THEN p.average_rating ELSE -1 END");
        }

        @Test
        @DisplayName("An unknown sort falls back to popularity rather than failing the request")
        void unknownSortFallsBackToPopularity() {
            run(MarketplaceQueryFilter.fromRequest(null, null, "whatever", null, null, null, null));

            assertThat(dataSql()).contains("user_publication_favorites");
        }

        @Test
        @DisplayName("rating=min_4 requires at least one review AND binds the 4.0 floor")
        void ratingFloorReachesSql() {
            run(MarketplaceQueryFilter.fromRequest(null, null, null, "min_4", null, null, null));

            assertThat(dataSql())
                    .contains("AND p.review_count > 0")
                    .contains("AND p.average_rating >= :minRating");
            verify(dataQuery).setParameter("minRating", 4d);
        }

        @Test
        @DisplayName("rating=rated asks only for a review, with no average floor bound")
        void ratedOnlyBindsNoFloor() {
            run(MarketplaceQueryFilter.fromRequest(null, null, null, "rated", null, null, null));

            assertThat(dataSql()).contains("AND p.review_count > 0").doesNotContain(":minRating");
            verify(dataQuery, org.mockito.Mockito.never())
                    .setParameter(org.mockito.ArgumentMatchers.eq("minRating"), any());
        }

        @Test
        @DisplayName("days=7 binds an absolute published_at floor about seven days back")
        void dateWindowReachesSql() {
            Instant before = Instant.now();
            run(MarketplaceQueryFilter.fromRequest(null, null, null, null, 7, null, null));

            assertThat(dataSql()).contains("AND p.published_at >= :publishedAfter");
            verify(dataQuery).setParameter(
                    org.mockito.ArgumentMatchers.eq("publishedAfter"),
                    argThat(value -> {
                        Instant bound = ((OffsetDateTime) value).toInstant();
                        // Seven days before "now", where now is bracketed by the
                        // instants either side of the call.
                        return !bound.isBefore(before.minus(java.time.Duration.ofDays(7)).minusSeconds(5))
                                && !bound.isAfter(Instant.now().minus(java.time.Duration.ofDays(7)).plusSeconds(5));
                    }));
        }

        @Test
        @DisplayName("A non-positive window is no window at all, not a request for future publications")
        void nonPositiveWindowIsIgnored() {
            run(MarketplaceQueryFilter.fromRequest(null, null, null, null, 0, null, null));

            assertThat(dataSql()).doesNotContain(":publishedAfter");
        }

        @Test
        @DisplayName("price=free keeps only publications that cost nothing")
        void freePriceReachesSql() {
            run(MarketplaceQueryFilter.fromRequest(null, null, null, null, null, "free", null));

            assertThat(dataSql()).contains("AND p.credits_per_use <= 0");
        }

        @Test
        @DisplayName("price=paid keeps only publications that charge credits")
        void paidPriceReachesSql() {
            run(MarketplaceQueryFilter.fromRequest(null, null, null, null, null, "paid", null));

            assertThat(dataSql()).contains("AND p.credits_per_use > 0");
        }

        @Test
        @DisplayName("The studio axis narrows the query, and COMPOSES with a category instead of replacing it")
        void studioAxisComposesWithCategory() {
            // The whole reason studio is a flag and not a category: a video studio is a Content app
            // AND a studio app. Asked together, both predicates have to be in the SQL - a filter
            // that dropped either would answer a different question than the reader asked.
            run(MarketplaceQueryFilter.fromRequest("content", null, null, null, null, null, true));

            assertThat(dataSql())
                    .contains("AND p.category_slug = :categorySlug")
                    .contains("AND p.studio = :studio");
            // The COUNT companion too. Binding the parameter is not the same as referencing it: a
            // count that lost the predicate would still bind `studio` and still page the grid
            // against the WHOLE catalogue, offering a next page long after the shelf ran out.
            assertThat(countSql()).contains("AND p.studio = :studio");
            verify(dataQuery).setParameter("studio", true);
            verify(countQuery).setParameter("studio", true);
        }

        @Test
        @DisplayName("No studio constraint leaves the predicate out entirely")
        void noStudioConstraintIsNotAPredicate() {
            // Binding `false` by default would hide every studio application from the ordinary
            // marketplace, which is the opposite of a second axis.
            run(MarketplaceQueryFilter.unfiltered());

            assertThat(dataSql()).doesNotContain("p.studio =");
        }

        @Test
        @DisplayName("A studio constraint alone is not 'unfiltered' - it does narrow the grid")
        void studioAloneIsARefinement() {
            // isUnfiltered() drives a fast path that answers the whole catalogue. A refinement it
            // does not know about would be silently discarded there.
            assertThat(MarketplaceQueryFilter.fromRequest(null, null, null, null, null, null, true).isUnfiltered()).isFalse();
        }

        @Test
        @DisplayName("The COUNT companion carries the SAME predicates, so totalPages describes the filtered set")
        void countQueryCarriesTheSamePredicates() {
            run(MarketplaceQueryFilter.fromRequest("ai", "APPLICATION", "recent", "min_3", 30, "free", null));

            // Without this, "load more" would page against a total computed over
            // the WHOLE catalogue: the grid would keep offering a next page long
            // after the filtered results ran out.
            assertThat(countSql())
                    .startsWith("SELECT COUNT(*)")
                    .contains("AND p.category_slug = :categorySlug")
                    .contains("AND p.display_mode = :displayMode")
                    .contains("AND p.review_count > 0")
                    .contains("AND p.published_at >= :publishedAfter")
                    .contains("AND p.credits_per_use <= 0")
                    .doesNotContain("ORDER BY");
            // Both queries must BIND what they reference or Hibernate throws.
            verify(countQuery).setParameter("displayMode", "APPLICATION");
            verify(countQuery).setParameter("categorySlug", "ai");
        }

        @Test
        @DisplayName("The two queries share ONE where clause, so no future refinement can reach only one")
        void countAndDataShareTheirWhereClause() {
            // The general form of the test above, which names its predicates one by one and so only
            // ever protects the refinements somebody remembered to list. This compares the clauses
            // themselves: a refinement added to the browse query and not to its count is caught
            // whether or not anyone thinks to extend a list here. Every refinement is switched on so
            // the comparison is made over the widest clause the builder can produce.
            run(MarketplaceQueryFilter.fromRequest("ai", "APPLICATION", "recent", "min_3", 30, "free", true));

            assertThat(whereOf(countSql()))
                    .as("the count and the browse query must filter identically")
                    .isEqualTo(whereOf(dataSql()));
            // And the clause is not empty, or the assertion above would hold for two queries that
            // filter nothing at all.
            assertThat(whereOf(countSql())).contains("AND p.studio = :studio");
        }

        /** Everything from WHERE up to the ordering, which the count query has no reason to carry. */
        private String whereOf(String sql) {
            int start = sql.indexOf("WHERE");
            assertThat(start).as("no WHERE clause in: %s", sql).isNotNegative();
            int end = sql.indexOf("ORDER BY", start);
            return (end < 0 ? sql.substring(start) : sql.substring(start, end)).trim();
        }

        @Test
        @DisplayName("An unfiltered browse emits no refinement predicate at all (unchanged for existing callers)")
        void unfilteredEmitsNoPredicates() {
            run(MarketplaceQueryFilter.unfiltered());

            assertThat(dataSql())
                    .doesNotContain(":displayMode")
                    .doesNotContain(":minRating")
                    .doesNotContain(":publishedAfter")
                    // credits_per_use is a SELECTED column, so only the predicate form proves absence.
                    .doesNotContain("AND p.credits_per_use")
                    .doesNotContain("AND p.review_count > 0")
                    .contains("user_publication_favorites");
        }

        @Test
        @DisplayName("Page size is clamped, so a caller cannot ask the marketplace for the whole table")
        void pageSizeIsClamped() {
            captureSql(() -> service.findMarketplacePublications(MarketplaceQueryFilter.unfiltered(), 0, 5000));

            verify(dataQuery).setMaxResults(100);
        }

        @Test
        @DisplayName("Search applies the same refinements, so typing narrows the grid instead of resetting it")
        void searchSharesTheRefinements() {
            captureSql(() -> service.searchMarketplace(
                    "invoice", MarketplaceQueryFilter.fromRequest(null, "APPLICATION", "recent", null, null, "free", null)));

            assertThat(dataSql())
                    .contains("AND p.display_mode = :displayMode")
                    .contains("AND p.credits_per_use <= 0")
                    .contains("ORDER BY p.published_at DESC");
            verify(dataQuery).setParameter("displayMode", "APPLICATION");
        }

        @Test
        @DisplayName("Search matches the description too - a visitor types what the app DOES, which the title often omits")
        void searchMatchesDescription() {
            captureSql(() -> service.searchMarketplace("invoice", null));

            assertThat(dataSql()).contains("LOWER(p.description) LIKE LOWER(:search)");
            verify(dataQuery).setParameter("search", "%invoice%");
        }
    }

    @Nested
    @DisplayName("searchMarketplace")
    class SearchMarketplace {

        @Test
        @DisplayName("should pass search term with wildcards")
        void searchWithWildcards() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();

            stubForSimpleQuery(List.<Object[]>of(buildRow(pubId, wfId, "Test Workflow", null)));

            List<PublicationListItem> results = service.searchMarketplace("test", null);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).title()).isEqualTo("Test Workflow");
            verify(dataQuery).setParameter("search", "%test%");
        }

        @Test
        @DisplayName("D-3 regression: search WITHOUT category does not bind a categorySlug parameter and SQL omits the AND clause")
        void searchWithoutCategoryOmitsAndClause() {
            stubForSimpleQuery(List.of());
            java.util.concurrent.atomic.AtomicReference<String> capturedSql = new java.util.concurrent.atomic.AtomicReference<>();
            doReturn(dataQuery).when(em).createNativeQuery(anyString());
            when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
                capturedSql.set(inv.getArgument(0));
                return dataQuery;
            });

            service.searchMarketplace("hello", MarketplaceQueryFilter.ofCategory(null));

            // Note: "p.category_slug" appears in the SELECT clause unconditionally
            // (it's a returned column). The WHERE clause must NOT contain the AND
            // filter when no category is supplied.
            assertThat(capturedSql.get()).doesNotContain("AND p.category_slug = :categorySlug");
            verify(dataQuery, org.mockito.Mockito.never()).setParameter(org.mockito.ArgumentMatchers.eq("categorySlug"), any());
        }

        @Test
        @DisplayName("D-3 regression: search WITH category ANDs the slug into the SQL and binds the parameter")
        void searchWithCategoryAddsAndClause() {
            stubForSimpleQuery(List.of());
            java.util.concurrent.atomic.AtomicReference<String> capturedSql = new java.util.concurrent.atomic.AtomicReference<>();
            when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
                capturedSql.set(inv.getArgument(0));
                return dataQuery;
            });

            service.searchMarketplace("loft", MarketplaceQueryFilter.ofCategory("rentals"));

            assertThat(capturedSql.get())
                .as("Pre-fix the /search endpoint took no category param at all - "
                    + "typing into the marketplace search bar silently dropped the active "
                    + "category filter, so a user who had picked 'Productivity' and then "
                    + "searched 'dashboard' got an unfiltered title-only result set.")
                .contains("AND p.category_slug = :categorySlug");
            verify(dataQuery).setParameter("search", "%loft%");
            verify(dataQuery).setParameter("categorySlug", "rentals");
        }

        @Test
        @DisplayName("Blank category string is treated as 'no filter' - no AND clause, no parameter bind")
        void searchWithBlankCategoryTreatedAsNoFilter() {
            stubForSimpleQuery(List.of());
            java.util.concurrent.atomic.AtomicReference<String> capturedSql = new java.util.concurrent.atomic.AtomicReference<>();
            when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
                capturedSql.set(inv.getArgument(0));
                return dataQuery;
            });

            service.searchMarketplace("loft", MarketplaceQueryFilter.ofCategory("   "));

            // Note: "p.category_slug" appears in the SELECT clause unconditionally
            // (it's a returned column). The WHERE clause must NOT contain the AND
            // filter when no category is supplied.
            assertThat(capturedSql.get()).doesNotContain("AND p.category_slug = :categorySlug");
            verify(dataQuery, org.mockito.Mockito.never()).setParameter(org.mockito.ArgumentMatchers.eq("categorySlug"), any());
        }
    }

    @Nested
    @DisplayName("findPopularPublications")
    class FindPopular {

        @Test
        @DisplayName("should pass limit parameter")
        void limitParam() {
            stubForSimpleQuery(List.of());

            List<PublicationListItem> results = service.findPopularPublications(5);

            assertThat(results).isEmpty();
            verify(dataQuery).setParameter("limit", 5);
        }
    }

    @Nested
    @DisplayName("findByPublisher / findByScope (#151 owner_type dual-write)")
    class FindByPublisher {

        @Test
        @DisplayName("findByPublisher delegates to USER-scope (owner_type='USER', owner_id=userId)")
        void filterByPublisher() {
            stubForSimpleQuery(List.of());

            List<PublicationListItem> results = service.findByPublisher("user-123");

            assertThat(results).isEmpty();
            verify(dataQuery).setParameter("ownerId", "user-123");
        }

        @Test
        @DisplayName("findByScope in org mode returns ORG-owned rows for any teammate of that org")
        void scopeOrgModeUsesOrgId() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            stubForSimpleQuery(List.<Object[]>of(buildRow(pubId, wfId, "Teammate's app", null)));

            List<PublicationListItem> results = service.findByScope("user-123", "org-acme", false);

            assertThat(results).hasSize(1);
            verify(dataQuery).setParameter("ownerId", "org-acme");
        }

        @Test
        @DisplayName("findByScope with blank organizationId routes to personal (USER) scope")
        void scopeBlankOrgRoutesToUser() {
            stubForSimpleQuery(List.of());

            service.findByScope("user-123", "   ", false);

            verify(dataQuery).setParameter("ownerId", "user-123");
        }
    }

    @Nested
    @DisplayName("findByScope - V223 scope-aware (#151)")
    class FindByScope {

        @Test
        @DisplayName("Org scope: queries owner_type='ORG' AND owner_id=orgId")
        void orgScopeQueriesOrgOwner() {
            stubForSimpleQuery(List.of());

            service.findByScope("user-alice", "org-team-1", false);

            // SQL fragment + ownerId binding
            org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(em).createNativeQuery(sqlCaptor.capture());
            assertThat(sqlCaptor.getValue()).contains("owner_type = 'ORG'");
            assertThat(sqlCaptor.getValue()).contains("owner_id = :ownerId");
            verify(dataQuery).setParameter("ownerId", "org-team-1");
        }

        @Test
        @DisplayName("Org application scope excludes member-restricted application IDs")
        void orgApplicationScopeExcludesRestrictedApplicationIds() {
            UUID deniedId = UUID.randomUUID();
            stubForSimpleQuery(List.of());

            service.findByScope("user-alice", "org-team-1", true, Set.of(deniedId));

            verify(em).createNativeQuery(argThat(sql ->
                    sql.contains("p.id NOT IN (:excludedPublicationIds)")
                            && sql.contains("p.publication_type = 'WORKFLOW'")
                            && sql.contains("p.showcase_interface_id IS NOT NULL")));
            verify(dataQuery).setParameter("excludedPublicationIds", Set.of(deniedId));
        }

        @Test
        @DisplayName("Personal scope (null org): queries owner_type='USER' AND owner_id=tenantId")
        void personalScopeQueriesUserOwner() {
            stubForSimpleQuery(List.of());

            service.findByScope("user-alice", null, false);

            org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(em).createNativeQuery(sqlCaptor.capture());
            assertThat(sqlCaptor.getValue()).contains("owner_type = 'USER'");
            verify(dataQuery).setParameter("ownerId", "user-alice");
        }

        @Test
        @DisplayName("Blank org id falls back to personal scope")
        void blankOrgFallsBackToPersonal() {
            stubForSimpleQuery(List.of());

            service.findByScope("user-alice", "   ", false);

            org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(em).createNativeQuery(sqlCaptor.capture());
            assertThat(sqlCaptor.getValue()).contains("owner_type = 'USER'");
            verify(dataQuery).setParameter("ownerId", "user-alice");
        }

        @Test
        @DisplayName("Bug #151 regression: org scope returns teammate-published rows")
        void bug151_orgScopeReturnsTeammatePublishedRows() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            // Row published by user-alice from org-team-1. Result should be
            // returned to *bob* (a teammate) querying with scope=(bob, org-team-1).
            Object[] row = buildRow(pubId, wfId, "Alice's app", null);
            row[10] = "user-alice"; // publisher_id = alice
            stubForSimpleQuery(List.<Object[]>of(row));

            // Bob queries with org scope: SQL filters on (owner_type, owner_id),
            // NOT on publisher_id - so Alice's row is returned to Bob.
            List<PublicationListItem> results = service.findByScope("user-bob", "org-team-1", false);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).publisherId()).isEqualTo("user-alice");
        }
    }

    @Nested
    @DisplayName("findByScopePaged")
    class FindByScopePaged {

        @Test
        @DisplayName("Org application scope excludes member-restricted application IDs from data and count queries")
        void orgApplicationScopeExcludesRestrictedApplicationIdsFromPagedQueries() {
            UUID deniedId = UUID.randomUUID();
            stubForPaginatedQuery(List.of(), 0L);

            Page<PublicationListItem> page =
                    service.findByScopePaged("user-alice", "org-team-1", true, "invoice", 0, 25, Set.of(deniedId));

            assertThat(page.getTotalElements()).isZero();
            verify(em).createNativeQuery(argThat(sql ->
                    sql.contains("p.id NOT IN (:excludedPublicationIds)")
                            && sql.contains("ORDER BY p.published_at DESC")));
            verify(em).createNativeQuery(argThat(sql ->
                    sql.contains("COUNT(*)")
                            && sql.contains("p.id NOT IN (:excludedPublicationIds)")));
            verify(dataQuery).setParameter("excludedPublicationIds", Set.of(deniedId));
            verify(countQuery).setParameter("excludedPublicationIds", Set.of(deniedId));
        }
    }

    @Nested
    @DisplayName("findByIds")
    class FindByIds {

        @Test
        @DisplayName("should return empty list for null IDs")
        void nullIds() {
            List<PublicationListItem> results = service.findByIds(null);
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should return empty list for empty IDs")
        void emptyIds() {
            List<PublicationListItem> results = service.findByIds(List.of());
            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("should batch load by IDs")
        void batchLoad() {
            UUID pubId1 = UUID.randomUUID();
            UUID pubId2 = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();

            stubForSimpleQuery(List.of(
                    buildRow(pubId1, wfId, "First", null),
                    buildRow(pubId2, wfId, "Second", null)
            ));

            List<PublicationListItem> results = service.findByIds(List.of(pubId1, pubId2));

            assertThat(results).hasSize(2);
        }
    }

    @Nested
    @DisplayName("findByIdsIncludingInactive (receipt-scoped: acquired apps + purchases)")
    class FindByIdsIncludingInactive {

        @Test
        @DisplayName("should return empty list for null / empty IDs")
        void emptyInputs() {
            assertThat(service.findByIdsIncludingInactive(null)).isEmpty();
            assertThat(service.findByIdsIncludingInactive(List.of())).isEmpty();
        }

        @Test
        @DisplayName("batch loads by IDs and binds the ids parameter")
        void batchLoadsAndBinds() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            stubForSimpleQuery(List.<Object[]>of(buildRow(a, UUID.randomUUID(), "Acquired app", null)));

            List<PublicationListItem> results = service.findByIdsIncludingInactive(List.of(a, b));

            assertThat(results).hasSize(1);
            verify(dataQuery).setParameter("ids", List.of(a, b));
        }

        @Test
        @DisplayName("regression: SQL scopes to status IN ('ACTIVE','INACTIVE') - so an unpublished/deleted (INACTIVE) acquired pub resolves, REJECTED/PENDING_REVIEW do NOT, and findByIds stays ACTIVE-only")
        void scopesToActiveAndInactiveUnlikeFindByIds() {
            UUID id = UUID.randomUUID();
            // Single capturing stub (re-stubbing em.createNativeQuery would re-fire the answer
            // with anyString()'s "" placeholder and clobber the capture). Order: [0] including, [1] findByIds.
            List<String> sqls = new java.util.ArrayList<>();
            when(em.createNativeQuery(anyString())).thenAnswer(inv -> { sqls.add(inv.getArgument(0)); return dataQuery; });
            when(dataQuery.getResultList()).thenReturn(List.of());

            service.findByIdsIncludingInactive(List.of(id));
            service.findByIds(List.of(id));

            assertThat(sqls).hasSize(2);
            String includingSql = sqls.get(0);
            String activeOnlySql = sqls.get(1);

            assertThat(includingSql).contains("p.id IN (:ids)");
            assertThat(includingSql)
                .as("Acquired/purchase enrichment must see INACTIVE rows so the installed-app card keeps "
                    + "rendering the interface after the publisher unpublishes/deletes the source.")
                .contains("p.status IN ('ACTIVE', 'INACTIVE')");
            assertThat(includingSql)
                .as("Scope stops at INACTIVE - a REJECTED/PENDING_REVIEW source must NOT surface its "
                    + "moderation rejectionReason on an acquirer's card.")
                .doesNotContain("REJECTED")
                .doesNotContain("PENDING_REVIEW");
            assertThat(activeOnlySql)
                .as("findByIds stays ACTIVE-only - this fix must NOT widen the public-list path.")
                .contains("p.status = 'ACTIVE'")
                .doesNotContain("INACTIVE");
        }
    }

    @Nested
    @DisplayName("Row mapping edge cases")
    class RowMapping {

        @Test
        @DisplayName("should handle null category fields (no category)")
        void nullCategoryFields() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();

            stubForSimpleQuery(List.<Object[]>of(buildRow(pubId, wfId, "No Category", null)));

            List<PublicationListItem> results = service.findByPublisher("pub-1");

            assertThat(results).hasSize(1);
            PublicationListItem item = results.get(0);
            assertThat(item.categoryId()).isNull();
            assertThat(item.categorySlug()).isNull();
            assertThat(item.toResponseMap()).containsEntry("category", null);
        }

        @Test
        @DisplayName("should map projectId so project modals can remove assigned applications")
        void mapsProjectIdForProjectAssignmentUi() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();
            Object[] row = buildRow(pubId, wfId, "Project App", null);
            row[36] = projectId; // project_id (shifted +2 by owner_type/owner_id at 16/17)

            stubForSimpleQuery(List.<Object[]>of(row));

            List<PublicationListItem> results = service.findByPublisher("pub-1");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).projectId()).isEqualTo(projectId);
            assertThat(results.get(0).toResponseMap()).containsEntry("projectId", projectId.toString());
        }

        @Test
        @DisplayName("should handle OffsetDateTime timestamps from JDBC")
        void offsetDateTimeTimestamps() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            Object[] row = buildRow(pubId, wfId, "ODT Test", null);
            OffsetDateTime odt = OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.UTC);
            row[29] = odt; // published_at as OffsetDateTime (shifted +2 by owner_type/owner_id)
            row[30] = odt; // updated_at as OffsetDateTime

            stubForSimpleQuery(List.<Object[]>of(row));

            List<PublicationListItem> results = service.findByPublisher("pub-1");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).publishedAt()).isEqualTo(odt.toInstant());
        }

        @Test
        @DisplayName("should handle null optional fields gracefully")
        void nullOptionalFields() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            Object[] row = buildRow(pubId, wfId, "Minimal", null);
            row[6] = null;  // showcase_interface_id
            row[7] = null;  // showcase_run_id
            row[11] = null; // publisher_name
            row[12] = null; // publisher_email
            row[13] = null; // publisher_avatar_url
            row[20] = null; // plan_version (shifted +2 by owner_type/owner_id at 16/17)
            row[21] = null; // node_icons

            stubForSimpleQuery(List.<Object[]>of(row));

            List<PublicationListItem> results = service.findByPublisher("pub-1");

            assertThat(results).hasSize(1);
            PublicationListItem item = results.get(0);
            assertThat(item.showcaseInterfaceId()).isNull();
            assertThat(item.showcaseRunId()).isNull();
            assertThat(item.publisherName()).isNull();
            assertThat(item.planVersion()).isNull();
            assertThat(item.nodeIcons()).isNull();
        }

        @Test
        @DisplayName("should map the CE-exclusive columns and expose them on the response")
        void mapsCeExclusiveColumns() {
            Object[] row = buildRow(UUID.randomUUID(), UUID.randomUUID(), "RAG App", null);
            row[44] = true;                                  // ce_exclusive
            row[45] = "[\"CLI_AGENT\",\"VECTOR_SEARCH\"]";   // ce_exclusive_features

            stubForSimpleQuery(List.<Object[]>of(row));

            PublicationListItem item = service.findByPublisher("pub-1").get(0);

            assertThat(item.ceExclusive()).isTrue();
            assertThat(item.toResponseMap())
                    .containsEntry("ceExclusive", true)
                    .containsEntry("ceExclusiveFeatures", List.of("CLI_AGENT", "VECTOR_SEARCH"));
        }

        @Test
        @DisplayName("a non-exclusive row still emits ceExclusive=false and an EMPTY feature list")
        void ceExclusiveDefaultsAreExplicit() {
            // The UI must never have to guess: an absent key would read as unknown.
            Object[] row = buildRow(UUID.randomUUID(), UUID.randomUUID(), "Plain App", null);

            stubForSimpleQuery(List.<Object[]>of(row));

            assertThat(service.findByPublisher("pub-1").get(0).toResponseMap())
                    .containsEntry("ceExclusive", false)
                    .containsEntry("ceExclusiveFeatures", List.of());
        }

        @Test
        @DisplayName("a boolean column returned as a Number (H2) still maps to true")
        void ceExclusiveAcceptsNumericBoolean() {
            Object[] row = buildRow(UUID.randomUUID(), UUID.randomUUID(), "RAG App", null);
            row[44] = 1;

            stubForSimpleQuery(List.<Object[]>of(row));

            assertThat(service.findByPublisher("pub-1").get(0).ceExclusive()).isTrue();
        }

        @Test
        @DisplayName("a boolean returned TEXTUALLY maps on Postgres' own spellings, not just \"true\"")
        void ceExclusiveAcceptsTextualBooleans() {
            // Boolean.parseBoolean("t") is FALSE - taking that path would silently
            // un-flag the row and offer the Install button on managed cloud.
            for (Object truthy : List.of("t", "T", "1", "true", "TRUE")) {
                Object[] row = buildRow(UUID.randomUUID(), UUID.randomUUID(), "RAG App", null);
                row[44] = truthy;
                stubForSimpleQuery(List.<Object[]>of(row));

                assertThat(service.findByPublisher("pub-1").get(0).ceExclusive())
                        .as("value %s", truthy).isTrue();
            }
            for (Object falsy : List.of("f", "0", "false")) {
                Object[] row = buildRow(UUID.randomUUID(), UUID.randomUUID(), "Plain App", null);
                row[44] = falsy;
                stubForSimpleQuery(List.<Object[]>of(row));

                assertThat(service.findByPublisher("pub-1").get(0).ceExclusive())
                        .as("value %s", falsy).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("suggestApplications (onboarding suggestions)")
    class SuggestApplications {

        @Test
        @DisplayName("Category match: filters category_slug IN, restricts to WORKFLOW+showcase, orders by use_count, binds slugs + limit")
        void categoryMatchReturnsApplications() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            java.util.concurrent.atomic.AtomicReference<String> capturedSql = new java.util.concurrent.atomic.AtomicReference<>();
            when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
                capturedSql.set(inv.getArgument(0));
                return dataQuery;
            });
            when(dataQuery.getResultList())
                    .thenReturn(List.<Object[]>of(buildRow(pubId, wfId, "CRM Sync", UUID.randomUUID())));

            List<PublicationListItem> results =
                    service.suggestApplications(List.of("sales-crm", "marketing"), 8);

            assertThat(results).hasSize(1);
            assertThat(capturedSql.get()).contains("p.category_slug IN (:slugs)");
            assertThat(capturedSql.get())
                    .contains("p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL");
            assertThat(capturedSql.get()).contains("ORDER BY p.use_count DESC");
            verify(dataQuery).setParameter("slugs", List.of("sales-crm", "marketing"));
            verify(dataQuery).setMaxResults(8);
        }

        @Test
        @DisplayName("Empty category result falls back to top applications across all categories (no category filter)")
        void fallbackWhenCategoryEmpty() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            List<String> sqls = new java.util.ArrayList<>();
            when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
                sqls.add(inv.getArgument(0));
                return dataQuery;
            });
            List<Object[]> empty = List.of();
            List<Object[]> one = List.<Object[]>of(buildRow(pubId, wfId, "Popular App", null));
            // First (category-filtered) query empty → triggers fallback; second returns a row.
            // Chained thenReturn (not varargs) to avoid generic-array inference on the raw List.
            when(dataQuery.getResultList()).thenReturn(empty).thenReturn(one);

            List<PublicationListItem> results = service.suggestApplications(List.of("finance"), 8);

            assertThat(results).hasSize(1);
            assertThat(sqls).hasSize(2);
            assertThat(sqls.get(1)).doesNotContain("category_slug IN");
            assertThat(sqls.get(1))
                    .contains("p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL");
        }

        @Test
        @DisplayName("Empty category slugs query only the fallback (single query, no slugs bind)")
        void emptySlugsUsesFallbackOnly() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            java.util.concurrent.atomic.AtomicReference<String> capturedSql = new java.util.concurrent.atomic.AtomicReference<>();
            when(em.createNativeQuery(anyString())).thenAnswer(inv -> {
                capturedSql.set(inv.getArgument(0));
                return dataQuery;
            });
            when(dataQuery.getResultList())
                    .thenReturn(List.<Object[]>of(buildRow(pubId, wfId, "App", null)));

            List<PublicationListItem> results = service.suggestApplications(List.of(), 8);

            assertThat(results).hasSize(1);
            verify(em, org.mockito.Mockito.times(1)).createNativeQuery(anyString());
            assertThat(capturedSql.get()).doesNotContain("category_slug IN");
            verify(dataQuery, org.mockito.Mockito.never())
                    .setParameter(org.mockito.ArgumentMatchers.eq("slugs"), any());
        }

        @Test
        @DisplayName("Limit is clamped to [1, 50]")
        void limitClamped() {
            when(em.createNativeQuery(anyString())).thenReturn(dataQuery);
            when(dataQuery.getResultList()).thenReturn(List.of());

            service.suggestApplications(List.of(), 0);
            verify(dataQuery).setMaxResults(1);

            service.suggestApplications(List.of(), 999);
            verify(dataQuery).setMaxResults(50);
        }
    }

    @Nested
    @DisplayName("findPublicByPublisherPaged (public profile - apps by this user)")
    class FindPublicByPublisher {

        @Test
        @DisplayName("filters by publisher_id + ACTIVE + PUBLIC, binds publisherId on data and count queries, maps rows")
        void filtersByPublisherActivePublic() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();
            stubForPaginatedQuery(List.<Object[]>of(buildRow(pubId, wfId, "Alice's public app", null)), 1L);

            Page<PublicationListItem> page = service.findPublicByPublisherPaged("user-alice", 0, 20);

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).title()).isEqualTo("Alice's public app");

            verify(em).createNativeQuery(argThat(sql ->
                    sql.contains("p.publisher_id = :publisherId")
                            && sql.contains("p.status = 'ACTIVE'")
                            && sql.contains("p.visibility = 'PUBLIC'")
                            && sql.contains("ORDER BY p.published_at DESC")));
            verify(em).createNativeQuery(argThat(sql ->
                    sql.contains("COUNT(*)")
                            && sql.contains("p.publisher_id = :publisherId")
                            && sql.contains("p.status = 'ACTIVE'")
                            && sql.contains("p.visibility = 'PUBLIC'")));
            verify(dataQuery).setParameter("publisherId", "user-alice");
            verify(countQuery).setParameter("publisherId", "user-alice");
        }

        @Test
        @DisplayName("ACTIVE+PUBLIC are SQL literals, not caller params - a PRIVATE/INACTIVE app can never surface on a public profile")
        void onlyActivePublicByConstruction() {
            stubForPaginatedQuery(List.of(), 0L);

            service.findPublicByPublisherPaged("user-bob", 0, 20);

            verify(dataQuery, org.mockito.Mockito.never())
                    .setParameter(org.mockito.ArgumentMatchers.eq("visibility"), any());
            verify(dataQuery, org.mockito.Mockito.never())
                    .setParameter(org.mockito.ArgumentMatchers.eq("status"), any());
        }

        @Test
        @DisplayName("returns an empty page when the user has no public apps")
        void emptyPage() {
            stubForPaginatedQuery(List.of(), 0L);

            Page<PublicationListItem> page = service.findPublicByPublisherPaged("nobody", 0, 20);

            assertThat(page.getTotalElements()).isZero();
            assertThat(page.getContent()).isEmpty();
        }

        @Test
        @DisplayName("clamps page size to [1,100] and page index to >= 0")
        void clampsPagination() {
            stubForPaginatedQuery(List.of(), 0L);

            Page<PublicationListItem> page = service.findPublicByPublisherPaged("u", -3, 9999);

            assertThat(page.getSize()).isEqualTo(100);
            assertThat(page.getNumber()).isZero();
            verify(dataQuery).setMaxResults(100);
        }
    }
}
