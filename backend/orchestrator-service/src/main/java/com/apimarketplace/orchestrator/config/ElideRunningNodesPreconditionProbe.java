package com.apimarketplace.orchestrator.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.Set;

/**
 * Fail-CLOSED startup gate for the {@code state-snapshot.elide-running-nodes} feature.
 *
 * <p>Activated by setting {@code state-snapshot.elide-running-nodes.enabled=true} in
 * {@code application.yml}. When active, this probe validates the three deploy
 * preconditions documented in the execution-kernel roadmap §7.6 / §3.8 test 5.
 * Any failure aborts application context startup with a descriptive
 * {@link IllegalStateException} - the per-tenant elide flag relies on these
 * environmental guarantees, and silently degrading them would invalidate the
 * bounded-acceptance argument for &gt;5min crash recovery.
 *
 * <p>Probes (all three must pass):
 * <ol>
 *   <li><b>scaling.backend=redis</b> - the {@link OrchestrationRecoveryService} zombie
 *       sweeper is gated on this property; without it, RUNNING-status orphans accumulate
 *       past the 5-minute threshold and the bounded-acceptance window for the elide
 *       feature does not close.</li>
 *   <li><b>Redis maxmemory-policy</b> ∈ {{@code noeviction}, {@code volatile-ttl}}.
 *       An {@code allkeys-*} policy can evict hot orchestrator keys (the running-node
 *       hash among them) under memory pressure independent of the 1h TTL, breaking
 *       the bounded-acceptance window.</li>
 *   <li><b>scaling.agent.queue.enabled=true</b> - the recovery sweeper's skip-protection
 *       relies on {@code PendingAgentRegistry.hasAnyPendingForRun(runId)} to recognize
 *       in-flight async agent calls. With the queue disabled, sync agent paths exceeding
 *       5min are false-positive zombie-killed.</li>
 * </ol>
 *
 * <p>The per-tenant rollout flag ({@code state-snapshot.elide-running-nodes} read
 * inside {@code EpochStateRunningElideSerializer}) is independent of the
 * {@code .enabled} feature wire; setting the feature wire to true does NOT enable
 * elision for any tenant - it just confirms the deploy-side preconditions hold so
 * tenant-side ramp can begin safely.
 */
@Component
@ConditionalOnProperty(
        prefix = "state-snapshot.elide-running-nodes",
        name = "enabled",
        havingValue = "true")
public class ElideRunningNodesPreconditionProbe {

    private static final Logger log = LoggerFactory.getLogger(ElideRunningNodesPreconditionProbe.class);

    /**
     * The only policies safe for the elide-running-nodes feature: {@code noeviction}
     * never evicts; {@code volatile-ttl} evicts only TTL-bearing keys closest to
     * expiration, which aligns with the natural 1h TTL on the running-nodes hash.
     * Other {@code volatile-*} policies (lru/lfu/random) can evict TTL-bearing keys
     * before TTL under memory pressure - same eviction hazard as {@code allkeys-*}
     * - so they are excluded.
     */
    static final Set<String> SAFE_MAXMEMORY_POLICIES = Set.of(
            "noeviction",
            "volatile-ttl");

    private final StringRedisTemplate redis;
    private final String scalingBackend;
    private final boolean agentQueueEnabled;

    public ElideRunningNodesPreconditionProbe(
            StringRedisTemplate redis,
            @Value("${scaling.backend:memory}") String scalingBackend,
            @Value("${scaling.agent.queue.enabled:false}") boolean agentQueueEnabled) {
        this.redis = redis;
        this.scalingBackend = scalingBackend;
        this.agentQueueEnabled = agentQueueEnabled;
    }

    @PostConstruct
    void verifyPreconditions() {
        verifyScalingBackendIsRedis();
        verifyAgentQueueEnabled();
        verifyMaxMemoryPolicy();
        log.info("[ElidePrecondition] All deploy preconditions satisfied - "
                + "state-snapshot.elide-running-nodes ramp may proceed per-tenant.");
    }

    void verifyScalingBackendIsRedis() {
        if (!"redis".equalsIgnoreCase(scalingBackend)) {
            throw new IllegalStateException(
                    "state-snapshot.elide-running-nodes.enabled=true requires "
                            + "scaling.backend=redis (currently '" + scalingBackend + "'). "
                            + "Without scaling.backend=redis, OrchestrationRecoveryService "
                            + "(the >5min zombie sweeper) is disabled and the bounded-acceptance "
                            + "argument for crash recovery does not hold. See §7.6 precondition #1.");
        }
    }

    void verifyAgentQueueEnabled() {
        if (!agentQueueEnabled) {
            throw new IllegalStateException(
                    "state-snapshot.elide-running-nodes.enabled=true requires "
                            + "scaling.agent.queue.enabled=true. Without it, sync agent paths "
                            + "exceeding the 5-minute zombie threshold run outside PendingAgentRegistry "
                            + "and would be false-positive zombie-killed by OrchestrationRecoveryService. "
                            + "See §7.6 precondition #3.");
        }
    }

    void verifyMaxMemoryPolicy() {
        String policy = readMaxMemoryPolicy();
        if (policy == null) {
            // Probe failure to read INFO memory itself fails closed. A cluster that
            // can't answer INFO can't be trusted to keep keys until TTL.
            throw new IllegalStateException(
                    "state-snapshot.elide-running-nodes.enabled=true requires reachable Redis "
                            + "with INFO memory access; the probe could not determine maxmemory-policy. "
                            + "See §7.6 precondition #2.");
        }
        if (!SAFE_MAXMEMORY_POLICIES.contains(policy.toLowerCase())) {
            throw new IllegalStateException(
                    "state-snapshot.elide-running-nodes.enabled=true requires Redis "
                            + "maxmemory-policy ∈ " + SAFE_MAXMEMORY_POLICIES + " "
                            + "(currently '" + policy + "'). With an allkeys-* policy, hot orchestrator "
                            + "keys can be evicted under memory pressure before the 1h TTL elapses, "
                            + "breaking the bounded-acceptance window. See §7.6 precondition #2.");
        }
    }

    private String readMaxMemoryPolicy() {
        try {
            Properties memInfo = redis.execute((RedisConnection conn) -> conn.serverCommands().info("memory"));
            if (memInfo == null) return null;
            return memInfo.getProperty("maxmemory_policy");
        } catch (Exception e) {
            log.warn("[ElidePrecondition] Failed to read Redis INFO memory: {}", e.getMessage());
            return null;
        }
    }
}
