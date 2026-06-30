package com.apimarketplace.datasource.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.publication.client.PublicationClient.ResourcePublicationStatusRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Tables list badge ("shared / in review / rejected / private") and its visibility filter + sort
 * are now resolved SERVER-side: {@code getDataSourcesPaged} returns the page already filtered, sorted
 * and enriched with each row's publication status in ONE batched call to publication-service (the old
 * frontend did one is-resource-published call PER row). These tests pin that contract:
 *   - the page carries {@code publicationStatuses} (id → {status, rejectionReason?});
 *   - the publication-client is hit at most ONCE per page (never per row);
 *   - visibility=public/private narrows by the shared split; sort orders name / lastModified.
 */
@ExtendWith(MockitoExtension.class)
class DataSourceServicePublicationStatusTest {

    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private PublicationClient publicationClient;

    private DataSourceService service;

    private static final String ORG = "org-1";
    private static final String USER = "user-1";

    @BeforeEach
    void setUp() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setProperty("app.edition", "ce");
        service = new DataSourceService(dataSourceRepository, dataSourceItemRepository,
                breakdownService, new ObjectMapper(), orgAccessGuard, entitlementGuard,
                new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env)),
                org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class),
                publicationClient);
        // Pass-through access filter: the page keeps every datasource the repo returns.
        when(orgAccessGuard.filterAccessible(anyList(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        // Row counts / samples are not under test here; keep them out of the way.
        when(dataSourceItemRepository.countByDataSourceIds(anyList())).thenReturn(Map.of());
        when(dataSourceItemRepository.sampleRowsByDataSourceIds(anyList(), anyInt())).thenReturn(Map.of());
    }

    private static DataSource ds(long id, String name, Instant createdAt, Instant updatedAt) {
        return new DataSource(id, "tenant", name, "desc", DataSourceType.INLINE,
                Map.of(), null, createdAt, updatedAt, USER, null, null, null, null, null, ORG);
    }

    private static DataSource ds(long id) {
        return ds(id, "table-" + id, null, null);
    }

    private static ResourcePublicationStatusRef ref(String status, String reason) {
        return new ResourcePublicationStatusRef(status, reason);
    }

    @Test
    @DisplayName("inlines each page row's publication badge (ACTIVE/PENDING_REVIEW/REJECTED+reason) in ONE batch call")
    void inlinesPageBadgeInSingleBatchCall() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L), ds(3L)));
        when(publicationClient.findResourcePublicationStatuses(eq("TABLE"), eq(List.of("1", "2", "3")), eq(USER)))
                .thenReturn(Map.of(
                        "1", ref("ACTIVE", null),
                        "2", ref("PENDING_REVIEW", null),
                        "3", ref("REJECTED", "off-topic")));

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25, null, null);

        assertThat(page.items()).hasSize(3);
        assertThat(page.publicationStatuses().get("1")).containsEntry("status", "ACTIVE");
        assertThat(page.publicationStatuses().get("2")).containsEntry("status", "PENDING_REVIEW");
        assertThat(page.publicationStatuses().get("3"))
                .containsEntry("status", "REJECTED")
                .containsEntry("rejectionReason", "off-topic");
        // The whole page's badge is resolved in a single batched call - never one per row.
        verify(publicationClient, times(1))
                .findResourcePublicationStatuses(anyString(), anyCollection(), anyString());
    }

    @Test
    @DisplayName("an unshared row is absent from publicationStatuses (the card reads it as private)")
    void unsharedRowAbsentFromStatuses() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L)));
        when(publicationClient.findResourcePublicationStatuses(eq("TABLE"), eq(List.of("1", "2")), eq(USER)))
                .thenReturn(Map.of("1", ref("ACTIVE", null))); // id 2 has no shared publication

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25, null, null);

        assertThat(page.publicationStatuses()).containsKey("1");
        assertThat(page.publicationStatuses()).doesNotContainKey("2");
    }

    @Test
    @DisplayName("visibility=public keeps only shared (ACTIVE) rows, resolved with a single full-set status call")
    void visibilityPublicKeepsOnlyShared() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L), ds(3L)));
        when(publicationClient.findResourcePublicationStatuses(eq("TABLE"), eq(List.of("1", "2", "3")), eq(USER)))
                .thenReturn(Map.of(
                        "1", ref("ACTIVE", null),
                        "2", ref("PENDING_REVIEW", null))); // 3 absent → not shared

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25, null, "public");

        assertThat(page.items()).extracting(DataSource::id).containsExactly(1L);
        assertThat(page.totalCount()).isEqualTo(1);
        // Full-set status is fetched once for the filter and REUSED for the page badge - not refetched.
        verify(publicationClient, times(1))
                .findResourcePublicationStatuses(anyString(), anyCollection(), anyString());
    }

    @Test
    @DisplayName("visibility=private keeps every non-shared row (in-review, rejected, never-published)")
    void visibilityPrivateKeepsNonShared() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L), ds(3L)));
        when(publicationClient.findResourcePublicationStatuses(eq("TABLE"), eq(List.of("1", "2", "3")), eq(USER)))
                .thenReturn(Map.of(
                        "1", ref("ACTIVE", null),
                        "2", ref("PENDING_REVIEW", null))); // 3 absent → not shared

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25, null, "private");

        assertThat(page.items()).extracting(DataSource::id).containsExactly(2L, 3L);
        assertThat(page.totalCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("visibility=all does NOT fetch the full set: status is fetched only for the page's ids")
    void visibilityAllFetchesOnlyPageIds() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(ds(1L), ds(2L), ds(3L)));
        // size=2 → only ids 1,2 are on page 0; id 3 must NOT be in the status batch.
        when(publicationClient.findResourcePublicationStatuses(eq("TABLE"), eq(List.of("1", "2")), eq(USER)))
                .thenReturn(Map.of("1", ref("ACTIVE", null)));

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 2, null, null);

        assertThat(page.items()).hasSize(2);
        verify(publicationClient).findResourcePublicationStatuses("TABLE", List.of("1", "2"), USER);
        verify(publicationClient, never()).findResourcePublicationStatuses(eq("TABLE"), eq(List.of("1", "2", "3")), eq(USER));
    }

    @Test
    @DisplayName("sort=name orders the page A→Z, case-insensitively")
    void sortByNameOrdersAlphabetically() {
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(
                        ds(1L, "banana", null, null),
                        ds(2L, "Apple", null, null),
                        ds(3L, "cherry", null, null)));
        when(publicationClient.findResourcePublicationStatuses(anyString(), anyCollection(), anyString()))
                .thenReturn(Map.of());

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25, "name", null);

        assertThat(page.items()).extracting(DataSource::name).containsExactly("Apple", "banana", "cherry");
    }

    @Test
    @DisplayName("default sort (lastModified) orders most-recent first; rows with no date sort last")
    void defaultSortOrdersByModifiedDescNullsLast() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-06-01T00:00:00Z");
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(
                        ds(1L, "old", null, older),
                        ds(2L, "new", null, newer),
                        ds(3L, "undated", null, null)));
        when(publicationClient.findResourcePublicationStatuses(anyString(), anyCollection(), anyString()))
                .thenReturn(Map.of());

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25, null, null);

        assertThat(page.items()).extracting(DataSource::id).containsExactly(2L, 1L, 3L);
    }

    @Test
    @DisplayName("lastModified falls back to createdAt when updatedAt is null")
    void lastModifiedFallsBackToCreatedAt() {
        Instant created = Instant.parse("2026-05-01T00:00:00Z");
        Instant updated = Instant.parse("2026-02-01T00:00:00Z");
        when(dataSourceRepository.findByOrganizationOrOwner(ORG, USER))
                .thenReturn(List.of(
                        ds(1L, "createdOnly", created, null),    // ranks on createdAt 2026-05
                        ds(2L, "updated", null, updated)));        // ranks on updatedAt 2026-02
        when(publicationClient.findResourcePublicationStatuses(anyString(), anyCollection(), anyString()))
                .thenReturn(Map.of());

        DataSourceService.DataSourcePage page =
                service.getDataSourcesPaged(USER, ORG, "MEMBER", null, 0, 25, null, null);

        // createdOnly (May) is more recent than updated (Feb) → it comes first.
        assertThat(page.items()).extracting(DataSource::id).containsExactly(1L, 2L);
    }
}
