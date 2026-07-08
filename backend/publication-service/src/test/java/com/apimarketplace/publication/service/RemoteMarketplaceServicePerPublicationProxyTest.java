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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-publication cloud-parity proxies of {@link RemoteMarketplaceService}: the
 * marketplace GRID proxies only populate cards, but each card thumbnail and the
 * detail page reached by clicking one read PER-PUBLICATION public resources
 * ({@code /publications/by-id/{id}/*}) plus the publisher avatar. On a
 * cloud-linked CE those carry cloud ids absent from the local DB, so the reads
 * must be forwarded to the cloud public API. Unlike the grid proxies these pass
 * the cloud's status + body THROUGH (a real 404 must reach the UI's not-found /
 * fallback handling) and only synthesize a status on a transport failure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RemoteMarketplaceService - per-publication read proxies")
class RemoteMarketplaceServicePerPublicationProxyTest {

    private static final String CLOUD_API_URL = "https://cloud.example/api";
    private static final UUID PUB_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

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

    private URI capturedStringUri() {
        ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).getForEntity(captor.capture(), eq(String.class));
        return captor.getValue();
    }

    @Nested
    @DisplayName("proxyPublicByIdJson")
    class ByIdJson {

        @Test
        @DisplayName("Should hit the cloud by-id detail path (no sub-path) and forward status + JSON body")
        void shouldProxyDetailHappyPath() {
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenReturn(ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"id\":\"" + PUB_ID + "\",\"title\":\"Cloud App\"}"));

            ResponseEntity<String> result = service.proxyPublicByIdJson(PUB_ID, "", null);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).contains("Cloud App");
            assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(capturedStringUri().getPath()).isEqualTo("/api/publications/by-id/" + PUB_ID);
        }

        @Test
        @DisplayName("Should append the sub-path segment for nested reads (landing-snapshot)")
        void shouldAppendSubPath() {
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{}"));

            service.proxyPublicByIdJson(PUB_ID, "landing-snapshot", null);

            assertThat(capturedStringUri().getPath())
                    .isEqualTo("/api/publications/by-id/" + PUB_ID + "/landing-snapshot");
        }

        @Test
        @DisplayName("Should forward query params (showcase-render page/size/epoch) to the cloud URL")
        void shouldForwardQueryParams() {
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{}"));
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("page", "2");
            params.add("size", "1");
            params.add("epoch", "3");

            service.proxyPublicByIdJson(PUB_ID, "showcase-render", params);

            URI uri = capturedStringUri();
            assertThat(uri.getPath()).isEqualTo("/api/publications/by-id/" + PUB_ID + "/showcase-render");
            assertThat(uri.getQuery()).contains("page=2").contains("size=1").contains("epoch=3");
        }

        @Test
        @DisplayName("Should pass a genuine cloud 404 THROUGH (status + body) so the UI shows not-found")
        void shouldPassThroughCloud404() {
            byte[] body = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(), body, StandardCharsets.UTF_8));

            ResponseEntity<String> result = service.proxyPublicByIdJson(PUB_ID, "showcase-render", null);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(result.getBody()).contains("not found");
        }

        @Test
        @DisplayName("Should map a transport failure (cloud unreachable) to 502, never fail-soft-to-empty-200")
        void shouldMapTransportFailureTo502() {
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            ResponseEntity<String> result = service.proxyPublicByIdJson(PUB_ID, "run-state", null);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(result.getBody()).contains("cloud_unreachable");
        }
    }

    @Nested
    @DisplayName("proxyUserAvatar")
    class Avatar {

        @Test
        @DisplayName("Should stream the cloud avatar bytes + content type through")
        void shouldProxyAvatarHappyPath() {
            byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
            when(restTemplate.getForEntity(any(URI.class), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png));

            ResponseEntity<byte[]> result = service.proxyUserAvatar("77");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isEqualTo(png);
            assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);

            ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
            verify(restTemplate).getForEntity(captor.capture(), eq(byte[].class));
            assertThat(captor.getValue().toString()).isEqualTo(CLOUD_API_URL + "/users/77/avatar");
        }

        @Test
        @DisplayName("Should map any cloud failure to 404 so the <img onError> falls back to initials")
        void shouldMapFailureTo404() {
            when(restTemplate.getForEntity(any(URI.class), eq(byte[].class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            ResponseEntity<byte[]> result = service.proxyUserAvatar("77");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(result.getBody()).isNull();
        }

        @Test
        @DisplayName("Should encode the userId as a single path segment (no traversal reaches the cloud)")
        void shouldEncodeUserIdPathSegment() {
            when(restTemplate.getForEntity(any(URI.class), eq(byte[].class)))
                    .thenReturn(ResponseEntity.ok(new byte[0]));

            service.proxyUserAvatar("../admin");

            ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
            verify(restTemplate).getForEntity(captor.capture(), eq(byte[].class));
            // The "/" inside the segment is percent-encoded, so it cannot climb the path.
            assertThat(captor.getValue().getRawPath()).isEqualTo("/api/users/..%2Fadmin/avatar");
        }
    }

    @Nested
    @DisplayName("proxyUserProfile")
    class Profile {

        @Test
        @DisplayName("Should hit the cloud public by-id path and forward status + JSON body (handle resolution)")
        void shouldProxyProfileHappyPath() {
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenReturn(ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"userId\":77,\"handle\":\"cloud_bob\"}"));

            ResponseEntity<String> result = service.proxyUserProfile("77");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).contains("cloud_bob");
            assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(capturedStringUri().toString()).isEqualTo(CLOUD_API_URL + "/users/public/by-id/77");
        }

        @Test
        @DisplayName("Should pass a genuine cloud 404 THROUGH (unknown / PRIVATE profile) so the UI no-ops")
        void shouldPassThroughCloud404() {
            byte[] body = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenThrow(HttpClientErrorException.create(
                            HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(), body, StandardCharsets.UTF_8));

            ResponseEntity<String> result = service.proxyUserProfile("77");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(result.getBody()).contains("not found");
        }

        @Test
        @DisplayName("Should map a transport failure (cloud unreachable) to 502")
        void shouldMapTransportFailureTo502() {
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenThrow(new ResourceAccessException("Connection refused"));

            ResponseEntity<String> result = service.proxyUserProfile("77");

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(result.getBody()).contains("cloud_unreachable");
        }

        @Test
        @DisplayName("Should encode the userId as a single path segment (no traversal reaches the cloud)")
        void shouldEncodeUserIdPathSegment() {
            when(restTemplate.getForEntity(any(URI.class), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{}"));

            service.proxyUserProfile("../admin");

            assertThat(capturedStringUri().getRawPath()).isEqualTo("/api/users/public/by-id/..%2Fadmin");
        }
    }
}
