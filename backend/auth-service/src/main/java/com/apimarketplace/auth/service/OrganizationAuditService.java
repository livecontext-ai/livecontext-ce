package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.repository.OrganizationAuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Centralised write side for the {@code organization_audit_event} log
 * (PR-4b MVP). Single entry-point so every ORG_* event lands with the
 * same shape and the same transactional semantics.
 *
 * <p><b>Transactional contract:</b> every {@link #record} call runs in a
 * {@code REQUIRES_NEW} transaction, so a failed audit insert never rolls
 * back the business operation that triggered it - and conversely, a
 * rolled-back business transaction does NOT erase the audit row. This
 * matches the SOC2 expectation that an audit log "records what was
 * attempted, even when it failed".
 *
 * <p>If the audit insert itself fails (DB down, schema drift, …) we
 * <b>log + swallow</b>. The business operation must not be blocked by
 * an audit failure ; an alert on the WARN log catches the drift.
 *
 * <p>The HMAC chain + ShedLock retention purge + WORM mirror are
 * deferred to PR-4b.1.
 */
@Service
public class OrganizationAuditService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationAuditService.class);

    private final OrganizationAuditEventRepository repository;

    public OrganizationAuditService(OrganizationAuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Persist one audit row. Fire-and-forget semantics: any persistence
     * exception is caught + WARN-logged ; business callers do not need
     * a {@code try/catch} around this call.
     *
     * @param orgId       organization id (FK auth.organization.id)
     * @param actorUserId user who triggered the event, or null for system events
     * @param eventType   one of {@link OrganizationAuditEvent.Type} constants
     * @param eventData   structured payload (e.g. {"targetUserId": 42, "oldRole": "MEMBER"})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID orgId, Long actorUserId, String eventType, Map<String, Object> eventData) {
        try {
            OrganizationAuditEvent event = new OrganizationAuditEvent(orgId, actorUserId, eventType, eventData);
            repository.save(event);
        } catch (Exception e) {
            // Audit must not block the business path. WARN so ops sees drift.
            log.warn("Failed to record ORG audit event type={} org={} actor={}: {}",
                    eventType, orgId, actorUserId, e.getMessage());
        }
    }
}
