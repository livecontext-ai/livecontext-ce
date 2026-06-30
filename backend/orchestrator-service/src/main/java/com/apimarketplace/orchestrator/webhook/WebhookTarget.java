package com.apimarketplace.orchestrator.webhook;

/**
 * Target information for a webhook - where to route incoming webhook calls.
 * Stored in Redis for O(1) lookup by token.
 */
public record WebhookTarget(
    String workflowId,
    String triggerId,
    String tenantId
) {
    /**
     * Redis key prefix for webhook tokens.
     */
    public static final String REDIS_KEY_PREFIX = "webhook:";

    /**
     * Get the Redis key for this webhook token.
     */
    public static String redisKey(String token) {
        return REDIS_KEY_PREFIX + token;
    }
}
