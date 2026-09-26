package com.apimarketplace.orchestrator.lifecycle;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts workflow runs executing on THIS instance on a thread nothing else waits for at
 * shutdown (today: the JDK default async pool that {@code POST /execute} and
 * {@code POST /{workflowId}/runs/{runId}/start} hand the run to). {@link AgentDrainCoordinator}
 * waits for it to reach zero, so a rolling restart does not cut such a run mid-node.
 *
 * <p>Runs started through the execution queue are counted by the queue itself
 * ({@code ExecutionQueue#getLocalActiveExecutions}); work on a Spring
 * {@code ThreadPoolTaskExecutor} bean is awaited by the executor's own lifecycle stop; work on
 * a request thread by Tomcat's graceful shutdown. Anything else that starts a run on an
 * unmanaged thread must go through {@link #runAsync}.
 */
@Component
public class LocalRunExecutionTracker {

    private final AtomicInteger active = new AtomicInteger();

    /**
     * Run {@code task} asynchronously on the JDK default async pool (the pool
     * {@code CompletableFuture.runAsync(Runnable)} picks: the common pool, or a thread per task
     * when the common pool has a parallelism of 1, as on a 1-2 CPU self-hosted box, so concurrent
     * runs are never serialized behind a single worker). Counted from BEFORE submission until the
     * task body ends, normally or by throwing: the release sits in a {@code finally} inside the
     * task, and the returned future is a copy, so cancelling it neither stops the run nor skips
     * the release, whether the task had started or not.
     */
    public CompletableFuture<Void> runAsync(Runnable task) {
        return submitCounted(task, null);
    }

    CompletableFuture<Void> runAsync(Runnable task, Executor executor) {
        return submitCounted(task, executor);
    }

    private CompletableFuture<Void> submitCounted(Runnable task, Executor executor) {
        active.incrementAndGet();
        Runnable counted = () -> {
            try {
                task.run();
            } finally {
                active.decrementAndGet();
            }
        };
        try {
            CompletableFuture<Void> submitted = executor == null
                    ? CompletableFuture.runAsync(counted)
                    : CompletableFuture.runAsync(counted, executor);
            // A copy: cancelling what the caller holds never cancels the submitted task, so a
            // cancellation BEFORE a worker picked the task up cannot skip the body (and with it
            // the release in its finally) and leave the run counted forever.
            return submitted.copy();
        } catch (RuntimeException rejected) {
            active.decrementAndGet();
            throw rejected;
        }
    }

    /** Runs currently counted on this instance. */
    public int activeCount() {
        return active.get();
    }
}
