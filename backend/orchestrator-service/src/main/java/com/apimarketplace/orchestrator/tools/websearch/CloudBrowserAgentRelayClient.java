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
 * CE-side HTTP client for the cloud browser-agent relay. Sibling of
 * {@link CloudWebSearchRelayClient}: authenticates with the cloud-link access
 * token + install id header, posts to the linked cloud deployment, which runs the
 * whole browser stack (Chromium + per-step LLM) locally and bills the linked
 * cloud account (BROWSER_AGENT_EXECUTION). The cloud response carries the
 * cloud-hosted CDP live-view URL/token verbatim so the CE frontend connects
 * DIRECTLY to {@code wss://<cloud>/cdp/{sid}?token=...}.
 *
 * <p>Only exists in CE ({@code websearch.enabled=false}) - in cloud the local
 * {@link BrowserAgentModule} handles browser sessions directly.
 */
@Component
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "false")
public class CloudBrowserAgentRelayClient {

    static final String AGENT_BROWSE_PATH = "/ce-websearch/agent_browse";
    static final String CONTROL_PATH_PREFIX = "/ce-websearch/agent/sessions/";
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    // A browser-agent session runs the full runner wall-clock (~600 s cap), so
    // the relay read timeout must sit above it - mirror the WebSearchToolsProvider
    // agent_browse tool ceiling (640 s) plus a small margin.
    private static final int READ_TIMEOUT_MS = 650_000;

    private final RestTemplate restTemplate;

    @Autowired
    public CloudBrowserAgentRelayClient() {
        this(defaultRestTemplate());
    }

    CloudBrowserAgentRelayClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Relay a new {@code agent_browse} session to the linked cloud deployment.
     *
     * @return the cloud browser-agent response body (same shape as a local
     *         {@link BrowserAgentModule} result: {@code {final_result, extracted_data,
     *         stop_reason, session_id, cdp_ws_url, cdp_token, run_id, node_id, steps,
     *         cost, ...}})
     * @throws IllegalStateException when the relay returns a non-2xx status or an empty body
     */
    public Map<String, Object> agentBrowse(CloudLlmRuntimeCredentials credentials, CeBrowseRelayRequest request) {
        return post(url(credentials.cloudApiUrl(), AGENT_BROWSE_PATH), credentials, request,
                "Cloud browser agent relay");
    }

    /**
     * Relay a session-control call ({@code status}/{@code intervene}/{@code abort}/{@code screenshot})
     * to the linked cloud deployment.
     *
     * @param action the verb ({@code status}/{@code intervene}/{@code abort}/{@code screenshot})
     * @return the cloud control-endpoint response body
     * @throws IllegalStateException when the relay returns a non-2xx status or an empty body
     */
    public Map<String, Object> browseControl(CloudLlmRuntimeCredentials credentials, String sessionId,
                                             String action, CeBrowseControlRequest request) {
        String path = CONTROL_PATH_PREFIX + sessionId + "/" + action;
        return post(url(credentials.cloudApiUrl(), path), credentials, request,
                "Cloud browser agent " + action + " relay");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String url, CloudLlmRuntimeCredentials credentials,
                                     Object body, String label) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credentials.accessToken());
        headers.set(INSTALL_HEADER, credentials.installId());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            throw new IllegalStateException(label + " returned " + e.getStatusCode().value()
                    + (responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody), e);
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException(label + " returned " + response.getStatusCode().value());
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
