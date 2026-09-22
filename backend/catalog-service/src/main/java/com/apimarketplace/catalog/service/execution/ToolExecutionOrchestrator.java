package com.apimarketplace.catalog.service.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Output projection for catalog API tool executions.
 *
 * Given a raw response and the tool's declared {@code execution.mode}, projects it through
 * {@link OutputProjector}. All four modes ({@code sync}, {@code upload}, {@code async_poll},
 * {@code streaming}) project identically here: the mode-specific work happens upstream, and an
 * unknown mode fails fast rather than passing through silently.
 *
 * <h3>Wiring</h3>
 * This class does NOT make HTTP calls, encode request bodies, poll async jobs or handle binary
 * responses. It used to expose facade methods for all of those, whose javadoc claimed they were
 * "Called by HttpExecutionService"; that service has always called BinaryResponseHandler,
 * MultipartBodyEncoder and AsyncPollExecutor directly, so the facade methods had zero callers
 * repo-wide and were removed along with the collaborator fields they held.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ToolExecutionOrchestrator {

    private final OutputProjector outputProjector;

    /**
     * Apply post-processing on a raw execution result.
     *
     * @param rawData          raw response data from HttpExecutionService
     * @param outputSchemaJson tool's declared output schema (JSONB string, may be null)
     * @param executionMode    tool's execution mode
     * @return projected result
     */
    public Object projectResult(Object rawData, String outputSchemaJson, String executionMode) {
        return projectResult(rawData, outputSchemaJson, executionMode, null);
    }

    /**
     * Same, with the call's RESPONSE HEADERS available to the projection.
     *
     * <p>Only a field that declares {@code "source": "header"} reads from them, so passing headers
     * changes nothing for any tool that does not. See {@link OutputProjector#project(Object,
     * String, java.util.Map)} for why a provider would put a needed value there.
     *
     * <p>NOT every mode carries usable headers, and the difference is invisible at this layer.
     * {@code sync} and {@code upload} pass the real response headers. {@code streaming} has none
     * (HttpExecutionService puts an empty map: an aggregated SSE stream has no single response),
     * and {@code async_poll} passes the SUBMIT call's headers, not those of the final poll that
     * produced the data, because AsyncPollExecutor returns a converted body and discards them. A
     * header-sourced field on either of those two would therefore resolve to nothing, silently,
     * which is why validate_apis.py REFUSES that combination at import time rather than letting a
     * seed author discover it at run time.
     *
     * @param responseHeaders response headers from the HTTP call, may be null
     */
    public Object projectResult(Object rawData, String outputSchemaJson, String executionMode,
                                java.util.Map<String, String> responseHeaders) {
        String mode = normalizeMode(executionMode);
        switch (mode) {
            case "sync":
            case "upload":
                // upload only changes the REQUEST encoding; the response is plain JSON.
                return outputProjector.project(rawData, outputSchemaJson, responseHeaders);
            case "async_poll":
                // The async executor has already returned the resolved result body before this
                // method is called, so we project it the same way as sync.
                return outputProjector.project(rawData, outputSchemaJson, responseHeaders);
            case "streaming":
                // StreamingResponseHandler has already aggregated the SSE chunks into a
                // {chunks, chunk_count, terminated, truncated, error?} envelope. Project
                // it through OutputProjector so the tool's outputSchema can shape each chunk.
                return outputProjector.project(rawData, outputSchemaJson, responseHeaders);
            default:
                // Unknown mode - fail-fast instead of silent passthrough.
                log.error("ToolExecutionOrchestrator: unknown execution mode '{}'", mode);
                throw new IllegalStateException("Unknown execution.mode: " + mode);
        }
    }


    // ─────────────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────────────────

    private String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "sync" : mode;
    }
}
