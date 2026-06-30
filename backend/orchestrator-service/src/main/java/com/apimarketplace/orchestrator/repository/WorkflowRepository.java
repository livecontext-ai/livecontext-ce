package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository pour la gestion des workflows
 */
@Repository
public interface WorkflowRepository extends JpaRepository<WorkflowEntity, UUID> {
    
    
    /**
     * Lightweight projection of the workflow's organization_id only.
     * Used by {@code WorkflowOwnershipResolver} to answer
     * {@code /api/internal/orchestrator/owner/workflow/{id}} without
     * hydrating the full entity (no plan JSONB load).
     *
     * <p>Post-V261 (2026-05-19): every workflow row carries a non-null
     * {@code organization_id} (personal workspaces use the user's default
     * personal org). The legacy {@code IS NOT NULL} guard against pre-V261
     * NULL rows is dropped - the projection now returns the org id for any
     * existing workflow.
     */
    @Query("SELECT w.organizationId FROM WorkflowEntity w WHERE w.id = :id")
    Optional<String> findOrganizationIdById(@Param("id") UUID id);

    /**
     * Phase 2b - batch (id, name) pairs for a set of workflow ids. Used by the Storage Explorer
     * controller to resolve the display name of VIRTUAL workflow folders without hydrating the full
     * entity (no plan JSONB load). Each row is an {@code Object[]} of {@code [UUID id, String name]}.
     * Workflow name resolution lives in the orchestrator (the storage boundary must not query the
     * workflows table).
     */
    @Query("SELECT w.id, w.name FROM WorkflowEntity w WHERE w.id IN :ids")
    List<Object[]> findIdNamePairs(@Param("ids") Collection<UUID> ids);

    /**
     * Batch (id, pinnedVersion) pairs for a set of workflow ids - the Applications page reads each
     * card's pinned version to draw the Live/Active badge, and used to fire one
     * {@code /v2/workflows/dag/{id}/versions} request PER card (an N+1 over ~200). Each row is an
     * {@code Object[]} of {@code [UUID id, Integer pinnedVersion]}; {@code pinnedVersion} is null for
     * an unpinned (Inactive) workflow, and a workflow id absent from the result reads as "load failed"
     * on the client (badge hidden). Lightweight scalar projection - no plan JSONB hydration.
     *
     * <p>Returns the scope columns ({@code tenantId, organizationId}) alongside the version so the
     * caller can apply {@code ScopeGuard.isInStrictScope} - the SAME strict-workspace predicate the
     * per-card {@code /versions} endpoint this replaces used ({@code WorkflowVersionController#verifyOwnership}).
     * Each row is {@code [UUID id, Integer pinnedVersion, String tenantId, String organizationId]}.
     * Filtering in Java (not SQL) reuses the one canonical scope helper instead of duplicating its
     * two-branch predicate, and keeps the projection trivially testable.
     */
    @Query("SELECT w.id, w.pinnedVersion, w.tenantId, w.organizationId FROM WorkflowEntity w WHERE w.id IN :ids")
    List<Object[]> findPinnedVersionScopeRows(@Param("ids") Collection<UUID> ids);

    /**
     * BATCH-B (2026-05-20) - strict-org listing of every workflow in an org workspace.
     * Pairs with the orphaned tenant-only {@link #findByTenantId(String)} which leaked
     * cross-org rows when a user belonged to multiple orgs sharing the same userId.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId")
    List<WorkflowEntity> findByOrganizationIdStrict(@Param("orgId") String orgId);

    /**
     * @deprecated Use {@link #findByOrganizationIdStrict(String)}. Kept only because
     * sibling services still reference it via the legacy method name.
     * <p>BATCH-B (2026-05-20): all orchestrator-service callers rerouted.
     */
    @Deprecated
    List<WorkflowEntity> findByTenantId(String tenantId);

    /**
     * PR30 - strict-org workflow list. WorkflowEntity.organization_id was added by
     * V209 (PR15 chain). Pre-PR30 the list endpoint used the tenant-only finder
     * and returned cross-scope workflows when a tenant belonged to multiple orgs.
     *
     * <p>Post-V261 (2026-05-19): every user-scoped workflow row carries a non-null
     * {@code organization_id} (gateway always injects {@code X-Organization-ID},
     * personal workspaces use the user's default personal org). The companion
     * {@code findByTenantIdAndOrganizationIdIsNullOrderByCreatedAtDesc} was removed
     * - callers in personal scope now resolve the personal org id and route through
     * this strict-org finder.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId ORDER BY w.createdAt DESC")
    List<WorkflowEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(@Param("orgId") String orgId);

    /** PR30 - strict-org active workflow list. */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.isActive = true ORDER BY w.createdAt DESC")
    List<WorkflowEntity> findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(@Param("orgId") String orgId);
    
