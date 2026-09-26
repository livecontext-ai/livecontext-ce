package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.apimarketplace.common.scheduling.BundlePollBackoff;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CE-side periodic API-catalog sync: fetch latest signed bundle → verify
 * Ed25519 signature → apply to {@code catalog.*}. Gated on
 * {@code api-catalog.bundle.sync.enabled=true} so cloud instances never run it.
 * Mirrors {@code agent-service CatalogBundleSyncScheduler}.
 *
 * <p>Every non-OK outcome is persisted on {@code api_catalog_bundle_sync_status}
 * (consecutive-failure counter, structured status, error detail) and the method
 * returns normally - the scheduler must never crash out of an exception,
 * otherwise Spring's scheduler can stop firing for the app lifetime.
 *
 * <p><b>No ShedLock</b> (unlike the model-bundle scheduler): catalog-service
 * carries no ShedLock dependency and the {@code catalog} schema has no
 * shedlock table. The sync only runs on CE installs (single-pod monolith); if
 * two pods ever raced, the apply path is idempotent and the partial unique
 * index {@code idx_api_catalog_bundles_one_active} makes the losing activation
 * fail loudly rather than corrupt state.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "api-catalog.bundle.sync.enabled",
        havingValue = "true")
public class ApiCatalogBundleSyncScheduler {

    private final ApiCatalogBundleFetcher fetcher;
    /** Reads this install's applied bundle identity, payload-free. */
    private final ApiCatalogBundleService bundleService;
    private final ApiCatalogBundleVerifier verifier;
    private final ApiCatalogBundleApplier applier;
    private final ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    private final ApiCatalogTrustedKeyRegistry trustedKeys;
    /** Trust-on-first-use bootstrap for a cloud-pointed CE that has no pinned key yet. */
    private final ApiCatalogBundleTrustBootstrap trustBootstrap;

    @Value("${api-catalog.bundle.cloud-url:}")
    private String cloudUrl;

    /** Backoff level of the attempt in progress, set by {@link #beginAttempt()}. */
    private int attemptLevel;

    /**
     * Serialises the scheduled tick and a manual "sync now". This scheduler has no ShedLock (CE is
     * one process), and a manual sync runs on the request thread: both entry points use tryLock so
     * neither ever waits behind the other.
     */
    private final ReentrantLock runLock = new ReentrantLock();

    // The default is drawn per process rather than fixed on the quarter hour:
    // a wall-clock default makes the whole CE fleet download the payload in the
    // same second whenever a new bundle is published. An operator who pins the
    // property still gets exactly the expression they set. See PollSpread.
    //
    // One consequence to be honest about: the lock below no longer collapses
    // replicas that used to fire together, because with a per-process slot they
    // no longer do. It still serialises a scheduled tick against a manual "sync
    // now", and still stops two runs overlapping. A multi-replica install would
    // therefore poll once per replica per period; today CE runs a single
    // process, and the cloud does not run these pollers at all.
    //
    // Scheduled entry point: runs only when the backoff allows it. A failing install used to keep
    // this 15-minute cadence forever, i.e. a 32 MB download per quarter hour; see BundlePollBackoff.
    @Scheduled(cron = "${api-catalog.bundle.sync.cron:#{T(com.apimarketplace.common.scheduling.PollSpread).quarterHourlyCron()}}")
    public void tickIfDue() {
        if (!runLock.tryLock()) {
            log.debug("API catalog bundle sync: a sync is already running - skipping this tick");
            return;
        }
        try {
            Instant nextAttemptAt;
            try {
                nextAttemptAt = loadOrInit().getNextAttemptAt();
            } catch (Exception e) {
                // Same contract as the sync itself: a scheduled method never throws. Without the
                // status row there is nothing to record a sync into either, so skip this tick.
                log.warn("API catalog bundle sync: cannot read the backoff state, skipping this tick: {}", e.getMessage());
                return;
            }
            if (BundlePollBackoff.isDeferred(nextAttemptAt, Instant.now())) {
                log.debug("API catalog bundle sync: backing off until {} - skipping this tick", nextAttemptAt);
                return;
            }
            runSync();
        } finally {
            runLock.unlock();
        }
    }

