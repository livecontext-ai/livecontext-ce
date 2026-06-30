package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
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
 * CE-side periodic sync: fetch latest signed bundle → verify signature →
 * apply to {@code model_config_overrides}. Gated on
 * {@code catalog.bundle.sync.enabled=true} so cloud instances never run it.
 *
 * <p>Every non-OK outcome is persisted on {@code catalog_bundle_sync_status}
 * (consecutive-failure counter, structured status, error detail) and the
 * method returns normally - the scheduler must never crash out of an
 * exception, otherwise Spring's scheduler can stop firing for the app
 * lifetime.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "catalog.bundle.sync.enabled",
        havingValue = "true")
public class CatalogBundleSyncScheduler {

    private final CatalogBundleFetcher fetcher;
    private final CatalogBundleVerifier verifier;
    private final CatalogBundleApplier applier;
    private final CatalogBundleSyncStatusRepository syncStatusRepo;
    private final TrustedKeyRegistry trustedKeys;
    /** Trust-on-first-use bootstrap for a cloud-linked CE that has no pinned key yet. */
    private final CatalogBundleTrustBootstrap trustBootstrap;
    /**
     * Resolves THE active cloud-link credentials of this install. {@code ObjectProvider}
     * (not a hard dependency) so a misconfigured stack - {@code sync.enabled=true} without
     * the CE cloud-link beans ({@code marketplace.mode=remote}) - degrades to "not linked"
     * (skip) instead of failing to start. Present on a normal CE install.
     */
    private final ObjectProvider<CloudLlmRuntimeAccess> runtimeAccessProvider;

    @Value("${catalog.bundle.cloud-url:}")
    private String cloudUrl;