    /**
     * Trouve tous les workflows actifs
     */
    List<WorkflowEntity> findByIsActiveTrue();

    // ===== Recent-activity aggregator (V234 partial indexes back these) =====

    /**
     * Top-N workflows (including APPLICATION subtype) in an org workspace
     * ordered by last edit time. Used by the orchestrator's
     * {@code RecentActivityAggregatorService} own-DB branch covering BOTH
     * WORKFLOW and APPLICATION row kinds via the {@code WorkflowType}
     * discriminator on the result. Backed by the V234 partial index
     * {@code idx_workflows_org_updated_at}.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId ORDER BY w.updatedAt DESC")
    List<WorkflowEntity> findRecentByOrganizationIdStrict(@Param("orgId") String orgId, Pageable pageable);

    /**
     * Stamp {@code lastExecutedAt} for a single workflow without loading the entity.
     *
     * <p>{@link com.apimarketplace.orchestrator.trigger.ReusableTriggerService} stamps
     * this on every reusable-trigger fire, but used to call
     * {@code run.getWorkflow().setLastExecutedAt(...); workflowRepository.save(entity)}
     * - which forced lazy proxy initialization on {@code WorkflowRunEntity.workflow}
     * ({@code FetchType.LAZY}). When the surrounding Hibernate session was already
     * closed (schedule-spread async path), the proxy initialization threw
     * {@code LazyInitializationException: Could not initialize proxy ... - no session}
     * and {@code lastExecutedAt} was silently lost - observed 13×/day in prod for
     * scheduled workflows (regression introduced 2026-05-05 commit 2a083618b7).
     *
     * <p>The bulk-update form sidesteps the proxy entirely by issuing a direct
     * {@code UPDATE} keyed by id. {@code @Transactional} ensures the JPA executor
     * has a session for the {@code @Modifying} query even when the caller is not in
     * an active transaction (the original failure mode).
     */
    @Modifying
    @Transactional
    // JPQL bulk @Modifying bypasses @PreUpdate on WorkflowEntity, so the
    // Activity bell tab (orders by workflows.updated_at DESC) won't see this
    // fire unless we explicitly SET it here too. Mirror of the agent JPQL
    // fix in AgentExecutionRepository.incrementCounters.
    @Query("UPDATE WorkflowEntity w SET w.lastExecutedAt = :ts, w.updatedAt = :ts WHERE w.id = :id")
    int updateLastExecutedAt(@Param("id") UUID id, @Param("ts") Instant ts);
    
    /**
     * Compte le nombre de workflows d'un tenant.
     * <p>Only the integration test references this in-tree; the strict-org variant
     * {@link #countByOrganizationIdStrict(String)} is the runtime path. Kept as
     * the test fixture builder for tenant-only assertions but no production caller.
     */
    long countByTenantId(String tenantId);

    /** PR30 - strict-org workflow count for quota display. */
    @Query("SELECT COUNT(w) FROM WorkflowEntity w WHERE w.organizationId = :orgId")
    long countByOrganizationIdStrict(@Param("orgId") String orgId);

    /** PR30 - strict-org active workflow count. */
    @Query("SELECT COUNT(w) FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.isActive = true")
    long countByOrganizationIdStrictAndIsActiveTrue(@Param("orgId") String orgId);
    
    
    /**
     * Trouve les workflows par nom et tenant (recherche partielle)
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.tenantId = :tenantId AND w.name ILIKE %:name%")
    List<WorkflowEntity> findByTenantIdAndNameContainingIgnoreCase(@Param("tenantId") String tenantId,
                                                                  @Param("name") String name);

    /**
     * BATCH-B (2026-05-20) - strict-org partial name search. Replaces
     * {@link #findByTenantIdAndNameContainingIgnoreCase(String, String)} for the MCP
     * agent workflow builder (used by load-by-name). The tenant-only finder leaked
     * workflow names across orgs when a single user belonged to multiple workspaces.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.name ILIKE %:name%")
    List<WorkflowEntity> findByOrganizationIdAndNameContainingIgnoreCaseStrict(@Param("orgId") String orgId,
                                                                              @Param("name") String name);
    
    
    /**
     * Debug: Trouve les workflows avec un tenantId similaire (pour debug)
     * Utilise une recherche avec LIKE pour trouver des correspondances partielles.
     * <p>Debug-only helper invoked by {@code WorkflowListController.logDebugInfo}
     * when an empty-result query triggers diagnostic logging. Retained because
     * removing it would lose the on-prod tenantId-encoding troubleshooting path
     * (URL-encoded vs raw tenantId mismatch) it was built for. No production code
     * path consumes it.
     */
    @Query(value = "SELECT * FROM workflows WHERE tenant_id LIKE CONCAT('%', :pattern, '%') ORDER BY created_at DESC", nativeQuery = true)
    List<WorkflowEntity> findByTenantIdContaining(@Param("pattern") String pattern);
    
