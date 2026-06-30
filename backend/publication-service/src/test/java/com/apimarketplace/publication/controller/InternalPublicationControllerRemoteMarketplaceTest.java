package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.RemoteMarketplaceService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The agent's {@code application(action='search')} tool reads the marketplace through
 * {@code PublicationClient} → these {@code /api/internal/publications/marketplace*} endpoints.
 * In a cloud-linked CE the marketplace the HUMAN browses is the CLOUD one (served via
 * {@code RemoteMarketplaceService}), but this install's LOCAL publication table is near-empty -
 * so before this fix the agent saw a different (local) marketplace than the human.
 *
 * <p>These tests pin the fix: when the CE-only {@link RemoteMarketplaceService} bean is present
 * the endpoints proxy to the cloud (adapting the cloud {@code {publications,count}} shape to this
 * endpoint's stable {@code {content,totalElements}} contract); in cloud (bean absent) they keep
 * querying the local repository. They also pin the shape adapter directly.
 */
@DisplayName("InternalPublicationController - agent marketplace reads honor the CE cloud proxy")
class InternalPublicationControllerRemoteMarketplaceTest {

    @SuppressWarnings("unchecked")
    private InternalPublicationController controllerWith(RemoteMarketplaceService remote,
                                                         WorkflowPublicationRepository repo) {
        ObjectProvider<RemoteMarketplaceService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(remote);
        return new InternalPublicationController(
                repo,
                mock(WorkflowPublicationService.class),
                mock(AgentPublicationService.class),
                mock(ResourcePublicationService.class),
                mock(OrchestratorInternalClient.class),
                mock(ShowcaseSnapshotBackfillService.class),
                provider);
    }

    @Nested
    @DisplayName("CE (RemoteMarketplaceService bean present) → proxy to the cloud marketplace")
    class CloudLinkedCe {

        @Test
        @DisplayName("list delegates to the cloud and adapts publications→content, count→totalElements; local repo untouched")
        void listProxiesToCloud() {
            RemoteMarketplaceService remote = mock(RemoteMarketplaceService.class);
            WorkflowPublicationRepository repo = mock(WorkflowPublicationRepository.class);
            when(remote.fetchMarketplacePublications(0, 10, null)).thenReturn(Map.of(
                    "publications", List.of(Map.of("id", "cloud-1", "title", "Cloud App")),
                    "count", 1));

            Map<String, Object> body = controllerWith(remote, repo).getMarketplacePublications(0, 10).getBody();

            assertThat(body).containsEntry("totalElements", 1L);
            assertThat((List<Map<String, Object>>) body.get("content"))
                    .singleElement()
                    .satisfies(pub -> assertThat(pub).containsEntry("id", "cloud-1"));
            // The whole point: the agent must NOT fall back to the (near-empty) local table.
            verify(repo, never()).findMarketplacePublications(any());
        }

        @Test
        @DisplayName("search delegates to the cloud search (local FTS repo untouched)")
        void searchProxiesToCloud() {
            RemoteMarketplaceService remote = mock(RemoteMarketplaceService.class);
            WorkflowPublicationRepository repo = mock(WorkflowPublicationRepository.class);
            when(remote.searchMarketplacePublications("gmail", null)).thenReturn(Map.of(
                    "publications", List.of(Map.of("id", "cloud-2")), "count", 1));

            Map<String, Object> body = controllerWith(remote, repo).searchMarketplace("gmail", 0, 10).getBody();

            assertThat((List<?>) body.get("content")).hasSize(1);
            verify(remote).searchMarketplacePublications("gmail", null);
            verify(repo, never()).searchMarketplace(any(), any(), any());
        }

        @Test
        @DisplayName("regression: the cloud search list is sliced to ONE page (the cloud /search is NOT paginated)")
        void searchSlicesUnpaginatedCloudListToOnePage() {
            // The cloud /publications/search returns the FULL match set. Without slicing, the agent's
            // AgentListEnvelope.paginateProjection throws ("slice exceeds limit") for >size matches.
            RemoteMarketplaceService remote = mock(RemoteMarketplaceService.class);
            WorkflowPublicationRepository repo = mock(WorkflowPublicationRepository.class);
            List<Map<String, Object>> twelve = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) twelve.add(Map.of("id", "cloud-" + i, "title", "App " + i));
            when(remote.searchMarketplacePublications("app", null)).thenReturn(Map.of("publications", twelve, "count", 12));

            Map<String, Object> body = controllerWith(remote, repo).searchMarketplace("app", 0, 10).getBody();

            // Page returned to the agent must NOT exceed the requested size (= limit), or paginateProjection throws.
            assertThat((List<?>) body.get("content")).hasSize(10);
            assertThat(body).containsEntry("totalElements", 12L); // full match count preserved for hasMore
            assertThat(body).containsEntry("totalPages", 2);
        }

        @Test
        @DisplayName("by-category passes the slug to the cloud as the category filter")
        void byCategoryProxiesToCloud() {
            RemoteMarketplaceService remote = mock(RemoteMarketplaceService.class);
            WorkflowPublicationRepository repo = mock(WorkflowPublicationRepository.class);
            when(remote.fetchMarketplacePublications(0, 10, "productivity")).thenReturn(Map.of(
                    "publications", List.of(), "count", 0));

            controllerWith(remote, repo).getByCategory("productivity", 0, 10);

            verify(remote).fetchMarketplacePublications(0, 10, "productivity");
            verify(repo, never()).findMarketplacePublicationsByCategorySlug(any(), any());
        }

        @Test
        @DisplayName("list defends against an unpaginated cloud payload by slicing it to one page")
        void listSlicesOversizedCloudPayload() {
            // Robustness: should the cloud LIST ever return more than `size` rows in one shot, the
            // adapter must still hand the agent ONE page (≤ limit) or paginateProjection throws.
            RemoteMarketplaceService remote = mock(RemoteMarketplaceService.class);
            WorkflowPublicationRepository repo = mock(WorkflowPublicationRepository.class);
            List<Map<String, Object>> twelve = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) twelve.add(Map.of("id", "c" + i));
            when(remote.fetchMarketplacePublications(0, 10, null)).thenReturn(Map.of("publications", twelve, "count", 12));

            Map<String, Object> body = controllerWith(remote, repo).getMarketplacePublications(0, 10).getBody();

            assertThat((List<?>) body.get("content")).hasSize(10);
            assertThat(body).containsEntry("totalElements", 12L);
        }
    }

    @Nested
    @DisplayName("Cloud (bean absent) → keep querying the LOCAL marketplace (= the marketplace)")
    class CloudEdition {

        @Test
        @DisplayName("list queries the local repository and never touches a remote proxy")
        void listStaysLocal() {
            WorkflowPublicationRepository repo = mock(WorkflowPublicationRepository.class);
            when(repo.findMarketplacePublications(any())).thenReturn(Page.empty(PageRequest.of(0, 10)));

            Map<String, Object> body = controllerWith(null, repo).getMarketplacePublications(0, 10).getBody();

            assertThat(body).containsKey("content").containsKey("totalElements");
            verify(repo).findMarketplacePublications(any());
        }

        @Test
        @DisplayName("search uses the local FTS repository (no remote service consulted)")
        void searchStaysLocal() {
            RemoteMarketplaceService remote = mock(RemoteMarketplaceService.class); // present as a value but provider returns null
            WorkflowPublicationRepository repo = mock(WorkflowPublicationRepository.class);
            when(repo.searchMarketplace(any(), any(), any())).thenReturn(Page.empty(PageRequest.of(0, 10)));

            controllerWith(null, repo).searchMarketplace("gmail", 0, 10);

            verify(repo).searchMarketplace(eq("gmail"), any(), any());
            verifyNoInteractions(remote);
        }
    }

    @Nested
    @DisplayName("remoteToInternalPage - cloud payload → stable internal page contract")
    class ShapeAdapter {

        @Test
        @DisplayName("maps publications→content and count→totalElements, derives totalPages from count/size")
        void mapsCloudShape() {
            Map<String, Object> out = InternalPublicationController.remoteToInternalPage(
                    Map.of("publications", List.of(Map.of("id", "a"), Map.of("id", "b")), "count", 25), 1, 10);

            assertThat(out).containsEntry("totalElements", 25L);
            assertThat((List<?>) out.get("content")).hasSize(2);
            assertThat(out).containsEntry("totalPages", 3); // ceil(25/10)
            assertThat(out).containsEntry("page", 1).containsEntry("size", 10);
        }

        @Test
        @DisplayName("an already-paged payload (≤ size) passes through untouched - even for page>0 - total reflects the full count")
        void alreadyPagedPassesThrough() {
            // The cloud LIST endpoint is server-paginated: page 2 returns just that page's ≤size rows
            // with count = grand total. The adapter must NOT re-slice it (that would empty page>0).
            Map<String, Object> out = InternalPublicationController.remoteToInternalPage(
                    Map.of("publications", List.of(Map.of("id", "p20"), Map.of("id", "p21")), "count", 25), 2, 10);
            assertThat((List<?>) out.get("content")).hasSize(2); // passed through, NOT emptied
            assertThat(out).containsEntry("totalElements", 25L).containsEntry("totalPages", 3);
        }

        @Test
        @DisplayName("an unpaginated (oversized) payload is sliced per page; full total preserved")
        void slicesOversizedPayloadPerPage() {
            List<Map<String, Object>> twelve = new java.util.ArrayList<>();
            for (int i = 0; i < 12; i++) twelve.add(Map.of("id", "p" + i));
            Map<String, Object> remote = Map.of("publications", twelve, "count", 12);

            Map<String, Object> page0 = InternalPublicationController.remoteToInternalPage(remote, 0, 10);
            assertThat((List<?>) page0.get("content")).hasSize(10);
            assertThat(page0).containsEntry("totalElements", 12L).containsEntry("totalPages", 2);

            Map<String, Object> page1 = InternalPublicationController.remoteToInternalPage(remote, 1, 10);
            assertThat((List<?>) page1.get("content")).hasSize(2); // remainder
            assertThat(page1).containsEntry("totalElements", 12L);

            // A page past the end yields an empty (never throwing) slice.
            Map<String, Object> page9 = InternalPublicationController.remoteToInternalPage(remote, 9, 10);
            assertThat((List<?>) page9.get("content")).isEmpty();
        }

        @Test
        @DisplayName("fail-soft: null payload / missing keys → empty page, never throws")
        void failSoftOnNullOrMissing() {
            Map<String, Object> fromNull = InternalPublicationController.remoteToInternalPage(null, 0, 10);
            assertThat((List<?>) fromNull.get("content")).isEmpty();
            assertThat(fromNull).containsEntry("totalElements", 0L);

            // count absent → falls back to the content list size (here 1).
            Map<String, Object> noCount = InternalPublicationController.remoteToInternalPage(
                    Map.of("publications", List.of(Map.of("id", "x"))), 0, 10);
            assertThat(noCount).containsEntry("totalElements", 1L);
        }
    }
}
