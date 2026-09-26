package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.web.VersionInfo;
import com.apimarketplace.auth.web.version.CeReleaseController.LatestRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * CE-only poller that learns the latest published release from the cloud release
 * feed and feeds {@link VersionUpdateService}, so the Settings &gt; Information
 * card can tell a self-hosted user they are behind.
 *
 * <p>Gated to the embedded (CE) edition and to {@code ce.version-check.enabled}
 * (default on, opt-out for privacy). It never runs in cloud (keycloak mode). The
 * request carries the reported version ({@link VersionInfo#reportedVersion()}: the release tag
 * or {@code "dev"}) and, unless
 * {@code ce.version-check.send-install-id=false}, this install's anonymous id
 * (see {@link CeInstallIdProvider}), so live self-hosted installs can be counted.
 *
 * <p>Best-effort: a failed fetch is logged at debug and leaves the previously
 * known status untouched; the next run retries.
 */
@Component
@ConditionalOnExpression("'${auth.mode:keycloak}' == 'embedded' && '${ce.version-check.enabled:true}' == 'true'")
public class CeVersionCheckScheduler {

    private static final Logger log = LoggerFactory.getLogger(CeVersionCheckScheduler.class);

    private final VersionUpdateService versionUpdateService;
    private final ReleaseFeedClient feedClient;

    private final Supplier<String> reportedVersion;

    @Autowired
    public CeVersionCheckScheduler(VersionUpdateService versionUpdateService,
                                   ReleaseFeedClient feedClient) {
        this(versionUpdateService, feedClient, VersionInfo::reportedVersion);
    }

    /** Test seam: otherwise the reported version comes from the process environment. */
    CeVersionCheckScheduler(VersionUpdateService versionUpdateService,
                            ReleaseFeedClient feedClient,
                            Supplier<String> reportedVersion) {
        this.versionUpdateService = versionUpdateService;
        this.feedClient = feedClient;
        this.reportedVersion = reportedVersion;
    }

    /** Check once shortly after the app is ready, so the card is accurate without waiting a day. */
    @EventListener(ApplicationReadyEvent.class)
    public void checkOnStartup() {
        checkNow();
    }

    /**
     * Daily refresh, anchored on THIS install's startup rather than on a shared clock time.
     *
     * <p>It used to be a cron at 05:00 UTC, which meant every self-hosted install on earth called
     * the same cloud endpoint inside the same minute. That was already a poor load profile; once
     * the request began carrying an install id it became a correctness problem, because the
     * collector's per-minute burst budget then had to absorb the entire fleet in one window and
     * dropped whatever did not fit, silently and at DEBUG. Boot times are spread across the day by
     * nature, so a fixed delay spreads the fleet with them and no coordination is needed.
     *
     * <p>The cost is that the poll drifts relative to wall-clock time. For a daily check on whether
     * a newer release exists, that is not a property anyone depends on.
     */
    @Scheduled(
            initialDelayString = "${ce.version-check.interval:PT24H}",
            fixedDelayString = "${ce.version-check.interval:PT24H}")
    public void checkPeriodically() {
        checkNow();
    }

    /** Fetch the feed and update the held status. Best-effort: never throws. */
    public void checkNow() {
        // The REPORTED version, not the displayed one: a release tag or "dev". The fleet ledger
        // counts it, and a from-source build's Maven version (never bumped) would otherwise be
        // counted as a published release.
        String current = reportedVersion.get();
        try {
            LatestRelease body = feedClient.fetchLatest(current);
            // A 200 carrying no version counts as "no release", exactly like a null body.
            // Storing it would overwrite a good status with nulls and blank the update
            // banner, and an install cannot tell that apart from "you are up to date".
            // Any feed hiccup that answers 200-with-null must therefore be inert here.
            if (body == null || body.latestVersion() == null || body.latestVersion().isBlank()) {
                log.debug("CE version check: feed advertised no release (running={})", current);
                return;
            }
            versionUpdateService.update(new UpdateStatus(
                    body.latestVersion(),
                    body.releaseUrl(),
                    body.securityFix(),
                    body.publishedAt(),
                    Instant.now()));
            log.debug("CE version check: latest={} (running={})", body.latestVersion(), current);
        } catch (RuntimeException failure) {
            log.debug("CE version check failed - keeping previous status: {}", failure.getMessage());
        }
    }
}
