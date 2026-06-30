package com.apimarketplace.orchestrator.services.streaming.bus;

import com.apimarketplace.orchestrator.metrics.WorkflowMetrics;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowEvent;
import com.apimarketplace.orchestrator.services.streaming.events.WorkflowStatusEvent;
import com.apimarketplace.orchestrator.services.streaming.state.RunStateStore;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WorkflowEventProcessor implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowEventProcessor.class);

    private final String runId;
    private final BlockingQueue<WorkflowEvent> queue;
    private final RunStateStore runStateStore;
    private final WorkflowMetrics metrics;
    private final Duration pollTimeout;
    private final Runnable onShutdown;
    private final AtomicBoolean running = new AtomicBoolean();

    WorkflowEventProcessor(String runId,
                           int capacity,
                           RunStateStore runStateStore,
                           WorkflowMetrics metrics,
                           Duration pollTimeout,
                           Runnable onShutdown) {
        this.runId = runId;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.runStateStore = runStateStore;
        this.metrics = metrics;
        this.pollTimeout = pollTimeout;
        this.onShutdown = onShutdown;
    }

    void start(ExecutorService executor) {
        if (running.compareAndSet(false, true)) {
            executor.submit(this);
        }
    }

    boolean offer(WorkflowEvent event) {
        if (!running.get()) {
            return false;
        }
        boolean accepted = queue.offer(event);
        if (accepted) {
            metrics.recordWorkflowEventEnqueued(runId, queue.size());
        }
        return accepted;
    }

    void stop() {
        running.set(false);
    }

    @Override
    public void run() {
        try {
            boolean shouldStopAfterDrain = false;
            while (running.get() || !queue.isEmpty()) {
                WorkflowEvent event = queue.poll(pollTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                runStateStore.applyEvent(event);
                metrics.recordWorkflowEventProcessed(runId, queue.size());
                if (event instanceof WorkflowStatusEvent statusEvent && statusEvent.terminal()) {
                    shouldStopAfterDrain = true;
                }
                if (shouldStopAfterDrain && queue.isEmpty()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Workflow event processor interrupted for runId={}", runId);
        } finally {
            running.set(false);
            metrics.cleanupRun(runId);
            if (onShutdown != null) {
                onShutdown.run();
            }
        }
    }
}
