package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.dto.AgentAvatarResponse;
import com.apimarketplace.agent.dto.AgentTaskDispatchView;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentRepository extends JpaRepository<AgentEntity, UUID> {

    List<AgentEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);

    /** PR27 - strict-org list. */
    @Query("SELECT a FROM AgentEntity a WHERE a.organizationId = :orgId ORDER BY a.createdAt DESC")
    List<AgentEntity> findByOrganizationIdStrictOrderByCreatedAtDesc(@Param("orgId") String orgId);

    /** Strict single-row lookup for code paths that already carry the active org scope. */
    @Query("SELECT a FROM AgentEntity a WHERE a.id = :id AND a.organizationId = :orgId")
    Optional<AgentEntity> findByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    /**
     * V340 - lightweight projection of the per-agent backlog opt-in flag. Used by
     * the backlog-gating paths (ScheduledTaskPromptBuilder, AgentTaskService
     * .getTaskSummaryForPrompt, AgentDelegationModule claim/backlog) to decide
     * whether to serve / allow shared backlog work WITHOUT loading the full
     * entity (and its system_prompt LOB). Empty when the agent is missing →
     * callers treat as not-enabled (deny).
     */
    @Query("SELECT a.backlogEnabled FROM AgentEntity a WHERE a.id = :id")
    Optional<Boolean> findBacklogEnabledById(@Param("id") UUID id);

    /**
     * Lightweight task dispatch lookup. Avoids loading {@code system_prompt} LOB
     * when async task execution only needs routing/config fields.
     */
    @Query("SELECT new com.apimarketplace.agent.dto.AgentTaskDispatchView(" +
           "a.id, a.name, a.modelProvider, a.modelName, a.isActive) " +
           "FROM AgentEntity a WHERE a.id = :id")
    Optional<AgentTaskDispatchView> findTaskDispatchViewById(@Param("id") UUID id);

    /**
     * Org-strict variant of {@link #findTaskDispatchViewById(UUID)}.
     */
    @Query("SELECT new com.apimarketplace.agent.dto.AgentTaskDispatchView(" +
           "a.id, a.name, a.modelProvider, a.modelName, a.isActive) " +
           "FROM AgentEntity a WHERE a.id = :id AND a.organizationId = :orgId")
    Optional<AgentTaskDispatchView> findTaskDispatchViewByIdAndOrganizationIdStrict(@Param("id") UUID id,
                                                                                    @Param("orgId") String orgId);

    /**
     * Loads all agents for a tenant with PESSIMISTIC_WRITE (SELECT FOR UPDATE).
     * Used by circular reference validation to serialize concurrent sub-agent updates.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AgentEntity a WHERE a.tenantId = :tenantId ORDER BY a.createdAt DESC")
    List<AgentEntity> findByTenantIdForUpdate(@Param("tenantId") String tenantId);

    /**
     * Batch A (2026-05-20) - org-aware SELECT FOR UPDATE. The circular-reference
     * cycle check operates on the agent graph visible in the active workspace,
     * so locking must also be workspace-scoped. Personal-workspace callers
     * should still use {@link #findByTenantIdForUpdate} (no org switch).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AgentEntity a WHERE a.organizationId = :orgId ORDER BY a.createdAt DESC")
    List<AgentEntity> findByOrganizationIdForUpdateStrict(@Param("orgId") String orgId);

    Optional<AgentEntity> findByTenantIdAndNameAndIsActiveTrue(String tenantId, String name);

    /** PR27 - strict-org active-by-name lookup. */
    @Query("SELECT a FROM AgentEntity a WHERE a.organizationId = :orgId AND a.name = :name AND a.isActive = true")
    Optional<AgentEntity> findByOrganizationIdStrictAndNameAndIsActiveTrue(@Param("orgId") String orgId, @Param("name") String name);

    // Retourne une liste car plusieurs agents peuvent avoir le même conversationId
    // On prendra le premier (le plus récent) dans le service
    List<AgentEntity> findByConversationIdOrderByCreatedAtDesc(UUID conversationId);

    long countByTenantId(String tenantId);

    /** PR27 - strict-org count for quota display. */
    @Query("SELECT COUNT(a) FROM AgentEntity a WHERE a.organizationId = :orgId")
    long countByOrganizationIdStrict(@Param("orgId") String orgId);

    // ===== Recent-activity aggregator (V236 partial indexes back these) =====

    /**
     * Top-N agents in an org workspace ordered by last edit time. Used by
     * {@code /api/internal/agents/recent-activity} feeding orchestrator's
     * {@code RecentActivityAggregatorService}. Backed by the V236 partial
     * index {@code idx_agents_org_updated_at}.
     */
    @Query("SELECT a FROM AgentEntity a WHERE a.organizationId = :orgId ORDER BY a.updatedAt DESC")
    List<AgentEntity> findRecentByOrganizationIdStrict(@Param("orgId") String orgId, Pageable pageable);

    List<AgentEntity> findByProjectId(UUID projectId);

    List<AgentEntity> findByProjectIdAndTenantId(UUID projectId, String tenantId);

    long countByProjectId(UUID projectId);

    long countByProjectIdAndTenantId(UUID projectId, String tenantId);

    /** Batch A (2026-05-20) - org-aware project agent list. */
    @Query("SELECT a FROM AgentEntity a WHERE a.projectId = :projectId AND a.organizationId = :orgId")
    List<AgentEntity> findByProjectIdAndOrganizationIdStrict(@Param("projectId") UUID projectId,
                                                              @Param("orgId") String orgId);

    /** Batch A (2026-05-20) - org-aware project agent count. */
    @Query("SELECT COUNT(a) FROM AgentEntity a WHERE a.projectId = :projectId AND a.organizationId = :orgId")
    long countByProjectIdAndOrganizationIdStrict(@Param("projectId") UUID projectId,
                                                  @Param("orgId") String orgId);

    /**
     * Lightweight tenant ownership check - avoids loading full entity (LOB fields, etc.).
     * Used by WebSocket channel authorization (agent:activity:{id}).
     */
    boolean existsByIdAndTenantId(UUID id, String tenantId);

    /** PR27 - strict-org membership check (WS channel auth). */
    @Query("SELECT (COUNT(a) > 0) FROM AgentEntity a WHERE a.id = :id AND a.organizationId = :orgId")
    boolean existsByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    List<AgentEntity> findByWorkflowIdAndTenantId(UUID workflowId, String tenantId);

    /** Batch A (2026-05-20) - org-aware workflow agent list. */
    @Query("SELECT a FROM AgentEntity a WHERE a.workflowId = :workflowId AND a.organizationId = :orgId")
    List<AgentEntity> findByWorkflowIdAndOrganizationIdStrict(@Param("workflowId") UUID workflowId,
                                                               @Param("orgId") String orgId);

    /**
     * Find agents visible in an organization workspace. Post-V261 (2026-05-19)
     * every agent row carries a non-null organization_id, so the legacy
     * {@code OR a.tenantId = :userId} branch was dead code that also bled
     * cross-workspace rows owned by the caller. Strict-org match closes the leak.
     * Signature preserves {@code userId} (ignored) for backward compatibility;
     * Phase 11 will rename + drop the param.
     */
    @Query("SELECT a FROM AgentEntity a WHERE a.organizationId = :orgId ORDER BY a.createdAt DESC")
    List<AgentEntity> findByOrganizationOrOwner(@Param("orgId") String orgId, @Param("userId") String userId);

    /**
     * Lightweight (id, avatarUrl) projection - backs {@code GET /api/agents/avatars}
     * for the conversation sidebar. Constructor projection ensures JPA only fetches
     * the two columns we need; the system_prompt LOB and other heavy fields are not
     * loaded.
     */
    @Query("SELECT new com.apimarketplace.agent.dto.AgentAvatarResponse(a.id, a.avatarUrl) " +
           "FROM AgentEntity a WHERE a.tenantId = :tenantId " +
           "ORDER BY a.createdAt DESC")
    List<AgentAvatarResponse> findAvatarsByTenantId(@Param("tenantId") String tenantId);

    /**
     * Org-aware (id, avatarUrl) projection - same as
     * {@link #findByOrganizationOrOwner} but materializes only the two avatar
     * columns. The org-access restriction filter is applied in the service layer
     * post-query (matches the pattern used by {@link #findByOrganizationOrOwner}).
     */
    @Query("SELECT new com.apimarketplace.agent.dto.AgentAvatarResponse(a.id, a.avatarUrl) " +
           "FROM AgentEntity a WHERE a.organizationId = :orgId " +
           "ORDER BY a.createdAt DESC")
    List<AgentAvatarResponse> findAvatarsByOrganizationOrOwner(@Param("orgId") String orgId, @Param("userId") String userId);

    /** Tenant-scoped name/description ILIKE search - used by paginated list endpoint. */
    @Query("SELECT a FROM AgentEntity a WHERE a.tenantId = :tenantId " +
           "AND (LOWER(a.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(COALESCE(a.description, '')) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "ORDER BY a.createdAt DESC")
    List<AgentEntity> searchByTenant(@Param("tenantId") String tenantId, @Param("q") String q);

    /**
     * Atomically increment credits consumed for an agent.
     */
    @Modifying
    @Query("UPDATE AgentEntity a SET a.creditsConsumed = a.creditsConsumed + :credits WHERE a.id = :agentId")
    void incrementCreditsConsumed(@Param("agentId") UUID agentId, @Param("credits") BigDecimal credits);

    /**
     * Manual reset - "Reset Credits" button (§6.3). Atomically zero {@code credits_consumed}
     * only when no reservations are held. Unconditional on {@code budget_last_reset} - the
     * user explicitly asked for a reset, so the prior window boundary is irrelevant.
     *
     * <p>Replaces the pre-{@link org.hibernate.annotations.DynamicUpdate} pattern
     * {@code setCreditsConsumed(ZERO); save(agent);} which silently no-ops post-annotation
     * because {@code credits_consumed} carries {@code updatable = false}. See
     * {@code the project docs} for the writer-audit rationale.
     *
     * @return 1 if the reset happened (agent existed AND {@code credits_reserved = 0}),
     *         0 if the agent didn't exist OR a reservation is currently held.
     *         Callers MUST treat a return of 0 as "reset refused - reservations in flight"
     *         and surface 409 Conflict (the controller already pre-checks {@code reserved == 0}
     *         and returns 409 upfront; a 0 here means a race slipped through).
     */
    @Modifying
    @Query("UPDATE AgentEntity a " +
           "SET a.creditsConsumed = 0, " +
           "    a.creditsConsumedFromSubagents = 0, " +
           "    a.budgetLastReset = :now " +
           "WHERE a.id = :id " +
           "  AND a.creditsReserved = 0")
    int zeroCreditsConsumedById(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Auto-reset with CAS - used by both
     * {@link com.apimarketplace.agent.service.budget.BudgetResolver#resolveAndPersist}
     * (§4.6, lazy per-call path) and
     * {@link com.apimarketplace.agent.service.AgentService#resetBudgetIfNeeded}
     * (§6.2, polled endpoint). Atomically zero {@code credits_consumed} when:
     * <ul>
     *   <li>the agent exists</li>
     *   <li>no reservations are held ({@code credits_reserved = 0})</li>
     *   <li>{@code budget_last_reset} still matches the value the caller read
     *       when it decided the rollover was due (CAS - prevents double-reset
     *       when two auto-reset paths race)</li>
     * </ul>
     *
     * <p>NULL handling: agents created before V68 (and any monthly/weekly agent that
     * has never rolled over) have {@code budget_last_reset = NULL}. A naive
     * {@code WHERE a.budgetLastReset = :expectedLastReset} would never match when
     * both sides are NULL (JPQL {@code = NULL} is UNKNOWN, not TRUE), silently
     * refusing the very first reset. Postgres ALSO rejects a single JPQL query that
     * mixes {@code :param IS NULL} with an untyped {@code :param} binding (SQLSTATE
     * 42P18 - "could not determine data type of parameter"). So we split into two
     * variants and the caller dispatches: {@link #resetConsumedIfFirstReset} for the
     * first-reset branch (expected == null) and this method for the steady-state
     * branch (expected != null).
     *
     * <p><strong>Callers MUST {@code entityManager.detach(agent)} after calling this
     * method</strong> to prevent a JPA dirty flush of the in-memory entity from
     * racing a second UPDATE against the one we just landed. See
     * {@code the project docs}.
     *
     * @return 1 if the reset happened (all three predicates matched),
     *         0 if the agent didn't exist OR {@code credits_reserved > 0} OR
     *         {@code budget_last_reset} was bumped by a concurrent reset since the
     *         caller's read. On 0, callers typically log at INFO and surface "not
     *         reset" - the next poll re-reads and either skips (already reset) or
     *         retries (still blocked).
     */
    @Modifying
    @Query("UPDATE AgentEntity a " +
           "SET a.creditsConsumed = 0, " +
           "    a.creditsConsumedFromSubagents = 0, " +
           "    a.budgetLastReset = :now " +
           "WHERE a.id = :id " +
           "  AND a.creditsReserved = 0 " +
           "  AND a.budgetLastReset = :expectedLastReset")
    int resetConsumedIfUnreservedAndUnchanged(
        @Param("id") UUID id,
        @Param("now") Instant now,
        @Param("expectedLastReset") Instant expectedLastReset);

    /**
     * First-reset variant of {@link #resetConsumedIfUnreservedAndUnchanged} - used
     * when {@code budgetLastReset} has never been set (e.g. a weekly/monthly agent
     * that has never rolled over). Semantics are identical except the CAS predicate
     * matches {@code budget_last_reset IS NULL} instead of a timestamp equality.
     *
     * <p>Split from the steady-state query to sidestep Postgres SQLSTATE 42P18
     * ("could not determine data type of parameter") which fires when a nullable
     * {@code Instant} parameter is bound into both {@code IS NULL} and a direct
     * equality inside the same disjunction - Postgres cannot infer the type of the
     * untyped null binding.
     */
    @Modifying
    @Query("UPDATE AgentEntity a " +
           "SET a.creditsConsumed = 0, " +
           "    a.creditsConsumedFromSubagents = 0, " +
           "    a.budgetLastReset = :now " +
           "WHERE a.id = :id " +
           "  AND a.creditsReserved = 0 " +
           "  AND a.budgetLastReset IS NULL")
    int resetConsumedIfFirstReset(
        @Param("id") UUID id,
        @Param("now") Instant now);

    /**
     * Aggregate fleet counters directly in SQL - avoids loading full entities (and LOB fields).
     * Returns a single row: [count, sumExecutions, sumTokens, sumToolCalls, sumDuration, sumSuccess, sumFailure, sumCreditsConsumed].
     *
     * <p>Legacy tenant-only variant. New callers should prefer the scope-aware
     * {@link #getFleetCountersByOrganizationIdStrict} so org workspace users see
     * org-wide rollups instead of just their personal contribution.
     */
    @Query("SELECT COUNT(a), " +
           "COALESCE(SUM(a.totalExecutions), 0), " +
           "COALESCE(SUM(a.totalTokensUsed), 0), " +
           "COALESCE(SUM(a.totalToolCalls), 0), " +
           "COALESCE(SUM(a.totalDurationMs), 0), " +
           "COALESCE(SUM(a.successCount), 0), " +
           "COALESCE(SUM(a.failureCount), 0), " +
           "COALESCE(SUM(a.creditsConsumed), 0) " +
           "FROM AgentEntity a WHERE a.tenantId = :tenantId")
    Object[] getFleetCounters(@Param("tenantId") String tenantId);

    /**
     * PR29 - strict-org fleet counters. Aggregates over agents tagged with the
     * given organizationId. Same shape as {@link #getFleetCounters(String)}.
     */
    @Query("SELECT COUNT(a), " +
           "COALESCE(SUM(a.totalExecutions), 0), " +
           "COALESCE(SUM(a.totalTokensUsed), 0), " +
           "COALESCE(SUM(a.totalToolCalls), 0), " +
           "COALESCE(SUM(a.totalDurationMs), 0), " +
           "COALESCE(SUM(a.successCount), 0), " +
           "COALESCE(SUM(a.failureCount), 0), " +
           "COALESCE(SUM(a.creditsConsumed), 0) " +
           "FROM AgentEntity a WHERE a.organizationId = :orgId")
    Object[] getFleetCountersByOrganizationIdStrict(@Param("orgId") String orgId);
}
