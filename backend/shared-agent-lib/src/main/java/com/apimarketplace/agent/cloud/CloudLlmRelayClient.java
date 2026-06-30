package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * CE-side HTTP client for the cloud LLM relay. The relay returns model deltas;
 * CE still owns tools, execution state, observability, and metrics.
 */
@Component
public class CloudLlmRelayClient {

    private static final String COMPLETE_PATH = "/ce-llm/complete";
    private static final String STREAM_PATH = "/ce-llm/stream";
    private static final String SETTLE_PATH = "/ce-llm/settle";
    private static final String RELEASE_PATH = "/ce-llm/release";
    private static final String INSTALL_HEADER = "X-LiveContext-Install-Id";
    private static final int DEFAULT_STREAM_READ_TIMEOUT_MS = 70 * 60 * 1000;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final int streamReadTimeoutMs;

    @Autowired
    public CloudLlmRelayClient(ObjectMapper objectMapper) {
        this(new RestTemplate(), objectMapper, DEFAULT_STREAM_READ_TIMEOUT_MS);
    }

    CloudLlmRelayClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this(restTemplate, objectMapper, DEFAULT_STREAM_READ_TIMEOUT_MS);
    }

    CloudLlmRelayClient(RestTemplate restTemplate, ObjectMapper objectMapper, int streamReadTimeoutMs) {
        if (streamReadTimeoutMs <= 0) {
            throw new IllegalArgumentException("streamReadTimeoutMs must be positive");
        }
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.streamReadTimeoutMs = streamReadTimeoutMs;
    }

    public CompletionResponse complete(CloudLlmRuntimeCredentials credentials, CloudLlmRelayRequest request) {
        HttpHeaders headers = headers(credentials, MediaType.APPLICATION_JSON);
        ResponseEntity<CompletionResponse> response;
        try {
            response = restTemplate.exchange(
                    url(credentials.cloudApiUrl(), COMPLETE_PATH),
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    CompletionResponse.class
            );
        } catch (RestClientResponseException e) {
            String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            throw new LLMProviderException(request.provider(),
                    "Cloud LLM relay returned " + e.getStatusCode().value()
                            + (body == null || body.isBlank() ? "" : ": " + body),
                    e);
        }
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new LLMProviderException(request.provider(),
                    "Cloud LLM relay returned " + response.getStatusCode().value());
        }
        return response.getBody();
    }

    /**
     * Terminal settle for a centralized execution: tells the cloud to bill the accrued usage as one
     * {@code CE_LLM_RELAY} line (idempotent). Best-effort from the caller's perspective - the cloud
     * reaper backstops a failed terminal settle - but throws on a non-2xx so the caller can log.
     */
    public void settle(CloudLlmRuntimeCredentials credentials, CeRelaySettleRequest request) {
        postJson(credentials, SETTLE_PATH, request);
    }

    /** Releases a centralized execution that incurred no billable usage (drops its accrual). */
    public void release(CloudLlmRuntimeCredentials credentials, CeRelayReleaseRequest request) {
        postJson(credentials, RELEASE_PATH, request);
    }

    private void postJson(CloudLlmRuntimeCredentials credentials, String path, Object body) {
        HttpHeaders headers = headers(credentials, MediaType.APPLICATION_JSON);
        try {
            restTemplate.exchange(
                    url(credentials.cloudApiUrl(), path),
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Void.class
            );
        } catch (RestClientResponseException e) {
            String responseBody = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            throw new LLMProviderException("ce-relay",
                    "Cloud LLM relay " + path + " returned " + e.getStatusCode().value()
                            + (responseBody == null || responseBody.isBlank() ? "" : ": " + responseBody),
                    e);
        }
    }

    public void stream(CloudLlmRuntimeCredentials credentials,
                       CloudLlmRelayRequest request,
                       StreamingCallback callback) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url(credentials.cloudApiUrl(), STREAM_PATH))
                    .toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(streamReadTimeoutMs);
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, "Bearer " + credentials.accessToken());
            connection.setRequestProperty(INSTALL_HEADER, credentials.installId());
            connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty(HttpHeaders.ACCEPT, "application/x-ndjson");

            try (OutputStream out = connection.getOutputStream()) {
                objectMapper.writeValue(out, request);
            }

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                String error = readBody(connection.getErrorStream());
                callback.onError("Cloud LLM relay returned " + status + (error.isBlank() ? "" : ": " + error));
                return;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    dispatch(objectMapper.readValue(line, CloudLlmStreamEvent.class), callback);
                    if (callback.shouldStop()) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            callback.onError("Cloud LLM relay stream failed", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void dispatch(CloudLlmStreamEvent event, StreamingCallback callback) {
        if (event == null || event.type() == null) {
            callback.onError("Cloud LLM relay emitted an invalid stream event");
            return;
        }
        switch (event.type()) {
            case CONTENT_CHUNK -> callback.onChunk(event.content() == null ? "" : event.content());
            case THINKING_CHUNK -> callback.onThinking(event.thinking() == null ? "" : event.thinking());
            case TOOL_CALL -> callback.onToolCall(event.toolCall());
            case COMPLETED -> callback.onComplete(event.response() != null
                    ? event.response()
                    : CompletionResponse.builder().finishReason("stop").build());
            case ERROR -> callback.onError(event.error() == null ? "Cloud LLM relay error" : event.error());
        }
    }

    private static HttpHeaders headers(CloudLlmRuntimeCredentials credentials, MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credentials.accessToken());
        headers.set(INSTALL_HEADER, credentials.installId());
        headers.setContentType(contentType);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        return headers;
    }

    private static String url(String base, String path) {
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return cleanBase + path;
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            stream.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
