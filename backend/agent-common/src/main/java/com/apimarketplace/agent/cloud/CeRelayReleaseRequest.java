package com.apimarketplace.agent.cloud;

/**
 * Releases a centralized CE LLM relay execution that incurred NO billable usage - e.g. the agent
 * loop ended without any forwarded completion, or was cancelled before the first call. Drops the
 * cloud accrual so the reaper never settles a phantom zero-cost row. {@code reason} is for audit
 * logging only.
 */
public record CeRelayReleaseRequest(
        String executionId,
        String reason
) {
}
