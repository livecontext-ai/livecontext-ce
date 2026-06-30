package com.apimarketplace.orchestrator.tools.websearch;

/**
 * Single source of truth for the {@code wss://websearch-host/cdp/{sessionId}}
 * URL the frontend uses to upgrade into the CDP WebSocket bridge.
 *
 * <p>Both the initial {@code agent_browse} response (built in
 * {@link BrowserAgentModule}) and the JWT refresh response (built in
 * {@code BrowserAgentTakeoverController}) need to emit the same URL -
 * extracting the helper here means a future scheme change (e.g. moving
 * the {@code /cdp/} prefix or adding a port suffix) lands in one place
 * instead of two that can drift.
 */
public final class CdpUrls {

    private CdpUrls() {}

    /**
     * Convert the configured websearch HTTP base URL into the CDP WS URL
     * for a given session.
     *
     * <p>The websearch service URL is configured as http(s)://… for
     * regular REST calls; the CDP bridge upgrades the scheme to ws(s)://.
     * Returns {@code null} if {@code base} is null/blank so callers can
     * gate the response field - a missing CDP URL is a soft failure
     * (the panel renders a static fallback rather than the live view).
     *
     * @param base       configured websearch service URL (e.g.
     *                   {@code https://websearch-host.example.com}).
     *                   May be {@code null} or blank.
     * @param sessionId  browser-agent session id (becomes the URL path
     *                   segment after {@code /cdp/}).
     * @return {@code wss://…/cdp/{sessionId}} (or {@code ws://} for HTTP
     *         bases), or {@code null} if base is missing.
     */
    public static String buildWsUrl(String base, String sessionId) {
        if (base == null || base.isBlank()) {
            return null;
        }
        // Strip trailing slash so a configured base ending with `/`
        // doesn't produce `wss://host//cdp/{sid}`. Mirrors the
        // explicit handling in BrowserAgentTakeoverController#pushRunnerResume.
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        if (normalized.startsWith("https://")) {
            return "wss://" + normalized.substring("https://".length()) + "/cdp/" + sessionId;
        }
        if (normalized.startsWith("http://")) {
            return "ws://" + normalized.substring("http://".length()) + "/cdp/" + sessionId;
        }
        return normalized + "/cdp/" + sessionId;
    }
}
