package com.apimarketplace.orchestrator.services.triggers;

import com.apimarketplace.auth.client.AuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Shared helper to resolve a user's display name from a tenantId for trigger
 * outputs.
 *
 * <p>All trigger resolvers populate {@code triggered_by} in their persisted
 * output using this helper. The underlying {@link AuthClient#getDisplayName}
 * handles both legacy numeric tenant ids (auth.users.id) and Keycloak
 * provider ids (UUID sub) via the dual-lookup endpoint. Missing / unknown
 * users return an empty string - callers always have a String value and
 * interface {@code variable_mapping} references like
 * {@code {{trigger:x.output.triggered_by}}} never resolve to null.
 *
 * <p>When AuthClient is not wired (tests, minimal boot profiles), returns
 * an empty string as safe fallback.
 */
@Component
public class TriggerUserResolver {

    private static final Logger log = LoggerFactory.getLogger(TriggerUserResolver.class);

    @Autowired(required = false)
    private AuthClient authClient;

    /**
     * @return the user's display name, or empty string when unknown / unavailable.
     */
    public String resolveDisplayName(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return "";
        if (authClient == null) return "";
        try {
            String displayName = authClient.getDisplayName(tenantId);
            return displayName != null ? displayName : "";
        } catch (Exception e) {
            log.debug("[TriggerUserResolver] display-name lookup failed for {}: {}",
                    tenantId, e.getMessage());
            return "";
        }
    }
}
