package com.apimarketplace.catalog.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fetch contract: structured outcomes (no exceptions escape) keyed off the
 * cloud's response, against the public download URL
 * {@code {cloud-url}/api/catalog/public/bundles/latest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiCatalogBundleFetcher - structured fetch outcomes")
class ApiCatalogBundleFetcherTest {

    private static final String URL = "https://cloud.example/api/catalog/public/bundles/latest";

    @Mock private RestTemplate restTemplate;

    private ApiCatalogBundleFetcher fetcher(String cloudUrl) {
        return new ApiCatalogBundleFetcher(restTemplate, cloudUrl);
    }

    @Test
    @DisplayName("200 with body → FETCHED carrying the bundle")
    void fetched() {
        ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(1L, 1, "cs", "sig", "k", "c",
                10, 40, 1000, "cA==");
        when(restTemplate.getForEntity(eq(URL), eq(ApiCatalogSignedBundle.class)))
                .thenReturn(ResponseEntity.ok(bundle));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest();

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.FETCHED);
        assertThat(r.bundle()).isEqualTo(bundle);
    }

    @Test
    @DisplayName("Trailing slashes on cloud-url are normalised before appending the path")
    void trailingSlashNormalised() {
        ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(1L, 1, "cs", "sig", "k", "c",
                1, 1, 10, "cA==");
        when(restTemplate.getForEntity(eq(URL), eq(ApiCatalogSignedBundle.class)))
                .thenReturn(ResponseEntity.ok(bundle));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example///").fetchLatest();

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.FETCHED);
    }

    @Test
    @DisplayName("200 with empty body → HTTP_ERROR")
    void emptyBody() {
        when(restTemplate.getForEntity(eq(URL), eq(ApiCatalogSignedBundle.class)))
                .thenReturn(ResponseEntity.ok().build());

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest();

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("empty body");
    }

    @Test
    @DisplayName("404 → NO_ACTIVE (not a failure: cloud has nothing activated yet)")
    void notFoundIsNoActive() {
        when(restTemplate.getForEntity(eq(URL), eq(ApiCatalogSignedBundle.class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", null, null, null));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest();

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.NO_ACTIVE);
        assertThat(r.detail()).isNull();
    }

    @Test
    @DisplayName("Other HTTP errors → HTTP_ERROR with status detail")
    void httpError() {
        when(restTemplate.getForEntity(eq(URL), eq(ApiCatalogSignedBundle.class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest();

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("HTTP 500");
    }

    @Test
    @DisplayName("Connection failure → NETWORK_ERROR")
    void networkError() {
        when(restTemplate.getForEntity(eq(URL), eq(ApiCatalogSignedBundle.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest();

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.NETWORK_ERROR);
        assertThat(r.detail()).contains("Connection refused");
    }

    @Test
    @DisplayName("Empty cloud-url → NOT_CONFIGURED without any HTTP call")
    void notConfigured() {
        ApiCatalogBundleFetcher.FetchResult r = fetcher("  ").fetchLatest();

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.NOT_CONFIGURED);
        verifyNoInteractions(restTemplate);
    }
}
