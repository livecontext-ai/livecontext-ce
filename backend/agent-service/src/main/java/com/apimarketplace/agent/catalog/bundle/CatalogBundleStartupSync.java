package com.apimarketplace.agent.catalog.bundle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs ONE catalog-bundle sync right after boot so a freshly-started CE install
 * picks up the cloud's latest model catalog without waiting up to a full cron
 * interval ({@code catalog.bundle.sync.cron}, default 15 min). Gated on the same
 * {@code catalog.bundle.sync.enabled=true} as {@link CatalogBundleSyncScheduler},
 * so it exists only where the scheduler does (CE).
 *
 * <p>Two deliberate choices:
 * <ul>
 *   <li><b>{@link ApplicationReadyEvent}, not {@code @PostConstruct}</b>: fires after
 *       Flyway + full bean init, so {@code model_config_overrides} exists and the apply
 *       can't run against a half-built context.</li>
 *   <li><b>On a daemon thread, through the injected scheduler bean</b>: the fetch +
 *       apply does network I/O, so a slow/unreachable cloud must never delay readiness -
 *       the work runs off the event thread. It calls {@code scheduler.tick()} on the
 *       Spring proxy (NOT a self-invocation) so the {@code @SchedulerLock} on {@code tick()}
 *       still fires and the startup sync can't race the cron tick or a peer pod. The
 *       scheduler swallows all errors, so the thread is fire-and-forget.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "catalog.bundle.sync.enabled", havingValue = "true")
public class CatalogBundleStartupSync {

    private final CatalogBundleSyncScheduler scheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnceOnStartup() {
        Thread t = new Thread(() -> {
            try {
                log.info("Catalog bundle sync: running initial sync on startup");
                scheduler.tick();
            } catch (Exception e) {
                // tick() already persists its own failures; this is belt-and-braces so a
                // startup-sync error never escapes the daemon thread.
                log.warn("Catalog bundle startup sync failed (already persisted): {}", e.getMessage());
            }
        }, "catalog-bundle-startup-sync");
        t.setDaemon(true);
        t.start();
    }
}
