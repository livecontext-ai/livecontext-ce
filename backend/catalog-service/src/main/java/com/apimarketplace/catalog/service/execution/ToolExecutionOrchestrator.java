package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Centralized typed-execution facade for catalog API tools.
 *
 * Routes a tool execution to the right strategy based on its declared {@code execution.mode}:
 *
 * <ul>
 *   <li>{@code sync}        - passthrough, project response via {@link OutputProjector}</li>
 *   <li>{@code async_poll}  - handled by {@link AsyncPollExecutor}</li>
 *   <li>{@code upload}      - request body assembled by {@link MultipartBodyEncoder};
 *       deprecated alias for sync (no real endpoints declare this mode)</li>
 *   <li>{@code streaming}   - SSE chunks aggregated upstream by
 *       {@link StreamingResponseHandler}, then projected like sync</li>
 * </ul>
 *
 * Binary RESPONSES (orthogonal to mode) are routed through {@link BinaryResponseHandler}
 * when {@code execution.response.type=binary} (Phase 8).
 *
 * <h3>Wiring</h3>
 * The orchestrator does NOT make HTTP calls itself - those stay in
 * {@link com.apimarketplace.catalog.service.http.HttpExecutionService}. The orchestrator is
 * a thin coordination layer: pre-process the request (multipart encoding), post-process the
 * response (binary upload, output projection, async poll resolution).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolExecutionOrchestrator {

    private final OutputProjector outputProjector;
    private final BinaryResponseHandler binaryResponseHandler;
    private final MultipartBodyEncoder multipartBodyEncoder;
    private final AsyncPollExecutor asyncPollExecutor;
    private final ObjectMapper objectMapper;

    /**
     * Apply post-processing on a raw execution result.
     *
     * @param rawData          raw response data from HttpExecutionService
     * @param outputSchemaJson tool's declared output schema (JSONB string, may be null)
     * @param executionMode    tool's execution mode
     * @return projected result
     */
    public Object projectResult(Object rawData, String outputSchemaJson, String executionMode) {
        String mode = normalizeMode(executionMode);
        switch (mode) {
            case "sync":
            case "upload":
                // upload only changes the REQUEST encoding; the response is plain JSON.
                return outputProjector.project(rawData, outputSchemaJson);
            case "async_poll":
                // The async executor has already returned the resolved result body before this
                // method is called, so we project it the same way as sync.
                return outputProjector.project(rawData, outputSchemaJson);
            case "streaming":
                // StreamingResponseHandler has already aggregated the SSE chunks into a
                // {chunks, chunk_count, terminated, truncated, error?} envelope. Project
                // it through OutputProjector so the tool's outputSchema can shape each chunk.
                return outputProjector.project(rawData, outputSchemaJson);
            default:
                // Unknown mode - fail-fast instead of silent passthrough.
                log.error("ToolExecutionOrchestrator: unknown execution mode '{}'", mode);
                throw new IllegalStateException("Unknown execution.mode: " + mode);
        }
    }

    /**
     * Process a binary response by uploading it to MinIO and returning a typed FileRef map.
     * Called by HttpExecutionService when {@code execution.response.type=binary}.
     */
    public Map<String, Object> handleBinaryResponse(byte[] rawBytes,
                                                    String contentType,
                                                    String tenantId,
                                                    String outputSchemaJson,
                                                    String toolSlug) {
        return binaryResponseHandler.handle(rawBytes, contentType, tenantId, outputSchemaJson, toolSlug);
    }

    /**
     * Encode the multipart body for a tool whose execution.request.bodyType=multipart.
     * Called by HttpExecutionService before issuing the upstream request.
     */
    public org.springframework.util.MultiValueMap<String, Object> encodeMultipartBody(
            String executionSpecJson,
            Map<String, Object> parameters,
            String tenantId) {
        JsonNode multipartFields = readPath(executionSpecJson, "request", "multipartFields");
        return multipartBodyEncoder.encode(multipartFields, parameters, tenantId);
    }

    /**
     * Resolve a long-running async job by polling. Called by HttpExecutionService after the
     * initial submit when {@code execution.mode=async_poll}.
     */
    public Object resolveAsyncJob(String baseUrl,
                                  Object submitResponse,
                                  String executionSpecJson,
                                  org.springframework.http.HttpHeaders pollHeaders) throws AsyncPollExecutor.AsyncPollFailureException {
        JsonNode asyncCfg = readPath(executionSpecJson, "async");
        if (asyncCfg == null || !asyncCfg.isObject()) {
            throw new AsyncPollExecutor.AsyncPollFailureException("execution.async block missing");
        }
        return asyncPollExecutor.pollUntilDone(
            baseUrl,
            objectMapper.valueToTree(submitResponse),
            asyncCfg,
            pollHeaders
        );
    }

    /**
     * Inspect a tool's execution mode from its execution_spec JSON. Returns "sync" when
     * the spec is missing or malformed.
     */
    public String resolveMode(String executionSpecJson) {
        if (executionSpecJson == null || executionSpecJson.isBlank()) return "sync";
        try {
            JsonNode root = objectMapper.readTree(executionSpecJson);
            return normalizeMode(root.path("mode").asText("sync"));
        } catch (Exception e) {
            return "sync";
        }
    }

    /**
     * Returns true when the tool's execution_spec declares a binary response.
     */
    public boolean expectsBinaryResponse(String executionSpecJson) {
        if (executionSpecJson == null || executionSpecJson.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(executionSpecJson);
            return "binary".equals(root.path("response").path("type").asText());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true when the tool's execution_spec declares a multipart request body.
     */
    public boolean expectsMultipartBody(String executionSpecJson) {
        if (executionSpecJson == null || executionSpecJson.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(executionSpecJson);
            return "multipart".equals(root.path("request").path("bodyType").asText());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Convenience method that wraps a typed-projected payload back into the {@code Map} envelope
     * expected by ToolExecutionManager.
     */
    public Object enrich(Object projected, Map<String, Object> extraEnvelope) {
        if (extraEnvelope == null || extraEnvelope.isEmpty()) {
            return projected;
        }
        if (projected instanceof Map<?, ?> projectedMap) {
            @SuppressWarnings("unchecked")
            Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) projectedMap);
            merged.putAll(extraEnvelope);
            return merged;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("data", projected);
        wrapped.putAll(extraEnvelope);
        return wrapped;
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "sync" : mode;
    }

    private JsonNode readPath(String executionSpecJson, String... path) {
        if (executionSpecJson == null || executionSpecJson.isBlank()) return null;
        try {
            JsonNode current = objectMapper.readTree(executionSpecJson);
            for (String segment : path) {
                current = current.path(segment);
                if (current.isMissingNode()) return null;
            }
            return current;
        } catch (Exception e) {
            return null;
        }
    }
}
