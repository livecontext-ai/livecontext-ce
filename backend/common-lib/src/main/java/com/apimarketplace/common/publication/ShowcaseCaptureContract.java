package com.apimarketplace.common.publication;

/**
 * The wire contract between the orchestrator's full-snapshot endpoint and publication-service.
 *
 * <p>The orchestrator answers a showcase capture whose epoch is not in the run with a 404 whose body
 * carries {@code {"error": EPOCH_NOT_FOUND, "epoch": N}}; publication-service turns exactly that body
 * into a 400 the publisher can act on. The code lives here, once, because renaming it on one side only
 * would not fail anything: the reader would silently stop matching and a wrong epoch would go back to
 * being a 500 "empty payload".
 */
public final class ShowcaseCaptureContract {

    /** Error code of the full-snapshot 404 when the requested epoch is not in the run. */
    public static final String EPOCH_NOT_FOUND = "EPOCH_NOT_FOUND";

    /**
     * LC-066: key of a showcase snapshot the orchestrator withheld. Its value names why
     * ({@link #WITHHELD_RESTRICTED_DATA}). Such a snapshot carries only the header keys
     * ({@code version}, {@code capturedAt}, {@code sourceRunId}): no run state, steps, renders or
     * files, so publication-service stores it as is and serves the "no preview" state.
     */
    public static final String WITHHELD_KEY = "showcaseWithheld";

    /** {@link #WITHHELD_KEY} value: the source run holds Gmail or Google Drive data (RESTRICTED). */
    public static final String WITHHELD_RESTRICTED_DATA = "RESTRICTED_DATA";

    /**
     * Key set to {@code true} on a full snapshot whose source run was checked and found not
     * RESTRICTED at capture time. A stored snapshot without it (and without {@link #WITHHELD_KEY})
     * predates the check, so publication-service verifies it before serving it.
     */
    public static final String RESTRICTION_CHECKED_KEY = "restrictionChecked";

    /**
     * Key of the orchestrator's run-restriction answer ({@code GET .../runs/{runId}/restricted}):
     * {@code true} when the run holds Gmail or Google Drive data. Present only when the run exists.
     */
    public static final String RUN_RESTRICTED_KEY = "restricted";

    /**
     * Key of the same answer saying whether the run still exists. {@code false} (and no
     * {@link #RUN_RESTRICTED_KEY}) when it was deleted: publication-service then judges a legacy
     * snapshot from its own content. A reply without this key (an orchestrator that predates it)
     * is no answer at all.
     */
    public static final String RUN_EXISTS_KEY = "runExists";

    /**
     * Key of the same answer giving, for a restricted run, when its FIRST restricted payload was
     * written (ISO-8601 instant). Absent when unknown: an orchestrator that predates it, or a run
     * marked restricted with no restricted row written yet. publication-service judges a legacy
     * snapshot captured before that moment clean, and leaves it unverified (retried later, nothing
     * persisted) while the moment is unknown.
     */
    public static final String FIRST_RESTRICTED_AT_KEY = "firstRestrictedAt";

    private ShowcaseCaptureContract() {
    }
}
