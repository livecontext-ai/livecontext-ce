package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CreditLedgerEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CreditLedgerRepository extends JpaRepository<CreditLedgerEntry, Long> {

    /**
     * History endpoint feed. Filters out transient {@code PLATFORM_MARKUP_RESERVE}
     * rows (committed + released rows are kept; users see {@code PLATFORM_MARKUP},
     * {@code PLATFORM_MARKUP_RELEASED}, {@code PLATFORM_MARKUP_RELEASED_TIMEOUT}).
     * The {@code _RESERVE} variant is bookkeeping-only and would confuse non-technical
     * users (it's a debit-then-flip-not-a-final-charge state).
     */
    @Query("SELECT e FROM CreditLedgerEntry e WHERE e.userId = :userId " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "ORDER BY e.createdAt DESC")
    Page<CreditLedgerEntry> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId,
                                                             @Param("orgId") String orgId,
                                                             Pageable pageable);

    /**
     * Filtered history. Same {@code _RESERVE} exclusion as {@link #findByUserIdOrderByCreatedAtDesc}
     * with an additional sourceType match. If the caller explicitly filters on
     * {@code PLATFORM_MARKUP_RESERVE} they get nothing (intentional - UI dropdown
     * never offers that source type).
     */
    @Query("SELECT e FROM CreditLedgerEntry e WHERE e.userId = :userId " +
           "AND e.sourceType = :sourceType " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "ORDER BY e.createdAt DESC")
    Page<CreditLedgerEntry> findByUserIdAndSourceTypeOrderByCreatedAtDesc(@Param("userId") Long userId,
                                                                           @Param("sourceType") String sourceType,
                                                                           @Param("orgId") String orgId,
                                                                           Pageable pageable);

    /**
     * Owner-pays history feed. Returns every ledger entry where the user
     * participated EITHER as the payer ({@code userId}) OR as the executor
     * ({@code executorUserId}). A guest member of an org sees the rows their
     * own consumes produced (payer=owner, executor=guest) in addition to any
     * rows where they were both payer and executor (solo personal sub case).
     *
     * <p>For the solo-personal-sub case, payer == executor and the row matches
     * both predicates - but the {@code OR} returns the same row only once
     * (no UNION ALL semantics in JPQL; the row is selected by ID).
     *
     * <p>Mirrors the {@code PLATFORM_MARKUP_RESERVE} exclusion of
     * {@link #findByUserIdOrderByCreatedAtDesc}.
     */
    @Query("SELECT e FROM CreditLedgerEntry e " +
           "WHERE (e.userId = :userId OR e.executorUserId = :userId) " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "ORDER BY e.createdAt DESC")
    Page<CreditLedgerEntry> findByUserParticipationOrderByCreatedAtDesc(
            @Param("userId") Long userId, Pageable pageable);

    /**
     * Workspace-scoped member view - 2026-05-22 user-reported fix. Returns
     * rows billed to {@code payerUserId} (the workspace wallet) AND executed
     * by {@code executorUserId} (the viewer). Used when the viewer is a
     * MEMBER of a TEAM workspace: they see ONLY their own activity within
     * THIS workspace, not their other workspaces and not their colleagues'.
     *
     * <p>Switching workspaces changes the {@code payerUserId} (via
     * {@code resolvePayer} which reads X-Active-Organization-ID), so the
     * result changes too - the contract the user expects.
     */
    @Query("SELECT e FROM CreditLedgerEntry e " +
           "WHERE e.userId = :payerUserId " +
           "AND e.executorUserId = :executorUserId " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "ORDER BY e.createdAt DESC")
    Page<CreditLedgerEntry> findByPayerAndExecutorOrderByCreatedAtDesc(
            @Param("payerUserId") Long payerUserId,
            @Param("executorUserId") Long executorUserId,
            @Param("orgId") String orgId,
            Pageable pageable);

    /** Same as {@link #findByPayerAndExecutorOrderByCreatedAtDesc} with sourceType filter. */
    @Query("SELECT e FROM CreditLedgerEntry e " +
           "WHERE e.userId = :payerUserId " +
           "AND e.executorUserId = :executorUserId " +
           "AND e.sourceType = :sourceType " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "ORDER BY e.createdAt DESC")
    Page<CreditLedgerEntry> findByPayerAndExecutorAndSourceTypeOrderByCreatedAtDesc(
            @Param("payerUserId") Long payerUserId,
            @Param("executorUserId") Long executorUserId,
            @Param("sourceType") String sourceType,
            @Param("orgId") String orgId,
            Pageable pageable);

    /**
     * Filtered companion of {@link #findByUserParticipationOrderByCreatedAtDesc}
     * with an additional sourceType match.
     */
    @Query("SELECT e FROM CreditLedgerEntry e " +
           "WHERE (e.userId = :userId OR e.executorUserId = :userId) " +
           "AND e.sourceType = :sourceType " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "ORDER BY e.createdAt DESC")
    Page<CreditLedgerEntry> findByUserParticipationAndSourceTypeOrderByCreatedAtDesc(
            @Param("userId") Long userId,
            @Param("sourceType") String sourceType,
            Pageable pageable);

    /**
     * Single-row lookup by sourceId + sourceType - used by
     * {@link com.apimarketplace.auth.service.CreditService#commitReservation} and
     * {@code releaseReservation} to read a reserve row under {@code findSubscriptionForUpdate}
     * lock and decide its outcome (commit vs already-committed vs expired).
     */
    Optional<CreditLedgerEntry> findFirstBySourceIdAndSourceType(String sourceId, String sourceType);

    /** Read whatever lifecycle state a sourceId is in (RESERVE / MARKUP / RELEASED*). */
    Optional<CreditLedgerEntry> findFirstBySourceId(String sourceId);

    /**
     * Lifecycle mutation lookup. Commit/release paths must lock the ledger row
     * before inspecting sourceType so sweeper and inline release cannot both
     * observe PLATFORM_MARKUP_RESERVE and refund twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM CreditLedgerEntry e WHERE e.sourceId = :sourceId")
    Optional<CreditLedgerEntry> findFirstBySourceIdForUpdate(@Param("sourceId") String sourceId);

    /**
     * Sweeper feed: expired un-committed reservations.
     * Indexed by {@code idx_cl_expires_pending} (V150).
     */
    @Query("SELECT e FROM CreditLedgerEntry e " +
           "WHERE e.sourceType = 'PLATFORM_MARKUP_RESERVE' " +
           "AND e.expiresAt IS NOT NULL " +
           "AND e.expiresAt < :now " +
           "ORDER BY e.expiresAt ASC")
    List<CreditLedgerEntry> findExpiredReserves(@Param("now") LocalDateTime now, Pageable pageable);

    @Query("SELECT e FROM CreditLedgerEntry e WHERE e.userId = :userId AND e.createdAt >= :from AND e.createdAt <= :to ORDER BY e.createdAt DESC")
    List<CreditLedgerEntry> findByUserIdAndPeriod(@Param("userId") Long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT e.sourceType, COUNT(e), SUM(ABS(e.amount)) FROM CreditLedgerEntry e " +
           "WHERE e.userId = :userId AND e.amount < 0 AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "GROUP BY e.sourceType")
    List<Object[]> getConsumptionSummary(@Param("userId") Long userId, @Param("from") LocalDateTime from,
                                          @Param("orgId") String orgId);

    /**
     * Participation-aware companion of {@link #getConsumptionSummary} - 2026-05-21
     * audit-A HIGH finding. Pre-fix, the summary widget on the /settings/quota
     * page showed payer-scoped totals while the history list + daily chart on
     * the same page used participation scope. Same-page mismatch. Switch the
     * summary to participation so all three views agree.
     */
    @Query("SELECT e.sourceType, COUNT(e), SUM(ABS(e.amount)) FROM CreditLedgerEntry e " +
           "WHERE (e.userId = :userId OR e.executorUserId = :userId) " +
           "AND e.amount < 0 AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "GROUP BY e.sourceType")
    List<Object[]> getConsumptionSummaryForParticipant(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    boolean existsBySourceId(String sourceId);

    @Query("SELECT CAST(e.createdAt AS date), e.sourceType, COUNT(e), SUM(ABS(e.amount)), " +
           "COALESCE(SUM(e.promptTokens),0) + COALESCE(SUM(e.completionTokens),0) " +
           "FROM CreditLedgerEntry e WHERE e.userId = :userId AND e.amount < 0 " +
           "AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "GROUP BY CAST(e.createdAt AS date), e.sourceType ORDER BY 1")
    List<Object[]> getDailyUsageByType(@Param("userId") Long userId, @Param("from") LocalDateTime from,
                                        @Param("orgId") String orgId);

    @Query("SELECT CAST(e.createdAt AS date), e.sourceType, COUNT(e), SUM(ABS(e.amount)), " +
           "COALESCE(SUM(e.promptTokens),0) + COALESCE(SUM(e.completionTokens),0) " +
           "FROM CreditLedgerEntry e WHERE e.userId = :userId AND e.amount < 0 " +
           "AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:sourceType IS NULL OR e.sourceType = :sourceType) " +
           "AND (:provider IS NULL OR e.provider = :provider) " +
           "AND (:model IS NULL OR e.model = :model) " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "GROUP BY CAST(e.createdAt AS date), e.sourceType ORDER BY 1")
    List<Object[]> getDailyUsageFiltered(@Param("userId") Long userId,
                                          @Param("from") LocalDateTime from,
                                          @Param("sourceType") String sourceType,
                                          @Param("provider") String provider,
                                          @Param("model") String model,
                                          @Param("orgId") String orgId);

    @Query("SELECT DISTINCT e.provider FROM CreditLedgerEntry e " +
           "WHERE e.userId = :userId AND e.provider IS NOT NULL AND e.amount < 0 AND e.createdAt >= :from " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId)")
    List<String> getDistinctProviders(@Param("userId") Long userId, @Param("from") LocalDateTime from,
                                       @Param("orgId") String orgId);

    @Query("SELECT DISTINCT e.model FROM CreditLedgerEntry e " +
           "WHERE e.userId = :userId AND e.model IS NOT NULL AND e.amount < 0 AND e.createdAt >= :from " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId)")
    List<String> getDistinctModels(@Param("userId") Long userId, @Param("from") LocalDateTime from,
                                    @Param("orgId") String orgId);

    @Query("SELECT DISTINCT e.sourceType FROM CreditLedgerEntry e " +
           "WHERE e.userId = :userId AND e.amount < 0 AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId)")
    List<String> getDistinctSourceTypes(@Param("userId") Long userId, @Param("from") LocalDateTime from,
                                         @Param("orgId") String orgId);

    // ════════════════════════════════════════════════════════════════════════
    // Participation-aware analytics (2026-05-21 fix - user-reported usage history
    // bug). Mirror the 5 analytics queries above but match
    // {@code user_id = :userId OR executor_user_id = :userId} so a TEAM-org
    // member sees aggregates of activities they performed, not just rows where
    // they are the bill payer. CreditService.getUsageAnalytics now uses these.
    // ════════════════════════════════════════════════════════════════════════

    @Query("SELECT CAST(e.createdAt AS date), e.sourceType, COUNT(e), SUM(ABS(e.amount)), " +
           "COALESCE(SUM(e.promptTokens),0) + COALESCE(SUM(e.completionTokens),0) " +
           "FROM CreditLedgerEntry e " +
           "WHERE (e.userId = :userId OR e.executorUserId = :userId) AND e.amount < 0 " +
           "AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "GROUP BY CAST(e.createdAt AS date), e.sourceType ORDER BY 1")
    List<Object[]> getDailyUsageByTypeForParticipant(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    @Query("SELECT CAST(e.createdAt AS date), e.sourceType, COUNT(e), SUM(ABS(e.amount)), " +
           "COALESCE(SUM(e.promptTokens),0) + COALESCE(SUM(e.completionTokens),0) " +
           "FROM CreditLedgerEntry e " +
           "WHERE (e.userId = :userId OR e.executorUserId = :userId) AND e.amount < 0 " +
           "AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:sourceType IS NULL OR e.sourceType = :sourceType) " +
           "AND (:provider IS NULL OR e.provider = :provider) " +
           "AND (:model IS NULL OR e.model = :model) " +
           "GROUP BY CAST(e.createdAt AS date), e.sourceType ORDER BY 1")
    List<Object[]> getDailyUsageFilteredForParticipant(@Param("userId") Long userId,
                                                       @Param("from") LocalDateTime from,
                                                       @Param("sourceType") String sourceType,
                                                       @Param("provider") String provider,
                                                       @Param("model") String model);

    @Query("SELECT DISTINCT e.provider FROM CreditLedgerEntry e " +
           "WHERE (e.userId = :userId OR e.executorUserId = :userId) " +
           "AND e.provider IS NOT NULL AND e.amount < 0 AND e.createdAt >= :from")
    List<String> getDistinctProvidersForParticipant(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    @Query("SELECT DISTINCT e.model FROM CreditLedgerEntry e " +
           "WHERE (e.userId = :userId OR e.executorUserId = :userId) " +
           "AND e.model IS NOT NULL AND e.amount < 0 AND e.createdAt >= :from")
    List<String> getDistinctModelsForParticipant(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    @Query("SELECT DISTINCT e.sourceType FROM CreditLedgerEntry e " +
           "WHERE (e.userId = :userId OR e.executorUserId = :userId) " +
           "AND e.amount < 0 AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE'")
    List<String> getDistinctSourceTypesForParticipant(@Param("userId") Long userId, @Param("from") LocalDateTime from);

    // ════════════════════════════════════════════════════════════════════════
    // Workspace-scoped member analytics - 2026-05-22 user-reported fix. Same
    // shape as the ForParticipant queries but with an additional AND clause
    // restricting to {@code user_id = :payerUserId} so the viewer sees ONLY
    // their executions within the CURRENT workspace, not their activity in
    // other workspaces they belong to.
    // ════════════════════════════════════════════════════════════════════════

    @Query("SELECT e.sourceType, COUNT(e), SUM(ABS(e.amount)) FROM CreditLedgerEntry e " +
           "WHERE e.userId = :payerUserId AND e.executorUserId = :executorUserId " +
           "AND e.amount < 0 AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "GROUP BY e.sourceType")
    List<Object[]> getConsumptionSummaryByPayerAndExecutor(@Param("payerUserId") Long payerUserId,
                                                           @Param("executorUserId") Long executorUserId,
                                                           @Param("from") LocalDateTime from,
                                                           @Param("orgId") String orgId);

    @Query("SELECT CAST(e.createdAt AS date), e.sourceType, COUNT(e), SUM(ABS(e.amount)), " +
           "COALESCE(SUM(e.promptTokens),0) + COALESCE(SUM(e.completionTokens),0) " +
           "FROM CreditLedgerEntry e " +
           "WHERE e.userId = :payerUserId AND e.executorUserId = :executorUserId " +
           "AND e.amount < 0 AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "GROUP BY CAST(e.createdAt AS date), e.sourceType ORDER BY 1")
    List<Object[]> getDailyUsageByTypeForPayerAndExecutor(@Param("payerUserId") Long payerUserId,
                                                          @Param("executorUserId") Long executorUserId,
                                                          @Param("from") LocalDateTime from,
                                                          @Param("orgId") String orgId);

    @Query("SELECT CAST(e.createdAt AS date), e.sourceType, COUNT(e), SUM(ABS(e.amount)), " +
           "COALESCE(SUM(e.promptTokens),0) + COALESCE(SUM(e.completionTokens),0) " +
           "FROM CreditLedgerEntry e " +
           "WHERE e.userId = :payerUserId AND e.executorUserId = :executorUserId " +
           "AND e.amount < 0 AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:sourceType IS NULL OR e.sourceType = :sourceType) " +
           "AND (:provider IS NULL OR e.provider = :provider) " +
           "AND (:model IS NULL OR e.model = :model) " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId) " +
           "GROUP BY CAST(e.createdAt AS date), e.sourceType ORDER BY 1")
    List<Object[]> getDailyUsageFilteredForPayerAndExecutor(@Param("payerUserId") Long payerUserId,
                                                            @Param("executorUserId") Long executorUserId,
                                                            @Param("from") LocalDateTime from,
                                                            @Param("sourceType") String sourceType,
                                                            @Param("provider") String provider,
                                                            @Param("model") String model,
                                                            @Param("orgId") String orgId);

    @Query("SELECT DISTINCT e.provider FROM CreditLedgerEntry e " +
           "WHERE e.userId = :payerUserId AND e.executorUserId = :executorUserId " +
           "AND e.provider IS NOT NULL AND e.amount < 0 AND e.createdAt >= :from " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId)")
    List<String> getDistinctProvidersForPayerAndExecutor(@Param("payerUserId") Long payerUserId,
                                                         @Param("executorUserId") Long executorUserId,
                                                         @Param("from") LocalDateTime from,
                                                         @Param("orgId") String orgId);

    @Query("SELECT DISTINCT e.model FROM CreditLedgerEntry e " +
           "WHERE e.userId = :payerUserId AND e.executorUserId = :executorUserId " +
           "AND e.model IS NOT NULL AND e.amount < 0 AND e.createdAt >= :from " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId)")
    List<String> getDistinctModelsForPayerAndExecutor(@Param("payerUserId") Long payerUserId,
                                                      @Param("executorUserId") Long executorUserId,
                                                      @Param("from") LocalDateTime from,
                                                      @Param("orgId") String orgId);

    @Query("SELECT DISTINCT e.sourceType FROM CreditLedgerEntry e " +
           "WHERE e.userId = :payerUserId AND e.executorUserId = :executorUserId " +
           "AND e.amount < 0 AND e.createdAt >= :from " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "AND (:orgId IS NULL OR e.organizationId = :orgId)")
    List<String> getDistinctSourceTypesForPayerAndExecutor(@Param("payerUserId") Long payerUserId,
                                                           @Param("executorUserId") Long executorUserId,
                                                           @Param("from") LocalDateTime from,
                                                           @Param("orgId") String orgId);

    /**
     * Sum all ledger amounts for a user. The result = expected balance.
     * Positive entries are grants/purchases, negative are consumptions.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM CreditLedgerEntry e WHERE e.userId = :userId")
    BigDecimal sumAmountByUserId(@Param("userId") Long userId);

    /**
     * Lifetime ledger sum for balance reconciliation, excluding released
     * markup reservations. A {@code PLATFORM_MARKUP_RELEASED*} row keeps its
     * original {@code -reserved} amount as an audit trail while
     * {@code releaseReservation} refunds the same amount back to the balance:
     * the release is balance-neutral but leaves the raw ledger sum short by
     * {@code reserved}, permanently, for every released reservation (partial
     * catalog results, sweeper timeouts - routine traffic). Excluding those
     * rows restores the invariant this sum exists to check:
     * {@code sum(ledger) == current balance} when no movement was lost.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM CreditLedgerEntry e WHERE e.userId = :userId " +
           "AND (e.sourceType IS NULL OR e.sourceType NOT LIKE 'PLATFORM_MARKUP_RELEASED%')")
    BigDecimal sumAmountByUserIdExcludingReleasedReserves(@Param("userId") Long userId);

    /**
     * Sum ledger amounts for a user within a billing period.
     */
    @Query("SELECT COALESCE(SUM(e.amount), 0) FROM CreditLedgerEntry e WHERE e.userId = :userId AND e.createdAt >= :periodStart")
    BigDecimal sumAmountByUserIdSince(@Param("userId") Long userId, @Param("periodStart") LocalDateTime periodStart);

    /**
     * Get all distinct user IDs that have ledger entries (for reconciliation).
     */
    @Query("SELECT DISTINCT e.userId FROM CreditLedgerEntry e")
    List<Long> findAllDistinctUserIds();

    /**
     * Sum total cost for a specific workflow run (sourceId starts with runId:).
     * Covers both WORKFLOW_NODE and AGENT_EXECUTION/CLASSIFY/GUARDRAIL entries.
     */
    @Query("SELECT COALESCE(SUM(ABS(e.amount)), 0) FROM CreditLedgerEntry e " +
           "WHERE e.userId = :userId AND e.amount < 0 AND e.sourceId LIKE :runIdPrefix " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE'")
    BigDecimal sumCostByRunId(@Param("userId") Long userId, @Param("runIdPrefix") String runIdPrefix);

    /**
     * Sum total cost for a workflow run, including tool-family source IDs that
     * carry their own prefix before the run ID - web-search
     * ({@code web-search:RUN:<runId>:...}) and web-fetch
     * ({@code web-fetch:RUN:<runId>:...}).
     */
    @Query("SELECT COALESCE(SUM(ABS(e.amount)), 0) FROM CreditLedgerEntry e " +
           "WHERE e.userId = :userId AND e.amount < 0 " +
           "AND (e.sourceId LIKE :runIdPrefix OR e.sourceId LIKE :webSearchRunIdPrefix " +
           "OR e.sourceId LIKE :webFetchRunIdPrefix) " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE'")
    BigDecimal sumCostByRunIdIncludingWebSearch(@Param("userId") Long userId,
                                                 @Param("runIdPrefix") String runIdPrefix,
                                                 @Param("webSearchRunIdPrefix") String webSearchRunIdPrefix,
                                                 @Param("webFetchRunIdPrefix") String webFetchRunIdPrefix);

    /**
     * Breakdown by sourceType for a specific workflow run.
     */
    @Query("SELECT e.sourceType, COUNT(e), SUM(ABS(e.amount)), " +
           "COALESCE(SUM(e.promptTokens), 0), COALESCE(SUM(e.completionTokens), 0) " +
           "FROM CreditLedgerEntry e WHERE e.userId = :userId AND e.amount < 0 " +
           "AND e.sourceId LIKE :runIdPrefix " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "GROUP BY e.sourceType")
    List<Object[]> getCostBreakdownByRunId(@Param("userId") Long userId, @Param("runIdPrefix") String runIdPrefix);

    /**
     * Breakdown by sourceType for a workflow run, including web-search tool
     * calls ({@code web-search:RUN:<runId>:...}) and web-fetch tool calls
     * ({@code web-fetch:RUN:<runId>:...}).
     */
    @Query("SELECT e.sourceType, COUNT(e), SUM(ABS(e.amount)), " +
           "COALESCE(SUM(e.promptTokens), 0), COALESCE(SUM(e.completionTokens), 0) " +
           "FROM CreditLedgerEntry e WHERE e.userId = :userId AND e.amount < 0 " +
           "AND (e.sourceId LIKE :runIdPrefix OR e.sourceId LIKE :webSearchRunIdPrefix " +
           "OR e.sourceId LIKE :webFetchRunIdPrefix) " +
           "AND e.sourceType <> 'PLATFORM_MARKUP_RESERVE' " +
           "GROUP BY e.sourceType")
    List<Object[]> getCostBreakdownByRunIdIncludingWebSearch(@Param("userId") Long userId,
                                                             @Param("runIdPrefix") String runIdPrefix,
                                                             @Param("webSearchRunIdPrefix") String webSearchRunIdPrefix,
                                                             @Param("webFetchRunIdPrefix") String webFetchRunIdPrefix);

    // ========== Platform Credential Markup Queries (V63) ==========

    /**
     * Sum of gross markup debits for a run (absolute value of negative amounts).
     * The prefix MUST end with {@code "%"} and include a trailing delimiter after the runId
     * (e.g. {@code "platform-markup:RUN:abc:%"}) so a runId "abc" does not match "abcd".
     * Uses {@code idx_cl_user_source_prefix} for efficient prefix-match.
     */
    @Query(value = "SELECT COALESCE(SUM(ABS(amount)), 0) FROM auth.credit_ledger " +
                   "WHERE user_id = :userId AND source_id LIKE :sourceIdPrefix AND amount < 0",
           nativeQuery = true)
    BigDecimal sumGrossCostByRunId(@Param("userId") Long userId,
                                    @Param("sourceIdPrefix") String sourceIdPrefix);

    /**
     * Net markup cost for a run (debits minus refunds), clamped at zero.
     * Refund rows carry a positive amount + {@code related_source_id} pointing to the debit;
     * if a refund over-pays the debit, {@code GREATEST(..., 0)} keeps the net non-negative.
     */
    @Query(value = "SELECT GREATEST(COALESCE(-SUM(amount), 0), 0) FROM auth.credit_ledger " +
                   "WHERE user_id = :userId AND source_id LIKE :sourceIdPrefix",
           nativeQuery = true)
    BigDecimal sumNetCostByRunId(@Param("userId") Long userId,
                                  @Param("sourceIdPrefix") String sourceIdPrefix);

    /**
     * Per-source_id breakdown of gross/refund/net for a run.
     * Row layout: {@code [source_id:String, gross:BigDecimal, refund:BigDecimal, net:BigDecimal]}.
     * Net is clamped at zero (never negative).
     */
    @Query(value = "SELECT source_id, " +
                   "       COALESCE(SUM(CASE WHEN amount < 0 THEN ABS(amount) ELSE 0 END), 0) AS gross, " +
                   "       COALESCE(SUM(CASE WHEN amount > 0 THEN amount ELSE 0 END), 0) AS refund, " +
                   "       GREATEST(COALESCE(-SUM(amount), 0), 0) AS net " +
                   "FROM auth.credit_ledger " +
                   "WHERE user_id = :userId AND source_id LIKE :sourceIdPrefix " +
                   "GROUP BY source_id " +
                   "ORDER BY source_id",
           nativeQuery = true)
    List<Object[]> getNetCostBreakdownByRunId(@Param("userId") Long userId,
                                                @Param("sourceIdPrefix") String sourceIdPrefix);

    // ----- PR11: per-member quota enforcement -----

    /**
     * Sum of debits (positive value = credits consumed) attributed to an
     * executor since a given period start. Used by {@code MemberQuotaService}
     * to compare current-period consumption against the member's cap.
     *
     * <p>Filters:
     * <ul>
     *   <li>{@code executor_user_id = :executorUserId} - quota is per-EXECUTOR,
     *       not per-payer (Q1=b safety: a member with a 100-credit cap can't
     *       burn 1000 of the owner's credits).</li>
     *   <li>{@code amount &lt; 0} - debits only; refunds/grants/zero-cost audits
     *       must NOT count against the cap. This filter ALSO excludes
     *       rejection-audit rows (zero-amount by construction in
     *       {@code CreditService.deductCredits}); the partial index
     *       {@code idx_cl_executor_date} relies on this for coverage.</li>
     *   <li>{@code created_at &gt;= :since} - current billing period only.</li>
     *   <li>{@code source_type NOT LIKE '%_REJECTED'} - DEAD defence-in-depth
     *       in v1 (rejection rows already excluded by {@code amount &lt; 0}).
     *       Kept so a future refactor landing non-zero rejections degrades
     *       into a slower scan rather than a silent correctness bug. If
     *       the rejection-amount contract changes, EITHER drop this clause
     *       (and keep the V200 partial-index predicate as-is) OR add
     *       {@code source_type NOT LIKE '%_REJECTED'} to the V200 index
     *       predicate to preserve coverage.</li>
     * </ul>
     *
     * <p>Covered by {@code idx_cl_executor_date} (V200 partial index on
     * {@code (executor_user_id, created_at DESC) WHERE amount < 0} - the
     * {@code NOT LIKE} JPQL clause is redundant relative to the index
     * predicate today; see clause note above).
     *
     * @return absolute value of consumed credits (always {@code >= 0}). Zero
     *         when no matching rows.
     */
    @Query("SELECT COALESCE(SUM(-e.amount), 0) FROM CreditLedgerEntry e " +
           "WHERE e.executorUserId = :executorUserId " +
           "  AND e.createdAt >= :since " +
           "  AND e.amount < 0 " +
           "  AND e.sourceType NOT LIKE '%_REJECTED'")
    BigDecimal sumDebitedByExecutorSince(@Param("executorUserId") Long executorUserId,
                                          @Param("since") LocalDateTime since);

    /**
     * Token-count companion of {@link #sumDebitedByExecutorSince}. Sums
     * {@code prompt_tokens + completion_tokens} for the same filter shape.
     * NULL token columns (workflow node / web search / image gen rows) are
     * coalesced to 0.
     */
    @Query("SELECT COALESCE(SUM(COALESCE(e.promptTokens, 0) + COALESCE(e.completionTokens, 0)), 0) " +
           "FROM CreditLedgerEntry e " +
           "WHERE e.executorUserId = :executorUserId " +
           "  AND e.createdAt >= :since " +
           "  AND e.amount < 0 " +
           "  AND e.sourceType NOT LIKE '%_REJECTED'")
    Long sumTokensByExecutorSince(@Param("executorUserId") Long executorUserId,
                                    @Param("since") LocalDateTime since);

}
