package com.apimarketplace.common.scope;

/**
 * Marker interface for JPA entities that carry an {@code organization_id} column.
 *
 * <p><b>Inheritance invariant for child entities</b> (audit 2026-05-19 Phase 2 H3) -
 * entities like {@code AgentExecutionIterationEntity}, {@code AgentExecutionMessageEntity},
 * {@code AgentExecutionToolCallEntity} mirror their parent's {@code organizationId}.
 * Service-layer code that creates a child MUST copy the parent's orgId explicitly
 * (PR20 contract). The {@link OrgScopedEntityListener} is only a safety net for
 * the case where service code forgot. By construction parent and child are
 * persisted from the same request thread, so the listener's fallback to the
 * request-scoped orgId resolves to the same value as the parent's. The
 * {@code OrgScopedChildInheritanceTest} ArchUnit guard ensures this invariant
 * holds across future refactors.</p>
 *
 *
 * <p>Combined with {@link OrgScopedEntityListener}, this enables a JPA
 * {@code @PrePersist} safety net that auto-fills {@code organizationId} from
 * the current request context when the entity is persisted with a null value.
 *
 * <p>Implementation pattern:
 * <pre>{@code
 * @Entity
 * @EntityListeners(OrgScopedEntityListener.class)
 * @Table(name = "workflows")
 * public class WorkflowEntity implements OrgScopedEntity {
 *     @Column(name = "organization_id")
 *     private String organizationId;
 *
 *     @Override
 *     public String getOrganizationId() { return organizationId; }
 *     @Override
 *     public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
 * }
 * }</pre>
 *
 * <p>Phase 2 of the {@code MIGRATION_ORG_ID_NOT_NULL.md} rollout. Phase 6 will
 * turn the column NOT NULL once Phase 4 backfill confirms zero NULL rows.
 */
public interface OrgScopedEntity {

    /**
     * @return the entity's organization scope, or {@code null} until
     *         Phase 6 turns the column NOT NULL across all USER_SCOPED tables.
     */
    String getOrganizationId();

    /**
     * Set the entity's organization scope. Called by
     * {@link OrgScopedEntityListener#ensureOrgId} when the field is null at
     * persist time AND the current thread has a bound {@code X-Organization-ID}
     * header. Callers SHOULD set this explicitly via service-layer code paths;
     * the listener is a safety net, not the primary mechanism.
     */
    void setOrganizationId(String organizationId);
}
