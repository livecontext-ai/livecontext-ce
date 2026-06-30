package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;

import java.util.Map;

/**
 * Abstraction over the execution queue that schedules and prioritizes
 * workflow trigger executions.
 *
 * <p>The single-instance implementation ({@link ExecutionQueueService}) uses
 * an in-memory priority queue with a semaphore-based worker pool. Redis-backed
 * implementations keep this interface as the orchestration boundary so callers
 * do not depend on broker details.
 */
public interface ExecutionQueue {

    /**
     * Enqueue an execution and wait for the result.
     *
     * <p>Fast path: if a worker slot is available immediately, execute
     * without going through the queue (zero latency for uncongested systems).
     *
     * <p>Slow path: place in priority queue, block caller until result or timeout.
     *
     * @param run         the workflow run entity
     * @param triggerId   the trigger identifier
     * @param triggerType the trigger type
     * @param payload     the trigger payload
     * @param userPlan    the user's subscription plan (determines priority)
     * @return the trigger execution result (never null)
     */
    TriggerExecutionResult enqueueAndWait(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan);

    default TriggerExecutionResult enqueueAndWait(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan,
            String requestId) {
        return enqueueAndWait(run, triggerId, triggerType, payload, userPlan);
    }

    /**
     * Enqueue an execution and return immediately (fire-and-forget).
     *
     * <p>The work is dispatched to the worker pool - fast path when a
     * semaphore permit is available, otherwise the priority queue
     * (orphan future, no waiter). The returned result reports
     * {@code success=true} with {@code epoch=-1} and an empty
     * {@code readySteps} set; callers that need actual progress must
     * subscribe to SSE or poll the run state.
     *
     * <p>Used by the HTTP trigger endpoints in AUTOMATIC mode so the
     * Tomcat thread is freed instead of blocking for the full epoch
     * cycle. Synchronous callers (MCP agent, schedule cron) keep using
     * {@link #enqueueAndWait}.
     *
     * @return an "accepted" result; never null
     */
    TriggerExecutionResult enqueueAsync(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan);

    default TriggerExecutionResult enqueueAsync(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan,
            String requestId) {
        return enqueueAsync(run, triggerId, triggerType, payload, userPlan);
    }

    /**
     * Returns the current queue depth (for monitoring).
     */
    int getQueueSize();

    /**
     * Returns the number of available worker slots (for monitoring).
     */
    int getAvailableWorkers();

    /**
     * Returns whether the queue can accept new work right now.
     */
    default boolean isReadyForEnqueue() {
        return true;
    }
}