    /**
     * Debug: Trouve tous les tenantIds uniques (pour debug)
     */
    @Query(value = "SELECT DISTINCT tenant_id FROM workflows ORDER BY tenant_id", nativeQuery = true)
    List<String> findAllDistinctTenantIds();

    /**
     * Find workflows that have a workflow trigger referencing a specific parent workflow.
     * This is used to find downstream workflows that should be triggered when a parent workflow completes.
     *
     * The query searches for workflows where the plan contains a trigger with:
     * - type = "workflow"
     * - id = parentWorkflowId
     *
     * @param parentWorkflowId The ID of the parent workflow (as string UUID)
     * @return List of workflows that are triggered by the parent workflow
     */
    @Query(value = "SELECT * FROM workflows w WHERE w.is_active = true AND EXISTS (" +
           "SELECT 1 FROM jsonb_array_elements(w.plan->'triggers') AS t " +
           "WHERE t->>'type' = 'workflow' AND t->>'id' = :parentWorkflowId)", nativeQuery = true)
    List<WorkflowEntity> findByWorkflowTrigger(@Param("parentWorkflowId") String parentWorkflowId);

    /**
     * Find workflows that have an error trigger referencing a specific parent workflow.
     * This is used to find error handler workflows that should be triggered when a parent workflow fails.
     *
     * The query searches for workflows where the plan contains a trigger with:
     * - type = "error"
     * - id = parentWorkflowId
     *
     * @param parentWorkflowId The ID of the parent workflow (as string UUID)
     * @return List of workflows that handle errors from the parent workflow
     */
    @Query(value = "SELECT * FROM workflows w WHERE w.is_active = true AND EXISTS (" +
           "SELECT 1 FROM jsonb_array_elements(w.plan->'triggers') AS t " +
           "WHERE t->>'type' = 'error' AND t->>'id' = :parentWorkflowId)", nativeQuery = true)
    List<WorkflowEntity> findByErrorTrigger(@Param("parentWorkflowId") String parentWorkflowId);

    /**
     * Find acquired APPLICATION roots (from marketplace) for an organization
     * workspace. Typed to APPLICATION on purpose: since the V268 invariant
     * (one APPLICATION root per (org, publication)), acquire also clones
     * sub-workflow children as standard WORKFLOW rows that carry the same
     * sourcePublicationId - listing them here would surface one Applications
     * card per child and let "Remove" target a child instead of the root.
     */
    @Query("""
            SELECT w FROM WorkflowEntity w
            WHERE w.organizationId = :organizationId
              AND w.sourcePublicationId IS NOT NULL
              AND w.workflowType = :type
            ORDER BY w.acquiredAt DESC
            """)
    List<WorkflowEntity> findAcquiredByOrganizationId(
            @Param("organizationId") String organizationId,
            @Param("type") WorkflowEntity.WorkflowType type);

    /**
     * Find acquired workflows by tenant for the legacy PERSONAL scope only
     * ({@code organization_id IS NULL}).
     *
     * <p>2026-05-21 fix - InternalPublicationSupportController.getAcquiredWorkflows
     * declared {@code organizationId} as required=true. When the frontend was on a
     * scope where no org header propagated (legacy paths / direct API access),
     * Spring 404'd and the Applications page showed zero acquired apps.
     *
     * <p>2026-05-21 audit-A MEDIUM follow-up - earlier version of this finder
     * filtered by {@code tenantId} only, which leaked acquisitions across all
     * orgs the user belongs to (multi-org user in personal fallback saw the
     * union). Tightened to {@code organization_id IS NULL} so the personal
     * fallback returns ONLY legacy personal acquisitions, never org-scoped
     * rows. Multi-org users now see an empty list in personal fallback
     * (correct - they have no personal-scope acquisitions, only org-scoped
     * ones which require the org header).
     */
    @Query("""
            SELECT w FROM WorkflowEntity w
            WHERE w.tenantId = :tenantId
              AND w.organizationId IS NULL
              AND w.sourcePublicationId IS NOT NULL
              AND w.workflowType = :type
            ORDER BY w.acquiredAt DESC
            """)
    List<WorkflowEntity> findAcquiredByTenantId(
            @Param("tenantId") String tenantId,
            @Param("type") WorkflowEntity.WorkflowType type);

