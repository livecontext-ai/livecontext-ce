package com.apimarketplace.agent.bridge;

import com.apimarketplace.agent.provider.LLMProviderException;

/**
 * Thrown when a user is denied access to a CLI bridge provider
 * (disabled, not admin, not in allowlist, quota exhausted, …).
 *
 * <p>Not retryable. Callers convert this to a 403 (access issue) or 429
 * (quota) at the API boundary based on {@link #getReason()}.
 */
public class BridgeAccessDeniedException extends LLMProviderException {

    private final String reason;
    private final Integer remainingRequestsToday;

    public BridgeAccessDeniedException(String bridgeProvider, String reason) {
        this(bridgeProvider, reason, null);
    }

    public BridgeAccessDeniedException(String bridgeProvider, String reason, Integer remainingRequestsToday) {
        super(bridgeProvider,
                "Bridge access denied for " + bridgeProvider + ": " + reason,
                reason,
                false);
        this.reason = reason;
        this.remainingRequestsToday = remainingRequestsToday;
    }

    public String getReason() {
        return reason;
    }

    public Integer getRemainingRequestsToday() {
        return remainingRequestsToday;
    }

    /**
     * True iff the denial is specifically a quota exhaustion (convert to HTTP 429
     * at the API boundary; everything else stays 403).
     */
    public boolean isQuotaExhausted() {
        return BridgeAccessDecision.REASON_QUOTA_EXHAUSTED.equals(reason);
    }
}
