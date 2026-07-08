package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cloud-parity read proxies of {@link RemoteMarketplaceService} (linked CE
 * marketplace UI): marketplace listing, search and curated highlights are
 * forwarded to the cloud public API and FAIL-SOFT to empty payloads when the
 * cloud is unreachable - the CE marketplace must degrade, never break.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RemoteMarketplaceService - cloud-parity read proxies")
class RemoteMarketplaceServiceReadProxyTest {

    private static final String CLOUD_API_URL = "https://cloud.example/api";

    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private CloudLinkService cloudLinkService;
    @Mock private AuthClient authClient;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private RestTemplate restTemplate;

    private RemoteMarketplaceService service;

    @BeforeEach
    void setUp() {
        service = new RemoteMarketplaceService(
                CLOUD_API_URL, snapshotCloneService, receiptRepository,
                cloudLinkService, new ObjectMapper(), authClient,
                agentPublicationService, resourcePublicationService,
                null /* orchestratorClient: unused by these read-proxy tests (no acquire) */,
                null /* entitlementGuard: acquire-only, unused here */, restTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubCloudResponse(Map<String, Object> body) {
        when(restTemplate.getForObject(any(URI.class), eq(Map.class))).thenReturn((Map) body);
    }

    private void stubCloudFailure() {
        when(restTemplate.getForObject(any(URI.class), eq(Map.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));
    }

    private URI capturedUri() {
        ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForObject(captor.capture(), eq(Map.class));
        return captor.getValue();
    }

    @Nested
    @DisplayName("fetchMarketplacePublications")
    class FetchMarketplace {

        @Test
        @DisplayName("Should return the cloud body and call the cloud listing with page, size and category")
        void shouldProxyHappyPath() {
            Map<String, Object> cloudBody = Map.of(
                    "publications", List.of(Map.of("id", "p1", "title", "Cloud App")),
                    "count", 1);
            stubCloudResponse(cloudBody);

            Map<String, Object> result = service.fetchMarketplacePublications(1, 20, "operations");

            assertThat(result).isSameAs(cloudBody);
            URI uri = capturedUri();
            assertThat(uri.getPath()).isEqualTo("/api/publications/marketplace");
            assertThat(uri.getQuery()).contains("page=1").contains("size=20").contains("category=operations");
        }

        @Test
        @DisplayName("Should omit the category parameter when category is null")
        void shouldOmitNullCategory() {
            stubCloudResponse(Map.of("publications", List.of(), "count", 0));

            service.fetchMarketplacePublications(0, 50, null);

            assertThat(capturedUri().getQuery()).doesNotContain("category");
        }

        @Test
        @DisplayName("Should fail-soft to an empty page when the cloud is unreachable")
        void shouldFailSoftOnUpstreamFailure() {
            stubCloudFailure();

            Map<String, Object> result = service.fetchMarketplacePublications(3, 10, "ai");

            assertThat(result)
                    .containsEntry("publications", List.of())
                    .containsEntry("count", 0)
                    .containsEntry("totalPages", 0)
                    .containsEntry("page", 3)
                    .containsEntry("size", 10);
        }

        @Test
        @DisplayName("Should fail-soft to an empty page when the cloud returns a null body")
        void shouldFailSoftOnNullBody() {
            stubCloudResponse(null);

            Map<String, Object> result = service.fetchMarketplacePublications(0, 50, null);

            assertThat(result).containsEntry("publications", List.of()).containsEntry("count", 0);
        }
    }

    @Nested
    @DisplayName("searchMarketplacePublications")
    class SearchMarketplace {

        @Test
        @DisplayName("Should return the cloud body and URL-encode the query string")
        void shouldProxyAndEncodeQuery() {
            Map<String, Object> cloudBody = Map.of("publications", List.of(Map.of("id", "p2")), "count", 1);
            stubCloudResponse(cloudBody);

            Map<String, Object> result = service.searchMarketplacePublications("crm sync", "ai");

            assertThat(result).isSameAs(cloudBody);
            URI uri = capturedUri();
            assertThat(uri.getPath()).isEqualTo("/api/publications/search");
            assertThat(uri.getRawQuery()).contains("q=crm%20sync").contains("category=ai");
        }

        @Test
        @DisplayName("Should omit the category parameter when category is blank")
        void shouldOmitBlankCategory() {
            stubCloudResponse(Map.of("publications", List.of(), "count", 0));

            service.searchMarketplacePublications("alpha", "  ");

            assertThat(capturedUri().getQuery()).doesNotContain("category");
        }

        @Test
        @DisplayName("Should fail-soft to an empty result when the cloud is unreachable")
        void shouldFailSoftOnUpstreamFailure() {
            stubCloudFailure();

            Map<String, Object> result = service.searchMarketplacePublications("anything", null);

            assertThat(result)
                    .containsEntry("publications", List.of())
                    .containsEntry("count", 0);
        }
    }

    @Nested
    @DisplayName("fetchHighlights")
    class FetchHighlights {

        @Test
        @DisplayName("Should return the cloud body and call the cloud highlights path for the displayMode")
        void shouldProxyHappyPath() {
            Map<String, Object> cloudBody = Map.of(
                    "displayMode", "APPLICATION",
                    "highlights", List.of(Map.of("rank", 1, "publication", Map.of("id", "p3"))));
            stubCloudResponse(cloudBody);

            Map<String, Object> result = service.fetchHighlights("APPLICATION");

            assertThat(result).isSameAs(cloudBody);
            assertThat(capturedUri().getPath()).isEqualTo("/api/publications/highlights/APPLICATION");
        }

        @Test
        @DisplayName("Should fail-soft to an empty highlights list when the cloud is unreachable")
        void shouldFailSoftOnUpstreamFailure() {
            stubCloudFailure();

            Map<String, Object> result = service.fetchHighlights("APPLICATION");

            assertThat(result)
                    .containsEntry("displayMode", "APPLICATION")
                    .containsEntry("highlights", List.of());
        }
    }
}
