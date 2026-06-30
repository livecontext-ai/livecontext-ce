package com.apimarketplace.storage.service.file;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for the streaming download pipeline.
 *
 * <p>Wired into {@link com.apimarketplace.storage.web.FileController#proxyDownload}
 * and {@link com.apimarketplace.storage.web.InternalFileController#download} to
 * give ops the visibility the v1 audit (round 1, auditor C) flagged as
 * required before deploy. Without these counters we are blind to:
 * <ul>
 *   <li>How many concurrent streams the service is handling (predictor of
 *       Apache HTTP client pool saturation - the SDK default 50 was a
 *       round-1 BLOCKING risk; bumped to 100 + 30s acquisition timeout).</li>
 *   <li>How many bytes have been streamed (capacity planning, throughput).</li>
 *   <li>How many client disconnects (signals slow clients holding pool
 *       connections).</li>
 *   <li>Per-stream latency (p99 = tail of slow downloads).</li>
 * </ul>
 *
 * <p>Optional via {@code @Autowired(required = false)} so test contexts
 * without a {@link MeterRegistry} (the unit-test profile of
 * {@code FileControllerTest} / {@code InternalFileControllerTest}) still
 * boot cleanly. Production has the actuator MeterRegistry available.
 */
@Component
public class StorageStreamingMetrics {

    private final MeterRegistry registry;
    private final AtomicLong concurrentStreams = new AtomicLong(0);

    private final Counter bytesServed;
    private final Counter clientDisconnects;
    private final Counter streamErrors;

    public StorageStreamingMetrics(@Autowired(required = false) MeterRegistry registry) {
        this.registry = registry;
        if (registry != null) {
            registry.gauge("storage.streaming.concurrent", concurrentStreams);
            this.bytesServed = Counter.builder("storage.streaming.bytes.served")
                .description("Total bytes streamed to clients via /api/files/proxy and /api/internal/storage/download")
                .register(registry);
            this.clientDisconnects = Counter.builder("storage.streaming.client.disconnects")
                .description("Streaming downloads aborted because the client closed the connection mid-transfer")
                .register(registry);
            this.streamErrors = Counter.builder("storage.streaming.errors")
                .description("Streaming downloads aborted by a non-disconnect error (S3 read failure, etc.)")
                .register(registry);
        } else {
            this.bytesServed = null;
            this.clientDisconnects = null;
            this.streamErrors = null;
        }
    }

    /** @return a {@link StreamSpan} that MUST be closed (try-with-resources)
     *  to record duration + decrement the concurrent gauge. */
    public StreamSpan startStream() {
        concurrentStreams.incrementAndGet();
        Timer.Sample sample = registry != null ? Timer.start(registry) : null;
        return new StreamSpan(sample);
    }

    public void recordBytes(long count) {
        if (bytesServed != null && count > 0) {
            bytesServed.increment(count);
        }
    }

    public void recordClientDisconnect() {
        if (clientDisconnects != null) {
            clientDisconnects.increment();
        }
    }

    public void recordStreamError() {
        if (streamErrors != null) {
            streamErrors.increment();
        }
    }

    /** Span returned by {@link #startStream()}. Always close it. */
    public final class StreamSpan implements AutoCloseable {
        private final Timer.Sample sample;
        private boolean closed = false;

        StreamSpan(Timer.Sample sample) {
            this.sample = sample;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            concurrentStreams.decrementAndGet();
            if (sample != null && registry != null) {
                sample.stop(Timer.builder("storage.streaming.duration")
                    .description("Wall-clock time from S3 stream open to client transfer completion")
                    .register(registry));
            }
        }
    }
}
