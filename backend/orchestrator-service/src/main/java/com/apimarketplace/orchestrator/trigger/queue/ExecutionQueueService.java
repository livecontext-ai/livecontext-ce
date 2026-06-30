package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * System-wide execution queue with priority ordering by subscription plan.
 *
 * <p>All trigger executions (manual, webhook, chat, schedule, datasource, workflow)
 * pass through this service. When worker threads are available, execution happens
 * immediately (fast path). When all workers are busy, executions are queued in
 * priority order (ENTERPRISE before FREE) with a configurable timeout.
 *
 * <p>The per-trigger epoch concurrency limiter ({@code EpochConcurrencyLimiter})
 * is preserved and checked inside {@code executeTriggerInternal()} after dequeue.
 */
@Service
@ConditionalOnProperty(name = "workflow.execution-queue.backend", havingValue = "memory", matchIfMissing = true)
public class ExecutionQueueService implements ExecutionQueue, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(ExecutionQueueService.class);

    private final ReusableTriggerService triggerService;
    private final ExecutionQueueMetrics metrics;
    private final PriorityBlockingQueue<QueuedExecution> queue;
    private final Semaphore workerSemaphore;
    private final ExecutorService workerPool;
    private final Thread dispatcherThread;

    /**
     * Count of executions currently running on a worker (fast-path + slow-path).
     * Used to back the {@code workflow_queue_workers_busy} gauge with a value that is
     * independent of the dispatcher's held permit.
     */
    private final AtomicInteger activeExecutions = new AtomicInteger(0);

    private final int timeoutSeconds;
    private final int workerThreads;

    public ExecutionQueueService(
            @Lazy ReusableTriggerService triggerService,
            ExecutionQueueMetrics metrics,
            @Value("${workflow.execution-queue.worker-threads:20}") int workerThreads,
            @Value("${workflow.execution-queue.timeout-seconds:300}") int timeoutSeconds) {
        this.triggerService = triggerService;
        this.metrics = metrics;
        this.workerThreads = workerThreads;
        this.timeoutSeconds = timeoutSeconds;
        this.queue = new PriorityBlockingQueue<>();
        this.workerSemaphore = new Semaphore(workerThreads);
        this.workerPool = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r, "exec-queue-worker");
            t.setDaemon(true);
            return t;
        });

        metrics.bindQueueGauges(this::getQueueSize, this::getAvailableWorkers, activeExecutions::get);

        // Single dispatcher daemon thread: polls queue, acquires semaphore, dispatches
        this.dispatcherThread = new Thread(this::dispatchLoop, "exec-queue-dispatcher");
        this.dispatcherThread.setDaemon(true);
        this.dispatcherThread.start();

        logger.info("[ExecutionQueue] Started with {} worker threads, {}s timeout",
            workerThreads, timeoutSeconds);
    }

    /**
     * Enqueue an execution and wait for the result.
     *
     * Fast path: if a worker semaphore permit is available immediately, execute
     * without going through the queue (zero latency for uncongested systems).
     *
     * Slow path: place in priority queue, block caller until result or timeout.
     *
     * @return the trigger execution result (never null)
     */
    @Override
    public TriggerExecutionResult enqueueAndWait(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan) {

        int priority = PlanPriorityMapper.getPriority(userPlan);
        String runId = run.getRunIdPublic();
        String tenantId = run.getTenantId();

        // Heap-pressure backpressure was MOVED to ScheduleExecutorService.checkAndExecuteSchedules
        // (audit 2026-05-06 P0 #1): the previous check here ran AFTER ScheduleExecutorService
        // .executeWorkflowSchedule had already called advanceSchedule(scheduleId), so a deferred
        // schedule had its nextExecutionAt advanced to the next cron slot - losing the fire for
        // up to a full cron interval (1 h for "0 * * * *"). The check now runs at the top of
        // the cron tick, before any per-schedule dispatch, so {@code findDueSchedules} at the
        // next minute-tick re-picks the still-due schedules cleanly.

        // Fast path: worker available → execute immediately
        if (workerSemaphore.tryAcquire()) {
            metrics.recordEnqueued(userPlan, tenantId, ExecutionQueueMetrics.PATH_FAST);
            logger.info("[ExecutionQueue] Fast path: immediate execution for runId={}, plan={}, priority={}",
                runId, userPlan, priority);
            activeExecutions.incrementAndGet();
            TriggerExecutionResult result = null;
            try {
                result = triggerService.executeTriggerInternal(run, triggerId, triggerType, payload, false);
                return result;
            } catch (RuntimeException e) {
                // Surface the queue-layer exception in logs before it propagates.
                // The finally block still records OUTCOME_FAILURE (result stays null),
                // so the completed counter matches the enqueued counter even on throw.
                logger.error("[ExecutionQueue] Fast-path execution threw for runId={}, plan={}: {}",
                    runId, userPlan, e.getMessage(), e);
                throw e;
            } finally {
                // Record FIRST, then release the permit + decrement busy count. If the later
                // operations ever threw (they shouldn't, but the JDK doesn't document release()
                // as never-throw), we'd still preserve rate(enqueued) == rate(completed).
                metrics.recordCompleted(userPlan, tenantId, outcomeOf(result));
                activeExecutions.decrementAndGet();
                workerSemaphore.release();
            }
        }

        // Slow path: queue and wait
        metrics.recordEnqueued(userPlan, tenantId, ExecutionQueueMetrics.PATH_SLOW);
        QueuedExecution item = new QueuedExecution(run, triggerId, triggerType, payload, priority, userPlan);
        queue.offer(item);
        logger.info("[ExecutionQueue] Queued execution: runId={}, plan={}, priority={}, queueSize={}",
            runId, userPlan, priority, queue.size());

        try {
            TriggerExecutionResult result = item.getFuture().get(timeoutSeconds, TimeUnit.SECONDS);
            metrics.recordCompleted(userPlan, tenantId, outcomeOf(result));
            return result;
        } catch (TimeoutException e) {
            // Timeout: cancel and remove from queue
            item.cancel();
            queue.remove(item);
            String normalizedPlan = userPlan != null ? userPlan.toUpperCase() : "FREE";
            String message = "Execution queue timeout: your workflow could not start within "
                + (timeoutSeconds / 60) + " minutes. Current plan: " + normalizedPlan
                + ". Upgrade your plan for higher execution priority.";
            logger.warn("[ExecutionQueue] Timeout for runId={}, plan={}: {}", runId, normalizedPlan, message);
            metrics.recordCompleted(userPlan, tenantId, ExecutionQueueMetrics.OUTCOME_TIMEOUT);
            return TriggerExecutionResult.failure(runId, triggerId, triggerType, message);
        } catch (CancellationException e) {
            logger.info("[ExecutionQueue] Execution cancelled for runId={}", runId);
            metrics.recordCompleted(userPlan, tenantId, ExecutionQueueMetrics.OUTCOME_CANCELLED);
            return TriggerExecutionResult.failure(runId, triggerId, triggerType, "Execution was cancelled");
        } catch (ExecutionException e) {
            logger.error("[ExecutionQueue] Execution failed for runId={}: {}", runId, e.getCause().getMessage(), e);
            metrics.recordCompleted(userPlan, tenantId, ExecutionQueueMetrics.OUTCOME_FAILURE);
            return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                "Execution failed: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            item.cancel();
            queue.remove(item);
            metrics.recordCompleted(userPlan, tenantId, ExecutionQueueMetrics.OUTCOME_CANCELLED);
            return TriggerExecutionResult.failure(runId, triggerId, triggerType, "Execution interrupted");
        }
    }

    private static String outcomeOf(TriggerExecutionResult result) {
        if (result == null) return ExecutionQueueMetrics.OUTCOME_FAILURE;
        return result.success() ? ExecutionQueueMetrics.OUTCOME_SUCCESS : ExecutionQueueMetrics.OUTCOME_FAILURE;
    }

    /**
     * Fire-and-forget variant of {@link #enqueueAndWait} - dispatches the
     * trigger to the worker pool and returns immediately with an
     * {@code accepted} result. Used by HTTP controllers in AUTOMATIC mode
     * so the Tomcat thread is freed instead of blocking for the full
     * epoch cycle (~10 s on Gmail-sized workflows).
     *
     * <p>Fast path: tryAcquire permit → submit to {@link #workerPool}
     * directly. The submitted task records enqueue/complete metrics and
     * releases the permit on completion or exception, mirroring the
     * fast-path semantics of {@code enqueueAndWait} so monitoring stays
     * intact (rate(enqueued) == rate(completed)).
     *
     * <p>Slow path: queue with no waiter. The dispatcher loop already
     * picks queued items, acquires a permit, and submits them; the only
     * difference here is that the future is orphaned (no caller blocks
     * on {@code item.getFuture().get(...)}). Worker-side errors are
     * still logged and recorded as OUTCOME_FAILURE in
     * {@link #dispatchLoop()}.
     *
     * <p>Note: {@link #destroy()} drains the queue and completes
     * outstanding futures with FAILURE on shutdown; for orphan futures
     * this drops the work silently - acceptable for graceful restart
     * (the user can re-fire), unacceptable for crash recovery (handled
     * elsewhere by signal recovery + reusable-trigger storms).
     */
    @Override
    public TriggerExecutionResult enqueueAsync(
            WorkflowRunEntity run,
            String triggerId,
            TriggerType triggerType,
            Map<String, Object> payload,
            String userPlan) {

        int priority = PlanPriorityMapper.getPriority(userPlan);
        String runId = run.getRunIdPublic();
        String tenantId = run.getTenantId();

        // Fast path: worker available → submit to pool, return ack immediately
        if (workerSemaphore.tryAcquire()) {
            metrics.recordEnqueued(userPlan, tenantId, ExecutionQueueMetrics.PATH_FAST);
            logger.info("[ExecutionQueue] Fast path async: dispatched runId={}, plan={}, priority={}",
                runId, userPlan, priority);
            activeExecutions.incrementAndGet();
            // Capture orgId outside the lambda so the worker thread can re-bind the
            // ThreadLocal scope via TenantResolver.runWithOrgScope - otherwise
            // OrgScopedEntityListener.ensureOrgId sees null and V261 NOT NULL
            // constraints on storage.storage / orchestrator.* INSERTs blow up.
            final String orgIdForWorker = run.getOrganizationId();
            try {
                workerPool.submit(() -> {
                    com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgIdForWorker, () -> {
                        TriggerExecutionResult[] resultHolder = new TriggerExecutionResult[1];
                        try {
                            resultHolder[0] = triggerService.executeTriggerInternal(
                                run, triggerId, triggerType, payload, false);
                        } catch (RuntimeException e) {
                            logger.error("[ExecutionQueue] Async fast-path execution threw for runId={}, plan={}: {}",
                                runId, userPlan, e.getMessage(), e);
                        } finally {
                            metrics.recordCompleted(userPlan, tenantId, outcomeOf(resultHolder[0]));
                            activeExecutions.decrementAndGet();
                            workerSemaphore.release();
                        }
                    });
                });
            } catch (RejectedExecutionException rex) {
                // Window: workerPool.shutdown() called while a request is mid-flight.
                // Roll back bookkeeping so the permit + busy-gauge + counter parity
                // (rate(enqueued) == rate(completed)) survive a graceful shutdown
                // without permanently shrinking pool capacity on the next boot.
                logger.error("[ExecutionQueue] workerPool rejected async submission for runId={} (likely shutdown): {}",
                    runId, rex.getMessage());
                metrics.recordCompleted(userPlan, tenantId, ExecutionQueueMetrics.OUTCOME_FAILURE);
                activeExecutions.decrementAndGet();
                workerSemaphore.release();
                return TriggerExecutionResult.failure(runId, triggerId, triggerType,
                    "Server shutting down, please retry");
            }
            return TriggerExecutionResult.accepted(runId, triggerId, triggerType);
        }

        // Slow path: queue, no waiter. Marker fireAndForget=true so the dispatcher
        // records OUTCOME on completion (orphan future has no caller to do it).
        metrics.recordEnqueued(userPlan, tenantId, ExecutionQueueMetrics.PATH_SLOW);
        QueuedExecution item = new QueuedExecution(run, triggerId, triggerType, payload, priority, userPlan, true);
        queue.offer(item);
        logger.info("[ExecutionQueue] Queued async execution: runId={}, plan={}, priority={}, queueSize={}",
            runId, userPlan, priority, queue.size());
        return TriggerExecutionResult.accepted(runId, triggerId, triggerType);
    }

    /**
     * Dispatcher loop: takes items from the priority queue, acquires a worker semaphore
     * permit, then dispatches to the worker pool.
     */
    private void dispatchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Acquire worker slot first, then take from queue.
                // This ensures the highest-priority item at dispatch time is chosen,
                // rather than holding a lower-priority item while waiting for a worker.
                workerSemaphore.acquire();

                QueuedExecution item = queue.take(); // blocks until item available

                // Skip cancelled or expired items
                if (item.isCancelled()) {
                    logger.debug("[ExecutionQueue] Skipping cancelled item");
                    workerSemaphore.release();
                    continue;
                }
                if (item.isExpired(timeoutSeconds)) {
                    logger.debug("[ExecutionQueue] Skipping expired item");
                    workerSemaphore.release();
                    continue;
                }

                final QueuedExecution dispatched = item;
                // Capture orgId outside the lambda so the worker thread can re-bind the
                // ThreadLocal scope via TenantResolver.runWithOrgScope (V261 NOT NULL).
                final String dispatchedOrgId = dispatched.getRun() != null ? dispatched.getRun().getOrganizationId() : null;
                try {
                    workerPool.submit(() -> {
                        com.apimarketplace.common.web.TenantResolver.runWithOrgScope(dispatchedOrgId, () -> {
                            // Short-circuit cancelled items BEFORE recording wait_ms: a cancelled
                            // caller has already recorded the outcome via its own exception branch,
                            // so double-recording (and inflating wait_ms for work that never ran)
                            // would misrepresent queue latency. The caller's wait is still captured
                            // transitively via the timeout/cancelled outcome counter.
                            if (dispatched.isCancelled()) {
                                workerSemaphore.release();
                                return;
                            }
                            long waitMs = Duration.between(dispatched.getEnqueuedAt(), Instant.now()).toMillis();
                            String dispatchedTenant = dispatched.getRun() != null ? dispatched.getRun().getTenantId() : null;
                            metrics.recordWaitMs(dispatched.getUserPlan(), dispatchedTenant, waitMs);
                            activeExecutions.incrementAndGet();
                            TriggerExecutionResult[] resultHolder = new TriggerExecutionResult[1];
                            try {
                                resultHolder[0] = triggerService.executeTriggerInternal(
                                    dispatched.getRun(), dispatched.getTriggerId(), dispatched.getTriggerType(),
                                    dispatched.getPayload(), false);
                                dispatched.complete(resultHolder[0]);
                            } catch (Exception e) {
                                logger.error("[ExecutionQueue] Worker execution error: {}", e.getMessage(), e);
                                dispatched.completeExceptionally(e);
                            } finally {
                                // Fire-and-forget items have no caller to record completion
                                // metrics on their own; record here to keep counters balanced.
                                if (dispatched.isFireAndForget()) {
                                    metrics.recordCompleted(dispatched.getUserPlan(), dispatchedTenant, outcomeOf(resultHolder[0]));
                                }
                                activeExecutions.decrementAndGet();
                                workerSemaphore.release();
                            }
                        });
                    });
                } catch (RejectedExecutionException rex) {
                    // workerPool.shutdown() raced with the dispatcher. Release the permit,
                    // fail the orphan or sync waiter explicitly, and continue the loop -
                    // the next iteration's queue.take() / workerSemaphore.acquire() will
                    // exit cleanly when destroy() interrupts the dispatcher thread.
                    logger.warn("[ExecutionQueue] workerPool rejected dispatch (likely shutdown), failing item: {}",
                        rex.getMessage());
                    String tenant = dispatched.getRun() != null ? dispatched.getRun().getTenantId() : null;
                    String runIdLog = dispatched.getRun() != null ? dispatched.getRun().getRunIdPublic() : null;
                    TriggerExecutionResult failure = TriggerExecutionResult.failure(
                        runIdLog, dispatched.getTriggerId(), dispatched.getTriggerType(),
                        "Server shutting down, please retry");
                    dispatched.complete(failure);
                    if (dispatched.isFireAndForget()) {
                        metrics.recordCompleted(dispatched.getUserPlan(), tenant, ExecutionQueueMetrics.OUTCOME_FAILURE);
                    }
                    workerSemaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.info("[ExecutionQueue] Dispatcher thread interrupted, shutting down");
                break;
            }
        }
    }

    /**
     * Returns the current queue depth (for monitoring).
     */
    @Override
    public int getQueueSize() {
        return queue.size();
    }

    /**
     * Returns the number of available worker slots (for monitoring).
     */
    @Override
    public int getAvailableWorkers() {
        return workerSemaphore.availablePermits();
    }

    @Override
    public void destroy() {
        logger.info("[ExecutionQueue] Shutting down execution queue...");

        // Interrupt dispatcher thread
        dispatcherThread.interrupt();

        // Drain queue and complete pending futures with failure. Fire-and-forget items
        // have no waiter to record OUTCOME on the metric counter - record here so the
        // shutdown drain doesn't break rate(enqueued) == rate(completed) parity for
        // dashboards that span the restart.
        QueuedExecution item;
        while ((item = queue.poll()) != null) {
            String tenant = item.getRun() != null ? item.getRun().getTenantId() : null;
            item.complete(TriggerExecutionResult.failure(
                item.getRun() != null ? item.getRun().getRunIdPublic() : null,
                item.getTriggerId(),
                item.getTriggerType(),
                "Server shutting down"));
            if (item.isFireAndForget()) {
                metrics.recordCompleted(item.getUserPlan(), tenant, ExecutionQueueMetrics.OUTCOME_FAILURE);
            }
        }

        // Shutdown worker pool
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("[ExecutionQueue] Execution queue shut down");
    }

    // Package-private for testing
    int getWorkerThreads() {
        return workerThreads;
    }

    int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
