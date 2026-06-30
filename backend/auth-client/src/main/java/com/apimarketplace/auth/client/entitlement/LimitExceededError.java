package com.apimarketplace.auth.client.entitlement;

/**
 * Stable JSON contract returned in HTTP 409 responses when a user tries to
 * create a resource beyond their plan's allowed count. Frontend and the LLM
 * agent both rely on this exact shape - do not rename fields.
 *
 * <p>The {@code error} field is always {@value #ERROR_CODE} for safe matching.
 */
public record LimitExceededError(
        String error,
        ResourceType resourceType,
        String planCode,
        long currentCount,
        int limit,
        String upgradeHint
) {
    public static final String ERROR_CODE = "PLAN_RESOURCE_LIMIT_EXCEEDED";

    public static LimitExceededError of(ResourceType resourceType,
                                         String planCode,
                                         long currentCount,
                                         int limit,
                                         String upgradeHint) {
        return new LimitExceededError(ERROR_CODE, resourceType, planCode, currentCount, limit, upgradeHint);
    }
}
