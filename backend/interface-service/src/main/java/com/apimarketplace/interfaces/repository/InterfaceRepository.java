package com.apimarketplace.interfaces.repository;

import com.apimarketplace.interfaces.domain.InterfaceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InterfaceRepository extends JpaRepository<InterfaceEntity, UUID> {

    @Deprecated(since = "Batch-C", forRemoval = false)
    List<InterfaceEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    // ===== Paged-list whole-set projections (exclude @Lob templates + data JSONB) =====
    // listInterfacesPaged loads the WHOLE tenant/org set only to sort + slice one page, so it must
    // never pull the heavy html/css/js LOBs + data JSONB of every row. These return the lightweight
    // {@link InterfaceListView}; the page's full entities are then fetched by id via findAllById.

    /** Tenant-scoped whole-set, light projection (default paged-list path). */
    List<InterfaceListView> findViewByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** Org-scoped whole-set, light projection (mirrors {@link #findByOrganizationOrOwner}). */
    @Query("SELECT i.id AS id, i.name AS name, i.description AS description, "
            + "i.interfaceType AS interfaceType, i.dataSourceId AS dataSourceId, "
            + "i.createdAt AS createdAt, i.updatedAt AS updatedAt "
            + "FROM InterfaceEntity i WHERE i.organizationId = :orgId ORDER BY i.createdAt DESC")
    List<InterfaceListView> findViewByOrganizationId(@Param("orgId") String orgId);

    /** Tenant-scoped name/description ILIKE search, light projection (mirrors {@link #searchByTenant}). */
    @Query("SELECT i.id AS id, i.name AS name, i.description AS description, "
            + "i.interfaceType AS interfaceType, i.dataSourceId AS dataSourceId, "
            + "i.createdAt AS createdAt, i.updatedAt AS updatedAt "
            + "FROM InterfaceEntity i WHERE i.tenantId = :tenantId "
            + "AND (LOWER(i.name) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(COALESCE(i.description, '')) LIKE LOWER(CONCAT('%', :q, '%'))) "
            + "ORDER BY i.createdAt DESC")
    List<InterfaceListView> searchViewByTenant(@Param("tenantId") String tenantId, @Param("q") String q);

    List<InterfaceEntity> findByTenantIdAndInterfaceTypeOrderByCreatedAtDesc(String tenantId, String interfaceType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InterfaceEntity i WHERE i.id = :id")
    Optional<InterfaceEntity> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT i FROM InterfaceEntity i WHERE i.tenantId = :tenantId " +
           "AND (i.dataSourceId IS NULL) " +
           "ORDER BY i.createdAt DESC")
    List<InterfaceEntity> findInterfacesNotAttachedToTable(@Param("tenantId") String tenantId);

    long countByTenantId(String tenantId);

    List<InterfaceEntity> findByProjectId(UUID projectId);

    List<InterfaceEntity> findByProjectIdAndOrganizationId(UUID projectId, String organizationId);

    long countByProjectId(UUID projectId);

    long countByProjectIdAndOrganizationId(UUID projectId, String organizationId);

    // Post-V261 (2026-05-19): every interface row carries a non-null
    // organization_id; legacy OR-ownership branch was dead + cross-workspace
    // bleed. Strict-org match closes the leak. userId param preserved (ignored)
    // for back-compat; Phase 11 will rename + drop it.
    @Query("SELECT i FROM InterfaceEntity i WHERE i.organizationId = :orgId ORDER BY i.createdAt DESC")
    List<InterfaceEntity> findByOrganizationOrOwner(@Param("orgId") String orgId, @Param("userId") String userId);

    /**
     * #150 - strict-org single fetch. Returns the interface only when it lives in the
     * given org workspace, regardless of who created it. Tenant ownership is not checked
     * here - the {@link com.apimarketplace.auth.client.access.OrgAccessGuard} deny-list
     * is applied separately in the service layer for the (orgId, userId, orgRole) tuple.
     */
    @Query("SELECT i FROM InterfaceEntity i WHERE i.id = :id AND i.organizationId = :orgId")
    Optional<InterfaceEntity> findByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    /**
     * Strict org-scope listing - every row owned by this org regardless of which
     * member created it. Post-V261: org_id is always non-null on user-scoped
     * rows; this is the strict-isolation listing path used by org-aware
     * controllers. Tenant ownership is NOT checked here - the org workspace is
     * the gate, and the deny-list is layered on top by the service.
     */
    @Query("SELECT i FROM InterfaceEntity i WHERE i.organizationId = :orgId ORDER BY i.createdAt DESC")
    List<InterfaceEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(@Param("orgId") String orgId);

    /** Tenant-scoped name/description ILIKE search. Used by paginated list endpoint. */
    @Query("SELECT i FROM InterfaceEntity i WHERE i.tenantId = :tenantId " +
           "AND (LOWER(i.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(COALESCE(i.description, '')) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "ORDER BY i.createdAt DESC")
    List<InterfaceEntity> searchByTenant(@Param("tenantId") String tenantId, @Param("q") String q);

    List<InterfaceEntity> findBySourceWorkflowId(UUID sourceWorkflowId);

    // ===== Recent-activity aggregator (V235 partial indexes back these) =====

    /**
     * Top-N interfaces in an org workspace ordered by last edit time. Used
     * by {@code /api/internal/interfaces/recent-activity} feeding the
     * orchestrator's {@code RecentActivityAggregatorService}. Backed by the
     * V235 partial index {@code idx_interfaces_org_updated_at}. Pass a
     * {@code PageRequest.of(0, 50)} pageable to cap the result set.
     */
    @Query("SELECT i FROM InterfaceEntity i WHERE i.organizationId = :orgId ORDER BY i.updatedAt DESC")
    List<InterfaceEntity> findRecentByOrganizationIdStrict(@Param("orgId") String orgId, Pageable pageable);

    @Modifying
    @Query("DELETE FROM InterfaceEntity i WHERE i.sourceWorkflowId = :sourceWorkflowId")
    void deleteBySourceWorkflowId(@Param("sourceWorkflowId") UUID sourceWorkflowId);

    /**
     * 2026-05-18 - tenant-scoped variant of the cascade delete. Internal
     * callers (orchestrator workflow-deletion path) MUST prefer this overload
     * so a defense-in-depth gate prevents mass-delete if internal-only
     * routing ever regresses. Mirrors {@link #deleteByIdAndTenantId} shape.
     */
    @Modifying
    @Query("DELETE FROM InterfaceEntity i WHERE i.sourceWorkflowId = :sourceWorkflowId AND i.tenantId = :tenantId")
    int deleteBySourceWorkflowIdAndTenantId(@Param("sourceWorkflowId") UUID sourceWorkflowId,
                                             @Param("tenantId") String tenantId);

    @Deprecated(since = "Batch-C", forRemoval = false)
    @Modifying
    @Query("DELETE FROM InterfaceEntity i WHERE i.id = :id AND i.tenantId = :tenantId")
    int deleteByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") String tenantId);

    /**
     * Strict org-scope single-row delete. Post-V261, every user-scoped row
     * carries a non-null {@code organization_id}; this is the strict-isolation
     * delete used by org-aware service paths. Tenant ownership is NOT checked
     * here - the {@code organization_id} is the gate, and the scope-aware
     * service performs the deny-list check + ownership audit before invoking
     * this method.
     */
    @Modifying
    @Query("DELETE FROM InterfaceEntity i WHERE i.id = :id AND i.organizationId = :orgId")
    int deleteByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    /**
     * Bump {@code updated_at} on user-meaningful interface activity (action fire,
     * signal resolution from the iframe). The bell's Activity tab orders by
     * {@code interfaces.updated_at DESC} via the V236 partial index, so without
     * this bump the row would surface only on schema/config edit. Fire-and-forget
     * from orchestrator via {@code InterfaceClient.touchUpdatedAt}; returns row
     * count for caller observability.
     *
     * <p>{@code @PreUpdate} on {@code InterfaceEntity} is bypassed by JPQL bulk
     * UPDATEs (Hibernate contract), so the explicit SET is the only thing that
     * advances the column for this path.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE InterfaceEntity i SET i.updatedAt = :now WHERE i.id = :id")
    int touchUpdatedAt(@Param("id") UUID id, @Param("now") java.time.Instant now);

    /**
     * Locate the agent-browse Interface for a (conversation, message, agent)
     * triple. Same accumulator shape as {@link #findImageGenerationInterface}:
     * a single message can produce multiple successful browser-agent action
     * calls (agent_browse, browse_status, browse_intervene, …) which all
     * collapse to one Interface whose {@code data.results[]} accumulates.
     *
     * <p>Replaces the legacy {@code findWebSearchInterface} (removed 2026-05-22)
     * - search/fetch tool calls no longer persist (rendered inline via the
     * favicon stack), so the {@code web_search} type is now archived. The 1
     * pre-existing prod row with {@code interface_type='web_search'} but
     * {@code data.results[0].action='agent_browse'} is retagged by V279.
     */
    @Query("SELECT i FROM InterfaceEntity i WHERE i.tenantId = :tenantId " +
           "AND i.conversationId = :conversationId " +
           "AND i.messageId = :messageId " +
           "AND i.interfaceType = 'agent_browse' " +
           "AND (:agentId IS NULL AND i.agentId IS NULL OR i.agentId = :agentId)")
    Optional<InterfaceEntity> findAgentBrowseInterface(
        @Param("tenantId") String tenantId,
        @Param("conversationId") String conversationId,
        @Param("messageId") String messageId,
        @Param("agentId") String agentId);

    /**
     * Locate the image-generation Interface for a (conversation, message,
     * agent) triple - same grouping shape as
     * {@link #findAgentBrowseInterface}: a single message can produce
     * multiple successful tool calls, all collapsing to one Interface
     * whose {@code data.images[]} array accumulates.
     */
    @Query("SELECT i FROM InterfaceEntity i WHERE i.tenantId = :tenantId " +
           "AND i.conversationId = :conversationId " +
           "AND i.messageId = :messageId " +
           "AND i.interfaceType = 'image_generation' " +
           "AND (:agentId IS NULL AND i.agentId IS NULL OR i.agentId = :agentId)")
    Optional<InterfaceEntity> findImageGenerationInterface(
        @Param("tenantId") String tenantId,
        @Param("conversationId") String conversationId,
        @Param("messageId") String messageId,
        @Param("agentId") String agentId);
}
