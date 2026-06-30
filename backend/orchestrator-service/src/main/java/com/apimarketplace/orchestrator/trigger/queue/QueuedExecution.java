package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An item queued for execution in the priority-based execution queue.
 *
 * Ordering: higher priority first, then FIFO (lower sequence number first) within same priority.
 * This ensures ENTERPRISE executions are dequeued before FREE, and within the same plan,
 * earlier submissions are processed first.
 */
public class QueuedExecution implements Comparable<QueuedExecution> {

    private static final AtomicLong SEQUENCE_GENERATOR = new AtomicLong(0);

    private final WorkflowRunEntity run;
    private final String triggerId;
    private final TriggerType triggerType;
    private final Map<String, Object> payload;
    private final int priority;
    private final String userPlan;
    private final Instant enqueuedAt;
    private final long sequenceNumber;
    private final CompletableFuture<TriggerExecutionResult> future;
    private final AtomicBoolean cancelled;
    private final boolean fireAndForget;

    public QueuedExecution(WorkflowRunEntity run, String triggerId, TriggerType triggerType,
                           Map<String, Object> payload, int priority) {
        this(run, triggerId, triggerType, payload, priority, null, false);
    }

    public QueuedExecution(WorkflowRunEntity run, String triggerId, TriggerType triggerType,
                           Map<String, Object> payload, int priority, String userPlan) {
        this(run, triggerId, triggerType, payload, priority, userPlan, false);
    }

    public QueuedExecution(WorkflowRunEntity run, String triggerId, TriggerType triggerType,
                           Map<String, Object> payload, int priority, String userPlan,
                           boolean fireAndForget) {
        this.run = run;
        this.triggerId = triggerId;
        this.triggerType = triggerType;
        this.payload = payload;
        this.priority = priority;
        this.userPlan = userPlan;
        this.enqueuedAt = Instant.now();
        this.sequenceNumber = SEQUENCE_GENERATOR.incrementAndGet();
        this.future = new CompletableFuture<>();
        this.cancelled = new AtomicBoolean(false);
        this.fireAndForget = fireAndForget;
    }

    /**
     * Checks if this item has been waiting longer than the given timeout.
     */
    public boolean isExpired(int timeoutSeconds) {
        return !Instant.now().isBefore(enqueuedAt.plusSeconds(timeoutSeconds));
    }

    /**
     * Marks this execution as cancelled. The worker loop will skip cancelled items.
     */
    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || future.isCancelled();
    }

    /**
     * Completes the future with the given result.
     */
    public void complete(TriggerExecutionResult result) {
        future.complete(result);
    }

    /**
     * Completes the future with an exception.
     */
    public void completeExceptionally(Throwable ex) {
        future.completeExceptionally(ex);
    }

    /**
     * Higher priority first, then FIFO (lower sequence = earlier submission).
     */
    @Override
    public int compareTo(QueuedExecution other) {
        // Higher priority should come first (reverse order)
        int priorityCompare = Integer.compare(other.priority, this.priority);
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        // Same priority: FIFO (lower sequence first)
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }

    // Getters
    public WorkflowRunEntity getRun() { return run; }
    public String getTriggerId() { return triggerId; }
    public TriggerType getTriggerType() { return triggerType; }
    public Map<String, Object> getPayload() { return payload; }
    public int getPriority() { return priority; }
    public String getUserPlan() { return userPlan; }
    public Instant getEnqueuedAt() { return enqueuedAt; }
    public long getSequenceNumber() { return sequenceNumber; }
    public CompletableFuture<TriggerExecutionResult> getFuture() { return future; }
    public boolean isFireAndForget() { return fireAndForget; }
}
