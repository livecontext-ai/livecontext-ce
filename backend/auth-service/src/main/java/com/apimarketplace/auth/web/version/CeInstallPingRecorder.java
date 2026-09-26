package com.apimarketplace.auth.web.version;

import com.apimarketplace.auth.repository.CeInstallPingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Records that a self-hosted install was seen on the public release feed.
 *
 * <p>Gated on {@code ce.installs.telemetry.enabled}, set only on the cloud deployment. It is
 * deliberately NOT gated on {@code auth.mode}: keycloak is also declared for self-hosted
 * enterprise, so that condition would arm fleet collection on a customer's own box, the same trap
 * {@link CeReleaseAnnounceController} documents.
 *
 * <p>This hangs off an endpoint that is public, unauthenticated and free to call, so it needs
 * bounds; it also has to admit an entire fleet, and a single bound cannot do both. The two are
 * separated by asking the database which case this is:
 * <ul>
 *   <li><b>A sighting of an install already in the ledger is an UPDATE.</b> It cannot grow the
 *       table, so it is not subject to the ceiling and is applied whatever the ledger's size.
 *       That is what makes the fleet measurable under abuse, and it is the property an earlier
 *       row-cap design could not have: {@code lastWritten} is a PER-POD cache, empty after every
 *       deploy, so anything keyed on it treats the whole real fleet as unknown at exactly the
 *       wrong moment. It does carry a budget of its own
 *       ({@code ce.installs.telemetry.max-refreshes-per-minute}, default 2000 per pod), because
 *       "cannot consume disk" is not "costs nothing": this statement runs for ids that are NOT
 *       in the ledger as well, which is precisely the forged case, so without a bound every
 *       request to a public endpoint buys a transaction and a connection. The default is about
 *       thirty times what a 100,000-install fleet needs, and more than three times what the
 *       gateway's 600-per-IP anonymous limit lets one source produce.</li>
 *   <li><b>Creating a row is an INSERT, and is the only thing an anonymous caller can use to consume
 *       disk.</b> Every bound sits here: a per-minute budget
 *       ({@code ce.installs.telemetry.max-writes-per-minute}, default 200 per pod) and a hard
 *       ceiling on the table ({@code ce.installs.telemetry.max-rows}, default 5,000,000). At the
 *       ceiling new installs stop being counted and that is said at WARN, because it is a
 *       degradation an operator has to know about rather than a steady state.</li>
 * </ul>
 *
 * <p>A per-install interval ({@code ce.installs.telemetry.min-interval-hours}, default 6, per pod)
 * short-circuits both, so a crash-looping install cannot turn its restarts into a write per boot.
 *
 * <p>The gateway already limits anonymous callers to 600 requests a minute per client IP
 * ({@code ApiKeyRateLimiterFilter}, a global filter with no path exemption), so one source cannot
 * reach the refresh budget on its own; these bounds are what holds when that limiter is spread
 * across many addresses, or when Redis is down and it fails open. What they guarantee is that the
 * database and the disk stay bounded, and that any degradation of the count is partial rather than
 * total: the fleet's own polls are spread across the day, so only the fraction arriving inside a
 * saturated minute is lost.
 *
 * <p>Never throws. Counting installs is worth strictly less than the fleet's update banner working,
 * so every failure here is swallowed and the feed answers as if telemetry did not exist.
 */
@Service
@ConditionalOnProperty(name = "ce.installs.telemetry.enabled", havingValue = "true", matchIfMissing = false)
public class CeInstallPingRecorder {

    private static final Logger log = LoggerFactory.getLogger(CeInstallPingRecorder.class);

    /** How long a failed write suppresses further attempts. */
    static final Duration FAILURE_BACKOFF = Duration.ofSeconds(30);
    /**
     * Cap on the per-install cache, so a flood of invented ids cannot grow it without bound. At
     * roughly 110 bytes an entry this is about 20 MB of heap per pod, which is the cost of the
     * interval guard.
     */
    static final int MAX_TRACKED_INSTALLS = 200_000;
    /** Longest version string stored; anything longer is junk rather than a version. */
    static final int MAX_VERSION_LENGTH = 64;
    /** How long the ledger size is trusted before being counted again. */
    static final Duration SIZE_TTL = Duration.ofMinutes(5);
    /** How often the "ledger is full" warning may repeat, so it stays readable. */
    static final Duration FULL_WARNING_INTERVAL = Duration.ofHours(1);

