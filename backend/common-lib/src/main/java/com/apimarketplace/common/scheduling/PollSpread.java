package com.apimarketplace.common.scheduling;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds a cron expression that puts one process at its own moment inside a
 * shared period, so a fleet of pollers stops arriving together.
 *
 * <p><b>The problem.</b> The CE bundle pollers default to {@code 0 &#42;/15 &#42; &#42; &#42; &#42;},
 * a wall-clock cron, so every install in the fleet issues its request in the
 * same second of the same minute. Measured on the cloud: three installs polling
 * together added ~339 MiB to catalog-service's heap within one minute and
 * produced a recurring latency spike, while the endpoint sat idle for the rest
 * of the quarter hour. The cost is not that installs poll, it is that they poll
 * in phase, and it grows with the fleet. Conditional requests make the ordinary
 * tick cheap, but they do not help on the tick that matters: when a new bundle
 * is published every install's validator stops matching at once, and the whole
 * fleet downloads the payload in the same second.
 *
 * <p><b>Why the expression and not a delay.</b> Two alternatives were on the
 * table. Sleeping inside the task is the wrong one outright: these services run
 * on Spring's default scheduler pool of ONE thread, so the wait would block
 * every other scheduled task in the process. The serious one is a boot-anchored
 * {@code fixedDelayString} + {@code initialDelayString}, which is how
 * {@code CeVersionCheckScheduler} already spreads the same fleet for its daily
 * release check - start times differ, so the polls do too. It was not taken here
 * because these three pollers expose their schedule as a documented cron
 * property ({@code CATALOG_BUNDLE_SYNC_CRON} and its siblings), and a fixed
 * delay cannot honour an expression an operator has pinned. Moving the offset
 * into the DEFAULT expression keeps that contract and costs nothing at runtime.
 *
 * <p><b>What it does not try to be.</b> The offset is drawn per process, so a
 * restart redraws it. That is deliberate: an install's exact slot does not need
 * to survive a restart for the spread to work, and deriving it from something
 * stable in the configuration would give every install the same slot again. Two
 * installs can draw the same one; that is a coincidence between two of them, not
 * the fleet-wide alignment this replaces.
 *
 * <p>This covers the recurring tick only. The pollers also sync once at startup,
 * which is not spread, so a fleet restarted together - a coordinated upgrade,
 * say - still arrives together that one time.
 *
 * <p>Callers reach this as the DEFAULT of the schedule property, never as an
 * override: an operator who pinned {@code CATALOG_BUNDLE_SYNC_CRON} to an exact
 * expression still gets exactly that.
 */
public final class PollSpread {

    /** Minutes in the period these pollers share. */
    private static final int QUARTER_HOUR_MINUTES = 15;

    private static final int SECONDS_PER_MINUTE = 60;

    private PollSpread() {
    }

    /**
     * A cron firing every 15 minutes at a second and minute-offset drawn for
     * this process: {@code "<second> <offset>/15 * * * *"}.
     *
     * <p>900 distinct slots, one per second of the period, so the expected gap
     * between two installs grows with the fleet instead of every install
     * landing on the same one.
     */
    public static String quarterHourlyCron() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int second = random.nextInt(SECONDS_PER_MINUTE);
        int minuteOffset = random.nextInt(QUARTER_HOUR_MINUTES);
        return second + " " + minuteOffset + "/" + QUARTER_HOUR_MINUTES + " * * * *";
    }
}
