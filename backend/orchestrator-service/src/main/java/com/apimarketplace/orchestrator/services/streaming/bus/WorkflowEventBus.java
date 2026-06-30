package com.apimarketplace.orchestrator.services.streaming.bus;

import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.cache.RunScopedCache;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowEvent;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fournit un bus par run fondé sur des BlockingQueue bornées.
 */
@Component
public class WorkflowEventBus implements DisposableBean, RunScopedCache {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowEventBus.class);

    private final RunStateStore runStateStore;
    private final int queueCapacity;
    private final Duration pollTimeout;
    private final ExecutorService executor;
    private final Map<String, WorkflowEventProcessor> processors = new ConcurrentHashMap<>();
    private final WorkflowMetrics metrics;

    public WorkflowEventBus(RunStateStore runStateStore,
                            WorkflowMetrics metrics,
                            @Value("${workflow.streaming.bus-capacity:4096}") int queueCapacity,
                            @Value("${workflow.streaming.processor-poll-ms:50}") long pollMs,
                            @Value("${workflow.streaming.max-processors:200}") int maxProcessors) {
        this.runStateStore = runStateStore;
        this.metrics = metrics;
        this.queueCapacity = Math.max(queueCapacity, 128);
        this.pollTimeout = Duration.ofMillis(Math.max(pollMs, 10));
        this.executor = Executors.newFixedThreadPool(Math.max(maxProcessors, 16), new ProcessorThreadFactory());
    }

    public void publish(WorkflowEvent event) {
        if (event == null || event.runId() == null) {
            return;
        }
        WorkflowEventProcessor processor = processors.computeIfAbsent(
            event.runId(),
            this::createProcessor
        );
        if (!processor.offer(event)) {
            logger.warn("Dropping workflow event for runId={} because the queue is full", event.runId());
            metrics.recordWorkflowEventDropped(event.runId());
        }
    }

    public void shutdownProcessor(String runId) {
        WorkflowEventProcessor processor = processors.remove(runId);
        if (processor != null) {
            processor.stop();
        }
    }

    private WorkflowEventProcessor createProcessor(String runId) {
        WorkflowEventProcessor processor = new WorkflowEventProcessor(
            runId,
            queueCapacity,
            runStateStore,
            metrics,
            pollTimeout,
            () -> processors.remove(runId)
        );
        processor.start(executor);
        return processor;
    }

    @Override
    public void destroy() {
        processors.values().forEach(WorkflowEventProcessor::stop);
        executor.shutdownNow();
        processors.clear();
    }

    private static final class ProcessorThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("workflow-event-processor-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RunScopedCache Implementation
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void cleanupRun(String runId) {
        shutdownProcessor(runId);
    }

    @Override
    public String getCacheName() {
        return "WorkflowEventBusCache";
    }

    @Override
    public CacheDomain getDomain() {
        return CacheDomain.STREAMING;
    }

    @Override
    public int getCacheSize() {
        return processors.size();
    }
}
