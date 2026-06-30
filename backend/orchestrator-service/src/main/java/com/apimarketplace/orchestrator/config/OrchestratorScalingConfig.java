package com.apimarketplace.orchestrator.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Scaling configuration activated when {@code scaling.backend=redis}.
 *
 * <p>In Redis-backed scaling mode the orchestrator runs as multiple instances
 * behind a load balancer. This config adjusts infrastructure defaults - chiefly
 * the HikariCP pool size.
 *
 * <h2>Pool size MUST track the worker-permit count</h2>
 *
 * <p>The execution worker pool ({@code workflow.execution-queue.worker-threads},
 * a.k.a. local permits) is the dominant sustained holder of JDBC connections: a
 * busy worker holds a connection for the duration of its node's state writes. If
 * the Hikari pool is smaller than the worker count, workers (plus the HTTP
 * trigger-fire threads, batch flusher, pollers, recovery + signal timers) contend
 * for too few connections → HikariCP's 5 s acquire-timeout fires →
 * {@code SQLTransientConnectionException} → HTTP 500. This was observed at
 * {@code worker-threads=100} against the previously-fixed pool of 40
 * (total=40, active=40, waiting=63).
 *
 * <p>So the pool is no longer a hard-coded constant: it is
 * {@code max(MIN_POOL_SIZE, workerThreads + CONNECTION_HEADROOM)} by default, i.e.
 * it grows in lockstep with the permit count - raising
 * {@code WORKFLOW_EXECUTION_QUEUE_WORKER_THREADS} raises the pool by construction,
 * which prevents the pool&lt;permits starvation. An operator who knows their exact
 * database capacity can override with {@code SCALING_HIKARI_MAX_POOL_SIZE}
 * ({@code scaling.hikari.max-pool-size}).
 *
 * <p><b>Downstream capacity (operational):</b> the effective Postgres connection
 * demand is {@code pods × poolSize}. PgBouncer (transaction pooling) multiplexes
 * these onto a much smaller server-side pool, so a large app-side pool is safe as
 * long as PgBouncer's {@code default_pool_size} / Postgres {@code max_connections}
 * are provisioned for the fleet. Raise permits and pool together with the data tier
 * in mind.
 *
 * <p>When {@code scaling.backend=memory} (single instance), this configuration is
 * not loaded and the standard HikariCP profile settings apply.
 */
@Configuration
@ConditionalOnProperty(name = "scaling.backend", havingValue = "redis")
public class OrchestratorScalingConfig {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorScalingConfig.class);

    /**
     * Connection headroom added above the worker-thread count when deriving the
     * pool size: HTTP trigger-fire threads (~10) + batch flush (~5) + result poller
     * (1) + recovery watcher (1) + signal timer (~2) + slack. Covers the non-worker
     * connection holders so the worker pool itself never starves.
     */
    static final int CONNECTION_HEADROOM = 30;

    /**
     * Floor for the derived pool size - preserves the historical default for small
     * deployments (worker-threads ≤ 10) so this change is a no-op there.
     */
    static final int MIN_POOL_SIZE = 40;

    private final DataSource dataSource;
    private final int workerThreads;
    private final Integer explicitPoolSize;

    public OrchestratorScalingConfig(
            DataSource dataSource,
            @Value("${workflow.execution-queue.worker-threads:20}") int workerThreads,
            @Value("${scaling.hikari.max-pool-size:#{null}}") Integer explicitPoolSize) {
        this.dataSource = dataSource;
        this.workerThreads = workerThreads;
        this.explicitPoolSize = explicitPoolSize;
    }

    /**
     * Resolve the target Hikari pool size: an explicit positive override wins,
     * otherwise {@code max(MIN_POOL_SIZE, workerThreads + CONNECTION_HEADROOM)} so
     * the pool tracks the permit count and never sits below it.
     */
    int resolvePoolSize() {
        if (explicitPoolSize != null && explicitPoolSize > 0) {
            return explicitPoolSize;
        }
        return Math.max(MIN_POOL_SIZE, workerThreads + CONNECTION_HEADROOM);
    }

    @PostConstruct
    void tuneForRedisScaling() {
        if (dataSource instanceof HikariDataSource hikari) {
            int previousSize = hikari.getMaximumPoolSize();
            int target = resolvePoolSize();
            hikari.setMaximumPoolSize(target);
            hikari.setConnectionTimeout(5_000);       // fail fast
            hikari.setLeakDetectionThreshold(30_000);  // 30s leak detection
            hikari.setMinimumIdle(5);
            logger.info("[ScalingConfig] Redis scaling mode active - HikariCP pool: {} -> {} "
                        + "(workerThreads={}, explicitOverride={}), connectionTimeout=5s, leakDetection=30s",
                        previousSize, target, workerThreads, explicitPoolSize);
        } else {
            logger.warn("[ScalingConfig] Redis scaling mode active but DataSource is not HikariDataSource - " +
                        "pool tuning skipped (type={})", dataSource.getClass().getSimpleName());
        }
    }
}
