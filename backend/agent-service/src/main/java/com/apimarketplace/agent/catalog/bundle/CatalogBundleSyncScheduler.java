package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleRepository;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import com.apimarketplace.common.scheduling.BundlePollBackoff;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 *
 * <p><b>Backoff.</b> The scheduled poll and the startup sync go through
 * {@link #tickIfDue()}, which stays quiet until the row's {@code next_attempt_at};
 * a manual "sync now" calls {@link #tick()} and is never deferred. Each attempt
 * raises the backoff level BEFORE it talks to the cloud and only a completed
 * success lowers it, so an attempt that crashed still slows the next one down.
 * See {@link BundlePollBackoff}.
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
    /** Source of the checksum this install holds, sent as If-None-Match. */
    private final CatalogBundleRepository bundleRepo;
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
     * Backoff level of the attempt in progress, set by {@link #beginAttempt()}. Only read by the
     * failure bookkeeping of that same attempt, which runs on the same thread under the lock.
     */
    private int attemptLevel;

    /**
     * Scheduled + startup entry point: runs only when the backoff allows it.
     *
     * <p>Both this and {@link #tick()} carry the SAME lock, and both are only ever called
     * through the Spring proxy, so a scheduled firing, the startup sync and the admin
     * "sync now" still serialise against each other. Neither calls the other: a nested proxied
     * call would try to take the lock it already holds and silently skip.
     *
     * <p>{@code lockAtLeastFor=PT30S} intentionally throttles manual
     * re-triggers so an admin clicking "sync now" repeatedly cannot DoS the
     * cloud.
     *
     * <p>A firing that the backoff defers still holds the lock for {@code lockAtLeastFor} (30 s):
     * a "sync now" pressed inside those seconds finds the lock taken and is skipped. The admin
     * endpoint runs it asynchronously and the next poll of the status shows nothing new; pressing
     * again after 30 s works. Kept rather than splitting the entry point, because the lock is what
     * serialises every path that writes the active bundle.
     */
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
    @Scheduled(cron = "${catalog.bundle.sync.cron:#{T(com.apimarketplace.common.scheduling.PollSpread).quarterHourlyCron()}}")
    @SchedulerLock(name = "catalogBundleSync_tick",
                   lockAtMostFor = "PT5M",
                   lockAtLeastFor = "PT30S")
    public void tickIfDue() {
        Instant nextAttemptAt;
        try {
            nextAttemptAt = loadOrInit().getNextAttemptAt();
        } catch (Exception e) {
            // Same contract as the sync itself: a scheduled method never throws. Without the
            // status row there is nothing to record a sync into either, so skip this tick.
            log.warn("Catalog bundle sync: cannot read the backoff state, skipping this tick: {}", e.getMessage());
            return;
        }
        if (BundlePollBackoff.isDeferred(nextAttemptAt, Instant.now())) {
            log.debug("Catalog bundle sync: backing off until {} - skipping this tick", nextAttemptAt);
            return;
        }
        runSync();
    }

    /**
     * Manual entry point ("sync now"): runs whatever the backoff says, since the operator asked
     * for it explicitly. Its outcome still moves the backoff level.
     */
    @SchedulerLock(name = "catalogBundleSync_tick",
                   lockAtMostFor = "PT5M",
                   lockAtLeastFor = "PT30S")
    public void tick() {
        runSync();
    }

    private void runSync() {
        attemptLevel = 0;
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

        // Send the checksum this install already holds, so an unchanged bundle is a bodiless 304.
        String knownChecksum = bundleRepo.findActiveChecksum().orElse(null);
        // The download starts here: count it now, so a crash or an Error thrown past runSync()
        // is still a failure the next attempt waits for. Nothing before this point (link, trust)
        // downloads a bundle, so nothing before it may push the install into a long wait.
        beginAttempt();
        CatalogBundleFetcher.FetchResult fetched = fetcher.fetchLatest(creds.get(), knownChecksum);
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
                    } else {
                        // On APPLIED or ALREADY_APPLIED the applier has already written
                        // the sync-status row with OK + failures reset.
                        clearBackoff();
                    }
                } catch (Exception e) {
                    log.error("Catalog bundle apply threw unexpectedly", e);
                    recordFailure("APPLY_FAILED",
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            case NOT_MODIFIED -> {
                // The cloud confirmed the bundle applied here is still the active one: a
                // successful sync with nothing to do.
                recordNotModified();
                clearBackoff();
            }
            case NO_ACTIVE -> {
                // Cloud has no active bundle yet - not a failure. Still record
                // last_fetch_at so the UI can show "checked recently".
                recordFetchOnly("NO_ACTIVE", null);
                clearBackoff();
            }
            case NOT_CONFIGURED -> {
                log.warn("Catalog bundle sync: cloud-url is empty - skipping tick");
                // Nothing was downloaded: a configuration error must not leave a wait behind it,
                // or the fixed install would stay silent for hours after the operator's restart.
                clearBackoff();
                recordFailure("NOT_CONFIGURED", fetched.detail());
            }
            case HTTP_ERROR, NETWORK_ERROR -> {
                log.warn("Catalog bundle sync: fetch failed ({}): {}",
                        fetched.status(), fetched.detail());
                recordFailure(fetched.status().name(), fetched.detail(), fetched.retryAfter());
            }
        }
    }

    /** Raise the backoff level and set the wait BEFORE any cloud call (see the class note). */
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

    // @Transactional intentionally omitted: single-save flow is covered by the
    // repository's default TX. Applying @Transactional here would be a no-op
    // because the call is self-invoked from syncOnce()/runLocked() and Spring
    // AOP does not intercept those - the annotation would silently lie.
    void recordFailure(String status, String detail) {
        recordFailure(status, detail, null);
    }

    void recordFailure(String status, String detail, Duration retryAfter) {
        CatalogBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus(status);
        row.setLastFetchError(detail);
        row.setConsecutiveFailures(row.getConsecutiveFailures() + 1);
        row.setUpdatedAt(now());
        syncStatusRepo.save(row);
        backOffAfterFailure(retryAfter);
    }

    /**
     * Re-arm the wait for a failed DOWNLOAD attempt: the level was raised when it began, and a
     * Retry-After can only lengthen the wait. A failure before any download (no attempt begun:
     * trust bootstrap, configuration) leaves the backoff exactly as it was.
     */
    private void backOffAfterFailure(Duration retryAfter) {
        if (attemptLevel <= 0) return;
        Instant next = BundlePollBackoff.nextAttemptAt(attemptLevel, retryAfter, Instant.now());
        syncStatusRepo.updateBackoff(attemptLevel, next);
        if (attemptLevel >= 2) {
            log.info("Catalog bundle sync: {} consecutive unsuccessful attempts, next scheduled attempt at {}",
                    attemptLevel, next);
        }
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

    /** A 304: the same healthy status an ALREADY_APPLIED writes, without re-downloading. */
    void recordNotModified() {
        CatalogBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus("OK");
        row.setLastFetchError(null);
        row.setConsecutiveFailures(0);
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
