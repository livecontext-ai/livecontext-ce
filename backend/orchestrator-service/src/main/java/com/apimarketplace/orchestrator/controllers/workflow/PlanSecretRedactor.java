package com.apimarketplace.orchestrator.controllers.workflow;

import java.util.List;
import java.util.Map;

/**
 * Removes raw inline secrets (and credential references) from a workflow plan map.
 *
 * <p>A workflow plan can carry RAW secret values inline in node params: an HTTP Request
 * node's {@code authConfig} (bearer token / API key / basic-auth password / header value),
 * a Crypto/JWT node's {@code key}/{@code secret}/{@code token}, and the inline
 * {@code password}/{@code privateKey} fallbacks of SSH/SFTP/Database nodes. When the
 * {@code GET /api/workflows/{id}} response is served to an anonymous APPLICATION share-link
 * visitor (owner-impersonation, {@code X-Share-Context: true}), the raw plan would otherwise
 * hand those secrets to the visitor. This scrubs them.
 *
 * <p>Mirrors the marketplace acquire-time strip in publication-service's
 * {@code SnapshotCloneService.stripSensitiveCredentials}, and additionally covers the
 * SSH/SFTP/Database inline fields that strip omits (they resolve against the caller's own
 * credentials at execution time, so removing them from a shared read is a no-op for the app).
 */
public final class PlanSecretRedactor {

    private PlanSecretRedactor() {
    }

    /**
     * Redacts secret-bearing fields from the given plan map IN PLACE. Callers that must not
     * mutate the source (e.g. a persisted entity's plan) must pass a deep copy.
     */
    @SuppressWarnings("unchecked")
    public static void redact(Map<String, Object> plan) {
        if (plan == null) {
            return;
        }
        Object coresRaw = plan.get("cores");
        if (coresRaw instanceof List<?> cores) {
            for (Object core : cores) {
                if (!(core instanceof Map<?, ?> coreMap)) {
                    continue;
                }
                // Raw inline secrets.
                removeChildKeys(coreMap, "httpRequest", "authConfig");
                removeChildKeys(coreMap, "cryptoJwt", "key", "secret", "token");
                removeChildKeys(coreMap, "ssh", "password", "privateKey");
                removeChildKeys(coreMap, "sftp", "password", "privateKey");
                removeChildKeys(coreMap, "database", "password");
                // sendEmail carries an inline SMTP password RAW fallback (alongside credentialId).
                removeChildKeys(coreMap, "sendEmail", "credentialId", "smtpPassword");
                // Credential references (numeric ids, low value but not the viewer's to see).
                removeChildKeys(coreMap, "emailInbox", "credentialId");
                removeChildKeys(coreMap, "approvalDelegation", "credentialId");
            }
        }
        for (String stepBucket : List.of("mcps", "agents")) {
            Object bucket = plan.get(stepBucket);
            if (!(bucket instanceof List<?> steps)) {
                continue;
            }
            for (Object step : steps) {
                if (!(step instanceof Map<?, ?> stepMap)) {
                    continue;
                }
                Map<String, Object> mutableStep = (Map<String, Object>) stepMap;
                mutableStep.remove("selectedCredentialId");
                mutableStep.remove("credentialId");
                mutableStep.remove("platformCredentialId");
                mutableStep.remove("credentialSource");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeChildKeys(Map<?, ?> coreMap, String childKey, String... keysToRemove) {
        Object node = coreMap.get(childKey);
        if (node instanceof Map<?, ?> childMap) {
            Map<String, Object> mutable = (Map<String, Object>) childMap;
            for (String k : keysToRemove) {
                mutable.remove(k);
            }
        }
    }
}
