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

    private ShowcaseCaptureContract() {
    }
}
