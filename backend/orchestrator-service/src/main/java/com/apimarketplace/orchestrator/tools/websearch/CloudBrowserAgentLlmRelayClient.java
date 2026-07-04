package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.cloud.CloudLlmRelayRequest;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.CompletionResponse;
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

/**
 * Orchestrator-local client for the cloud LLM relay, used by {@code BrowserAgentLlmShimController}
 * so the browser agent's per-step LLM calls run through the cloud when the install is cloud-linked
 * (same {@code /ce-llm/complete} endpoint the chat/workflow agents use via shared-agent-lib's
 * {@code CloudLlmRelayClient}). Duplicated here because orchestrator-service depends on
 * {@code agent-common} (which owns {@link CloudLlmRelayRequest} / {@link CompletionResponse} /
 * {@link CloudLlmRuntimeCredentials}) but not on {@code shared-agent-lib}. Mirrors the sibling
 * {@link CloudWebSearchRelayClient} (the web-search equivalent).
 */
@Component
public class CloudBrowserAgentLlmRelayClient {

    static final String COMPLETE_PATH = "/ce-llm/complete";
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private final RestTemplate restTemplate;

    public CloudBrowserAgentLlmRelayClient() {
        this(defaultRestTemplate());
    }

    CloudBrowserAgentLlmRelayClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Relay one completion to the linked cloud deployment; the cloud executes + bills it
     * ({@code CE_LLM_RELAY}) on the linked account.
     *
     * @throws IllegalStateException on a non-2xx status or empty body (surfaced by the shim as a 502)
     */
    public CompletionResponse complete(CloudLlmRuntimeCredentials credentials, CloudLlmRelayRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credentials.accessToken());
        headers.set(INSTALL_HEADER, credentials.installId());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<CompletionResponse> response;
        try {
            response = restTemplate.exchange(
                    url(credentials.cloudApiUrl(), COMPLETE_PATH),
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    CompletionResponse.class);
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            throw new IllegalStateException("Cloud LLM relay returned " + e.getStatusCode().value()
                    + (body == null || body.isBlank() ? "" : ": " + body), e);
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Cloud LLM relay returned " + response.getStatusCode().value());
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
