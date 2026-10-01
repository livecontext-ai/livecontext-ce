package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.agent.client.queue.AgentExecutionRequestMessage;
import com.apimarketplace.agent.client.queue.AgentQueueProducer;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.services.resume.RunCancellationGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Carries a queued agent node's {@code nodePolicy} past the moment the node yields.
 *
 * <p>An agent, classify or guardrail node on the worker queue returns ASYNC_RUNNING at once, so
 * {@code NodePolicyRunner} never sees how the attempt ends: its retry loop and its
 * {@code timeoutMs} bound both stop at the dispatch. This component applies them to the attempt
 * itself, without holding a thread while the agent runs or while a retry waits:</p>
 * <ul>
 *   <li><b>retryCount</b>: the request is kept (only for a node that may retry) and, when an
 *       attempt fails and the policy asks for another, sent again after the wait
 *       {@code NodePolicyRunner.afterFailedAttempt} computed, with a new correlation id. The next
 *       attempt is registered at once, so its epoch stays open and a restart still finds it.</li>
 *   <li><b>timeoutMs</b>: when no answer has arrived {@code timeoutMs} after the request left, a
 *       failure flagged {@code policy_timeout} is published on the result channel exactly as a
 *       worker answer would be, so the ordinary delivery, and its atomic consume, settles which of
 *       the two ends the attempt. The agent may keep running: its late answer is billed, not
 *       delivered (see {@code AgentAsyncCompletionService}), as a timed-out synchronous node body
 *       still records its tokens.</li>
 * </ul>
 *
 * <p>Precision comes from a local timer. Restart safety comes from the recovery scan, which
 * calls {@link #recover(PendingAgent)} for every entry it holds; Redis claims keep the timer and
 * the scan from acting twice.</p>
 */
@Component
@ConditionalOnProperty(name = "scaling.agent.queue.enabled", havingValue = "true")
public class AgentAttemptScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AgentAttemptScheduler.class);

    /** The request of an attempt, kept for a node that may retry: {@code {message, sendAtMs}}. */
    static final String REQUEST_KEY_PREFIX = "agent:attempt:request:";
    /** Claim of the one send of a scheduled attempt (the timer and the recovery scan both try). */
    static final String SENT_KEY_PREFIX = "agent:attempt:sent:";
    /**
     * Held only between the claim and the moment the attempt is marked sent (its kept request's
     * sendAt set to 0, which is what stops a second send afterwards). Short, so a crash in that
     * window leaves the attempt to the recovery scan a minute later instead of for hours.
     */
    static final Duration SEND_CLAIM_TTL = Duration.ofSeconds(60);
    /** Claim of the one judgement of a failed attempt: which delivery of it sends the next one. */
    static final String JUDGED_KEY_PREFIX = "agent:attempt:judged:";
    /**
     * Covers two deliveries of the same failure that race (a startup replay of an in-flight entry
     * while the live delivery still runs), a matter of milliseconds. Shorter than an instance
     * restart (17 to 27 s measured in prod), so the replay of a delivery that died holding the
     * claim finds it expired and judges the failure again instead of stranding the attempt.
     */
    static final Duration JUDGE_CLAIM_TTL = Duration.ofSeconds(10);
    /** Must match {@code AgentQueueWorkerService.RESULT_KEY_PREFIX} in agent-service. */
    static final String RESULT_KEY_PREFIX = "agent:result:";
    /** Must match {@code AgentQueueWorkerService.RESULT_CHANNEL_PREFIX} in agent-service. */
    static final String RESULT_CHANNEL_PREFIX = "agent:result:channel:";
    /** The worker keeps its result key an hour; a published timeout does the same. */
    static final Duration RESULT_TTL = Duration.ofHours(1);
    /** How late the local timer may be before the recovery scan acts in its place. */
    static final Duration RECOVERY_GRACE = Duration.ofSeconds(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final PendingAgentRegistry registry;
    private final AgentQueueProducer queueProducer;
    private final ScheduledExecutorService timer;

    @Autowired(required = false)
    private RunCancellationGuard runCancellationGuard;

    /**
     * The producer is injected lazily: it is only used to send, and a producer that itself depends
     * on the async delivery (the e2e fixture's simulated worker does) would otherwise close a
     * cycle through {@code AgentAsyncCompletionService.attemptScheduler}.
     */
    @Autowired
    public AgentAttemptScheduler(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                 PendingAgentRegistry registry,
                                 @org.springframework.context.annotation.Lazy AgentQueueProducer queueProducer) {
        this(redisTemplate, objectMapper, registry, queueProducer, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-attempt-timer");
            t.setDaemon(true);
            return t;
        }));
    }

    /** Test constructor: inject the timer. */
    AgentAttemptScheduler(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                          PendingAgentRegistry registry, AgentQueueProducer queueProducer,
                          ScheduledExecutorService timer) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.queueProducer = queueProducer;
        this.timer = timer;
    }

    void setRunCancellationGuard(RunCancellationGuard runCancellationGuard) {
        this.runCancellationGuard = runCancellationGuard;
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Dispatch
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A request just left for the worker: keep it when the node may retry, and bound the wait for
     * its answer when the node has a timeout. A node without either is untouched.
     */
    public void onDispatched(WorkflowPlan plan, String nodeId, AgentExecutionRequestMessage message) {
        NodePolicy policy = plan != null && nodeId != null ? plan.getNodePolicy(nodeId) : null;
        if (policy == null || message == null || message.correlationId() == null) {
            return;
        }
        try {
            if (policy.retryCount() > 0) {
                saveRequest(withCallerRoles(message), 0L);
            }
        } catch (Exception e) {
            // The first attempt is already on its way; without the kept request it simply is not retried.
            logger.warn("[AgentAttempt] Could not keep the request for a retry: correlationId={}, nodeId={}, error={}",
                message.correlationId(), nodeId, e.getMessage());
        }
        if (policy.timeoutMs() > 0) {
            watchTimeout(message.correlationId(), policy.timeoutMs());
        }
    }

    /**
     * The correlation id of the attempt after {@code failed}: derived from it, never random, so a
     * delivery of the same failure replayed after a crash finds the attempt it already scheduled.
     */
    public static String nextCorrelationId(PendingAgent failed) {
        return java.util.UUID.nameUUIDFromBytes((failed.correlationId() + "#attempt-" + (failed.attempt() + 1))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    /**
     * Whether an attempt with this correlation id is already scheduled or on its way: registered
     * here or pending in Redis. A kept request alone does not count: it is written before the
     * attempt is registered, and an attempt that was never registered is never sent.
     */
    public boolean isScheduled(String correlationId) {
        if (correlationId == null) return false;
        if (registry.peek(correlationId).isPresent()) return true;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisPendingAgentStore.KEY_PREFIX + correlationId));
        } catch (Exception e) {
            logger.warn("[AgentAttempt] Could not check whether an attempt is scheduled: correlationId={}, error={}",
                correlationId, e.getMessage());
            return false;
        }
    }

    /**
     * Claims the judgement of a failed attempt for this delivery: only the delivery that wins sends
     * the next attempt, any other delivery of the same failure records nothing. Fails open, like
     * the other claims of the async path: without Redis nothing else here works either.
     */
    public boolean claimRetry(String failedCorrelationId) {
        if (failedCorrelationId == null) return true;
        try {
            return !Boolean.FALSE.equals(redisTemplate.opsForValue()
                .setIfAbsent(JUDGED_KEY_PREFIX + failedCorrelationId, "1", JUDGE_CLAIM_TTL));
        } catch (Exception e) {
            logger.warn("[AgentAttempt] Could not claim the retry of a failed attempt, proceeding: correlationId={}, error={}",
                failedCorrelationId, e.getMessage());
            return true;
        }
    }

    /**
     * Gives the judgement of a failed attempt back when sending its next attempt failed on the way
     * (Redis unavailable), so the redelivery of that same failure can judge it again. Best-effort:
     * the claim expires anyway.
     */
    public void releaseRetry(String failedCorrelationId) {
        if (failedCorrelationId == null) return;
        try {
            redisTemplate.delete(JUDGED_KEY_PREFIX + failedCorrelationId);
        } catch (Exception e) {
            logger.debug("[AgentAttempt] Could not release the retry claim (it expires): correlationId={}, error={}",
                failedCorrelationId, e.getMessage());
        }
    }

    /** Whether the request of this attempt is kept, so another attempt can be sent. */
    public boolean canResend(String correlationId) {
        try {
            return correlationId != null && Boolean.TRUE.equals(redisTemplate.hasKey(REQUEST_KEY_PREFIX + correlationId));
        } catch (Exception e) {
            logger.warn("[AgentAttempt] Could not read the kept request: correlationId={}, error={}",
                correlationId, e.getMessage());
            return false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Retry
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Registers {@code next} and sends its request after {@code waitMs}. Returns false, and changes
     * nothing, when the failed attempt's request is no longer kept: the caller then ends the
     * execution with that failure.
     */
    public boolean scheduleResend(PendingAgent failed, PendingAgent next, long waitMs) {
        StoredRequest stored = takeRequest(failed.correlationId());
        if (stored == null) {
            return false;
        }
        AgentExecutionRequestMessage message = forAttempt(stored.message(), next);
        long delayMs = Math.max(0L, waitMs);
        saveRequest(message, System.currentTimeMillis() + delayMs);
        registry.register(next);
        schedule(() -> sendIfDue(next.correlationId()), delayMs, next.correlationId());
        logger.info("[AgentAttempt] Attempt {} of nodeId={} scheduled in {}ms: runId={}, correlationId={}, failedCorrelationId={}",
            next.attempt(), next.nodeId(), delayMs, next.runId(), next.correlationId(), failed.correlationId());
        return true;
    }

    /**
     * Sends a scheduled attempt, once: the local timer and the recovery scan may both try, only
     * the first claim sends. An attempt whose run was stopped meanwhile is dropped.
     */
    void sendIfDue(String correlationId) {
        StoredRequest stored;
        Optional<PendingAgent> pending;
        try {
            stored = readRequest(correlationId);
            if (stored == null || stored.sendAtMs() <= 0) {
                return; // not scheduled, or already sent
            }
            pending = registry.peek(correlationId);
            if (pending.isEmpty()) {
                logger.info("[AgentAttempt] Scheduled attempt no longer pending (run stopped or cleaned): correlationId={}",
                    correlationId);
                deleteRequest(correlationId);
                return;
            }
            if (!claim(SENT_KEY_PREFIX + correlationId, SEND_CLAIM_TTL)) {
                return;
            }
        } catch (Exception e) {
            logger.warn("[AgentAttempt] Could not check the scheduled attempt, the recovery scan will retry it: correlationId={}, error={}",
                correlationId, e.getMessage());
            return;
        }
        PendingAgent attempt = pending.get();
        try {
            if (runStopped(attempt.runId())) {
                registry.consume(correlationId);
                deleteRequest(correlationId);
                logger.info("[AgentAttempt] Run stopped during the wait, attempt {} not sent: runId={}, nodeId={}",
                    attempt.attempt(), attempt.runId(), attempt.nodeId());
                return;
            }
            // The clocks of the attempt (its timeout, the recovery hard timeout) start when it leaves.
            registry.replace(attempt.sentAt(Instant.now()));
            // Marked sent BEFORE it leaves (kept for a further retry, sendAt 0): an answer can come
            // back at once, and its retry takes this request; marking it afterwards would recreate
            // a stale copy.
            saveRequest(stored.message(), 0L);
            TenantResolver.runWithOrgScope(attempt.organizationId(), () -> queueProducer.enqueue(stored.message()));
        } catch (Exception e) {
            // Not sent: put the schedule back and release the claim, so the recovery scan sends it.
            logger.warn("[AgentAttempt] Send failed, the recovery scan will retry it: correlationId={}, error={}",
                correlationId, e.getMessage());
            try {
                saveRequest(stored.message(), stored.sendAtMs());
                redisTemplate.delete(SENT_KEY_PREFIX + correlationId);
            } catch (Exception ignored) {
                // the claim expires with its TTL
            }
            return;
        }
        logger.info("[AgentAttempt] Sent attempt {} of nodeId={}: runId={}, correlationId={}",
            attempt.attempt(), attempt.nodeId(), attempt.runId(), correlationId);
        if (attempt.timeoutMs() > 0) {
            watchTimeout(correlationId, attempt.timeoutMs());
        }
    }

    /** Drops what was kept for an attempt that ended: its request and its send claim. */
    public void forget(String correlationId) {
        if (correlationId == null) return;
        try {
            redisTemplate.delete(java.util.List.of(REQUEST_KEY_PREFIX + correlationId, SENT_KEY_PREFIX + correlationId));
        } catch (Exception e) {
            logger.debug("[AgentAttempt] Could not drop the kept request (it expires): correlationId={}, error={}",
                correlationId, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Timeout
    // ═══════════════════════════════════════════════════════════════════════════

    void watchTimeout(String correlationId, long timeoutMs) {
        schedule(() -> expireIfWaiting(correlationId), timeoutMs, correlationId);
    }

    /**
     * A local timer. When the timer refuses the task (the instance is shutting down), the entry and
     * its kept request are already in Redis: the recovery scan acts on it once it is overdue, on
     * this instance or on the next one that starts (a scan only walks the entries its instance
     * holds, which a starting instance reloads from Redis). A refused timer is a lost timer, never
     * an error for the caller.
     */
    private void schedule(Runnable task, long delayMs, String correlationId) {
        try {
            timer.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            logger.warn("[AgentAttempt] Timer refused the task (shutting down), the recovery scan will act on it: correlationId={}",
                correlationId);
        }
    }

    /**
     * Ends an attempt whose answer did not come in time by publishing its failure as the worker
     * would. Written only if no answer is there yet ({@code SET NX}): an answer that already
     * arrived wins.
     */
    void expireIfWaiting(String correlationId) {
        try {
            Optional<PendingAgent> pending = registry.peek(correlationId);
            if (pending.isEmpty()) {
                return; // answered, or stopped, in time
            }
            PendingAgent attempt = pending.get();
            Map<String, Object> raw = timeoutFailure(attempt.timeoutMs());
            String json = objectMapper.writeValueAsString(raw);
            Boolean first = redisTemplate.opsForValue().setIfAbsent(RESULT_KEY_PREFIX + correlationId, json, RESULT_TTL);
            if (!Boolean.TRUE.equals(first)) {
                return;
            }
            redisTemplate.convertAndSend(RESULT_CHANNEL_PREFIX + correlationId, json);
            logger.warn("[AgentAttempt] No answer within nodePolicy.timeoutMs={}ms, attempt {} ends as a timeout: runId={}, nodeId={}, correlationId={}",
                attempt.timeoutMs(), attempt.attempt(), attempt.runId(), attempt.nodeId(), correlationId);
        } catch (Exception e) {
            logger.warn("[AgentAttempt] Could not publish the timeout, the recovery scan will retry it: correlationId={}, error={}",
                correlationId, e.getMessage());
        }
    }

    /** The failure a timed-out attempt ends with, shaped as a worker answer. */
    static Map<String, Object> timeoutFailure(long timeoutMs) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("success", false);
        raw.put("error", "TIMEOUT: no answer from the agent within " + timeoutMs
            + " ms (nodePolicy.timeoutMs). The agent may still be running; its late answer is not used.");
        raw.put("synthetic", true);
        raw.put(ExecutionMetadataKeys.POLICY_TIMEOUT, true);
        return raw;
    }

    /** Whether a delivered answer is the timeout this scheduler published. */
    public static boolean isPolicyTimeout(Map<String, Object> rawResult) {
        return rawResult != null
            && Boolean.TRUE.equals(rawResult.get("synthetic"))
            && Boolean.TRUE.equals(rawResult.get(ExecutionMetadataKeys.POLICY_TIMEOUT));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Recovery
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Called by the recovery scan for every entry it holds: sends an attempt, or ends it as a
     * timeout, when its local timer is overdue (lost with a restarted instance, or held by
     * another replica that went away).
     */
    public void recover(PendingAgent pending) {
        if (pending == null || pending.startedAt() == null) {
            return;
        }
        Instant overdue = Instant.now().minus(RECOVERY_GRACE);
        if (pending.attempt() > 1) {
            StoredRequest stored = readRequest(pending.correlationId());
            if (stored != null && stored.sendAtMs() > 0) {
                if (stored.sendAtMs() < overdue.toEpochMilli()) {
                    sendIfDue(pending.correlationId());
                }
                return; // not sent yet: no timeout can run
            }
        }
        if (pending.timeoutMs() > 0
                && pending.startedAt().plusMillis(pending.timeoutMs()).isBefore(overdue)) {
            expireIfWaiting(pending.correlationId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Redis
    // ═══════════════════════════════════════════════════════════════════════════

    record StoredRequest(AgentExecutionRequestMessage message, long sendAtMs) {}

    private void saveRequest(AgentExecutionRequestMessage message, long sendAtMs) {
        try {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("message", message);
            stored.put("sendAtMs", sendAtMs);
            redisTemplate.opsForValue().set(REQUEST_KEY_PREFIX + message.correlationId(),
                objectMapper.writeValueAsString(stored), RedisPendingAgentStore.DEFAULT_TTL);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Agent request not serializable: " + e.getMessage(), e);
        }
    }

    private StoredRequest readRequest(String correlationId) {
        return decode(redisTemplate.opsForValue().get(REQUEST_KEY_PREFIX + correlationId));
    }

    private StoredRequest takeRequest(String correlationId) {
        return decode(redisTemplate.opsForValue().getAndDelete(REQUEST_KEY_PREFIX + correlationId));
    }

    private void deleteRequest(String correlationId) {
        redisTemplate.delete(REQUEST_KEY_PREFIX + correlationId);
    }

    private StoredRequest decode(String json) {
        if (json == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
            AgentExecutionRequestMessage message =
                objectMapper.treeToValue(node.get("message"), AgentExecutionRequestMessage.class);
            long sendAtMs = node.hasNonNull("sendAtMs") ? node.get("sendAtMs").asLong() : 0L;
            return new StoredRequest(message, sendAtMs);
        } catch (Exception e) {
            logger.warn("[AgentAttempt] Unreadable kept request dropped: error={}", e.getMessage());
            return null;
        }
    }

    private boolean claim(String key, Duration ttl) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, "1", ttl));
    }

    private boolean runStopped(String runId) {
        return runCancellationGuard != null && runCancellationGuard.isRunStoppedOrTerminal(runId);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Request shape
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The caller's roles travel with the kept request: a retry is sent from a timer thread that
     * has no request to read them from, and the worker's bridge policy checks rely on them.
     */
    private static AgentExecutionRequestMessage withCallerRoles(AgentExecutionRequestMessage message) {
        if (message.userRoles() != null && !message.userRoles().isBlank()) {
            return message;
        }
        HttpHeaders forwarded = new HttpHeaders();
        OrgContextHeaderForwarder.forward(forwarded);
        String roles = forwarded.getFirst("X-User-Roles");
        if (roles == null || roles.isBlank()) {
            return message;
        }
        return new AgentExecutionRequestMessage(message.correlationId(), message.runId(), message.nodeId(),
            message.tenantId(), message.agentType(), message.provider(), message.model(),
            message.requestPayload(), roles.trim(), message.schemaVersion());
    }

    /**
     * The same request for the next attempt: its own correlation id and, when the agent writes to
     * a conversation, its own execution id (the one that links its messages), everything else as
     * sent the first time.
     */
    @SuppressWarnings("unchecked")
    static AgentExecutionRequestMessage forAttempt(AgentExecutionRequestMessage message, PendingAgent next) {
        Map<String, Object> payload = message.requestPayload() != null
            ? new HashMap<>(message.requestPayload()) : new HashMap<>();
        if (next.executionId() != null && payload.containsKey("executionId")) {
            payload.put("executionId", next.executionId());
            if (payload.get("credentials") instanceof Map<?, ?> credentials) {
                Map<String, Object> copy = new HashMap<>((Map<String, Object>) credentials);
                if (copy.containsKey("__executionId__")) {
                    copy.put("__executionId__", next.executionId());
                }
                payload.put("credentials", copy);
            }
        }
        return new AgentExecutionRequestMessage(next.correlationId(), message.runId(), message.nodeId(),
            message.tenantId(), message.agentType(), message.provider(), message.model(),
            payload, message.userRoles(), message.schemaVersion());
    }
}