    /**
     * Manual entry point ("sync now"): never deferred, but its outcome still moves the level.
     * It runs on the request thread, so when a sync is already in progress it returns at once
     * (the caller then reads the status row, which that sync is about to update) instead of
     * blocking the request for a whole apply and then downloading the bundle a second time.
     *
     * @return false when a sync was already running and this call did nothing
     */
    public boolean tick() {
        if (!runLock.tryLock()) {
            log.info("API catalog bundle sync: sync now ignored, a sync is already running");
            return false;
        }
        try {
            runSync();
            return true;
        } finally {
            runLock.unlock();
        }
    }

    /** Only ever called with {@link #runLock} held. */
    private void runSync() {
        attemptLevel = 0;
        try {
            syncOnce();
        } catch (Exception e) {
            // Belt-and-braces: every sub-step catches its own errors and
            // persists them, but if one slips, log it and keep the scheduler alive.
            log.error("API catalog bundle sync failed unexpectedly", e);
            try {
                recordFailure("UNEXPECTED_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignored) {
                // Nothing we can do if even status persistence fails.
            }
        }
    }

    private void syncOnce() {
        // Trust-on-first-use: a CE pointed at a cloud (api-catalog.bundle.cloud-url set) with no
        // operator-pinned key auto-pins the cloud's published Ed25519 signing key so API-catalog
        // updates flow without a manual CATALOG_BUNDLE_TRUSTED_KEYS. Only bootstraps an EMPTY
        // registry; an operator-pinned key (or an earlier TOFU pin) is never overwritten. The
        // bootstrap no-ops when no cloud URL is set, so an un-pointed CE still records
        // TRUST_UNCONFIGURED. We never proceed to apply an unverifiable bundle.
        if (!trustedKeys.hasKeys()) {
            ApiCatalogBundleTrustBootstrap.Result boot = trustBootstrap.bootstrapTrust();
            if (!boot.pinned()) {
                log.warn("API catalog bundle sync: trust not configured and TOFU bootstrap failed ({}). " +
                        "Set catalog.bundle.trusted-keys or check the cloud signing-key endpoint. " +
                        "Skipping this tick.", boot.detail());
                recordFailure("TRUST_UNCONFIGURED", boot.detail());
                return;
            }
            log.info("API catalog bundle sync: TOFU bootstrapped trust - pinned cloud signing key '{}'", boot.keyId());
        }

        // Send the checksum we already hold so an unchanged bundle comes back as
        // a bodiless 304. Read it through the payload-free projection: asking
        // "what do I have?" must not pull a stored payload into heap.
        // Only go conditional once this install can serve the price re-offer
        // locally. Until the active row carries its bundle's prices, fetch in
        // full: a 304 would leave nothing to re-offer and would quietly stop
        // pricing integrations whose provider key shows up later. That first
        // full fetch stores the prices, so this is a one-time cost per install.
        String knownChecksum = bundleService.getActiveBundleMetadata()
                .filter(m -> m.getPricesStored() != null && m.getPricesStored() == 1)
                .map(ApiCatalogBundleRepository.ActiveBundleMeta::getChecksum)
                .orElse(null);

        // The download starts here: count it now, so a crash or an Error thrown past runSync()
        // (an OutOfMemoryError while applying, the case that motivated this) is still a failure
        // the next scheduled attempt waits for. Nothing before this point downloads a bundle, so
        // nothing before it (trust bootstrap) may push the install into a long wait.
        beginAttempt();
        ApiCatalogBundleFetcher.FetchResult fetched = fetcher.fetchLatest(knownChecksum);
        switch (fetched.status()) {
            case FETCHED -> {
                ApiCatalogBundleVerifier.Result v = verifier.verify(fetched.bundle());
                if (!v.ok()) {
                    log.warn("API catalog bundle sync: verification failed ({}): {}",
                            v.status(), v.detail());
                    recordFailure(v.status().name(), v.detail());
                    return;
                }
                try {
                    ApiCatalogBundleApplier.ApplyResult r =
                            applier.apply(fetched.bundle(), v.payloadBytes(), cloudUrl);
                    if (r.status() == ApiCatalogBundleApplier.Status.APPLY_FAILED) {
                        recordFailure("APPLY_FAILED", r.detail());
                    } else if (r.status() == ApiCatalogBundleApplier.Status.APPLY_PARTIAL) {
                        // Not a success for the backoff: the next attempt downloads the whole
                        // bundle again, so a partial apply that keeps failing must slow down.
                        backOffAfterFailure(null);
                        // The applier already wrote the APPLY_PARTIAL status row
                        // (error detail + consecutive-failure bump) and did NOT
                        // record the bundle row - the next tick retries this
                        // version (idempotent UPSERT). No double bookkeeping here.
                        log.warn("API catalog bundle v{} partially applied - retrying next tick: {}",
                                r.version(), r.detail());
                    } else {
                        // On APPLIED or ALREADY_APPLIED the applier has already written
                        // the sync-status row with OK + failures reset.
                        clearBackoff();
                    }
                } catch (Exception e) {
                    log.error("API catalog bundle apply threw unexpectedly", e);
                    recordFailure("APPLY_FAILED",
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            case NOT_MODIFIED -> {
                // The cloud confirmed our bundle is still the active one, so
                // there is no catalog work to do. The price re-offer still runs,
                // from the copy stored when the bundle was applied - that is what
                // keeps a poll cheap WITHOUT deleting the behaviour that prices an
                // integration whose provider key arrived after the bundle did.
                // reofferStoredPrices writes the same OK status an ALREADY_APPLIED
                // does, so the operator UI shows a healthy sync.
                // Cleared FIRST: the cloud answered, so the download side succeeded. The re-offer
                // is purely local work; if it throws, runSync records that failure, and with no
                // attempt left armed it does not push a healthy install into a wait.
                clearBackoff();
                applier.reofferStoredPrices();
            }
            case NO_ACTIVE -> {
                // Cloud has no active bundle yet - not a failure. Still record
                // last_fetch_at so the UI can show "checked recently".
                recordFetchOnly("NO_ACTIVE", null);
                clearBackoff();
            }
            case NOT_CONFIGURED -> {
                log.warn("API catalog bundle sync: cloud-url is empty - skipping tick");
                // Nothing was downloaded: a configuration error must not leave a wait behind it.
                clearBackoff();
                recordFailure("NOT_CONFIGURED", fetched.detail());
            }
            case HTTP_ERROR, NETWORK_ERROR -> {
                log.warn("API catalog bundle sync: fetch failed ({}): {}",
                        fetched.status(), fetched.detail());
                recordFailure(fetched.status().name(), fetched.detail(), fetched.retryAfter());
            }
        }
    }

    /** Raise the backoff level and set the wait BEFORE any cloud call (see BundlePollBackoff). */
    private void beginAttempt() {
        int level = loadOrInit().getBackoffLevel() + 1;
        syncStatusRepo.updateBackoff(level, BundlePollBackoff.nextAttemptAt(level, null, Instant.now()));
        // Only once the wait is really armed: if that write failed, the fallback bookkeeping must
        // not try to re-arm a level that was never stored.
        attemptLevel = level;
    }

    private void clearBackoff() {
        attemptLevel = 0;
        syncStatusRepo.updateBackoff(0, null);
    }

    /** The level was raised when the attempt began; a Retry-After can only lengthen the wait. */
    private void backOffAfterFailure(Duration retryAfter) {
        // A failure before any download (no attempt begun: trust bootstrap, configuration) leaves
        // the backoff exactly as it was.
        if (attemptLevel <= 0) return;
        Instant next = BundlePollBackoff.nextAttemptAt(attemptLevel, retryAfter, Instant.now());
        syncStatusRepo.updateBackoff(attemptLevel, next);
        if (attemptLevel >= 2) {
            log.info("API catalog bundle sync: {} consecutive unsuccessful attempts, next scheduled attempt at {}",
                    attemptLevel, next);
        }
    }

    // @Transactional intentionally omitted: single-save flow is covered by the
    // repository's default TX, and the call is self-invoked from syncOnce() -
    // Spring AOP would not intercept it anyway (the annotation would lie).
    void recordFailure(String status, String detail) {
        recordFailure(status, detail, null);
    }

    void recordFailure(String status, String detail, Duration retryAfter) {
        ApiCatalogBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus(status);
        row.setLastFetchError(detail);
        row.setConsecutiveFailures(row.getConsecutiveFailures() + 1);
        row.setUpdatedAt(now());
        syncStatusRepo.save(row);
        backOffAfterFailure(retryAfter);
    }

    void recordFetchOnly(String status, String detail) {
        ApiCatalogBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus(status);
        row.setLastFetchError(detail);
        // Do not bump consecutiveFailures - this is an informational tick.
        row.setUpdatedAt(now());
        syncStatusRepo.save(row);
    }

    private ApiCatalogBundleSyncStatusEntity loadOrInit() {
        return syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(ApiCatalogBundleSyncStatusEntity::new);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
