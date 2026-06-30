package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.domain.CompletionRequest;

/**
 * Payload sent from CE to Cloud. Tool definitions are passed through so the
 * cloud model can request tool calls, but tool execution stays on CE.
 *
 * <p><b>Centralized billing (optional, backward-compatible).</b> When
 * {@code executionId} is non-null the cloud relay bills the CE execution as ONE
 * aggregated {@code CE_LLM_RELAY} ledger line (accrue→settle) instead of one line
 * per forwarded call. An un-upgraded CE install sends {@code null} (the 2-arg
 * convenience constructor) and keeps today's per-call billing - so the field
 * addition is fully backward-compatible.
 */
public record CloudLlmRelayRequest(
        String provider,
        CompletionRequest completionRequest,
        String executionId
) {

    /**
     * Legacy / per-call convenience constructor - no execution correlation, so the
     * relay bills per forwarded call exactly as before. Kept so existing call sites
     * (and old CE clients deserializing without the new field) stay valid.
     */
    public CloudLlmRelayRequest(String provider, CompletionRequest completionRequest) {
        this(provider, completionRequest, null);
    }
}
