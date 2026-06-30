package com.apimarketplace.sse;

import java.time.Duration;

/**
 * Tunables for {@link SseStreamConsumer}.
 *
 * <p>All limits are defensive - without them, a misbehaving upstream (infinite stream,
 * massive payloads) could exhaust catalog-service memory or hold a request thread forever.
 *
 * @param maxWait       hard cap on total stream consumption time. Default 5 minutes.
 * @param maxChunks     stop after this many chunks even if the stream is still alive.
 *                      Default 10 000. Returned response will be marked {@code truncated=true}.
 * @param maxBytes      stop after this many bytes have been received across all chunks.
 *                      Default 10 MiB. Returned response will be marked {@code truncated=true}.
 * @param terminators   set of raw chunk payloads that mark the end of the stream
 *                      (matched against {@link SseChunk#rawData()} after stripping
 *                      {@code data: }). Default: {@code ["[DONE]"]} for OpenAI compatibility.
 *                      Pass an empty list to disable terminator matching.
 */
public record SseConsumerConfig(
        Duration maxWait,
        int maxChunks,
        long maxBytes,
        java.util.List<String> terminators
) {

    public static final Duration DEFAULT_MAX_WAIT = Duration.ofMinutes(5);
    public static final int DEFAULT_MAX_CHUNKS = 10_000;
    public static final long DEFAULT_MAX_BYTES = 10L * 1024L * 1024L; // 10 MiB
    public static final java.util.List<String> DEFAULT_TERMINATORS = java.util.List.of("[DONE]");

    public static SseConsumerConfig defaults() {
        return new SseConsumerConfig(
                DEFAULT_MAX_WAIT,
                DEFAULT_MAX_CHUNKS,
                DEFAULT_MAX_BYTES,
                DEFAULT_TERMINATORS
        );
    }

    /** Builder-style: override the maxWait keeping the other defaults. */
    public SseConsumerConfig withMaxWait(Duration newMaxWait) {
        return new SseConsumerConfig(newMaxWait, maxChunks, maxBytes, terminators);
    }

    public SseConsumerConfig withMaxChunks(int newMaxChunks) {
        return new SseConsumerConfig(maxWait, newMaxChunks, maxBytes, terminators);
    }

    public SseConsumerConfig withMaxBytes(long newMaxBytes) {
        return new SseConsumerConfig(maxWait, maxChunks, newMaxBytes, terminators);
    }

    public SseConsumerConfig withTerminators(java.util.List<String> newTerminators) {
        return new SseConsumerConfig(maxWait, maxChunks, maxBytes, java.util.List.copyOf(newTerminators));
    }
}
