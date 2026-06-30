package com.apimarketplace.agent.skill.bundle;

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

/**
 * CE-side HTTP client that fetches the currently active skill bundle from the cloud.
 *
 * <p>The download is GATED behind an active cloud link: the caller passes the install's
 * cloud-link credentials and this client presents them as a bearer +
 * {@code X-LiveContext-Install-Id} (mirroring the LLM relay and the catalog bundle), so
 * the cloud serves the bundle only to a linked install. The scheduler resolves those
 * credentials (and skips entirely when the install isn't linked) - this client is a pure
 * HTTP client and is never called without credentials.
 *
 * <p>Sibling of {@code com.apimarketplace.agent.catalog.bundle.CatalogBundleFetcher}.
 */
@Slf4j
@Component
public class SkillBundleFetcher {

    /** Same header the LLM relay + catalog bundle use to carry the CE install id. */
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private final RestTemplate restTemplate;
    private final String cloudUrl;

    public SkillBundleFetcher(
            RestTemplate restTemplate,
            @Value("${skill.bundle.cloud-url:}") String cloudUrl) {
        this.restTemplate = restTemplate;
        this.cloudUrl = cloudUrl == null ? "" : cloudUrl.trim();
    }

    public record FetchResult(Status status, SignedSkillBundle bundle, String detail) {
        public static FetchResult fetched(SignedSkillBundle b) { return new FetchResult(Status.FETCHED, b, null); }
        public static FetchResult noActive()                   { return new FetchResult(Status.NO_ACTIVE, null, null); }
        public static FetchResult notConfigured()              { return new FetchResult(Status.NOT_CONFIGURED, null, "skill.bundle.cloud-url is empty"); }
        public static FetchResult httpError(String detail)     { return new FetchResult(Status.HTTP_ERROR, null, detail); }
        public static FetchResult networkError(String d)       { return new FetchResult(Status.NETWORK_ERROR, null, d); }
    }

    public enum Status { FETCHED, NO_ACTIVE, NOT_CONFIGURED, HTTP_ERROR, NETWORK_ERROR }

    public FetchResult fetchLatest(CloudLlmRuntimeCredentials credentials) {
        if (cloudUrl.isEmpty()) return FetchResult.notConfigured();
        String url = cloudUrl.replaceFirst("/+$", "") + "/api/skill-bundles/latest";
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credentials.accessToken());
        headers.set(INSTALL_HEADER, credentials.installId());
        try {
            ResponseEntity<SignedSkillBundle> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), SignedSkillBundle.class);
            SignedSkillBundle body = resp.getBody();
            if (body == null) {
                return FetchResult.httpError("cloud returned 200 with empty body");
            }
            return FetchResult.fetched(body);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return FetchResult.noActive();
            }
            return FetchResult.httpError("HTTP " + e.getStatusCode().value() + " from " + url);
        } catch (RestClientException e) {
            return FetchResult.networkError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
