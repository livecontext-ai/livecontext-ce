package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * CE-side HTTP client for the cloud web-search relay. Mirrors
 * {@link com.apimarketplace.agent.cloud.CloudLlmRelayClient}: authenticates with the
 * cloud-link access token + install id header, posts the search to the linked cloud
 * deployment, which executes it and bills the linked cloud account (WEB_SEARCH).
 *
 * <p>Only exists in CE ({@code websearch.enabled=false}) - in cloud the local
 * {@link WebSearchModule} handles searches directly.
 */
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "false")
public class CloudWebSearchRelayClient {

    static final String SEARCH_PATH = "/ce-websearch/search";
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final RestTemplate restTemplate;

    @Autowired
    public CloudWebSearchRelayClient() {
        this(defaultRestTemplate());
    }

    CloudWebSearchRelayClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Relay a search to the linked cloud deployment.
     *
     * @return the cloud search response body (same shape as a local websearch-service
     *         response: {@code {results: [{url, title, snippet}, ...], ...}})
     * @throws IllegalStateException when the relay returns a non-2xx status or an empty body
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(CloudLlmRuntimeCredentials credentials, CeWebSearchRelayRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credentials.accessToken());
        headers.set(INSTALL_HEADER, credentials.installId());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(
                    url(credentials.cloudApiUrl(), SEARCH_PATH),
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    Map.class
            );
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            throw new IllegalStateException("Cloud web search relay returned " + e.getStatusCode().value()
                    + (body == null || body.isBlank() ? "" : ": " + body), e);
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(
                    "Cloud web search relay returned " + response.getStatusCode().value());
        }
        return response.getBody();
    }

    private static RestTemplate defaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        RestTemplate template = new RestTemplate();
        template.setRequestFactory(factory);
        return template;
    }

    private static String url(String base, String path) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return cleanBase + path;
    }
}
