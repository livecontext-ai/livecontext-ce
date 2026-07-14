package com.apimarketplace.orchestrator.trigger;

/**
 * Thrown when an anonymous public chat/form share link exceeds its per-link or per-owner
 * daily invocation cap. Public controllers map it to HTTP 429 (Too Many Requests).
 */
public class ShareInvocationLimitExceededException extends RuntimeException {
    public ShareInvocationLimitExceededException(String message) {
        super(message);
    }
}
