package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * CE-side periodic sync: fetch latest signed skill bundle -> verify signature -> apply to
 * {@code agent.skills}. Gated on {@code skill.bundle.sync.enabled=true} so cloud instances
 * never run it. Sibling of
 * {@code com.apimarketplace.agent.catalog.bundle.CatalogBundleSyncScheduler}.
 *
 * <p>Every non-OK outcome is persisted on {@code skill_bundle_sync_status} and the method
 * returns normally - the scheduler must never crash out of an exception.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "skill.bundle.sync.enabled", havingValue = "true")
public class SkillBundleSyncScheduler {

    private final SkillBundleFetcher fetcher;
    private final SkillBundleVerifier verifier;
    private final SkillBundleApplier applier;
    private final SkillBundleSyncStatusRepository syncStatusRepo;
    /**
     * Resolves THE active cloud-link credentials of this install. {@code ObjectProvider} so
     * a misconfigured stack (sync enabled without the CE cloud-link beans) degrades to "not
     * linked" (skip) instead of failing to start.
     */
    private final ObjectProvider<CloudLlmRuntimeAccess> runtimeAccessProvider;

    @Value("${skill.bundle.cloud-url:}")
    private String cloudUrl;

    @Scheduled(cron = "${skill.bundle.sync.cron:0 */15 * * * *}")
    @SchedulerLock(name = "skillBundleSync_tick",
                   lockAtMostFor = "PT5M",
                   lockAtLeastFor = "PT30S")
    public void tick() {
        try {
            syncOnce();
        } catch (Exception e) {
            log.error("Skill bundle sync failed unexpectedly", e);
            try {
                recordFailure("UNEXPECTED_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignored) {
                // Nothing we can do if even status persistence fails.
            }
        }
    }

    private void syncOnce() {
        if (!verifier.hasKeys()) {
            log.warn("skill.bundle.sync.enabled=true but no trusted keys (catalog.bundle.trusted-keys) - " +
                    "skipping this tick.");
            recordFailure("TRUST_UNCONFIGURED", "no pinned keys in catalog.bundle.trusted-keys");
            return;
        }

        // The download is gated behind an active cloud link: resolve THE install's link
        // credentials; if not linked (or CE cloud beans absent), skip without failing.
        CloudLlmRuntimeAccess runtimeAccess = runtimeAccessProvider.getIfAvailable();
        Optional<CloudLlmRuntimeCredentials> creds =
                runtimeAccess == null ? Optional.empty() : runtimeAccess.resolveActiveCloudRuntime();
        if (creds.isEmpty()) {
            log.debug("Skill bundle sync: this CE install is not cloud-linked - skipping (no link, no updates)");
            recordFetchOnly("NOT_LINKED", "this CE install has no active cloud link");
            return;
        }

        SkillBundleFetcher.FetchResult fetched = fetcher.fetchLatest(creds.get());
        switch (fetched.status()) {
            case FETCHED -> {
                SkillBundleVerifier.Result v = verifier.verify(fetched.bundle());
                if (!v.ok()) {
                    log.warn("Skill bundle sync: verification failed ({}): {}", v.status(), v.detail());
                    recordFailure(v.status().name(), v.detail());
                    return;
                }
                try {
                    SkillBundleApplier.ApplyResult r =
                            applier.apply(fetched.bundle(), v.payloadBytes(), cloudUrl);
                    if (r.status() == SkillBundleApplier.Status.APPLY_FAILED) {
                        recordFailure("APPLY_FAILED", r.detail());
                    }
                    // On APPLIED / ALREADY_APPLIED the applier already wrote OK + reset failures.
                } catch (Exception e) {
                    log.error("Skill bundle apply threw unexpectedly", e);
                    recordFailure("APPLY_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            case NO_ACTIVE -> recordFetchOnly("NO_ACTIVE", null);
            case NOT_CONFIGURED -> {
                log.warn("Skill bundle sync: cloud-url is empty - skipping tick");
                recordFailure("NOT_CONFIGURED", fetched.detail());
            }
            case HTTP_ERROR, NETWORK_ERROR -> {
                log.warn("Skill bundle sync: fetch failed ({}): {}", fetched.status(), fetched.detail());
                recordFailure(fetched.status().name(), fetched.detail());
            }
        }
    }

    void recordFailure(String status, String detail) {
        SkillBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus(status);
        row.setLastFetchError(detail);
        row.setConsecutiveFailures(row.getConsecutiveFailures() + 1);
        row.setUpdatedAt(now());
        syncStatusRepo.save(row);
    }

    void recordFetchOnly(String status, String detail) {
        SkillBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus(status);
        row.setLastFetchError(detail);
        row.setUpdatedAt(now());
        syncStatusRepo.save(row);
    }

    private SkillBundleSyncStatusEntity loadOrInit() {
        return syncStatusRepo.findById(SkillBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(SkillBundleSyncStatusEntity::new);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
