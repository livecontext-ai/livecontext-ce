package com.apimarketplace.common.scope;

import com.apimarketplace.common.web.TenantResolver;
import jakarta.persistence.PrePersist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JPA EntityListener that auto-fills {@code organizationId} on persist when it
 * is null AND the current thread has a bound {@code X-Organization-ID} header
 * (via {@code RequestContextHolder}) OR an inherited async scope binding (via
 * {@link TenantResolver#runWithOrgScope}).
 *
 * <p>Post-V263 (NOT NULL on every OrgScopedEntity table), a persist with null
 * organizationId is a DB-level violation. Worse, the bad insert aborts the
 * transaction and cascade-fails other inserts in the same TX (e.g. a missed
 * {@code storage.storage} insert kills the {@code workflow_step_data} row in
 * the same step persist - the user-visible symptom is "step logs disappear
 * after sending a workflow"). The cascaded failure's stack points at the
 * SECOND insert, not the actual dispatch-site bug.
 *
 * <p>HOTFIX-2 (2026-05-20) - the listener now fails LOUD at persist time when
 * both the entity field is null AND no thread binding is available. The
 * thrown {@link IllegalStateException} surfaces the offending caller's stack
 * trace at the listener - the actual dispatch site - instead of letting the
 * bug land as a confusing cascaded failure on a downstream insert.
 *
 * <p>To opt in, an entity declares
 * {@code @EntityListeners(OrgScopedEntityListener.class)} AND implements
 * {@link OrgScopedEntity}. The interface gate lets the listener skip entities
 * that don't carry an org column without reflection.
 */
public class OrgScopedEntityListener {

    private static final Logger log = LoggerFactory.getLogger(OrgScopedEntityListener.class);

    @PrePersist
    public void ensureOrgId(Object entity) {
        if (!(entity instanceof OrgScopedEntity scoped)) {
            return;
        }
        if (scoped.getOrganizationId() != null) {
            return;
        }
        String orgFromRequest = TenantResolver.currentRequestOrganizationId();
        if (orgFromRequest != null) {
            scoped.setOrganizationId(orgFromRequest);
            return;
        }
        // Fail-loud post-V263: persist with null orgId is now a DB-level NOT NULL
        // violation AND aborts the transaction (cascade-failing other inserts like
        // workflow_step_data when storage.storage is the first victim). Surface the
        // dispatch-site stack trace at the listener so the bug points to the actual
        // offending caller, not the cascaded next victim's stack.
        String entityType = entity.getClass().getSimpleName();
        String msg = "OrgScopedEntity persist with null organizationId on " + entityType
                + " AND no request-context binding (currentRequestOrganizationId=null). "
                + "Caller is on a non-request thread without TenantResolver.runWithOrgScope() binding. "
                + "Either: (a) wrap the dispatch lambda in runWithOrgScope(orgId, ...) at the call site, "
                + "OR (b) set entity.organizationId explicitly before persist. "
                + "Stack trace identifies the dispatch site.";
        log.error("[OrgScoped] {}", msg);
        throw new IllegalStateException(msg);
    }
}
