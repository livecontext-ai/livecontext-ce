package com.apimarketplace.orchestrator.services.flag;

import com.apimarketplace.orchestrator.domain.FlagFlipAuditEntity;
import com.apimarketplace.orchestrator.repository.FlagFlipAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Sync, same-TX audit writer for tenant-flag flips (P2.1.7).
 *
 * <p>Write contract per execution-kernel roadmap rev12 §7.6:
 * <ul>
 *   <li><b>Sync</b> - no async, no event listener. The flag mutation thread blocks
 *       on the audit insert.</li>
 *   <li><b>Same TX as the flag mutation</b> - {@link Propagation#MANDATORY} forces
 *       the call to join the caller's existing transaction. If the caller has no
 *       transaction, Spring throws {@code IllegalTransactionStateException},
 *       failing the flip - the flag MUST NOT mutate without an audit row.</li>
 *   <li><b>Fail-the-flip-if-write-fails</b> - if the audit-row insert throws (DB
 *       unavailable, constraint violation, etc.), the caller's TX rolls back and
 *       the flag value never commits. Preserves the invariant "no flip without
 *       audit row".</li>
 * </ul>
 *
 * <p>The {@code @TransactionalEventListener(AFTER_COMMIT)} pattern is explicitly
 * rejected because a JVM crash between commit and listener execution loses the
 * audit row while the flag stays flipped.
 *
 * <p>Caller-context discipline (rev12 §7.6 audit B B-NEW-4): flag-mutation
 * methods MUST NOT run on hot-path threads (SSE event-loop, request threads
 * serving high-RPS endpoints, low-bound async pools) - sync TX cost includes
 * a Postgres INSERT and may take ~5-50ms under load. Allowed callers: admin
 * tooling RPC, scheduled flip pipelines, batch tooling.
 *
 * <p>Round-10 (2026-05-20): {@code organizationId} now threaded explicitly.
 * V265 flips {@code orchestrator.flag_flip_audit.organization_id} to NOT NULL.
 * The {@code OrgScopedEntityListener} on the entity is a defense-in-depth
 * filet - explicit threading from the caller is the primary mechanism.
 */
@Service
public class FlagFlipAuditWriter {

    private final FlagFlipAuditRepository repository;

    public FlagFlipAuditWriter(FlagFlipAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Record a flag flip in the audit log. Requires an active transaction
     * provided by the caller. Throws if invoked outside a transaction.
     *
     * @param flagName       the flag identifier (e.g. {@code state-snapshot.elide-running-nodes})
     * @param tenantId       the tenant scope of the flip ({@code null} for global flags)
     * @param organizationId the organization scope (V265 NOT NULL - must be non-null)
     * @param oldValue       the previous value (string-serialized)
     * @param newValue       the new value (string-serialized)
     * @param actor          who initiated the flip (user/service identifier)
     * @param reason         human-readable reason for the flip; required for forensic value
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFlip(String flagName,
                            String tenantId,
                            String organizationId,
                            String oldValue,
                            String newValue,
                            String actor,
                            String reason) {
        FlagFlipAuditEntity entity = new FlagFlipAuditEntity(
                flagName, tenantId, organizationId, oldValue, newValue, actor, reason, Instant.now());
        repository.save(entity);
    }
}