    /**
     * A published version: digits and dots, optionally with a pre-release or build suffix, and
     * optionally led by the {@code v} of its release tag.
     *
     * <p>The leading {@code v} is accepted because every released CE image reports its tag
     * verbatim ({@code APP_VERSION=v0.3.1}). Rejecting it recorded the ENTIRE released fleet as
     * {@code "dev"} from v0.2.14 to v0.3.1, while from-source builds reporting the bare Maven
     * version passed as if they were releases: the breakdown read exactly backwards. The
     * {@code v} is dropped before storing, so {@code v0.3.1} and {@code 0.3.1} share one bucket,
     * the same form the release feed advertises.
     *
     * <p>Anything else is stored as {@code "dev"}. A from-source build now reports {@code dev}
     * ({@code VersionInfo.reportedVersion}), but installs already in the field report
     * {@code dev-<short sha>}, and a fork's commit sha is a
     * near-unique string: keeping it would attach an identifying value to a stable install id for
     * the life of the row, and would scatter the version breakdown across one bucket per fork.
     */
    private static final Pattern PUBLISHED_VERSION = Pattern.compile("^[vV]?\\d+(\\.\\d+)*([-+][0-9A-Za-z.-]+)?$");
    static final String UNPUBLISHED_VERSION = "dev";

    private final CeInstallPingRepository repository;
    private final Clock clock;
    private final Duration minInterval;
    private final int maxWritesPerMinute;
    private final int maxRefreshesPerMinute;
    private final long maxRows;

    /** install id to when it was last written. Access is synchronized on the map itself. */
    private final Map<UUID, Instant> lastWritten;
    /** The insert budget's current window, replaced atomically so a roll cannot lose increments. */
    private final AtomicReference<Window> insertWindow = new AtomicReference<>(new Window(Instant.EPOCH, 0));
    /** The refresh budget's window. Separate, and far larger: it bounds load, not disk. */
    private final AtomicReference<Window> refreshWindow = new AtomicReference<>(new Window(Instant.EPOCH, 0));
    /**
     * Backoff for the refresh path, armed only by a refresh failure. Separate from the insert one
     * because they mean different things: a refresh failing says the database cannot serve this
     * feature at all, while a {@code count()} timeout or an insert conflict says nothing about
     * whether installs already counted can still be refreshed. Sharing one reference let a slow
     * {@code count()} on a large ledger blank the live fleet for 30 seconds per pod.
     */
    private final AtomicReference<Instant> refreshSuppressedUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> insertSuppressedUntil = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<LedgerSize> ledgerSize =
            new AtomicReference<>(new LedgerSize(Instant.EPOCH, 0L));
    private final AtomicReference<Instant> lastFullWarning = new AtomicReference<>(Instant.EPOCH);

    /** One minute of budget: the instant it opened and how many inserts it has admitted. */
    private record Window(Instant startedAt, int used) {
    }

    /** A row count and when it was taken. */
    private record LedgerSize(Instant takenAt, long rows) {
    }

    @Autowired
    public CeInstallPingRecorder(
            CeInstallPingRepository repository,
            @Value("${ce.installs.telemetry.min-interval-hours:6}") int minIntervalHours,
            @Value("${ce.installs.telemetry.max-writes-per-minute:200}") int maxWritesPerMinute,
            @Value("${ce.installs.telemetry.max-refreshes-per-minute:2000}") int maxRefreshesPerMinute,
            @Value("${ce.installs.telemetry.max-rows:5000000}") long maxRows) {
        this(repository, minIntervalHours, maxWritesPerMinute, maxRefreshesPerMinute, maxRows,
                Clock.systemUTC());
    }