    /**
     * Scheduled + manual entry point. Both {@link org.springframework.scheduling.annotation.Scheduled}
     * firings and the admin "sync now" endpoint call this through the Spring
     * proxy - keeping the lock annotation here (rather than on a helper method)
     * is intentional: moving it to a method invoked via {@code this.x()} would
     * silently break AOP interception on the scheduled path, and every 15 min
     * all pods in a multi-pod CE deployment would race on
     * {@code deactivateAll() + save(active=true)} against the partial unique
     * index {@code idx_catalog_bundles_one_active}.
     *
     * <p>{@code lockAtLeastFor=PT30S} intentionally throttles manual
     * re-triggers so an admin clicking "sync now" repeatedly cannot DoS the
     * cloud.
     */
    @Scheduled(cron = "${catalog.bundle.sync.cron:0 */15 * * * *}")
    @SchedulerLock(name = "catalogBundleSync_tick",
                   lockAtMostFor = "PT5M",
                   lockAtLeastFor = "PT30S")
    public void tick() {
        try {
            syncOnce();
        } catch (Exception e) {
            // Belt-and-braces: every sub-step catches its own errors and
            // persists them, but if one slips, log it and keep the scheduler
            // alive.
            log.error("Catalog bundle sync failed unexpectedly", e);
            try {
                recordFailure("UNEXPECTED_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignored) {
                // Nothing we can do if even status persistence fails.
            }
        }
    }

    private void syncOnce() {
        // The bundle download is gated behind an active cloud link - catalog freshness is a
        // benefit of being connected, not a free anonymous fetch. Resolve THE install's active
        // link credentials FIRST: both the trust-on-first-use bootstrap and the gated download
        // require it. If this install isn't linked (or the CE cloud beans are absent), skip
        // without failing: no link, no updates. The credentials authenticate the download,
        // which the cloud independently re-checks (userOwnsActiveCeLink).
        CloudLlmRuntimeAccess runtimeAccess = runtimeAccessProvider.getIfAvailable();
        Optional<CloudLlmRuntimeCredentials> creds =
                runtimeAccess == null ? Optional.empty() : runtimeAccess.resolveActiveCloudRuntime();
        if (creds.isEmpty()) {
            log.debug("Catalog bundle sync: this CE install is not cloud-linked - skipping (no link, no updates)");
            recordFetchOnly("NOT_LINKED", "this CE install has no active cloud link");
            return;
        }

        // Trust-on-first-use: a cloud-linked CE with no operator-pinned key auto-pins the
        // linked cloud's published Ed25519 signing key so model-catalog updates flow without a
        // manual CATALOG_BUNDLE_TRUSTED_KEYS. Only bootstraps an EMPTY registry; an
        // operator-pinned key (or an earlier TOFU pin) is never overwritten. If the bootstrap
        // misses (no cloud signing key yet, network error), record TRUST_UNCONFIGURED and retry
        // next tick - we never proceed to apply an unverifiable bundle.
        if (!trustedKeys.hasKeys()) {
            CatalogBundleTrustBootstrap.Result boot = trustBootstrap.bootstrapTrust();
            if (!boot.pinned()) {
                log.warn("Catalog bundle sync: trust not configured and TOFU bootstrap failed ({}). " +
                        "Set catalog.bundle.trusted-keys or check the cloud signing-key endpoint. " +
                        "Skipping this tick.", boot.detail());
                recordFailure("TRUST_UNCONFIGURED", boot.detail());
                return;
            }
            log.info("Catalog bundle sync: TOFU bootstrapped trust - pinned cloud signing key '{}'", boot.keyId());
        }

        CatalogBundleFetcher.FetchResult fetched = fetcher.fetchLatest(creds.get());
        switch (fetched.status()) {
            case FETCHED -> {
                CatalogBundleVerifier.Result v = verifier.verify(fetched.bundle());
                if (!v.ok()) {
                    log.warn("Catalog bundle sync: verification failed ({}): {}",
                            v.status(), v.detail());
                    recordFailure(v.status().name(), v.detail());
                    return;
                }
                try {
                    CatalogBundleApplier.ApplyResult r =
                            applier.apply(fetched.bundle(), v.payloadBytes(), cloudUrl);
                    if (r.status() == CatalogBundleApplier.Status.APPLY_FAILED) {
                        recordFailure("APPLY_FAILED", r.detail());
                    }
                    // On APPLIED or ALREADY_APPLIED the applier has already written
                    // the sync-status row with OK + failures reset.
                } catch (Exception e) {
                    log.error("Catalog bundle apply threw unexpectedly", e);
                    recordFailure("APPLY_FAILED",
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            case NO_ACTIVE -> {
                // Cloud has no active bundle yet - not a failure. Still record
                // last_fetch_at so the UI can show "checked recently".
                recordFetchOnly("NO_ACTIVE", null);
            }
            case NOT_CONFIGURED -> {
                log.warn("Catalog bundle sync: cloud-url is empty - skipping tick");
                recordFailure("NOT_CONFIGURED", fetched.detail());
            }
            case HTTP_ERROR, NETWORK_ERROR -> {
                log.warn("Catalog bundle sync: fetch failed ({}): {}",
                        fetched.status(), fetched.detail());
                recordFailure(fetched.status().name(), fetched.detail());
            }
        }
    }

    // @Transactional intentionally omitted: single-save flow is covered by the
    // repository's default TX. Applying @Transactional here would be a no-op
    // because the call is self-invoked from syncOnce()/runLocked() and Spring
    // AOP does not intercept those - the annotation would silently lie.
    void recordFailure(String status, String detail) {
        CatalogBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus(status);
        row.setLastFetchError(detail);
        row.setConsecutiveFailures(row.getConsecutiveFailures() + 1);
        row.setUpdatedAt(now());
        syncStatusRepo.save(row);
    }

    void recordFetchOnly(String status, String detail) {
        CatalogBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus(status);
        row.setLastFetchError(detail);
        // Do not bump consecutiveFailures - this is an informational tick.
        row.setUpdatedAt(now());
        syncStatusRepo.save(row);
    }

    private CatalogBundleSyncStatusEntity loadOrInit() {
        return syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(CatalogBundleSyncStatusEntity::new);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
