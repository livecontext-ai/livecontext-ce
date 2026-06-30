package com.apimarketplace.publication.service;

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
                "resource-1"                    // 41 resource_id
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
    @DisplayName("searchByTitle")
    class SearchByTitle {

        @Test
        @DisplayName("should pass search term with wildcards")
        void searchWithWildcards() {
            UUID pubId = UUID.randomUUID();
            UUID wfId = UUID.randomUUID();

            stubForSimpleQuery(List.<Object[]>of(buildRow(pubId, wfId, "Test Workflow", null)));

            List<PublicationListItem> results = service.searchByTitle("test");

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

            service.searchByTitle("hello", null);

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

            service.searchByTitle("loft", "rentals");

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

            service.searchByTitle("loft", "   ");

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
