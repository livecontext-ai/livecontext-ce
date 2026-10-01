package com.apimarketplace.catalog.service.relay;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CE-side HTTP client for the cloud catalog-tool execution relay. Mirrors
 * {@link com.apimarketplace.agent.cloud.CloudLlmRelayClient} /
 * {@code CloudWebSearchRelayClient}: authenticates with the cloud-link access token +
 * install id header and posts the whole tool execution to the linked cloud deployment,
 * which injects ITS platform credential and bills the linked cloud account.
 *
 * <p>A 200 is returned as a {@link ToolExecutionResponse} verbatim - INCLUDING
 * {@code success=false} upstream results (a failed Gmail call relayed through the cloud
 * is still a completed relay). Non-2xx relay-level errors ({@code SUBSCRIPTION_REQUIRED},
 * {@code INSUFFICIENT_CREDITS}, ...) surface as {@link CatalogRelayException} carrying
 * the error code so the caller can build an agent-actionable failure message.
 */
@Component
public class CloudCatalogRelayClient {

    static final String EXECUTE_PATH_PREFIX = "/ce-catalog/tools/";
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    // Read timeout is deliberately long: the cloud side runs the real upstream API call
    // (async-poll tools like video generation can take minutes-adjacent durations).
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public CloudCatalogRelayClient(ObjectMapper objectMapper) {
        this(defaultRestTemplate(), objectMapper);
    }

    CloudCatalogRelayClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Relay a catalog tool execution to the linked cloud deployment.
     *
     * @return the cloud's {@link ToolExecutionResponse} (success OR upstream failure)
     * @throws CatalogRelayException when the relay itself refuses the call (non-2xx with
     *         an {@code {"error": ...}} body) or answers with an empty/unusable body
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResponse execute(CloudLlmRuntimeCredentials credentials,
                                         String apiSlug,
                                         String toolSlug,
                                         Map<String, Object> parameters,
                                         List<String> expand,
                                         Integer maxItems,
                                         Boolean inlineBinaries) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credentials.accessToken());
        headers.set(INSTALL_HEADER, credentials.installId());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parameters", parameters != null ? parameters : Map.of());
        body.put("expand", expand);
        body.put("maxItems", maxItems);
        body.put("inlineBinaries", inlineBinaries);

        ResponseEntity<Map> response;
        try {
            response = restTemplate.exchange(
                    url(credentials.cloudApiUrl(), apiSlug, toolSlug),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class
            );
        } catch (RestClientResponseException e) {
            throw toRelayException(e);
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new CatalogRelayException(null, false,
                    "Cloud catalog relay returned " + response.getStatusCode().value() + " with no body");
        }
        return toToolExecutionResponse((Map<String, Object>) response.getBody());
    }

    /**
     * Map the cloud's serialized {@code ToolExecutionResponse} JSON back onto the local
     * DTO. Manual mapping because the DTO is builder-only (no no-arg ctor for Jackson).
     */
    @SuppressWarnings("unchecked")
    private static ToolExecutionResponse toToolExecutionResponse(Map<String, Object> body) {
        Object metadata = body.get("metadata");
        Object executionTimeMs = body.get("executionTimeMs");
        return ToolExecutionResponse.builder()
                .success(Boolean.TRUE.equals(body.get("success")))
                .result(body.get("result"))
                .error(asString(body.get("error")))
                .metadata(metadata instanceof Map ? (Map<String, Object>) metadata : null)
                .executionTimeMs(executionTimeMs instanceof Number n ? n.longValue() : 0L)
                .toolId(asString(body.get("toolId")))
                .requestId(asString(body.get("requestId")))
                .build();
    }

    private CatalogRelayException toRelayException(RestClientResponseException e) {
        String errorCode = null;
        boolean delinquent = false;
        String rawBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
        if (rawBody != null && !rawBody.isBlank()) {
            try {
                Map<?, ?> parsed = objectMapper.readValue(rawBody, Map.class);
                errorCode = asString(parsed.get("error"));
                delinquent = Boolean.TRUE.equals(parsed.get("delinquent"));
            } catch (Exception ignored) {
                // Non-JSON error body (proxy page, plain text) - keep errorCode null.
            }
        }
        return new CatalogRelayException(errorCode, delinquent,
                "Cloud catalog relay returned " + e.getStatusCode().value()
                        + (errorCode != null ? ": " + errorCode : ""));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String url(String base, String apiSlug, String toolSlug) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return cleanBase + EXECUTE_PATH_PREFIX + apiSlug + "/" + toolSlug + "/execute";
    }

    private static RestTemplate defaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        RestTemplate template = new RestTemplate();
        template.setRequestFactory(factory);
        return template;
    }

    /**
     * Relay-level refusal (never an upstream tool failure - those come back as a 200
     * with {@code success=false}). {@code errorCode} is one of the cloud's relay error
     * codes ({@code SUBSCRIPTION_REQUIRED}, {@code INSUFFICIENT_CREDITS},
     * {@code CE_LINK_NOT_ACTIVE}, {@code OAUTH_NOT_RELAYABLE},
     * {@code PLATFORM_NOT_AVAILABLE}, {@code TOOL_NOT_FOUND}, {@code RATE_LIMITED},
     * {@code AUTHENTICATION_REQUIRED}, {@code INVALID_RELAY_REQUEST}) or null when the
     * body was unparseable.
     */
    public static class CatalogRelayException extends RuntimeException {
        private final String errorCode;
        private final boolean delinquent;

        public CatalogRelayException(String errorCode, boolean delinquent, String message) {
            super(message);
            this.errorCode = errorCode;
            this.delinquent = delinquent;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public boolean isDelinquent() {
            return delinquent;
        }
    }
}
