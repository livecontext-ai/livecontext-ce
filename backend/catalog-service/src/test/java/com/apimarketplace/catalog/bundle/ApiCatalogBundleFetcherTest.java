package com.apimarketplace.catalog.bundle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

    /** Jackson's default cap on one string, which the RestTemplate converter keeps. */
    private static final int JACKSON_DEFAULT_MAX_STRING = 20_000_000;

    private static byte[] json(ApiCatalogSignedBundle bundle) {
        try {
            return new ObjectMapper().writeValueAsBytes(bundle);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A payload longer than one string may be under Jackson's default constraints. */
    private static String oversizedPayload() {
        return "A".repeat(JACKSON_DEFAULT_MAX_STRING + 1_000);
    }

    @Test
    @DisplayName("Regression 2026-09-03: a payload over Jackson's 20M-char string cap is FETCHED, not a failure")
    void payloadOverJacksonDefaultStringCapIsFetched() {
        ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(7L, 1, "cs", "sig", "k", "c",
                931, 31772, 243_679_290L, oversizedPayload());
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(json(bundle)));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.FETCHED);
        assertThat(r.bundle().payloadBase64()).hasSize(JACKSON_DEFAULT_MAX_STRING + 1_000);
        assertThat(r.bundle().version()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Regression 2026-09-03: the oversized payload also crosses a REAL RestTemplate with its default converters")
    void oversizedPayloadThroughRealRestTemplate() {
        // The mock-based tests cannot see the converter chain, which is where the cap lived:
        // a real RestTemplate with default converters failed this exact body before the fix.
        RestTemplate real = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(real).build();
        ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(8L, 1, "cs", "sig", "k", "c",
                1, 1, 1L, oversizedPayload());
        server.expect(requestTo(URL))
                .andRespond(withSuccess(json(bundle), MediaType.APPLICATION_JSON));

        ApiCatalogBundleFetcher.FetchResult r =
                new ApiCatalogBundleFetcher(real, "https://cloud.example").fetchLatest(null);

        server.verify();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.FETCHED);
        assertThat(r.bundle().payloadBase64()).hasSize(JACKSON_DEFAULT_MAX_STRING + 1_000);
    }

    @Test
    @DisplayName("304 through a REAL RestTemplate: the byte[] converter chain yields NOT_MODIFIED")
    void notModifiedThroughRealRestTemplate() {
        RestTemplate real = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(real).build();
        server.expect(requestTo(URL))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers
                        .header(org.springframework.http.HttpHeaders.IF_NONE_MATCH, "\"abc123\""))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(HttpStatus.NOT_MODIFIED));

        ApiCatalogBundleFetcher.FetchResult r =
                new ApiCatalogBundleFetcher(real, "https://cloud.example").fetchLatest("abc123");

        server.verify();
        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.NOT_MODIFIED);
    }

    @Test
    @DisplayName("A field the cloud adds to the envelope later is ignored, never a parse failure")
    void unknownEnvelopeFieldIsIgnored() {
        String body = "{\"version\":3,\"schemaVersion\":1,\"checksum\":\"cs\",\"signature\":\"sig\","
                + "\"signingKeyId\":\"k\",\"issuer\":\"c\",\"apiCount\":1,\"toolCount\":2,"
                + "\"rawBytesSize\":10,\"payloadBase64\":\"cA==\",\"addedByANewerCloud\":{\"x\":1}}";
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(body.getBytes(StandardCharsets.UTF_8)));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.FETCHED);
        assertThat(r.bundle().payloadBase64()).isEqualTo("cA==");
    }

    @Test
    @DisplayName("A body that transferred but does not parse is HTTP_ERROR naming the parse, never NETWORK_ERROR")
    void unreadableBodyIsHttpErrorNotNetworkError() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok("<html>maintenance</html>".getBytes(StandardCharsets.UTF_8)));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).startsWith("unreadable bundle body (24 bytes)");
    }

    @Test
    @DisplayName("200 with a zero-length body -> HTTP_ERROR (empty body), not a parse error")
    void zeroLengthBody() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[0]));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("empty body");
    }

    @Test
    @DisplayName("The read ceiling sits well above today's bundle (32.4M chars on the wire)")
    void ceilingLeavesHeadroomOverTodaysBundle() {
        assertThat(ApiCatalogBundleFetcher.MAX_PAYLOAD_CHARS).isGreaterThan(4 * 32_418_851);
    }

    @Test
    @DisplayName("200 with body → FETCHED carrying the bundle")
    void fetched() {
        ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(1L, 1, "cs", "sig", "k", "c",
                10, 40, 1000, "cA==");
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(json(bundle)));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.FETCHED);
        assertThat(r.bundle()).isEqualTo(bundle);
    }

    @Test
    @DisplayName("Trailing slashes on cloud-url are normalised before appending the path")
    void trailingSlashNormalised() {
        ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(1L, 1, "cs", "sig", "k", "c",
                1, 1, 10, "cA==");
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(json(bundle)));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example///").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.FETCHED);
    }

    @Test
    @DisplayName("200 with empty body → HTTP_ERROR")
    void emptyBody() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok().build());

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("empty body");
    }

    @Test
    @DisplayName("404 → NO_ACTIVE (not a failure: cloud has nothing activated yet)")
    void notFoundIsNoActive() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "nf", null, null, null));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.NO_ACTIVE);
        assertThat(r.detail()).isNull();
    }

    @Test
    @DisplayName("Other HTTP errors → HTTP_ERROR with status detail")
    void httpError() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.HTTP_ERROR);
        assertThat(r.detail()).contains("HTTP 500");
    }

    @Test
    @DisplayName("Connection failure → NETWORK_ERROR")
    void networkError() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.NETWORK_ERROR);
        assertThat(r.detail()).contains("Connection refused");
    }

    @Test
    @DisplayName("Empty cloud-url → NOT_CONFIGURED without any HTTP call")
    void notConfigured() {
        ApiCatalogBundleFetcher.FetchResult r = fetcher("  ").fetchLatest(null);

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.NOT_CONFIGURED);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("304 -> NOT_MODIFIED: the bundle we hold is still the active one")
    void notModifiedFromResponse() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_MODIFIED).build());

        ApiCatalogBundleFetcher.FetchResult r = fetcher("https://cloud.example").fetchLatest("abc123");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleFetcher.Status.NOT_MODIFIED);
        assertThat(r.bundle()).isNull();
    }

    @Test
    @DisplayName("A client stack that throws on 304 still yields NOT_MODIFIED, never HTTP_ERROR")
    void notModifiedFromException() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_MODIFIED, "Not Modified", null, null, null));

        assertThat(fetcher("https://cloud.example").fetchLatest("abc123").status())
                .isEqualTo(ApiCatalogBundleFetcher.Status.NOT_MODIFIED);
    }

    @Test
    @DisplayName("A known checksum travels as a quoted If-None-Match, which is what lets the cloud answer 304")
    void sendsIfNoneMatch() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.status(HttpStatus.NOT_MODIFIED).build());

        fetcher("https://cloud.example").fetchLatest("abc123");

        ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL), eq(HttpMethod.GET), captor.capture(), eq(byte[].class));
        assertThat(captor.getValue().getHeaders().getIfNoneMatch()).containsExactly("\"abc123\"");
    }

    @Test
    @DisplayName("A first sync sends no validator and downloads")
    void firstSyncSendsNoValidator() {
        ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(1L, 1, "cs", "sig", "k", "c", 1, 1, 1L, "p");
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(json(bundle)));

        assertThat(fetcher("https://cloud.example").fetchLatest("  ").status())
                .isEqualTo(ApiCatalogBundleFetcher.Status.FETCHED);

        ArgumentCaptor<HttpEntity<?>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(URL), eq(HttpMethod.GET), captor.capture(), eq(byte[].class));
        assertThat(captor.getValue().getHeaders().getIfNoneMatch()).isEmpty();
    }
}
