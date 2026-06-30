package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.config.AgentScalingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker service that dequeues agent execution tasks from Redis lists and routes
 * them to the appropriate execution service (agent loop, classify, or guardrail).
 *
 * <p>Activated only when {@code scaling.agent.queue.enabled=true}. Creates three
 * independent thread pools sized according to {@link AgentScalingConfig}:
 * <ul>
 *   <li><b>agent</b> pool - for long-running agent loop executions (1-65 min)</li>
 *   <li><b>classify</b> pool - for fast single-shot classifications (1-5s)</li>
 *   <li><b>guardrail</b> pool - for fast single-shot guardrail validations (1-5s)</li>
 * </ul>
 *
 * <p>Each worker polls from a Redis list (BRPOP with timeout), deserializes the
 * {@link AgentExecutionTask}, delegates to {@link AgentRemoteExecutionService#executeByType},
 * and publishes the result to a Redis key + pub/sub channel for the orchestrator to consume.
 *
 * <h2>Graceful shutdown - ordering (B2)</h2>
 *
 * <p>The drain runs in {@link SmartLifecycle#stop()} at phase {@link #STOP_PHASE}
 * ({@code Integer.MAX_VALUE}), NOT in {@code @PreDestroy}. Spring stops SmartLifecycle
 * beans in descending phase order during context close, so this bean stops FIRST -
 * while {@code LettuceConnectionFactory} (also a SmartLifecycle) is still alive and the
 * drain's Redis writes (result publishing, heartbeats, INTERRUPTED finalization) still
 * succeed. The previous {@code @PreDestroy}-only design ran AFTER all SmartLifecycle
 * stops, i.e. with Lettuce already torn down.
 *
 * <p>Spring 6.1+ additionally shuts the {@code ThreadPoolTaskScheduler} down on
 * {@code ContextClosedEvent} - BEFORE any lifecycle stop - so the
 * {@link ActiveStreamRegistry} {@code @Scheduled} 30s heartbeat tick is already dead
 * during the drain. The drain loop therefore calls
 * {@link ActiveStreamRegistry#refreshHeartbeats()} explicitly on every ~30s slice,
 * keeping the 120s-TTL liveness keys alive so the conversation-service reconciler does
 * not interrupt streams that are still finishing here. A minimal {@code @PreDestroy}
 * remains as a belt-and-braces fallback (no-op when {@code stop()} already drained).
 *
 * <h2>Shutdown time budget (M3)</h2>
 *
 * <p>All three pools begin draining at the same instant: classify/guardrail
 * (single-shot 1-5s tasks, {@value #SHUTDOWN_TIMEOUT_SECONDS}s window) drain IN PARALLEL
 * with the agent pool ({@code scaling.agent.queue.drain-timeout-seconds}, default 600s;
 * loop executions can legitimately run ~1 hour). Worst case for this stop phase is
 * therefore {@code max(600, 30) + one 30s slice ≈ 610s} - not the former sequential
 * {@code 30 + 30 + 600 = 660s}. The web server's own graceful phase
 * ({@code spring.lifecycle.timeout-per-shutdown-phase}, 60s) runs after this bean stops,
 * so total worst case ≈ {@code 60 + 610 = 670s}, inside the k8s
 * {@code terminationGracePeriodSeconds} of 720s (deploy/helm values.yaml).
 *
 * <p>If the agent drain window expires, surviving streams are finalized as INTERRUPTED
 * via {@link ActiveStreamRegistry} (conversation-service persists their partial content)
 * before the pool is force-stopped.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")
public class AgentQueueWorkerService implements SmartLifecycle {

    static final String QUEUE_PREFIX = "agent:queue:";
    static final String RESULT_KEY_PREFIX = "agent:result:";
    static final String RESULT_CHANNEL_PREFIX = "agent:result:channel:";
    static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    /** Interval between drain-progress log lines while waiting on the agent pool. */
    static final long DRAIN_PROGRESS_INTERVAL_SECONDS = 30;

    /**
     * Phase ceiling - mirrors orchestrator's {@code AgentDrainCoordinator}: higher than
     * {@code LettuceConnectionFactory} and {@code RedisMessageListenerContainer}
     * ({@code Integer.MAX_VALUE - 100}) so this bean stops FIRST in the
     * {@code SmartLifecycle.stop()} cycle, with Redis connectivity still alive for the
     * whole drain.
     */
    static final int STOP_PHASE = Integer.MAX_VALUE;

    private final AgentScalingConfig config;
    private final AgentRemoteExecutionService executionService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final ActiveStreamRegistry activeStreamRegistry;

    /**
     * Drain window for the agent pool on shutdown. The pool carries executions of up to
     * ~1 hour; 600s (10 min) is the chosen compromise between deploy speed and letting
     * most in-flight streams finish naturally.
     */
    private final long drainTimeoutSeconds;

    /**
     * Drain window for the fast pools (classify/guardrail, single-shot 1-5s tasks).
     * Defaults to {@value #SHUTDOWN_TIMEOUT_SECONDS}s; injectable for tests that need a
     * short, bounded worst case. The fast pools drain CONCURRENTLY with the agent pool,
     * so this window overlaps (does not add to) the agent drain.
     */
    private final long fastPoolTimeoutSeconds;

    private ExecutorService agentPool;
    private ExecutorService classifyPool;
    private ExecutorService guardrailPool;
    private final List<Future<?>> workerFutures = new ArrayList<>();
    private volatile boolean running = false;

    /** Guards the drain so SmartLifecycle.stop() and the @PreDestroy fallback run it once. */
    private final AtomicBoolean drained = new AtomicBoolean(false);

    @Autowired
    public AgentQueueWorkerService(AgentScalingConfig config,
                                    AgentRemoteExecutionService executionService,
                                    ObjectMapper objectMapper,
                                    StringRedisTemplate redisTemplate,
                                    ActiveStreamRegistry activeStreamRegistry,
                                    @org.springframework.beans.factory.annotation.Value(
                                        "${scaling.agent.queue.drain-timeout-seconds:600}") long drainTimeoutSeconds) {
        this(config, executionService, objectMapper, redisTemplate, activeStreamRegistry,
            drainTimeoutSeconds, SHUTDOWN_TIMEOUT_SECONDS);
    }

    /** Visible for tests: also injects the fast-pool (classify/guardrail) drain window. */
    AgentQueueWorkerService(AgentScalingConfig config,
                            AgentRemoteExecutionService executionService,
                            ObjectMapper objectMapper,
                            StringRedisTemplate redisTemplate,
                            ActiveStreamRegistry activeStreamRegistry,
                            long drainTimeoutSeconds,
                            long fastPoolTimeoutSeconds) {
        this.config = config;
        this.executionService = executionService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.activeStreamRegistry = activeStreamRegistry;
        this.drainTimeoutSeconds = drainTimeoutSeconds;
        this.fastPoolTimeoutSeconds = fastPoolTimeoutSeconds;
    }

    /**
     * Defer worker startup until Spring's {@link ApplicationReadyEvent}: the underlying
     * {@code LettuceConnectionFactory} is a {@code SmartLifecycle} bean whose {@code start()}
     * is invoked AFTER all {@code @PostConstruct} hooks finish. Starting workers in
     * {@code @PostConstruct} (the original design, intro commit 12b7f6b16 on 2026-04-10)
     * raced against that lifecycle and threw 16-22K
     * {@code IllegalStateException: LettuceConnectionFactory is STOPPING / has been STOPPED}
     * in the first ~5 s of every restart. ApplicationReadyEvent fires after all lifecycle
     * starts, so the Redis factory is guaranteed ready before any worker calls BRPOP.
     */
    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        start();
    }

    // ─── SmartLifecycle implementation ──────────────────────────────────────────────────
    // Stop ordering only: startup stays on ApplicationReadyEvent (see onApplicationReady
    // javadoc - workers must not race LettuceConnectionFactory's own lifecycle start), so
    // isAutoStartup() is false and Spring never calls start() during the start cycle.
    // isRunning() reflects the `running` flag flipped by onApplicationReady, which is what
    // makes DefaultLifecycleProcessor invoke stop() on context close.

    @Override
    public int getPhase() {
        return STOP_PHASE;
    }

    @Override
    public boolean isAutoStartup() {
        // Workers start on ApplicationReadyEvent (after ALL lifecycle starts), not here.
        return false;
    }

    @Override
    public void stop() {
        doShutdown();
    }

    @Override
    public void stop(Runnable callback) {
        try {
            doShutdown();
        } finally {
            callback.run();
        }
    }

    /** Returns true while the worker loops are active (drives SmartLifecycle.stop()). */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** Idempotent: a duplicate start (re-fired event, SmartLifecycle call) is a no-op. */
    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;

        int agentSize = config.getWorker().getAgentPoolSize();
        int classifySize = config.getWorker().getClassifyPoolSize();
        int guardrailSize = config.getWorker().getGuardrailPoolSize();

        agentPool = Executors.newFixedThreadPool(agentSize,
            new WorkerThreadFactory("agent-worker"));
        classifyPool = Executors.newFixedThreadPool(classifySize,
            new WorkerThreadFactory("classify-worker"));
        guardrailPool = Executors.newFixedThreadPool(guardrailSize,
            new WorkerThreadFactory("guardrail-worker"));

        // Start worker threads for each pool
        for (int i = 0; i < agentSize; i++) {
            String consumerId = "agent-worker-" + i;
            workerFutures.add(agentPool.submit(() -> workerLoop(consumerId, AgentExecutionTask.TYPE_AGENT)));
        }
        for (int i = 0; i < classifySize; i++) {
            String consumerId = "classify-worker-" + i;
            workerFutures.add(classifyPool.submit(() -> workerLoop(consumerId, AgentExecutionTask.TYPE_CLASSIFY)));
        }
        for (int i = 0; i < guardrailSize; i++) {
            String consumerId = "guardrail-worker-" + i;
            workerFutures.add(guardrailPool.submit(() -> workerLoop(consumerId, AgentExecutionTask.TYPE_GUARDRAIL)));
        }

        log.info("Agent queue workers started: agent={}, classify={}, guardrail={}, pollInterval={}ms",
            agentSize, classifySize, guardrailSize, config.getConsumer().getPollIntervalMs());
    }

    /**
     * Belt-and-braces {@code @PreDestroy} fallback. Spring runs {@code @PreDestroy} after
     * the SmartLifecycle stop phase, so when {@link #stop()} already drained, the
     * {@code drained} flag short-circuits this call. Kept for non-Spring test contexts
     * (and direct callers) where stop() is never invoked - note that on this late path
     * Lettuce/scheduler may already be down, so the drain's Redis writes are best-effort.
     */
    @PreDestroy
    void shutdown() {
        if (!drained.get()) {
            doShutdown();
        }
    }

    /**
     * Runs the drain exactly once (idempotent via {@code drained}). All three pools begin
     * draining at the same instant so the fast pools' window overlaps the agent drain -
     * worst case is {@code max(drainTimeoutSeconds, fastPoolTimeoutSeconds)} + one
     * progress slice, never their sum (see class javadoc, "Shutdown time budget").
     */
    void doShutdown() {
        if (!drained.compareAndSet(false, true)) {
            return; // already drained via stop() or a previous shutdown()
        }
        log.info("Shutting down agent queue workers...");
        // Stop the BRPOP intake first: idle workers exit on their next poll timeout,
        // busy workers finish (or drain) their current task without picking up new ones.
        running = false;

        long shutdownStartNanos = System.nanoTime();

        // Initiate shutdown on the fast pools NOW so their single-shot 1-5s tasks drain
        // CONCURRENTLY with the (much longer) agent drain below.
        if (classifyPool != null) classifyPool.shutdown();
        if (guardrailPool != null) guardrailPool.shutdown();

        // Agent pool: long-running loop executions - drain with progress logging,
        // then finalize survivors as INTERRUPTED before forcing the pool down.
        drainAgentPool();

        // Observe the fast pools against the SAME start instant: time already spent on
        // the agent drain counts toward their window, so this never adds wait on top.
        awaitFastPool(classifyPool, "classify", shutdownStartNanos);
        awaitFastPool(guardrailPool, "guardrail", shutdownStartNanos);

        log.info("Agent queue workers shut down");
    }

    /**
     * Drain the agent pool for up to {@link #drainTimeoutSeconds}, logging progress
     * (remaining active streams) every ~{@value #DRAIN_PROGRESS_INTERVAL_SECONDS}s.
     * On expiry, {@link ActiveStreamRegistry#interruptAll()} finalizes the surviving
     * streams as INTERRUPTED - conversation-service persists their partial content -
     * and only THEN the pool is force-stopped with {@code shutdownNow()}.
     */
    private void drainAgentPool() {
        if (agentPool == null) return;
        agentPool.shutdown();
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(drainTimeoutSeconds);
        try {
            while (true) {
                // Explicitly refresh the stream liveness heartbeats on every slice:
                // Spring 6.1+ shuts the ThreadPoolTaskScheduler down on ContextClosedEvent
                // (before any lifecycle stop), so the registry's @Scheduled 30s tick is
                // already dead during this drain. Without this, the 120s heartbeat TTL
                // expires mid-drain and the conversation-service reconciler interrupts
                // streams that are still finishing here. Also a safety net should the
                // SmartLifecycle phase ordering ever change.
                activeStreamRegistry.refreshHeartbeats();

                long remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime());
                if (remainingSeconds <= 0) {
                    break;
                }
                if (agentPool.awaitTermination(
                        Math.min(DRAIN_PROGRESS_INTERVAL_SECONDS, remainingSeconds), TimeUnit.SECONDS)) {
                    return; // drained cleanly - nothing left to interrupt
                }
                log.info("Draining agent pool: {} active stream(s) remaining, ~{}s left before forced interruption",
                    activeStreamRegistry.size(),
                    Math.max(0, TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime())));
            }
            log.warn("agent pool did not drain within {}s - finalizing {} surviving stream(s) as INTERRUPTED",
                drainTimeoutSeconds, activeStreamRegistry.size());
            activeStreamRegistry.interruptAll();
            agentPool.shutdownNow();
        } catch (InterruptedException e) {
            // Shutdown thread itself interrupted: still salvage the survivors' partial content.
            activeStreamRegistry.interruptAll();
            agentPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Core worker loop: polls from the Redis queue for the given type,
     * executes the task, and publishes the result.
     */
    void workerLoop(String consumerId, String queueType) {
        String queueKey = QUEUE_PREFIX + queueType;
        long pollTimeoutSeconds = Math.max(1, config.getConsumer().getPollIntervalMs() / 1000);
        int consecutiveErrors = 0;

        log.info("Worker {} started for queue {}", consumerId, queueKey);

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // BRPOP blocks until a message is available or timeout expires
                // Returns null if timeout (no message), allowing loop to check `running` flag
                String taskJson = dequeue(queueKey, pollTimeoutSeconds);
                consecutiveErrors = 0;
                if (taskJson == null) {
                    continue;
                }

                AgentExecutionTask task = objectMapper.readValue(taskJson, AgentExecutionTask.class);
                log.debug("Worker {} dequeued task: correlationId={}, type={}",
                    consumerId, task.correlationId(), task.agentType());

                processTask(task);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Worker {} interrupted, stopping", consumerId);
                break;
            } catch (Exception e) {
                consecutiveErrors++;
                long backoffMs = Math.min(1000L * consecutiveErrors, 30_000L);
                log.error("Worker {} error (attempt {}, backoff {}ms): {}", consumerId, consecutiveErrors, backoffMs, e.getMessage());
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Worker {} stopped", consumerId);
    }

    /**
     * Dequeues a task from the Redis list using BRPOP.
     * Returns null if no task is available within the timeout.
     */
    String dequeue(String queueKey, long timeoutSeconds) throws InterruptedException {
        // BRPOP returns a list of [key, value] or null on timeout
        try {
            var result = redisTemplate.opsForList().rightPop(
                queueKey, timeoutSeconds, TimeUnit.SECONDS);
            return result;
        } catch (Exception e) {
            if (e.getCause() instanceof InterruptedException) {
                throw (InterruptedException) e.getCause();
            }
            throw e;
        }
    }

    /** Key prefix for the workflow cancel signal in Redis - must match WorkflowRedisPublisher. */
    private static final String CANCEL_KEY_PREFIX = "workflow:cancel:";

    /**
     * Processes a single task: executes by type and publishes the result.
     */
    void processTask(AgentExecutionTask task) {
        String correlationId = task.correlationId();
        try {
            // Pre-execution cancellation check: if the workflow was cancelled/stopped
            // between enqueue and dequeue, skip execution entirely and publish an error
            // result so the orchestrator can clean up the pending entry.
            if (isWorkflowCancelled(task)) {
                log.info("Task skipped (workflow cancelled): correlationId={}, type={}, runId={}",
                    correlationId, task.agentType(), task.metadata() != null ? task.metadata().get("runId") : "?");
                publishError(correlationId, "Workflow cancelled before agent execution started");
                return;
            }

            // Phase 3 MIGRATION_ORG_ID_NOT_NULL.md (2026-05-19) - bind org context from
            // the serialized DTO's credentials to the worker thread via TenantResolver.
            // Queued worker threads have no RequestContextHolder, so downstream org-scope
            // checks must see both the id and role captured at enqueue time.
            String orgId = extractOrgIdFromPayload(task.requestPayload());
            String orgRole = extractOrgRoleFromPayload(task.requestPayload());
            String userRoles = extractUserRoles(task.metadata());
            String[] resultHolder = new String[1];
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(orgId, orgRole, () -> {
                try {
                    resultHolder[0] = userRoles != null
                        ? executionService.executeByType(task.agentType(), task.requestPayload(), userRoles)
                        : executionService.executeByType(task.agentType(), task.requestPayload());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            String resultJson = resultHolder[0];

            // Publish result: Redis key (durable) + pub/sub channel (fast notification)
            publishResult(correlationId, resultJson);

            log.debug("Task completed: correlationId={}, type={}", correlationId, task.agentType());

        } catch (Exception e) {
            log.error("Task execution failed: correlationId={}, type={}, error={}",
                correlationId, task.agentType(), e.getMessage(), e);

            // Publish error result so the orchestrator does not wait forever
            publishError(correlationId, e.getMessage());
        }
    }

    /**
     * Phase 3 MIGRATION_ORG_ID_NOT_NULL.md helper - extracts the workspace
     * orgId from the serialized DTO payload. New producers carry
     * {@code credentials.__orgId__}; {@code organizationId} is accepted as a
     * top-level fallback for DTOs that ignore unknown nested fields.
     * Returns null when the queue task was published without org context
     * (legacy producers, personal-scope dispatch).
     *
     * <p>Parses the JSON payload via ObjectMapper to access nested {@code
     * credentials.__orgId__}. Cost is one JSON parse per queued task - acceptable
     * since the worker is already deserializing the full payload right after
     * to run the agent loop. (We could share the parse with the loop but that's
     * a bigger refactor.)
     */
    String extractOrgIdFromPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(payloadJson);
            com.fasterxml.jackson.databind.JsonNode orgIdNode = root.path("credentials").path("__orgId__");
            if (orgIdNode.isTextual()) {
                String s = orgIdNode.asText();
                if (!s.isBlank()) return s;
            }
            com.fasterxml.jackson.databind.JsonNode topLevelOrgId = root.path("organizationId");
            if (topLevelOrgId.isTextual()) {
                String s = topLevelOrgId.asText();
                if (!s.isBlank()) return s;
            }
        } catch (Exception e) {
            log.debug("[Phase3] Failed to extract __orgId__ from payload: {}", e.getMessage());
        }
        return null;
    }

    String extractOrgRoleFromPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(payloadJson);
            com.fasterxml.jackson.databind.JsonNode orgRoleNode = root.path("credentials").path("__orgRole__");
            if (orgRoleNode.isTextual()) {
                String s = orgRoleNode.asText();
                if (!s.isBlank()) return s;
            }
            com.fasterxml.jackson.databind.JsonNode topLevelOrgRole = root.path("organizationRole");
            if (topLevelOrgRole.isTextual()) {
                String s = topLevelOrgRole.asText();
                if (!s.isBlank()) return s;
            }
        } catch (Exception e) {
            log.debug("[Phase3] Failed to extract __orgRole__ from payload: {}", e.getMessage());
        }
        return null;
    }

    String extractUserRoles(Map<String, String> metadata) {
        if (metadata == null) return null;
        String userRoles = metadata.get("userRoles");
        return userRoles != null && !userRoles.isBlank() ? userRoles.trim() : null;
    }

    /**
     * Publishes a successful result to Redis (key + pub/sub).
     */
    void publishResult(String correlationId, String resultJson) {
        String resultKey = RESULT_KEY_PREFIX + correlationId;
        String channelKey = RESULT_CHANNEL_PREFIX + correlationId;

        // Store result with 1-hour TTL (orchestrator will read and delete)
        redisTemplate.opsForValue().set(resultKey, resultJson, 1, TimeUnit.HOURS);

        // Notify via pub/sub for immediate consumption
        redisTemplate.convertAndSend(channelKey, resultJson);
    }

    /**
     * Publishes an error result to Redis (key + pub/sub).
     */
    void publishError(String correlationId, String errorMessage) {
        try {
            String errorJson = objectMapper.writeValueAsString(
                    Map.of("success", false, "error", errorMessage != null ? errorMessage : "unknown error"));
            publishResult(correlationId, errorJson);
        } catch (Exception e) {
            // Last resort: publish minimal valid JSON
            publishResult(correlationId, "{\"success\":false,\"error\":\"serialization failed\"}");
        }
    }

    /**
     * Check if the workflow run was cancelled/stopped before we start executing.
     * The cancel signal is a Redis key set by the orchestrator's WorkflowRedisPublisher.
     */
    private boolean isWorkflowCancelled(AgentExecutionTask task) {
        if (task.metadata() == null) return false;
        String runId = task.metadata().get("runId");
        if (runId == null || runId.isEmpty()) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(CANCEL_KEY_PREFIX + runId));
        } catch (Exception e) {
            log.debug("Failed to check cancel signal for runId={}: {}", runId, e.getMessage());
            return false; // fail-open
        }
    }

    /**
     * Wait for a fast pool (classify/guardrail) to finish, against the SHARED shutdown
     * start instant: {@code shutdown()} was already initiated on the pool at the top of
     * {@link #doShutdown()}, so the pool has been draining concurrently with the agent
     * drain. Only the unconsumed remainder of {@link #fastPoolTimeoutSeconds} is awaited
     * here (zero if the agent drain already outlasted it), then survivors are forced down.
     */
    private void awaitFastPool(ExecutorService pool, String name, long shutdownStartNanos) {
        if (pool == null) return;
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - shutdownStartNanos);
        long remainingSeconds = Math.max(0, fastPoolTimeoutSeconds - elapsedSeconds);
        try {
            if (!pool.awaitTermination(remainingSeconds, TimeUnit.SECONDS)) {
                log.warn("{} pool did not terminate within {}s, forcing shutdown",
                    name, fastPoolTimeoutSeconds);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Named thread factory for worker pool threads.
     */
    private static class WorkerThreadFactory implements ThreadFactory {
        private final String prefix;
        private final java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);

        WorkerThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, prefix + "-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    }
}
