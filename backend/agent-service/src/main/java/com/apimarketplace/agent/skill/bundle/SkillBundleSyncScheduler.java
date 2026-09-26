package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleTrustBootstrap;
import com.apimarketplace.agent.catalog.bundle.TrustedKeyRegistry;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.SkillBundleRepository;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
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
 * CE-side periodic sync: fetch latest signed skill bundle -> verify signature -> apply to
 * {@code agent.skills}. Gated on {@code skill.bundle.sync.enabled=true} so cloud instances
 * never run it. Sibling of
 * {@code com.apimarketplace.agent.catalog.bundle.CatalogBundleSyncScheduler}.
 *
 * <p>Every non-OK outcome is persisted on {@code skill_bundle_sync_status} and the method
 * returns normally - the scheduler must never crash out of an exception.
 *
 * <p><b>Backoff.</b> The scheduled poll and the startup sync go through {@link #tickIfDue()},
 * which stays quiet until the row's {@code next_attempt_at}; a manual "sync now" calls
 * {@link #tick()} and is never deferred. Each attempt raises the backoff level BEFORE it talks
 * to the cloud and only a completed success lowers it. See {@link BundlePollBackoff}.
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
    /** Source of the checksum this install holds, sent as If-None-Match. */
    private final SkillBundleRepository bundleRepo;
    /** Shared, runtime-mutable trust root - the SAME registry the model-catalog bundle TOFU-pins into. */
    private final TrustedKeyRegistry trustedKeys;
    /** TOFU bootstrap: pins the cloud signing key on an empty registry (no-op once any path pins it). */
    private final CatalogBundleTrustBootstrap trustBootstrap;
    /**
     * Resolves THE active cloud-link credentials of this install. {@code ObjectProvider} so
     * a misconfigured stack (sync enabled without the CE cloud-link beans) degrades to "not
     * linked" (skip) instead of failing to start.
     */
    private final ObjectProvider<CloudLlmRuntimeAccess> runtimeAccessProvider;

    @Value("${skill.bundle.cloud-url:}")
    private String cloudUrl;

    /** Backoff level of the attempt in progress, set by {@link #beginAttempt()}. */
    private int attemptLevel;

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
    // Scheduled + startup entry point: runs only when the backoff allows it. It and tick()
    // carry the SAME lock and are only called through the Spring proxy; neither calls the
    // other, since a nested proxied call would find its own lock held and silently skip.
    // A firing the backoff defers still holds the lock for lockAtLeastFor (30 s), so a "sync now"
    // pressed inside those seconds is skipped; pressing again after 30 s works.
    @Scheduled(cron = "${skill.bundle.sync.cron:#{T(com.apimarketplace.common.scheduling.PollSpread).quarterHourlyCron()}}")
    @SchedulerLock(name = "skillBundleSync_tick",
                   lockAtMostFor = "PT5M",
                   lockAtLeastFor = "PT30S")
    public void tickIfDue() {
        Instant nextAttemptAt;
        try {
            nextAttemptAt = loadOrInit().getNextAttemptAt();
        } catch (Exception e) {
            // Same contract as the sync itself: a scheduled method never throws. Without the
            // status row there is nothing to record a sync into either, so skip this tick.
            log.warn("Skill bundle sync: cannot read the backoff state, skipping this tick: {}", e.getMessage());
            return;
        }
        if (BundlePollBackoff.isDeferred(nextAttemptAt, Instant.now())) {
            log.debug("Skill bundle sync: backing off until {} - skipping this tick", nextAttemptAt);
            return;
        }
        runSync();
    }

    /** Manual entry point ("sync now"): never deferred, but its outcome still moves the level. */
    @SchedulerLock(name = "skillBundleSync_tick",
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
            log.error("Skill bundle sync failed unexpectedly", e);
            try {
                recordFailure("UNEXPECTED_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Exception ignored) {
                // Nothing we can do if even status persistence fails.
            }
        }
    }

    private void syncOnce() {
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

        // Trust: verify against the SHARED registry and TOFU-bootstrap it here too (mirroring the
        // model-catalog scheduler). A linked CE with an empty catalog.bundle.trusted-keys property
        // trust-on-first-use pins the cloud signing key (the ONE Ed25519 key that signs model,
        // API-catalog AND skill bundles); once either path pins it, both verify. Replaces the old
        // gate that read a frozen empty snapshot and left skill sync permanently TRUST_UNCONFIGURED.
        if (!trustedKeys.hasKeys()) {
            CatalogBundleTrustBootstrap.Result boot = trustBootstrap.bootstrapTrust();
            // A sibling scheduler (model-catalog) may TOFU-pin the SAME shared key CONCURRENTLY at
            // startup; our bootstrap then returns not-pinned ("already present"), but trust IS now
            // configured. Only fail when the key is GENUINELY still missing (re-check the shared
            // registry) - otherwise proceed. This kills the transient boot-time TRUST_UNCONFIGURED
            // that the losing racer used to record until the next tick self-healed it.
            if (!boot.pinned() && !trustedKeys.hasKeys()) {
                recordFailure("TRUST_UNCONFIGURED", boot.detail());
                return;
            }
            if (boot.pinned()) {
                log.info("Skill bundle sync: TOFU bootstrapped trust - pinned cloud signing key '{}'", boot.keyId());
            }
        }

        // Send the checksum this install already holds, so an unchanged bundle is a bodiless 304.
        String knownChecksum = bundleRepo.findActiveChecksum().orElse(null);
        // The download starts here: count it now, so a crash or an Error thrown past runSync()
        // is still a failure the next attempt waits for. Nothing before this point (link, trust)
        // downloads a bundle, so nothing before it may push the install into a long wait.
        beginAttempt();
        SkillBundleFetcher.FetchResult fetched = fetcher.fetchLatest(creds.get(), knownChecksum);
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
                    } else {
                        // On APPLIED / ALREADY_APPLIED the applier already wrote OK + reset failures.
                        clearBackoff();
                    }
                } catch (Exception e) {
                    log.error("Skill bundle apply threw unexpectedly", e);
                    recordFailure("APPLY_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            case NOT_MODIFIED -> {
                // The bundle applied here is still the cloud's active one: success, nothing to do.
                recordNotModified();
                clearBackoff();
            }
            case NO_ACTIVE -> {
                recordFetchOnly("NO_ACTIVE", null);
                clearBackoff();
            }
            case NOT_CONFIGURED -> {
                log.warn("Skill bundle sync: cloud-url is empty - skipping tick");
                // Nothing was downloaded: a configuration error must not leave a wait behind it.
                clearBackoff();
                recordFailure("NOT_CONFIGURED", fetched.detail());
            }
            case HTTP_ERROR, NETWORK_ERROR -> {
                log.warn("Skill bundle sync: fetch failed ({}): {}", fetched.status(), fetched.detail());
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

    void recordFailure(String status, String detail) {
        recordFailure(status, detail, null);
    }

    void recordFailure(String status, String detail, Duration retryAfter) {
        SkillBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus(status);
        row.setLastFetchError(detail);
        row.setConsecutiveFailures(row.getConsecutiveFailures() + 1);
        row.setUpdatedAt(now());
        syncStatusRepo.save(row);
        backOffAfterFailure(retryAfter);
    }

    /**
     * Re-arm the wait for a failed DOWNLOAD attempt (level raised when it began; Retry-After only
     * lengthens). A failure before any download leaves the backoff exactly as it was.
     */
    private void backOffAfterFailure(Duration retryAfter) {
        if (attemptLevel <= 0) return;
        Instant next = BundlePollBackoff.nextAttemptAt(attemptLevel, retryAfter, Instant.now());
        syncStatusRepo.updateBackoff(attemptLevel, next);
        if (attemptLevel >= 2) {
            log.info("Skill bundle sync: {} consecutive unsuccessful attempts, next scheduled attempt at {}",
                    attemptLevel, next);
        }
    }

    /** A 304: the same healthy status an ALREADY_APPLIED writes, without re-downloading. */
    void recordNotModified() {
        SkillBundleSyncStatusEntity row = loadOrInit();
        row.setLastFetchAt(now());
        row.setLastFetchStatus("OK");
        row.setLastFetchError(null);
        row.setConsecutiveFailures(0);
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
