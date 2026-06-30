package com.apimarketplace.auth.service;

import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.CeLinkAudit;
import com.apimarketplace.auth.repository.CeLinkAuditRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Single source of truth for ce-link audit writes (doc §1 #13, §7).
 * Every ce-link state change goes through here so we have ONE place to evolve
 * (add severity, route specific events to alert pipelines, etc.) - and so
 * we don't sprinkle DB inserts + Loki sink calls across every service.
 *
 * <p>Writes go to TWO authoritative sinks:
 * <ol>
 *   <li>{@code auth.ce_link_audit} table - append-only via BEFORE UPDATE/DELETE/TRUNCATE
 *       triggers (V260). Survives application restart, queryable for forensics.</li>
 *   <li>{@link AuditLogger} "AUDIT" SLF4J logger - shipped to Loki by promtail.
 *       Survives DBA-with-DDL tampering (doc §7 threat model).</li>
 * </ol>
 *
 * <p>Failure-mode: if the DB INSERT fails, we still emit the Loki line - so a
 * partial outage doesn't lose the trail. The reverse (Loki fail) is acceptable
 * because the DB row is the authoritative copy.
 */
@Service
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkAuditService {

    private final CeLinkAuditRepository repository;
    private final AuditLogger auditLogger;

    public CeLinkAuditService(CeLinkAuditRepository repository, AuditLogger auditLogger) {
        this.repository = repository;
        this.auditLogger = auditLogger;
    }

    /**
     * Record a ce-link state change to both sinks. Caller MUST already have an
     * open transaction (we propagate REQUIRED) so the audit row commits with
     * the business mutation.
     *
     * <p>{@code keyVersion} = HMAC key generation used to hash {@code ipHash}
     * (1 today; rotated by PR3c). Threaded through so a future key rotation
     * does not require a method-signature change at every callsite.
     *
     * <p>NPE on null {@code installId}/{@code actorRole}/{@code event}/{@code keyVersion}
     * is intentional: V260 declares those columns {@code NOT NULL}; failing fast
     * with a stack-traced NPE is preferable to a vague ConstraintViolationException
     * at flush.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRED)
    public void record(
            UUID installId,
            Long actorUserId,
            CeLinkAudit.ActorRole actorRole,
            CeLinkAudit.Event event,
            Integer keyVersion,
            String ipHash,
            String userAgent,
            Map<String, Object> metadata
    ) {
        Objects.requireNonNull(installId, "installId");
        Objects.requireNonNull(actorRole, "actorRole");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(keyVersion, "keyVersion");

        CeLinkAudit row = new CeLinkAudit();
        row.setInstallId(installId);
        row.setActorUserId(actorUserId);
        row.setActorRole(actorRole);
        row.setEvent(event);
        row.setKeyVersion(keyVersion);
        row.setIpHash(ipHash);
        row.setUserAgent(userAgent);
        row.setMetadata(metadata);
        try {
            // saveAndFlush forces the INSERT inside this try-block so a DB-level
            // failure (CHECK violation, trigger raise, deadlock) is caught here
            // BEFORE the Loki sink claims "ok" - closes the silent-divergence
            // gap called out in doc §7.
            repository.saveAndFlush(row);
        } catch (RuntimeException dbFailure) {
            emitLoki(installId, actorUserId, actorRole, event, "db_write_failed");
            throw dbFailure;
        }
        emitLoki(installId, actorUserId, actorRole, event, "ok");
    }

    private void emitLoki(UUID installId, Long actorUserId, CeLinkAudit.ActorRole actorRole,
                          CeLinkAudit.Event event, String result) {
        auditLogger
                .event("ce_link." + event.name().toLowerCase())
                .user(actorUserId)
                .result(result)
                .detail("install_id", installId.toString())
                .detail("actor_role", actorRole.name())
                .write();
    }
}
