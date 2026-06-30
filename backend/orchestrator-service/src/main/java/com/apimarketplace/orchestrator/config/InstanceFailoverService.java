package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.scaling.registry.RedisServiceRegistry;
import com.apimarketplace.common.scaling.registry.ServiceInstance;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.context.RunContextRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.data.redis.core.ScanOptions;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Detects dead orchestrator instances and recovers their orphaned workflow runs.
 *
 * <p>Runs every 15 seconds (protected by ShedLock - single execution cluster-wide).
 * When a registered instance stops sending heartbeats (stale > 30s in the registry),
 * this service:
 * <ol>
 *   <li>Reads the dead instance's active-runs set from Redis</li>
 *   <li>For each orphaned run still in RUNNING status, marks it FAILED
 *       (the existing OrchestrationRecoveryService zombie detector would catch these
 *       eventually, but this is faster - immediate vs. 5-minute threshold)</li>
 *   <li>Cleans up the dead instance's Redis tracking key</li>
 * </ol>
 *
 * <p>With run-level affinity, new requests for orphaned runs will be re-routed
 * to a different instance by the gateway's hash ring (the dead instance is no
 * longer in the ring). Signal recovery is handled by SignalRecoveryService at
 * startup and the Redis pub/sub cross-instance mechanism (Phase 2).
 *
 * <p>Only active when multi-instance mode is enabled (RedisServiceRegistry bean present).
 */
@Service
@ConditionalOnBean(RedisServiceRegistry.class)
public class InstanceFailoverService {

    private static final Logger logger = LoggerFactory.getLogger(InstanceFailoverService.class);

    private final RedisServiceRegistry registry;
    private final StringRedisTemplate redisTemplate;
    private final WorkflowRunRepository runRepository;
    private final OrchestratorInstanceRegistrar registrar;
    private final Clock clock;

    public InstanceFailoverService(RedisServiceRegistry registry,
                                    StringRedisTemplate redisTemplate,
                                    WorkflowRunRepository runRepository,
                                    OrchestratorInstanceRegistrar registrar,
                                    Clock clock) {
        this.registry = registry;
        this.redisTemplate = redisTemplate;
        this.runRepository = runRepository;
        this.registrar = registrar;
        this.clock = clock;
    }

    /**
     * Scan for dead instances and recover their orphaned runs.
     * Protected by ShedLock to ensure only one instance runs this at a time.
     */
    @Scheduled(fixedDelay = 15_000)
    @SchedulerLock(name = "instance-failover-scan", lockAtMostFor = "12s")
    public void scanForDeadInstances() {
        try {
            // Get all known instance IDs from the active-runs keys
            Set<String> trackedInstanceIds = findTrackedInstanceIds();
            if (trackedInstanceIds.isEmpty()) return;

            // Get currently alive instances from the registry
            List<ServiceInstance> aliveInstances = registry.getInstances(
                    OrchestratorInstanceRegistrar.SERVICE_NAME);
            Set<String> aliveIds = aliveInstances.stream()
                    .map(ServiceInstance::instanceId)
                    .collect(Collectors.toSet());

            // Skip self
            String selfId = registrar.getInstanceId();

            for (String instanceId : trackedInstanceIds) {
                if (instanceId.equals(selfId)) continue;
                if (aliveIds.contains(instanceId)) continue;

                // This instance is dead - recover its runs
                recoverOrphanedRuns(instanceId);
            }
        } catch (Exception e) {
            logger.warn("[Failover] Error scanning for dead instances: {}", e.getMessage());
        }
    }

    /**
     * Find all instance IDs that have active-run tracking keys in Redis.
     * Uses SCAN instead of KEYS to avoid blocking Redis.
     */
    private Set<String> findTrackedInstanceIds() {
        String prefix = "orch:instance:active-runs:";
        Set<String> instanceIds = new HashSet<>();
        try (var cursor = redisTemplate.scan(ScanOptions.scanOptions()
                .match(prefix + "*").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                instanceIds.add(key.substring(prefix.length()));
            }
        }
        return instanceIds;
    }

    /**
     * Recover orphaned runs from a dead instance.
     */
    private void recoverOrphanedRuns(String deadInstanceId) {
        String activeRunsKey = RunContextRegistry.activeRunsKeyFor(deadInstanceId);
        Set<String> orphanedRunIds = redisTemplate.opsForSet().members(activeRunsKey);

        if (orphanedRunIds == null || orphanedRunIds.isEmpty()) {
            // No active runs - just clean up the key
            redisTemplate.delete(activeRunsKey);
            logger.info("[Failover] Dead instance {} had no active runs, cleaned up", deadInstanceId);
            return;
        }

        logger.warn("[Failover] Dead instance {} has {} orphaned runs: {}",
                deadInstanceId, orphanedRunIds.size(), orphanedRunIds);

        Instant now = clock.instant();
        int failedCount = 0;

        for (String runId : orphanedRunIds) {
            try {
                WorkflowRunEntity run = runRepository.findByRunIdPublic(runId).orElse(null);
                if (run == null) continue;

                // Only fail runs that are still actively running
                if (run.getStatus() == RunStatus.RUNNING) {
                    run.setStatus(RunStatus.FAILED);
                    run.setEndedAt(now);
                    run.setUpdatedAt(now);
                    runRepository.save(run);
                    failedCount++;
                    logger.warn("[Failover] Marked orphaned run {} as FAILED (was on dead instance {})",
                            runId, deadInstanceId);
                }
            } catch (Exception e) {
                logger.error("[Failover] Error recovering orphaned run {}: {}", runId, e.getMessage());
            }
        }

        // Clean up the dead instance's tracking key
        redisTemplate.delete(activeRunsKey);
        logger.info("[Failover] Recovered {} orphaned runs from dead instance {}", failedCount, deadInstanceId);
    }
}