    /**
     * Check if a tenant already acquired a specific publication.
     */
    boolean existsByTenantIdAndSourcePublicationId(String tenantId, UUID sourcePublicationId);

    /**
     * Check if an organization workspace already acquired a specific publication.
     */
    boolean existsByOrganizationIdAndSourcePublicationId(String organizationId, UUID sourcePublicationId);

    /**
     * Typed "already acquired" probes - an acquisition exists iff its
     * APPLICATION root row does. The untyped variants above also match
     * sub-workflow children (WORKFLOW rows tagged with the same publication),
     * which would permanently block re-acquisition after a failed acquire
     * left orphan clones behind.
     */
    boolean existsByTenantIdAndSourcePublicationIdAndWorkflowType(
            String tenantId, UUID sourcePublicationId, WorkflowEntity.WorkflowType workflowType);

    boolean existsByOrganizationIdAndSourcePublicationIdAndWorkflowType(
            String organizationId, UUID sourcePublicationId, WorkflowEntity.WorkflowType workflowType);

    /**
     * Find workflows by tenant filtered by workflow type, ordered by update date descending.
     * <p>Retained only as the personal-scope fallback path for
     * {@link com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderLoader}.
     * All other callers must use the strict-org variant
     * {@link #findByOrganizationIdAndWorkflowTypeOrderByUpdatedAtDescStrict(String, WorkflowEntity.WorkflowType)}.
     */
    List<WorkflowEntity> findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc(
            String tenantId, WorkflowEntity.WorkflowType workflowType);

    /**
     * BATCH-B (2026-05-20) - strict-org workflow listing filtered by workflow type.
     * Replaces {@link #findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc} for the
     * MCP agent builder loader so the agent only sees workflows belonging to the
     * current workspace.
     */
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.workflowType = :workflowType ORDER BY w.updatedAt DESC")
    List<WorkflowEntity> findByOrganizationIdAndWorkflowTypeOrderByUpdatedAtDescStrict(
            @Param("orgId") String orgId,
            @Param("workflowType") WorkflowEntity.WorkflowType workflowType);

    /**
     * Find workflows belonging to an organization or owned by a specific user, filtered by type.
     */
    // Post-V261 (2026-05-19): every workflow row carries a non-null organization_id
    // (personal workspaces resolve to the user's default personal org), so the
    // legacy {@code OR w.tenantId = :userId} branch was dead code that also bled
    // cross-workspace rows owned by the caller across orgs they're in. Strict-org
    // match closes that leak. Signature preserved so callers still pass userId
    // (ignored) - Phase 11 will rename + drop the param.
    @Query("SELECT w FROM WorkflowEntity w WHERE w.organizationId = :orgId AND w.workflowType = :type ORDER BY w.updatedAt DESC")
    List<WorkflowEntity> findByOrganizationOrOwnerAndType(
            @Param("orgId") String orgId, @Param("userId") String userId,
            @Param("type") WorkflowEntity.WorkflowType type);

    @Query("""
            SELECT w FROM WorkflowEntity w
            WHERE w.organizationId = :organizationId
              AND w.sourcePublicationId = :pubId
              AND w.workflowType = :type
            """)
    Optional<WorkflowEntity> findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
            @Param("organizationId") String organizationId, @Param("pubId") UUID sourcePublicationId,
            @Param("type") WorkflowEntity.WorkflowType type);

    @Query("""
            SELECT w FROM WorkflowEntity w
            WHERE w.organizationId = :organizationId
              AND w.sourcePublicationId = :pubId
            """)
    List<WorkflowEntity> findAllByOrganizationIdAndSourcePublicationId(
            @Param("organizationId") String organizationId, @Param("pubId") UUID sourcePublicationId);

    /**
     * Find workflows assigned to a specific project.
     */
    List<WorkflowEntity> findByProjectId(UUID projectId);

    List<WorkflowEntity> findByProjectIdAndOrganizationId(UUID projectId, String organizationId);

    /**
     * Count workflows assigned to a specific project.
     */
    long countByProjectId(UUID projectId);

    long countByProjectIdAndOrganizationId(UUID projectId, String organizationId);

    /**
     * Find every workflow whose persisted plan still references a given
     * interface id under {@code plan.interfaces[].id}. Used by the
     * interface-deletion cascade to scrub dangling references before the
     * row in {@code interface.interfaces} is removed.
     */
    @Query(value = "SELECT * FROM workflows w WHERE EXISTS (" +
           "SELECT 1 FROM jsonb_array_elements(COALESCE(w.plan->'interfaces', '[]'::jsonb)) AS i " +
           "WHERE i->>'id' = :interfaceId)", nativeQuery = true)
    List<WorkflowEntity> findByPlanInterfaceId(@Param("interfaceId") String interfaceId);

}
