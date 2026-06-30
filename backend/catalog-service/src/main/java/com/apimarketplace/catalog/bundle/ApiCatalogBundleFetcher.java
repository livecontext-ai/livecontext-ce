package com.apimarketplace.catalog.bundle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

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
 *   <li>{@code HTTP_ERROR}: any other 4xx/5xx.</li>
 *   <li>{@code NETWORK_ERROR}: connection refused, timeout, DNS, etc.</li>
 *   <li>{@code NOT_CONFIGURED}: {@code api-catalog.bundle.cloud-url} empty.</li>
 * </ul>
 */
@Slf4j
@Component
public class ApiCatalogBundleFetcher {

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
        public static FetchResult notConfigured()                   { return new FetchResult(Status.NOT_CONFIGURED, null, "api-catalog.bundle.cloud-url is empty"); }
        public static FetchResult httpError(String detail)          { return new FetchResult(Status.HTTP_ERROR, null, detail); }
        public static FetchResult networkError(String d)            { return new FetchResult(Status.NETWORK_ERROR, null, d); }
    }

    public enum Status { FETCHED, NO_ACTIVE, NOT_CONFIGURED, HTTP_ERROR, NETWORK_ERROR }

    public FetchResult fetchLatest() {
        if (cloudUrl.isEmpty()) return FetchResult.notConfigured();
        String url = cloudUrl.replaceFirst("/+$", "") + "/api/catalog/public/bundles/latest";
        try {
            ResponseEntity<ApiCatalogSignedBundle> resp =
                    restTemplate.getForEntity(url, ApiCatalogSignedBundle.class);
            ApiCatalogSignedBundle body = resp.getBody();
            if (body == null) {
                return FetchResult.httpError("cloud returned 200 with empty body");
            }
            return FetchResult.fetched(body);
        } catch (HttpStatusCodeException e) {
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
