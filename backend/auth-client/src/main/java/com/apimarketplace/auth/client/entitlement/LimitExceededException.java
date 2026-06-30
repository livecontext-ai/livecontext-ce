package com.apimarketplace.auth.client.entitlement;

/**
 * Thrown by {@code EntitlementGuard} when a user attempts to create a resource
 * past the limit configured for their current plan. Carries a stable
 * {@link LimitExceededError} payload that is mapped to HTTP 409 by the global
 * exception handler and surfaced to the LLM agent verbatim.
 */
public class LimitExceededException extends RuntimeException {

    private final LimitExceededError payload;

    public LimitExceededException(LimitExceededError payload) {
        super(buildMessage(payload));
        this.payload = payload;
    }

    public LimitExceededError payload() {
        return payload;
    }

    /**
     * Human-readable message explicit enough for an LLM to understand and
     * stop retrying. Used as the exception's {@link #getMessage()} so it shows
     * up in stack traces and tool error strings.
     */
    private static String buildMessage(LimitExceededError p) {
        return String.format(
                "LIMIT REACHED: Your %s plan allows max %d %s%s (currently %d/%d). " +
                "Tell the user to upgrade their plan or delete an existing %s. " +
                "DO NOT RETRY this operation.",
                p.planCode(),
                p.limit(),
                p.resourceType().name().toLowerCase(),
                p.limit() == 1 ? "" : "s",
                p.currentCount(),
                p.limit(),
                p.resourceType().name().toLowerCase()
        );
    }
}
