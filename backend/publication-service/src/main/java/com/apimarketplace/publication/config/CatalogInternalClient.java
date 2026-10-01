package com.apimarketplace.publication.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the catalog-service internal APIs publication-service needs.
 *
 * <p>Publication-service owns no catalog data (cross-schema SQL is forbidden), so the
 * publish-time "is this a custom API?" question is answered by catalog-service.
 *
 * <p>Timeouts are bounded: this call sits inside the publisher's interactive publish
 * request, so an unreachable catalog must fail fast rather than stall the publish.
 */
public class CatalogInternalClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogInternalClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CatalogInternalClient(String catalogUrl) {
        this(buildBoundedRestTemplate(), catalogUrl);
    }

    public CatalogInternalClient(RestTemplate restTemplate, String catalogUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = catalogUrl;
    }

    private static RestTemplate buildBoundedRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    /**
     * Resolve which of the given workflow tool identifiers belong to a CUSTOM API
     * ({@code apis.source = 'custom'}), i.e. a tenant-private API that no acquirer can
     * ever resolve.
     *
     * <p>Accepts the identifier forms a plan uses: {@code apiSlug/toolSlug} (mcp node),
     * {@code apiSlug:toolSlug} (agent tool grant), a bare tool slug, or an
     * {@code api_tools.id} UUID.
     *
     * <p>The publishing tenant is forwarded so catalog-service can widen the match for the
     * publisher's OWN custom APIs while keeping another tenant's to an exact row (an
     * api slug is not globally unique, so prefix-matching a stranger's API would refuse
     * publications built on a shipped integration of the same slug).
     *
     * <p><b>Fail-closed is NOT applied here</b>: on a transport error an empty list is
     * returned so a catalog outage never blocks a legitimate publish. The
     * consequence of a miss is a publication whose custom-API nodes fail for
     * acquirers, which is the pre-existing behaviour; blocking every publish while
     * catalog-service is down would be worse.
     *
     * @param identifiers    tool identifiers collected from a plan (may be empty)
     * @param publisherId    the publishing tenant (X-User-ID)
     * @param publisherOrgId the publishing workspace, or null for personal scope
     * @return one entry per custom API: {@code {apiSlug, apiName, toolIdentifiers[]}}
     */
    public List<Map<String, Object>> findCustomApiRefs(Collection<String> identifiers,
                                                      String publisherId,
                                                      String publisherOrgId) {
        if (identifiers == null || identifiers.isEmpty()) {
            return List.of();
        }
        String url = baseUrl + "/api/internal/catalog/workflow-inspector/custom-apis";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (publisherId != null && !publisherId.isBlank()) {
            headers.set("X-User-ID", publisherId);
        }
        if (publisherOrgId != null && !publisherOrgId.isBlank()) {
            headers.set("X-Organization-ID", publisherOrgId);
        }
        HttpEntity<Map<String, Object>> entity =
                new HttpEntity<>(Map.of("toolSlugs", new ArrayList<>(identifiers)), headers);
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, new ParameterizedTypeReference<>() {});
            Map<String, Object> body = response.getBody();
            Object raw = body != null ? body.get("customApis") : null;
            if (!(raw instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> refs = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    refs.add(typed);
                }
            }
            return refs;
        } catch (Exception e) {
            log.error("Failed to resolve custom API references against catalog-service ({}): {}",
                    url, e.getMessage());
            return List.of();
        }
    }
}
