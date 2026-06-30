package com.apimarketplace.sse;

import java.util.List;

/**
 * The result of fully consuming an SSE stream into memory.
 *
 * <p>Returned by {@link SseStreamConsumer#consume} once the upstream connection has been
 * closed (terminator received, deadline reached, byte budget exhausted, or stream EOF).
 * Callers project the {@code chunks} list against their tool's {@code outputSchema}.
 *
 * @param chunks         all chunks received before termination, in arrival order
 * @param chunkCount     convenience: {@code chunks.size()}
 * @param terminated     true when the stream was closed by an explicit terminator
 *                       (e.g. {@code data: [DONE]}); false when closed by EOF / deadline /
 *                       byte budget
 * @param truncated      true when consumption stopped before the natural EOF because the
 *                       configured {@code maxChunks} or {@code maxBytes} budget was reached
 * @param error          non-null when the upstream call failed mid-stream; the partial
 *                       chunks collected so far are still returned for diagnostics
 */
public record SseAggregatedResponse(
        List<SseChunk> chunks,
        int chunkCount,
        boolean terminated,
        boolean truncated,
        String error
) {

    public static SseAggregatedResponse of(List<SseChunk> chunks, boolean terminated, boolean truncated) {
        return new SseAggregatedResponse(List.copyOf(chunks), chunks.size(), terminated, truncated, null);
    }

    public static SseAggregatedResponse withError(List<SseChunk> chunks, String error) {
        return new SseAggregatedResponse(List.copyOf(chunks), chunks.size(), false, false, error);
    }

    public boolean hasError() {
        return error != null;
    }
}
