package com.apimarketplace.auth.credential.service;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * Single source of truth for the audit trail of the third-party credential lifecycle: connect
 * (with the granted scopes), refresh failure, provider revocation, delete, and the hand-off of
 * credential material to another service (LC-058). Before this class the credential package
 * emitted nothing, so after a credential-dump incident there was no record of which credentials
 * had been read, by whom or when.
 *
 * <p><strong>Invariant.</strong> No token, client secret, authorization code, PKCE verifier or
 * decrypted field value may reach an event. Every method takes identifiers, integration slugs,
 * scope NAMES (public identifiers shown on the consent screen) and bounded labels only; there is
 * deliberately no pass-through details map a caller could smuggle a secret through.
 *
 * <p>Same pattern as {@code AuthEventRecorder}: {@link AuditLogger} is optional so hand-built
 * service instances in tests keep working (the recorder then no-ops), and every method swallows
 * its own failures: an audit write must never break a credential operation.
 */
@Component
public class CredentialAuditRecorder {

    /** Scope names listed per event; {@code scope_count} always carries the true total. */
    static final int MAX_SCOPES_LOGGED = 25;

    @Autowired(required = false)
    private AuditLogger auditLogger;

    /** Package-private for tests that wire a real logger without a Spring context. */
    void setAuditLogger(AuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    /** An OAuth2 connect completed and the credential was stored. */
    public void recordOAuthConnected(String userId, Long credentialId, String integration,
                                     List<String> grantedScopes) {
        if (auditLogger == null) return;
        try {
            builder(AuditEventTypes.CREDENTIAL_OAUTH_CONNECTED)
                    .user(userId)
                    .success()
                    .detail("credential_id", credentialId)
                    .detail("integration", integration)
                    .detail("scope_count", grantedScopes == null ? 0 : grantedScopes.size())
                    .detail("scopes", boundedScopes(grantedScopes))
                    .write();
        } catch (Exception ignored) {
            // Audit must never break the OAuth callback.
        }
    }

    /**
     * A token refresh failed. {@code bucket} is the bounded classifier outcome
     * ({@code terminal_user}, {@code terminal_config}, {@code transient}); {@code terminal}
     * means the tokens were scrubbed and the user must reconnect.
     */
    public void recordRefreshFailed(String userId, Long credentialId, String integration,
                                    String bucket, Integer httpStatus, boolean terminal) {
        if (auditLogger == null) return;
        try {
            AuditLogger.Builder b = builder(AuditEventTypes.CREDENTIAL_REFRESH_FAILED)
                    .user(userId)
                    .failure(bucket)
                    .detail("credential_id", credentialId)
                    .detail("integration", integration)
                    .detail("bucket", bucket)
                    .detail("terminal", terminal);
            if (httpStatus != null) {
                b.detail("http_status", httpStatus);
            }
            if (terminal) {
                b.warn();
            }
            b.write();
        } catch (Exception ignored) {
            // Audit must never break the refresh bookkeeping.
        }
    }

    /**
     * A stored credential was removed. {@code reason} is a bounded label of the path
     * ({@code user_delete}, {@code byok_client_deleted}, {@code account_purge},
     * {@code workspace_purge}); {@code revokeOutcome} is the result of the provider revocation
     * that ran first, so "disconnected but the grant may still be live" is answerable from the
     * trail alone.
     */
    public void recordDeleted(String userId, Long credentialId, String integration,
                              String reason, String revokeOutcome) {
        if (auditLogger == null) return;
        try {
            builder(AuditEventTypes.CREDENTIAL_DELETED)
                    .user(userId)
                    .success()
                    .detail("credential_id", credentialId)
                    .detail("integration", integration)
                    .detail("reason", reason)
                    .detail("provider_revocation", revokeOutcome)
                    .write();
        } catch (Exception ignored) {
            // Audit must never break the delete.
        }
    }

    /** Outcome of the RFC 7009 call to the provider; a failure is a WARN event. */
    public void recordProviderRevocation(String userId, Long credentialId, String integration,
                                         String outcome, boolean success) {
        if (auditLogger == null) return;
        try {
            AuditLogger.Builder b = builder(AuditEventTypes.CREDENTIAL_PROVIDER_REVOKED)
                    .user(userId)
                    .detail("credential_id", credentialId)
                    .detail("integration", integration)
                    .detail("outcome", outcome);
            if (success) {
                b.success();
            } else {
                b.warn().failure(outcome);
            }
            b.write();
        } catch (Exception ignored) {
            // Audit must never break the revoke.
        }
    }

    /**
     * Credential material was returned to a calling service.
     *
     * @param userId       the user the read was made for (the executing user)
     * @param credentialId the row read, when known (null for a by-name miss)
     * @param integration  the row's integration, when known
     * @param accessPath   bounded label of the endpoint ({@code access_token}, {@code data_map},
     *                     {@code credential_row}, ...), so an incident can be scoped to the reads
     *                     that actually exposed material
     */
    public void recordSecretRead(String userId, Long credentialId, String integration,
                                 String accessPath) {
        if (auditLogger == null) return;
        try {
            builder(AuditEventTypes.CREDENTIAL_SECRET_READ)
                    .user(userId)
                    .success()
                    .detail("credential_id", credentialId)
                    .detail("integration", integration)
                    .detail("access_path", accessPath)
                    .write();
        } catch (Exception ignored) {
            // Audit must never break credential resolution (which would break execution).
        }
    }

    /** Credential ids listed per batch event; {@code count} always carries the true total. */
    static final int MAX_IDS_LOGGED = 50;

    /**
     * ONE event for a call that returned several credentials (the internal list endpoint), with
     * the true count and a bounded id list, instead of one event per row.
     */
    public void recordSecretReadBatch(String userId, List<Long> credentialIds, String accessPath) {
        if (auditLogger == null) return;
        try {
            List<Long> ids = credentialIds == null ? List.of() : credentialIds;
            builder(AuditEventTypes.CREDENTIAL_SECRET_READ)
                    .user(userId)
                    .success()
                    .detail("count", ids.size())
                    .detail("credential_ids", ids.size() <= MAX_IDS_LOGGED
                            ? List.copyOf(ids) : List.copyOf(ids.subList(0, MAX_IDS_LOGGED)))
                    .detail("access_path", accessPath)
                    .write();
        } catch (Exception ignored) {
            // Audit must never break credential resolution.
        }
    }

    /**
     * Same event for a read resolved by NAME where the row is not on hand. The name is the
     * user's label for the connection (or an integration slug), never credential material.
     */
    public void recordSecretReadByName(String userId, String credentialName, String accessPath) {
        if (auditLogger == null) return;
        try {
            builder(AuditEventTypes.CREDENTIAL_SECRET_READ)
                    .user(userId)
                    .success()
                    .detail("credential_name", credentialName)
                    .detail("access_path", accessPath)
                    .write();
        } catch (Exception ignored) {
            // Audit must never break credential resolution.
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        } catch (Exception ignored) {
            // No request bound to this thread (scheduler, async): emit without ip/ua.
        }
        return null;
    }

    private AuditLogger.Builder builder(String eventType) {
        HttpServletRequest req = currentRequest();
        return req != null ? auditLogger.eventFromRequest(eventType, req) : auditLogger.event(eventType);
    }

    private static List<String> boundedScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) return List.of();
        return scopes.size() <= MAX_SCOPES_LOGGED
                ? List.copyOf(scopes)
                : List.copyOf(scopes.subList(0, MAX_SCOPES_LOGGED));
    }
}
