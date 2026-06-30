package com.apimarketplace.orchestrator.services.code;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Client for the Piston code execution engine (EE mode).
 * Sends code to Piston for sandboxed execution and returns the result.
 * <p>
 * Activated when piston.embedded is not set or false (default EE behavior).
 */
@Service
@ConditionalOnProperty(name = "piston.embedded", havingValue = "false", matchIfMissing = true)
public class PistonClient implements CodeExecutor {

    private static final Logger logger = LoggerFactory.getLogger(PistonClient.class);

    @Value("${piston.url:http://localhost:2000}")
    private String pistonUrl;

    @Value("${piston.run-timeout:10000}")
    private int defaultRunTimeout;

    @Value("${piston.memory-limit:128000000}")
    private long defaultMemoryLimit;

    /** Number of connection attempts (1 = no retry) for transient connect failures to Piston. */
    @Value("${piston.connect-max-attempts:4}")
    private int connectMaxAttempts = 4;

    /** Base back-off (ms) before the first retry; doubles each attempt up to the max. */
    @Value("${piston.connect-retry-base-backoff-ms:500}")
    private long connectRetryBaseBackoffMs = 500;

    /** Upper bound (ms) for a single inter-attempt back-off. */
    @Value("${piston.connect-retry-max-backoff-ms:3000}")
    private long connectRetryMaxBackoffMs = 3000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PistonClient() {
        this(new ObjectMapper());
    }

