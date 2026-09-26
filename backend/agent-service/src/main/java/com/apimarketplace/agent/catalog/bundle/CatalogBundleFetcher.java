package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
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

import com.apimarketplace.common.scheduling.BundlePollBackoff;

import java.time.Duration;
import java.time.Instant;

/**
 * CE-side HTTP client that fetches the currently active bundle from cloud.
 *
 * <p>The download is GATED behind an active cloud link: the caller passes the
 * install's cloud-link credentials and this client presents them as a bearer +
 * {@code X-LiveContext-Install-Id} (mirroring the LLM relay), so the cloud serves
 * the bundle only to a linked install. The scheduler resolves those credentials
 * (and skips entirely when the install isn't linked) - this client is a pure HTTP
 * client and is never called without credentials.
 *
 * <p>Returns a structured {@link FetchResult} so the scheduler can persist the
 * reason on {@code catalog_bundle_sync_status} without catching exceptions:
 * <ul>
 *   <li>{@code FETCHED}: cloud returned 200 with a parseable bundle.</li>
 *   <li>{@code NO_ACTIVE}: cloud returned 404 (no bundle activated yet) - not
 *       a failure, just "keep whatever CE already has".</li>
 *   <li>{@code HTTP_ERROR}: any other 4xx/5xx (incl. 401/403 if the link creds are
 *       rejected - the cloud refused an unlinked/inactive install).</li>
 *   <li>{@code NETWORK_ERROR}: connection refused, timeout, DNS, etc.</li>
 *   <li>{@code NOT_CONFIGURED}: {@code catalog.bundle.cloud-url} empty.</li>
 * </ul>
 */
@Slf4j
@Component
public class CatalogBundleFetcher {

    /** Same header the LLM relay uses to carry the CE install id. */
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private final RestTemplate restTemplate;
    private final String cloudUrl;

    public CatalogBundleFetcher(
            RestTemplate restTemplate,
            @Value("${catalog.bundle.cloud-url:}") String cloudUrl) {
        this.restTemplate = restTemplate;
        this.cloudUrl = cloudUrl == null ? "" : cloudUrl.trim();
    }

    /**
     * @param retryAfter the cloud's {@code Retry-After} on a refused request (429 / 503), or
     *                   null. The scheduler turns it into a longer backoff, never a shorter one.
     */
    public record FetchResult(Status status, SignedBundle bundle, String detail, Duration retryAfter) {
        public FetchResult(Status status, SignedBundle bundle, String detail) {
            this(status, bundle, detail, null);
        }
        public static FetchResult notModified() { return new FetchResult(Status.NOT_MODIFIED, null, null); }
        public static FetchResult fetched(SignedBundle b)   { return new FetchResult(Status.FETCHED, b, null); }
        public static FetchResult noActive()                { return new FetchResult(Status.NO_ACTIVE, null, null); }
        public static FetchResult notConfigured()           { return new FetchResult(Status.NOT_CONFIGURED, null, "catalog.bundle.cloud-url is empty"); }
        public static FetchResult httpError(String detail)  { return new FetchResult(Status.HTTP_ERROR, null, detail); }
        public static FetchResult networkError(String d)    { return new FetchResult(Status.NETWORK_ERROR, null, d); }
    }

    public enum Status { FETCHED, NO_ACTIVE, NOT_MODIFIED, NOT_CONFIGURED, HTTP_ERROR, NETWORK_ERROR }

    /**
     * @param knownChecksum checksum of the bundle currently applied on this install, sent as
     *                      {@code If-None-Match} so an unchanged bundle comes back as a bodiless
     *                      304 ({@code NOT_MODIFIED}). Null or blank (first sync) fetches in full.
     */
    public FetchResult fetchLatest(CloudLlmRuntimeCredentials credentials, String knownChecksum) {
        if (cloudUrl.isEmpty()) return FetchResult.notConfigured();
        String url = cloudUrl.replaceFirst("/+$", "") + "/api/catalog-bundles/latest";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credentials.accessToken());
        headers.set(INSTALL_HEADER, credentials.installId());
        if (knownChecksum != null && !knownChecksum.isBlank()) {
            headers.setIfNoneMatch("\"" + knownChecksum + "\"");
        }
        try {
            ResponseEntity<SignedBundle> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), SignedBundle.class);
            if (resp.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                return FetchResult.notModified();
            }
            SignedBundle body = resp.getBody();
            if (body == null) {
                return FetchResult.httpError("cloud returned 200 with empty body");
            }
            return FetchResult.fetched(body);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_MODIFIED) {
                // Some client stacks surface a 304 as an exception rather than a response.
                return FetchResult.notModified();
            }
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // Cloud has no active bundle - not an error, just "nothing to apply yet".
                return FetchResult.noActive();
            }
            HttpHeaders refused = e.getResponseHeaders();
            Duration retryAfter = refused == null ? null
                    : BundlePollBackoff.parseRetryAfter(refused.getFirst(HttpHeaders.RETRY_AFTER), Instant.now());
            return new FetchResult(Status.HTTP_ERROR, null,
                    "HTTP " + e.getStatusCode().value() + " from " + url, retryAfter);
        } catch (RestClientException e) {
            return FetchResult.networkError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
