package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.repository.CeLinkHeartbeatRepository;
import com.apimarketplace.auth.repository.CeLinkRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodic platform-wide ce-link observability. Emits two Prometheus gauges:
 *
 * <ul>
 *   <li>{@code ce_link_active_total} - total ACTIVE rows. Sanity baseline.</li>
 *   <li>{@code ce_link_stale_active_total} - ACTIVE rows whose heartbeat is
 *       older than {@code cloud-link.orphan-reporter.early-warning-days} (default 30).
 *       Early-warning before {@link CeLinkRetentionScheduler}'s 90-day sweep.</li>
 * </ul>
 *
 * <p>Refreshed every {@code cloud-link.orphan-reporter.refresh-minutes} (default 60).
 * Cheap: each refresh is two indexed COUNT queries.
 */
@Component
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkOrphanReporter {

    private static final Logger log = LoggerFactory.getLogger(CeLinkOrphanReporter.class);

    private final CeLinkRepository ceLinkRepository;
    private final CeLinkHeartbeatRepository heartbeatRepository;
    private final MeterRegistry registry;
    private final long earlyWarningDays;

    private final AtomicLong activeCount = new AtomicLong();
    private final AtomicLong staleCount = new AtomicLong();

    public CeLinkOrphanReporter(CeLinkRepository ceLinkRepository,
                                CeLinkHeartbeatRepository heartbeatRepository,
                                MeterRegistry registry,
                                @Value("${cloud-link.orphan-reporter.early-warning-days:30}") long earlyWarningDays) {
        this.ceLinkRepository = ceLinkRepository;
        this.heartbeatRepository = heartbeatRepository;
        this.registry = registry;
        this.earlyWarningDays = earlyWarningDays;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("ce_link_active_total", activeCount, AtomicLong::doubleValue)
                .description("Total ce_link rows in ACTIVE status")
                .register(registry);
        Gauge.builder("ce_link_stale_active_total", staleCount, AtomicLong::doubleValue)
                .description("ACTIVE ce_link rows whose heartbeat is older than the early-warning cutoff")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${cloud-link.orphan-reporter.refresh-minutes:60}", timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void refresh() {
        try {
            activeCount.set(ceLinkRepository.countByStatus(CeLink.Status.ACTIVE));
            Instant cutoff = Instant.now().minus(Duration.ofDays(earlyWarningDays));
            staleCount.set(heartbeatRepository.findStaleActive(cutoff).size());
            log.debug("CeLinkOrphanReporter: active={} stale_active={} (cutoff={}d)",
                    activeCount.get(), staleCount.get(), earlyWarningDays);
        } catch (RuntimeException unexpected) {
            // Never let a metrics refresh kill anything else.
            log.warn("CeLinkOrphanReporter.refresh failed: {}", unexpected.getMessage());
        }
    }
}
