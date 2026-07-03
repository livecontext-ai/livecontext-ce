package com.apimarketplace.orchestrator.tools.websearch;

/**
 * Wire format of the CE → cloud browser-agent SESSION-CONTROL relay call
 * ({@code POST /api/ce-websearch/agent/sessions/{sessionId}/{action}} where
 * {@code action} is one of {@code status} / {@code intervene} / {@code abort}
 * / {@code screenshot}).
 *
 * <p>These operate on an already-running cloud browser session by id and return
 * immediately - they do NOT submit a new job and are not billed. {@code hint} is
 * only meaningful for {@code intervene} (the guidance text injected into the
 * running session); it is null/ignored for the other verbs.
 */
public record CeBrowseControlRequest(
        String sessionId,
        String hint
) {
}
