package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * HTTP boundary contract for the CE fetcher: every failure mode must land as
 * a structured {@link CatalogBundleFetcher.FetchResult} so the scheduler can
 * persist the reason without try/catch. The download is authenticated with the
 * install's cloud-link credentials (bearer + install header) - the cloud serves
 * the bundle only to a linked install.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogBundleFetcher - cloud HTTP mapping")
class CatalogBundleFetcherTest {

    private static final String URL = "https://cloud.example/api/catalog-bundles/latest";
    private static final CloudLlmRuntimeCredentials CREDS =
            new CloudLlmRuntimeCredentials("tok-123", "install-1", "https://cloud.example/api");

    @Mock private RestTemplate restTemplate;

    private CatalogBundleFetcher fetcher(String cloudUrl) {
        return new CatalogBundleFetcher(restTemplate, cloudUrl);
    }

    private SignedBundle sample() {
        return new SignedBundle(1L, 1, "a".repeat(64), "sig", "k1", "cloud",
                10, 1000, "cGF5bG9hZA==");
    }

    @SuppressWarnings("unchecked")
    private void stubExchange(ResponseEntity<SignedBundle> response) {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedBundle.class)))
                .thenReturn(response);
    }

    @Test
    @DisplayName("Empty cloud-url → NOT_CONFIGURED without HTTP call")
    void notConfigured() {
        CatalogBundleFetcher.FetchResult r = fetcher("").fetchLatest(CREDS);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.NOT_CONFIGURED);
        assertThat(r.bundle()).isNull();
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("200 with bundle → FETCHED")
    void fetched() {
        SignedBundle sb = sample();
        stubExchange(ResponseEntity.ok(sb));

        CatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(CREDS);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.FETCHED);
        assertThat(r.bundle()).isSameAs(sb);
    }

    @Test
    @DisplayName("The download presents the cloud-link credentials (bearer + install header) so only a linked install is served")
    @SuppressWarnings("unchecked")
    void sendsCloudLinkCredentials() {
        ArgumentCaptor<HttpEntity<Void>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), captor.capture(), eq(SignedBundle.class)))
                .thenReturn(ResponseEntity.ok(sample()));

        fetcher("https://cloud.example").fetchLatest(CREDS);

        HttpHeaders sent = captor.getValue().getHeaders();
        assertThat(sent.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-123");
        assertThat(sent.getFirst("X-LiveContext-Install-Id")).isEqualTo("install-1");
    }

    @Test
    @DisplayName("Trailing slashes on cloud-url are normalised")
    void trailingSlashNormalised() {
        stubExchange(ResponseEntity.ok(sample()));

        CatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example///").fetchLatest(CREDS);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.FETCHED);
    }

    @Test
    @DisplayName("200 with empty body → HTTP_ERROR (cloud contract violation)")
    void emptyBody() {
        stubExchange(ResponseEntity.ok().body(null));

        CatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(CREDS);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("empty body");
    }

    @Test
    @DisplayName("404 → NO_ACTIVE (cloud has no active bundle yet, not an error)")
    void noActive() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedBundle.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        CatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(CREDS);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.NO_ACTIVE);
    }

    @Test
    @DisplayName("403 (link rejected by cloud) → HTTP_ERROR with status in detail")
    void forbiddenWhenLinkRejected() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedBundle.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

        CatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(CREDS);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("403");
    }

    @Test
    @DisplayName("5xx from cloud → HTTP_ERROR with status in detail")
    void serverError() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedBundle.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "oops", null, null, null));

        CatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(CREDS);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("500");
    }

    @Test
    @DisplayName("Connection refused → NETWORK_ERROR")
    void networkError() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(SignedBundle.class)))
                .thenThrow(new ResourceAccessException("I/O error: Connection refused"));

        CatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(CREDS);

        assertThat(r.status()).isEqualTo(CatalogBundleFetcher.Status.NETWORK_ERROR);
        assertThat(r.detail()).contains("Connection refused");
    }
}
