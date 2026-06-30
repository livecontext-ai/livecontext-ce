package com.apimarketplace.agent.skill.bundle;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Runs ONE skill-bundle sync right after boot so a freshly-started CE install picks up the
 * cloud's latest global skills without waiting a full cron interval. Gated on the same
 * {@code skill.bundle.sync.enabled=true} as {@link SkillBundleSyncScheduler}. Mirrors
 * {@code com.apimarketplace.agent.catalog.bundle.CatalogBundleStartupSync}: fires on
 * {@link ApplicationReadyEvent} (after Flyway + bean init) on a daemon thread, calling
 * {@code scheduler.tick()} on the Spring proxy so the {@code @SchedulerLock} still fires.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "skill.bundle.sync.enabled", havingValue = "true")
public class SkillBundleStartupSync {

    private final SkillBundleSyncScheduler scheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void syncOnceOnStartup() {
        Thread t = new Thread(() -> {
            try {
                log.info("Skill bundle sync: running initial sync on startup");
                scheduler.tick();
            } catch (Exception e) {
                log.warn("Skill bundle startup sync failed (already persisted): {}", e.getMessage());
            }
        }, "skill-bundle-startup-sync");
        t.setDaemon(true);
        t.start();
    }
}
