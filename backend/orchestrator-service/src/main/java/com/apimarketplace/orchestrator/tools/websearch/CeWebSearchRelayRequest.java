package com.apimarketplace.orchestrator.tools.websearch;

/**
 * Wire format of the CE → cloud web-search relay call
 * ({@code POST /api/ce-websearch/search}).
 *
 * <p>Built CE-side by {@link CloudWebSearchRelayClient} and parsed cloud-side by
 * {@code CloudWebSearchRelayController} (same artifact, different runtime config).
 * {@code streamId}/{@code toolCallId} carry the CE chat identifiers so the cloud
 * bills with the same idempotency-safe sourceId scheme as a local search
 * ({@code web-search:CHAT:<streamId>:<toolCallId>:0}).
 */
public record CeWebSearchRelayRequest(
        String query,
        Integer maxResults,
        String timeRange,
        String streamId,
        String toolCallId
) {
}
