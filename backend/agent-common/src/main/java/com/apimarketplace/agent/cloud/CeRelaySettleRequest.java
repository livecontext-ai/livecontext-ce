package com.apimarketplace.agent.cloud;

/**
 * Terminal settle for a centralized CE LLM relay execution. Sent by CE once its agent loop ends,
 * carrying only the correlation {@code executionId}: the cloud settles ONE {@code CE_LLM_RELAY}
 * ledger line from the usage it accrued across the relayed turns (CE need not re-send the
 * aggregate). Idempotent on {@code executionId}, so a retry - or a race with the crash-recovery
 * reaper - never double-bills.
 */
public record CeRelaySettleRequest(
        String executionId
) {
}
