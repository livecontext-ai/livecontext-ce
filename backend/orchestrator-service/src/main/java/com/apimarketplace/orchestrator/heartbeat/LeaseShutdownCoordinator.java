package com.apimarketplace.orchestrator.heartbeat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Plan v4 §10 §20 - coordinates self-evict shutdown when the heartbeat
 * circuit breaker trips.
 *
 * <p>Sequence: (1) initiate Spring application shutdown (Actuator graceful
 * shutdown phase stops accepting new HTTP requests), (2) wait up to
 * {@code drainSeconds} for in-flight requests to drain, (3) exit JVM with
 * non-zero exit code so systemd {@code Restart=on-failure} respawns.
 *
 * <p>Why exit(2) and not exit(0): a clean exit(0) would tell systemd we
 * shut down intentionally; with {@code Restart=on-failure}, systemd would
 * NOT respawn. exit(2) signals "I am unhealthy and gave up", systemd
 * respawns, the new process bootstraps with generation++, and the orphan
 * cleanup runs.
 *
 * <p>The graceful-shutdown phase is best-effort: if HTTP requests don't
 * drain in {@code drainSeconds}, they're dropped (5xx). Plan §20 trades
 * a small window of dropped requests for guaranteed bounded recovery
 * latency.
 */
@Component
public class LeaseShutdownCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LeaseShutdownCoordinator.class);
    private static final int EXIT_CODE_SELF_EVICT = 2;

    private final ApplicationContext applicationContext;

    public LeaseShutdownCoordinator(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Trip the self-evict sequence. Idempotent - calling twice is harmless
     * (the second call sees the context already shutting down).
     *
     * @param drainSeconds upper bound on time to wait for in-flight HTTP
     *                     requests to complete before forcing JVM exit
     */
    public void drainAndExit(int drainSeconds) {
        log.error("[LeaseShutdownCoordinator] self-evict initiated - draining for {}s then exit({})",
                drainSeconds, EXIT_CODE_SELF_EVICT);

        // Spawn shutdown on a separate thread so the caller (the @Scheduled
        // heartbeat thread) can return promptly. SpringApplication.exit()
        // blocks until @PreDestroy hooks finish; we want the heartbeat
        // thread free to log.
        Thread evictor = new Thread(() -> {
            try {
                int exitCode = SpringApplication.exit(applicationContext, () -> EXIT_CODE_SELF_EVICT);
                log.error("[LeaseShutdownCoordinator] Spring context shut down (exit={}). Calling System.exit.",
                        exitCode);
            } catch (RuntimeException ex) {
                log.error("[LeaseShutdownCoordinator] Spring shutdown failed: {} (forcing exit anyway)",
                        ex.getMessage());
            } finally {
                // Sleep drainSeconds AFTER Spring context closes - gives any
                // post-context cleanup (Hikari pool close, etc.) a moment.
                try {
                    Thread.sleep(drainSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                System.exit(EXIT_CODE_SELF_EVICT);
            }
        }, "lease-shutdown-coordinator");
        evictor.setDaemon(false);
        evictor.start();
    }
}
