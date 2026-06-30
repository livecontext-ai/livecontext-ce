package com.apimarketplace.agent.bridge;

/**
 * Transport-agnostic access check. Default impl is HTTP
 * ({@link HttpBridgeAccessClient}); tests can swap a fake.
 *
 * <p>Kept as an interface so shared-agent-lib never hard-codes how the
 * auth-service is reached (in-process in the CE monolith,
 * over HTTP in cloud agent-service).
 */
public interface BridgeAccessClient {

    /**
     * @param userId         stable user id (Keycloak sub)
     * @param userRoles      comma-separated roles (forwards to X-User-Roles)
     * @param bridgeProvider e.g. {@code claude-code}
     * @param incrementUsage true → count this call against today's quota
     * @return auth-service's typed decision
     */
    BridgeAccessDecision check(String userId,
                               String userRoles,
                               String bridgeProvider,
                               boolean incrementUsage);
}
