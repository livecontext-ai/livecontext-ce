package com.apimarketplace.orchestrator.tools.websearch;

import java.util.Map;

/**
 * Wire format of the CE → cloud browser-agent relay call
 * ({@code POST /api/ce-websearch/agent_browse}).
 *
 * <p>Sibling of {@link CeWebSearchRelayRequest} (search) - built CE-side by
 * {@link CloudBrowserAgentRelayClient} and parsed cloud-side by
 * {@code CloudWebSearchRelayController} (same artifact, different runtime config).
 * The cloud reconstructs the {@code agent_browse} parameter map from these fields
 * and runs its local {@link BrowserAgentModule}, which owns the whole browser
 * stack (Chromium + per-step LLM), bills the linked cloud account, and mints the
 * cloud-hosted CDP live-view URL/token in its response.
 *
 * <ul>
 *   <li>{@code task} - required natural-language goal for the browser agent.</li>
 *   <li>{@code startUrl} - optional starting URL.</li>
 *   <li>{@code llm} - optional {@code {provider, model, ...}} block; omit (or omit
 *       provider/model) to let the cloud substitute its platform default.</li>
 *   <li>{@code maxSteps} - optional override of the runner's default step budget.</li>
 *   <li>{@code options} - optional pass-through for the remaining {@code agent_browse}
 *       parameters the runner honours: {@code expected_output_schema},
 *       {@code interaction_mode}, {@code domain_allowlist}, {@code domain_denylist},
 *       {@code screenshot_policy}, {@code session}. Mirrors the allowlist
 *       {@code BrowserAgentModule.buildJobParameters} forwards to the runner.</li>
 *   <li>{@code streamId}/{@code toolCallId} - the CE chat/run identifiers. The cloud
 *       threads them into the browser-agent context so its
 *       {@code CdpTokenIssuer}-minted token carries the matching {@code rid}/{@code nid}
 *       claims and the CE frontend can route CDP reconnect/refresh. Unlike the search
 *       relay these never form a billing dedup key (browser-agent billing goes through
 *       observability with a server-generated source), so threading them is replay-safe.</li>
 * </ul>
 */
public record CeBrowseRelayRequest(
        String task,
        String startUrl,
        Map<String, Object> llm,
        Integer maxSteps,
        Map<String, Object> options,
        String streamId,
        String toolCallId
) {
}