    public PistonClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    /**
     * Constructor for testing with custom URL.
     */
    PistonClient(ObjectMapper objectMapper, HttpClient httpClient, String pistonUrl,
                 int defaultRunTimeout, long defaultMemoryLimit) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.pistonUrl = pistonUrl;
        this.defaultRunTimeout = defaultRunTimeout;
        this.defaultMemoryLimit = defaultMemoryLimit;
    }

    /** Test seam: tune the connect-retry policy without a Spring context. */
    void setConnectRetryConfigForTest(int maxAttempts, long baseBackoffMs, long maxBackoffMs) {
        this.connectMaxAttempts = maxAttempts;
        this.connectRetryBaseBackoffMs = baseBackoffMs;
        this.connectRetryMaxBackoffMs = maxBackoffMs;
    }

    @Override
    public CodeResult execute(CodeRequest request) throws Exception {
        PistonRequest pistonRequest = new PistonRequest(
            request.language(), request.version(), request.code(), request.stdin(),
            request.runTimeoutMs(), request.compileTimeoutMs(), request.memoryLimit()
        );
        PistonResponse response = executePiston(pistonRequest);
        return new CodeResult(
            response.language(), response.version(),
            response.run() != null ? response.run().stdout() : "",
            response.run() != null ? response.run().stderr() : "",
            response.run() != null ? response.run().code() : -1,
            response.run() != null ? response.run().signal() : null,
            response.run() != null ? response.run().output() : "",
            response.compile() != null ? response.compile().stdout() : null,
            response.compile() != null ? response.compile().stderr() : null
        );
    }

    /**
     * Execute code via Piston API.
     * Returns PistonResponse with stdout, stderr, exit code.
     */
    public PistonResponse executePiston(PistonRequest request) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("language", request.language());
        body.put("version", request.version() != null ? request.version() : "*");
        body.put("files", List.of(Map.of("name", "main", "content", request.code())));
        if (request.stdin() != null) body.put("stdin", request.stdin());
        body.put("run_timeout", request.runTimeoutMs() > 0 ? request.runTimeoutMs() : defaultRunTimeout);
        body.put("compile_timeout", request.compileTimeoutMs() > 0 ? request.compileTimeoutMs() : defaultRunTimeout);
        body.put("memory_limit", request.memoryLimit() > 0 ? request.memoryLimit() : defaultMemoryLimit);

        String jsonBody = objectMapper.writeValueAsString(body);

        long httpTimeoutSec = Math.max((request.runTimeoutMs() > 0 ? request.runTimeoutMs() : defaultRunTimeout) / 1000 + 10, 30);

        logger.info("Piston execute request: language={}, codeLength={}, bodyLength={}", request.language(), request.code().length(), jsonBody.length());

        return executeWithConnectRetry(jsonBody, httpTimeoutSec);
    }

    /**
     * Send the execute request to Piston, retrying transient CONNECT failures with bounded back-off.
     *
     * <p>A {@link java.net.ConnectException} ("Connection refused" / connect failure) means the TCP
     * connection was never established - so the request body was never sent and Piston executed
     * nothing. Retrying is therefore idempotent: there is no risk of double-running the user's code.
     * Piston runs as a single shared edge container that briefly stops accepting connections during
     * its daily restart and under co-tenant load; a bounded retry rides over those windows instead of
     * hard-failing the code node. Read timeouts and non-200 responses are intentionally NOT retried
     * (the code may already have run, or Piston returned a real answer).
     */
    PistonResponse executeWithConnectRetry(String jsonBody, long httpTimeoutSec) throws Exception {
        int attempts = Math.max(1, connectMaxAttempts);
        java.net.ConnectException lastError = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return executeOnce(jsonBody, httpTimeoutSec);
            } catch (java.net.ConnectException e) {
                lastError = e;
                if (attempt >= attempts) break;
                long backoffMs = connectRetryBackoffMs(attempt);
                logger.warn("Piston connection failed (attempt {}/{}), retrying in {}ms: {}",
                    attempt, attempts, backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        logger.error("Piston unreachable after {} connection attempt(s): {}",
            attempts, lastError != null ? lastError.getMessage() : "unknown");
        throw lastError;
    }

    /** Back-off before the retry that follows a 1-based failed attempt: base*2^(attempt-1), capped. */
    long connectRetryBackoffMs(int attempt) {
        long shift = Math.min(Math.max(attempt - 1, 0), 30); // guard against overflow on absurd counts
        long backoff = connectRetryBaseBackoffMs << shift;
        return Math.min(backoff, connectRetryMaxBackoffMs);
    }

    /**
     * A single HTTP execute attempt against Piston. Throws {@link java.net.ConnectException} when the
     * connection cannot be established (caller retries that); other failures propagate unretried.
     */
    protected PistonResponse executeOnce(String jsonBody, long httpTimeoutSec) throws Exception {
        // Use HttpURLConnection instead of HttpClient to avoid chunked encoding issues.
        // disconnect() in finally guarantees the socket returns to the pool even on parse-error / OOM,
        // avoiding fd leaks under sustained load.
        java.net.URL url = new java.net.URL(pistonUrl + "/api/v2/execute");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout((int) (httpTimeoutSec * 1000));
            byte[] bodyBytes = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);
            try (var os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }
            int statusCode = conn.getResponseCode();
            String responseBody;
            try (var is = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                responseBody = is != null ? new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) : "";
            }

            if (statusCode != 200) {
                logger.error("Piston HTTP {} response: {}", statusCode, responseBody);
                throw new RuntimeException("Piston returned HTTP " + statusCode + ": " + responseBody);
            }

            return parsePistonResponse(responseBody);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * List available runtimes from Piston.
     */
    public List<Map<String, Object>> listRuntimes() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(pistonUrl + "/api/v2/runtimes"))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(response.body(), new TypeReference<>() {});
    }

    // ==================== Records ====================

    public record PistonRequest(String language, String version, String code, String stdin,
                                int runTimeoutMs, int compileTimeoutMs, long memoryLimit) {}

    public record PistonResponse(String language, String version,
                                 PistonRun compile, PistonRun run) {}

    public record PistonRun(String stdout, String stderr, int code, String signal, String output) {}

    // ==================== Parsing ====================

    @SuppressWarnings("unchecked")
    private PistonResponse parsePistonResponse(String json) throws Exception {
        Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
        String lang = (String) map.get("language");
        String ver = (String) map.get("version");
        PistonRun compile = map.containsKey("compile") ? parseRun((Map<String, Object>) map.get("compile")) : null;
        PistonRun run = map.containsKey("run") ? parseRun((Map<String, Object>) map.get("run")) : null;
        return new PistonResponse(lang, ver, compile, run);
    }

    private PistonRun parseRun(Map<String, Object> map) {
        if (map == null) return null;
        return new PistonRun(
            (String) map.getOrDefault("stdout", ""),
            (String) map.getOrDefault("stderr", ""),
            map.get("code") instanceof Number n ? n.intValue() : 0,
            (String) map.get("signal"),
            (String) map.getOrDefault("output", "")
        );
    }
}
