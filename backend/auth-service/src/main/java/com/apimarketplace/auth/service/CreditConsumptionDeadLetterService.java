package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.CreditConsumptionDeadLetterEntity;
import com.apimarketplace.auth.domain.CreditConsumptionDeadLetterEntity.Status;
import com.apimarketplace.auth.repository.CreditConsumptionDeadLetterRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.CreditDeadLetterHandler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dead-letter handler for auth-service.
 * Persists failed credit consumption attempts and periodically retries them.
 * The table now lives in the auth schema (credit/billing domain).
 */
@Service
public class CreditConsumptionDeadLetterService implements CreditDeadLetterHandler {

    private static final Logger log = LoggerFactory.getLogger(CreditConsumptionDeadLetterService.class);

    private static final int MAX_RETRIES = 10;
    private static final int BATCH_SIZE = 50;

    private final CreditConsumptionDeadLetterRepository repository;
    private final CreditConsumptionClient creditClient;

    public CreditConsumptionDeadLetterService(CreditConsumptionDeadLetterRepository repository,
                                                CreditConsumptionClient creditClient,
                                                MeterRegistry meterRegistry) {
        this.repository = repository;
        this.creditClient = creditClient;

        // Expose dead-letter queue depth as a Prometheus gauge for alerting
        Gauge.builder("credit_dead_letter_pending", repository,
                repo -> repo.countByStatusIn(List.of(Status.PENDING, Status.RETRYING)))
                .description("Number of pending credit consumption dead-letter entries awaiting retry")
                .register(meterRegistry);
    }

    @PostConstruct
    void init() {
        creditClient.setDeadLetterHandler(this);
    }

    /**
     * Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2, 2026-05-19) - canonical 9-arg
     * persist. The {@code organizationId} arg is stamped on the V263-NOT-NULL
     * column. Daemon/async callers pass it explicitly (captured at producer
     * thread before the @Async hop); HTTP-bound callers reach the 8-arg
     * default in the interface which resolves via TenantResolver.
     *
     * <p>Round-7 audit (2026-05-20): null {@code organizationId} fail-fast
     * BEFORE the try/catch so a programmer error surfaces as NPE at the
     * producer site instead of being swallowed into a silent log-and-drop.
     * Persist failures now include the exception class + stack trace so
     * NOT NULL violations are visible in error budgets.
     */
    @Override
    public void persistFailedConsumption(String tenantId, String sourceType, String sourceId,
                                          String provider, String model,
                                          Integer promptTokens, Integer completionTokens,
                                          String errorReason, String organizationId) {
        Objects.requireNonNull(organizationId,
            "organizationId required for dead-letter persist (post-V263 NOT NULL)");
        try {
            CreditConsumptionDeadLetterEntity entry = new CreditConsumptionDeadLetterEntity();
            entry.setTenantId(tenantId);
            entry.setOrganizationId(organizationId);
            entry.setSourceType(sourceType);
            entry.setSourceId(sourceId);
            entry.setProvider(provider);
            entry.setModel(model);
            entry.setPromptTokens(promptTokens);
            entry.setCompletionTokens(completionTokens);
            entry.setErrorReason(errorReason);
            repository.save(entry);
            log.warn("Dead-letter entry created for failed credit consumption: tenant={}, source={}/{}, org={}",
                    tenantId, sourceType, sourceId, organizationId);
        } catch (Exception e) {
            log.error("Failed to persist dead-letter entry for tenant {} org {}: {} ({})",
                    tenantId, organizationId, e.getMessage(), e.getClass().getSimpleName(), e);
        }
    }

    /**
     * Scheduled retry job - runs every 5 minutes.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    @SchedulerLock(name = "credit_dlq_retry", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void retryFailedConsumptions() {
        List<CreditConsumptionDeadLetterEntity> entries = repository.findByStatusInOrderByCreatedAtAsc(
                List.of(Status.PENDING, Status.RETRYING));

        if (entries.isEmpty()) return;

        int total = entries.size();
        int success = 0;
        int failed = 0;
        int permanent = 0;

        log.info("Dead-letter retry: processing {} entries", Math.min(total, BATCH_SIZE));

        for (int i = 0; i < Math.min(total, BATCH_SIZE); i++) {
            CreditConsumptionDeadLetterEntity entry = entries.get(i);
            try {
                entry.setStatus(Status.RETRYING);
                entry.setRetryCount(entry.getRetryCount() + 1);
                entry.setLastRetryAt(Instant.now());

                // DECIDED POLICY (cache-aware billing, 2026-06-11): dead-letter rows do
                // not persist the cache/reasoning breakdown, so replays bill at the
                // legacy formula (expensive for bridge providers whose promptTokens
                // include cache, cheap for direct-API providers whose cache counters
                // are additive). Accepted for this rare degraded path rather than
                // widening the dead-letter schema; the primary path is cache-aware.
                Map<String, Object> result = creditClient.consumeCredits(
                        entry.getTenantId(), entry.getSourceType(), entry.getSourceId(),
                        entry.getProvider(), entry.getModel(),
                        entry.getPromptTokens(), entry.getCompletionTokens());

                if (Boolean.TRUE.equals(result.get("success"))) {
                    entry.setStatus(Status.RECONCILED);
                    entry.setErrorReason(null);
                    success++;
                } else {
                    String error = String.valueOf(result.get("error"));
                    entry.setErrorReason(error);
                    if (error.contains("402") || error.contains("Insufficient") || entry.getRetryCount() >= MAX_RETRIES) {
                        entry.setStatus(Status.FAILED);
                        permanent++;
                    }
                    failed++;
                }
            } catch (Exception e) {
                entry.setErrorReason(e.getMessage());
                if (entry.getRetryCount() >= MAX_RETRIES) {
                    entry.setStatus(Status.FAILED);
                    permanent++;
                }
                failed++;
            }
            repository.save(entry);
        }

        log.info("Dead-letter retry complete: {} success, {} failed ({} permanent), {} remaining",
                success, failed, permanent, total - Math.min(total, BATCH_SIZE));
    }

    /**
     * Count pending dead-letter entries for a tenant.
     * Used by CreditReconciliationService - the per-user (tenant) credit
     * reconciliation path iterates by tenant regardless of org membership,
     * so this stays tenant-scoped. New org-dashboard callers should use
     * {@link #countPendingForOrganization(String)} instead.
     */
    public long countPendingForTenant(String tenantId) {
        return repository.countByTenantIdAndStatusIn(tenantId, List.of(Status.PENDING, Status.RETRYING));
    }

    /**
     * Org-scoped strict count of pending dead-letter entries. Backed by the
     * V263-NOT-NULL {@code organization_id} column. Intended for org-aware
     * dashboards / retry jobs that already hold the active workspace's
     * orgId.
     */
    public long countPendingForOrganization(String organizationId) {
        return repository.countByOrganizationIdAndStatusIn(
                organizationId, List.of(Status.PENDING, Status.RETRYING));
    }
}
