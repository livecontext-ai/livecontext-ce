package com.apimarketplace.catalog.bundle;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

/**
 * CE-side HTTP client that fetches the currently active API-catalog bundle
 * from the cloud ({@code GET {api-catalog.bundle.cloud-url}/api/catalog/public/bundles/latest}).
 * Mirrors {@code agent-service CatalogBundleFetcher}.
 *
 * <p>Returns a structured {@link FetchResult} so the scheduler can persist the
 * reason on {@code api_catalog_bundle_sync_status} without catching exceptions:
 * <ul>
 *   <li>{@code FETCHED}: cloud returned 200 with a parseable bundle.</li>
 *   <li>{@code NO_ACTIVE}: cloud returned 404 (no bundle activated yet) - not
 *       a failure, just "keep whatever CE already has".</li>
 *   <li>{@code NOT_MODIFIED}: cloud returned 304 because the bundle this install
 *       already holds is still the active one. The steady-state outcome, and the
 *       reason the poll costs nothing instead of transferring ~32 MB.</li>
 *   <li>{@code HTTP_ERROR}: any other 4xx/5xx.</li>
 *   <li>{@code NETWORK_ERROR}: connection refused, timeout, DNS, etc.</li>
 *   <li>{@code NOT_CONFIGURED}: {@code api-catalog.bundle.cloud-url} empty.</li>
 * </ul>
 */
@Slf4j
@Component
public class ApiCatalogBundleFetcher {

    /**
     * Longest {@code payloadBase64} this install accepts, in characters.
     *
     * <p>The body is read as bytes and parsed here, NOT through the RestTemplate's Jackson
     * converter: that converter keeps Jackson's default 20,000,000-character cap on a single
     * string, and the whole catalog travels as ONE string. The bundle activated on 2026-09-03
     * (931 APIs, 243 MB raw, 32.4 MB on the wire) crossed it, so from then on every CE install
     * failed every poll with a {@code StreamConstraintsException}, re-downloaded the full body
     * every 15 minutes, and never received another catalog update. 256 Mi characters is about
     * 190 MB of gzip, some six times today's bundle: a ceiling against a runaway body, not a
     * size the catalog is expected to approach.
     */
    static final int MAX_PAYLOAD_CHARS = 256 * 1024 * 1024;

    /**
     * Unknown properties are ignored, as the Spring-configured converter did before: the cloud
     * adding a field to the envelope must never break an install that predates it.
     */
    private static final ObjectMapper BUNDLE_READER = JsonMapper.builder(
                    JsonFactory.builder()
                            .streamReadConstraints(StreamReadConstraints.builder()
                                    .maxStringLength(MAX_PAYLOAD_CHARS)
                                    .build())
                            .build())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RestTemplate restTemplate;
    private final String cloudUrl;

    public ApiCatalogBundleFetcher(
            RestTemplate restTemplate,
            @Value("${api-catalog.bundle.cloud-url:}") String cloudUrl) {
        this.restTemplate = restTemplate;
        this.cloudUrl = cloudUrl == null ? "" : cloudUrl.trim();
    }

    public record FetchResult(Status status, ApiCatalogSignedBundle bundle, String detail) {
        public static FetchResult fetched(ApiCatalogSignedBundle b) { return new FetchResult(Status.FETCHED, b, null); }
        public static FetchResult noActive()                        { return new FetchResult(Status.NO_ACTIVE, null, null); }
        public static FetchResult notModified()                     { return new FetchResult(Status.NOT_MODIFIED, null, null); }
        public static FetchResult notConfigured()                   { return new FetchResult(Status.NOT_CONFIGURED, null, "api-catalog.bundle.cloud-url is empty"); }
        public static FetchResult httpError(String detail)          { return new FetchResult(Status.HTTP_ERROR, null, detail); }
        public static FetchResult networkError(String d)            { return new FetchResult(Status.NETWORK_ERROR, null, d); }
    }

    public enum Status { FETCHED, NO_ACTIVE, NOT_MODIFIED, NOT_CONFIGURED, HTTP_ERROR, NETWORK_ERROR }

    /**
     * Fetch the cloud's active bundle, skipping the transfer when this install
     * already holds it.
     *
     * @param knownChecksum checksum of the bundle currently applied here, sent as
     *                      {@code If-None-Match}. An unchanged bundle then comes
     *                      back as a bodiless 304 instead of ~32 MB of JSON.
     *                      Null or blank (a first sync) simply fetches.
     */
    public FetchResult fetchLatest(String knownChecksum) {
        if (cloudUrl.isEmpty()) return FetchResult.notConfigured();
        String url = cloudUrl.replaceFirst("/+$", "") + "/api/catalog/public/bundles/latest";
        try {
            HttpHeaders headers = new HttpHeaders();
            if (knownChecksum != null && !knownChecksum.isBlank()) {
                headers.setIfNoneMatch("\"" + knownChecksum + "\"");
            }
            ResponseEntity<byte[]> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
            if (resp.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return FetchResult.notModified();
            }
            byte[] raw = resp.getBody();
            if (raw == null || raw.length == 0) {
                return FetchResult.httpError("cloud returned 200 with empty body");
            }
            ApiCatalogSignedBundle body;
            try {
                body = BUNDLE_READER.readValue(raw, ApiCatalogSignedBundle.class);
            } catch (IOException e) {
                // The transfer succeeded and the body is what the cloud sent: this is not a
                // network problem, and reporting it as one is what hid the size cap above.
                return FetchResult.httpError("unreadable bundle body (" + raw.length + " bytes): "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (body == null) {
                return FetchResult.httpError("cloud returned 200 with empty body");
            }
            return FetchResult.fetched(body);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                // Some client stacks surface a 304 as an exception rather than a
                // response. It is a success either way, never an error.
                return FetchResult.notModified();
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // Cloud has no active bundle - not an error, nothing to apply yet.
                return FetchResult.noActive();
            }
            return FetchResult.httpError("HTTP " + e.getStatusCode().value() + " from " + url);
        } catch (RestClientException e) {
            return FetchResult.networkError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