    /**
     * Clock-injecting constructor, so the interval, the budget window and the backoff can be
     * exercised without a test that sleeps for them.
     *
     * <p>The public constructor above carries {@code @Autowired} deliberately: a second constructor
     * with none leaves the container unable to choose, and the failure surfaces as the CE monolith
     * refusing to start rather than as anything pointing here.
     */
    CeInstallPingRecorder(
            CeInstallPingRepository repository,
            int minIntervalHours,
            int maxWritesPerMinute,
            int maxRefreshesPerMinute,
            long maxRows,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.minInterval = Duration.ofHours(Math.max(0, minIntervalHours));
        this.maxWritesPerMinute = Math.max(1, maxWritesPerMinute);
        this.maxRefreshesPerMinute = Math.max(1, maxRefreshesPerMinute);
        this.maxRows = Math.max(1, maxRows);
        // accessOrder=true so a lookup counts as a use. Under a sustained flood the map turns over
        // regardless, and evicting a real install only costs it one extra UPDATE, but in the
        // ordinary case it keeps the ids that are actually being presented.
        this.lastWritten = Collections.synchronizedMap(new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Instant> eldest) {
                return size() > MAX_TRACKED_INSTALLS;
            }
        });
    }

    /**
     * Records one sighting, best-effort.
     *
     * @param installId the caller's anonymous install id; a null id is simply not counted
     * @param version   the caller's running version, or null
     */
    public void record(UUID installId, String version) {
        if (installId == null) {
            return;
        }
        Instant now = clock.instant();
        if (now.isBefore(refreshSuppressedUntil.get())) {
            return;
        }
        Instant previous = lastWritten.get(installId);
        if (previous != null && previous.plus(minInterval).isAfter(now)) {
            return;
        }
        if (outOfBudget(refreshWindow, maxRefreshesPerMinute, now, "refresh")) {
            return;
        }
        String stored = sanitizeVersion(version);
        try {
            // Ask the ledger, not the per-pod cache. An install already there is refreshed whatever
            // the ledger's size: that statement cannot consume disk, and gating it on the ceiling is
            // how a flood would otherwise stop the real fleet being counted.
            if (repository.refreshSighting(installId, stored) > 0) {
                // The stamp is written only after a successful call, so a failure leaves nothing to
                // undo and the SAME install is retried once the backoff lifts. Stamping first would
                // cost that install its whole interval, i.e. it would miss a daily poll.
                lastWritten.put(installId, now);
                return;
            }
        } catch (RuntimeException failure) {
            refreshSuppressedUntil.set(now.plus(FAILURE_BACKOFF));
            log.warn("CE install sighting not refreshed, pausing collection for {}s: {}",
                    FAILURE_BACKOFF.toSeconds(), failure.getMessage());
            return;
        }
        // From here on this install is NOT in the ledger, so everything below can only add a row.
        // Its own backoff, because a failure here says nothing about whether installs already
        // counted can still be refreshed.
        if (now.isBefore(insertSuppressedUntil.get())) {
            return;
        }
        try {
            if (ledgerFull(now) || outOfBudget(insertWindow, maxWritesPerMinute, now, "insert")) {
                return;
            }
            repository.insertSighting(installId, stored);
            lastWritten.put(installId, now);
        } catch (RuntimeException failure) {
            insertSuppressedUntil.set(now.plus(FAILURE_BACKOFF));
            log.warn("CE install not added to the ledger, pausing new installs for {}s: {}",
                    FAILURE_BACKOFF.toSeconds(), failure.getMessage());
        }
    }

    /**
     * @return true when the ledger is at its row ceiling and must not take on new installs
     */
    private boolean ledgerFull(Instant now) {
        LedgerSize known = ledgerSize.get();
        if (known.takenAt().plus(SIZE_TTL).isBefore(now)) {
            // Only ever counted on the path that creates rows, and at most once per TTL, so this
            // never runs for an install the ledger already knows.
            known = new LedgerSize(now, repository.count());
            ledgerSize.set(known);
        }
        if (known.rows() < maxRows) {
            return false;
        }
        // WARN, rate-limited: at the ceiling the fleet count stops growing, which looks exactly
        // like adoption stalling. That has to be visible rather than inferred from a flat graph.
        Instant lastWarned = lastFullWarning.get();
        if (lastWarned.plus(FULL_WARNING_INTERVAL).isBefore(now)
                && lastFullWarning.compareAndSet(lastWarned, now)) {
            log.warn("CE install ledger is at its {} row ceiling: installs already counted keep "
                            + "being refreshed, but NEW installs are no longer recorded. Raise "
                            + "ce.installs.telemetry.max-rows or investigate where the rows came from.",
                    maxRows);
        }
        return true;
    }

    /**
     * Fixed one-minute windows. The window and its counter are one immutable record swapped by a
     * single compare-and-set, because incrementing a counter that a concurrent roll then resets
     * discards those increments and lets the window admit more than its cap. That is not a rare
     * interleaving: it recurs on every roll.
     */
    private boolean outOfBudget(AtomicReference<Window> budget, int cap, Instant now, String what) {
        while (true) {
            Window current = budget.get();
            Window next = now.isAfter(current.startedAt().plusSeconds(60))
                    ? new Window(now, 1)
                    : new Window(current.startedAt(), current.used() + 1);
            if (budget.compareAndSet(current, next)) {
                if (next.used() > cap) {
                    log.debug("CE install ping {} budget reached for this minute - dropping", what);
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * A version is display data we did not authenticate: it is echoed back by the fleet read and
     * into log lines, so it is length-capped and anything that is not a published version number
     * becomes {@code "dev"}.
     * Blank becomes null, which the refresh reads as "keep what you already knew".
     */
    private static String sanitizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        // Validated exactly as received: no trimming of characters, no truncation, before the
        // decision. Cleaning first can only turn a value that should have been rejected into one
        // that passes - "0.2<control>13" would become the plausible-looking "0.213", and 64 nines
        // followed by a control character would become 64 nines - and both would then be stored as
        // if they were real versions. Anything too long to be a published version is not one.
        // strip(), not trim(): trim() removes every character <= U+0020, control characters
        // included, so it would itself be cleaning before validating. strip() removes Unicode
        // whitespace and leaves a control character where the validation can see it.
        String candidate = version.strip();
        boolean publishable = candidate.length() <= MAX_VERSION_LENGTH
                && PUBLISHED_VERSION.matcher(candidate).matches();
        if (!publishable) {
            return UNPUBLISHED_VERSION;
        }
        char first = candidate.charAt(0);
        return first == 'v' || first == 'V' ? candidate.substring(1) : candidate;
    }
}
