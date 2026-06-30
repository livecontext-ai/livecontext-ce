package com.apimarketplace.conversation.repository;

import com.apimarketplace.agent.summary.ColdSummaryEnvelope;
import com.apimarketplace.conversation.entity.Conversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Conversation entities
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {
    
    /**
     * Find all conversations for a specific user
     */
    Page<Conversation> findByUserIdAndActiveTrueOrderByUpdatedAtDesc(String userId, Pageable pageable);

    /**
     * Find all conversations for a specific user (including inactive)
     */
    Page<Conversation> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    /**
     * Find conversations by user ID and title containing search term
     */
    @Query("SELECT c FROM Conversation c WHERE c.userId = :userId AND c.active = true AND LOWER(c.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) ORDER BY c.updatedAt DESC")
    Page<Conversation> findByUserIdAndTitleContainingIgnoreCase(@Param("userId") String userId, @Param("searchTerm") String searchTerm, Pageable pageable);

    /**
     * Count active conversations for a user
     */
    long countByUserIdAndActiveTrue(String userId);

    /**
     * Find conversation by user ID and workflow ID
     */
    Optional<Conversation> findByUserIdAndWorkflowIdAndActiveTrue(String userId, String workflowId);

    // ──────────────────────────────────────────────────────────────────────
    // Post-V261 strict-isolation finders - org workspace only.
    //
    //   • *ByOrganizationIdStrict → returns ONLY rows tagged with the given
    //     org. Post-V261 every conversation row has a non-null
    //     organization_id (personal-workspace users get their personal org
    //     UUID from auth.organization_member.is_default=true, set at user
    //     onboarding). The legacy *ByUserIdAndOrganizationIdIsNull personal-
    //     scope twins were deleted with the V261 sweep - calling code now
    //     funnels every read through the strict-org finder.
    //
    //   The user_id is not filtered on the org-strict finders because org
    //   workspace lists EVERY team chat regardless of which member created
    //   it (membership asserted upstream by gateway X-Organization-Role).
    // ──────────────────────────────────────────────────────────────────────

    // Listing defense-in-depth: hide EMPTY workflow-linked conversations.
    //
    //   A workflow-bound conversation is meant to exist only once the user
    //   sends a message in the workflow's chat (it's created lazily on that
    //   first message). A workflow conversation with zero messages is therefore
    //   always an artifact (e.g. a caller that POSTed /conversations/workflow/{id}
    //   without ever sending, or a create-then-send-failure orphan). Excluding
    //   `c.workflowId IS NOT NULL AND c.messages IS EMPTY` from the listing keeps
    //   those empties out of the sidebar / recent list / search scope. This is
    //   self-healing: the workflow-bound find-or-create lookup
    //   (findByOrganizationIdStrictAndWorkflowIdAndActiveTrue) is NOT filtered,
    //   so the next real message reuses the same row and - now that it carries a
    //   message - it reappears in the listing. Non-workflow conversations
    //   (workflowId IS NULL: generic chats, agent chats) are never affected.
    //
    //   Agent-bound conversations (agentId set, workflowId NULL) are
    //   INTENTIONALLY left visible even when empty: the "one agent = one
    //   conversation per workspace" contract keeps a single persistent chat row
    //   per agent, so an empty agent chat is a legitimate placeholder, not an
    //   orphan. Only workflow conversations are create-on-first-message.

    /** Strict-org sidebar listing (active only). Returns ALL team chats, minus empty workflow conversations. */
    @Query("SELECT c FROM Conversation c "
         + "WHERE c.organizationId = :orgId AND c.active = true "
         + "AND (c.workflowId IS NULL OR c.messages IS NOT EMPTY) "
         + "ORDER BY c.updatedAt DESC")
    Page<Conversation> findByOrganizationIdStrictAndActiveTrueOrderByUpdatedAtDesc(
            @Param("orgId") String orgId, Pageable pageable);

    /** Strict-org sidebar listing (including inactive), minus empty workflow conversations. */
    @Query("SELECT c FROM Conversation c "
         + "WHERE c.organizationId = :orgId "
         + "AND (c.workflowId IS NULL OR c.messages IS NOT EMPTY) "
         + "ORDER BY c.updatedAt DESC")
    Page<Conversation> findByOrganizationIdStrictOrderByUpdatedAtDesc(
            @Param("orgId") String orgId, Pageable pageable);

    /** Strict-org title search. */
    @Query("SELECT c FROM Conversation c "
         + "WHERE c.organizationId = :orgId AND c.active = true "
         + "AND (c.workflowId IS NULL OR c.messages IS NOT EMPTY) "
         + "AND LOWER(c.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) "
         + "ORDER BY c.updatedAt DESC")
    Page<Conversation> findByOrganizationIdStrictAndTitleContainingIgnoreCase(
            @Param("orgId") String orgId,
            @Param("searchTerm") String searchTerm,
            Pageable pageable);

    /** Strict-org active count - matches the listing (empty workflow conversations excluded). */
    @Query("SELECT COUNT(c) FROM Conversation c "
         + "WHERE c.organizationId = :orgId AND c.active = true "
         + "AND (c.workflowId IS NULL OR c.messages IS NOT EMPTY)")
    long countByOrganizationIdStrictAndActiveTrue(@Param("orgId") String orgId);

    /** Strict-org workflow-bound conversation lookup. */
    @Query("SELECT c FROM Conversation c "
         + "WHERE c.organizationId = :orgId AND c.workflowId = :workflowId AND c.active = true")
    Optional<Conversation> findByOrganizationIdStrictAndWorkflowIdAndActiveTrue(
            @Param("orgId") String orgId, @Param("workflowId") String workflowId);

    /**
     * Strict-org agent-bound conversation lookup. The "one agent = one
     * conversation per org workspace" contract: every team member who chats with
     * the same agent converges on the same shared conversation row.
     */
    @Query("SELECT c FROM Conversation c "
         + "WHERE c.organizationId = :orgId AND c.agentId = :agentId AND c.active = true "
         + "ORDER BY c.createdAt ASC")
    List<Conversation> findByOrganizationIdStrictAndAgentIdAndActiveTrueOrderByCreatedAtAsc(
            @Param("orgId") String orgId, @Param("agentId") String agentId);

    /**
     * Strict-org message-content search. JOIN onto messages and scope by the
     * parent conversation's organization_id.
     */
    @Query("SELECT DISTINCT c FROM Conversation c JOIN c.messages m "
         + "WHERE c.organizationId = :orgId AND c.active = true "
         + "AND LOWER(m.content) LIKE LOWER(CONCAT('%', :searchTerm, '%')) "
         + "ORDER BY c.updatedAt DESC")
    Page<Conversation> findByOrganizationIdStrictAndMessageContentContaining(
            @Param("orgId") String orgId,
            @Param("searchTerm") String searchTerm,
            Pageable pageable);

    /**
     * Strict-org single fetch (scope-check gate for the detail / messages /
     * share / cancel endpoints). Returns Optional.empty when the conversation
     * exists but in a different workspace.
     */
    @Query("SELECT c FROM Conversation c WHERE c.id = :id AND c.organizationId = :orgId")
    Optional<Conversation> findByIdAndOrganizationIdStrict(
            @Param("id") String id, @Param("orgId") String orgId);

    /**
     * Find all conversations by workflow ID (for cascade deletion when workflow is deleted)
     */
    List<Conversation> findByWorkflowId(String workflowId);

    /**
     * Find all conversations by workflow ID and user ID (tenant-scoped cascade deletion)
     */
    List<Conversation> findByWorkflowIdAndUserId(String workflowId, String userId);

    /**
     * Find conversation by user ID and agent ID.
     *
     * <p>Find THE conversation for a given user+agent pair - duplicate-tolerant.
     *
     * <p>Enforces the "one agent = one conversation" contract at the read path:
     * if legacy/racy writes ever created multiple active rows, we deterministically
     * return the OLDEST one (by {@code createdAt}). This makes subsequent widget
     * sessions (and every other find-or-create caller) converge on a single stable
     * conversation instead of blowing up with {@code IncorrectResultSizeDataAccessException}
     * or silently fanning out to new conversations.
     */
    Optional<Conversation> findFirstByUserIdAndAgentIdAndActiveTrueOrderByCreatedAtAsc(String userId, String agentId);

    /**
     * Find all conversations by agent ID (for cascade deletion when agent is deleted)
     */
    List<Conversation> findByAgentId(String agentId);

    /**
     * Find all conversations by agent ID and user ID (tenant-scoped cascade deletion)
     */
    List<Conversation> findByAgentIdAndUserId(String agentId, String userId);

    
    /**
     * Find conversations with messages containing search term
     */
    @Query("SELECT DISTINCT c FROM Conversation c JOIN c.messages m WHERE c.userId = :userId AND c.active = true AND LOWER(m.content) LIKE LOWER(CONCAT('%', :searchTerm, '%')) ORDER BY c.updatedAt DESC")
    Page<Conversation> findByUserIdAndMessageContentContaining(@Param("userId") String userId, @Param("searchTerm") String searchTerm, Pageable pageable);

    Optional<Conversation> findByShareToken(String shareToken);

    /**
     * Stage 1a.2 - merge a single derivation-keyed entry into the cached
     * skills-snapshot map for one conversation. The column is a JSONB map
     * of {@code "<derivation_key>": {rendered_text, cached_at}} so the
     * agent-skills path and the request-skills path can coexist in the
     * same row without clobbering each other.
     *
     * <p>Uses PostgreSQL's native {@code ||} JSONB merge - atomic at the DB
     * layer, so two concurrent writers on the same conversation but with
     * different keys both land. Same-key concurrent writes are last-wins,
     * which is safe because both payloads are correct for that key.
     *
     * <p>The {@code COALESCE} guards the first write (column starts NULL).
     * Native query because JPQL cannot express the {@code ||} operator.
     */
    @Modifying
    @Query(value = "UPDATE conversation.conversations " +
            "SET skills_snapshot_json = COALESCE(skills_snapshot_json, '{}'::jsonb) || CAST(:entry AS jsonb) " +
            "WHERE id = :id", nativeQuery = true)
    int mergeSkillsSnapshotEntry(@Param("id") String id, @Param("entry") String entryJson);

    /**
     * Stage 1b.1 - race-tolerant pin of {@code thinking_level_pinned}. Only
     * writes on a NULL target so the first MAIN Claude turn wins; subsequent
     * concurrent resolvers are no-ops. Returns the number of rows updated
     * (0 when already pinned, 1 on first pin).
     *
     * <p>Native query because JPQL cannot express the {@code IS NULL} guard
     * without also requiring a read-then-write pattern that would race.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE conversation.conversations " +
            "SET thinking_level_pinned = :level " +
            "WHERE id = :id AND thinking_level_pinned IS NULL", nativeQuery = true)
    int pinThinkingLevelIfAbsent(@Param("id") String id, @Param("level") String level);

    /**
     * Stage 1b.1 - read-only projection of the pinned level. Returns
     * {@link Optional#empty()} when the row does not exist or the column
     * is NULL (not yet pinned). Avoids loading the full conversation entity
     * (messages, JSONB columns) when all we need is the tier tag.
     */
    @Query(value = "SELECT thinking_level_pinned FROM conversation.conversations WHERE id = :id", nativeQuery = true)
    Optional<String> findThinkingLevelPinned(@Param("id") String id);

    /**
     * Stage 5.1 (hardened) - write the full COLD summary envelope with an
     * atomic <b>monotone-recall guard</b>. The ShedLock in
     * {@code ColdSummarizerService} is the first line of defence against
     * concurrent writers, but its TTL can expire under a slow LLM call -
     * after which a stale writer would land <i>last</i> and silently shrink
     * the agent's recall (an envelope covering fewer turns replacing a
     * richer one). The {@code WHERE} guard makes the overwrite conditional
     * at the DB layer so the race is unwinnable:
     * <ul>
     *   <li>first write ({@code summary_cold IS NULL}) always lands;</li>
     *   <li>otherwise the new envelope must cover at least as many turns
     *       as the stored one ({@code :coveredCount} vs
     *       {@code jsonb_array_length(turns_covered)});</li>
     *   <li>a stored envelope marked {@code status='stale'} may always be
     *       replaced - that is the convergence escape hatch for legitimate
     *       history shrink (window reconfiguration, deleted turns), where
     *       the orchestrator marks the row stale first and the next
     *       regeneration is allowed to cover fewer turns.</li>
     * </ul>
     * Returns 0 when the guard rejected the write (caller distinguishes
     * "guard rejected" from "row missing" via {@code existsById}).
     *
     * <p>Malformed stored shapes (scalar {@code summary_cold}, jsonb-null or
     * non-array {@code turns_covered}) must never ERROR the guard - the
     * {@code CASE} on {@code jsonb_typeof} treats any non-array coverage as
     * 0, so a junk row is always overwritable by a fresh envelope
     * (self-healing, mirroring the defensive posture of the read paths).
     *
     * <p>JSONB cast is applied at the DB boundary so callers pass the
     * serialised JSON string (from Jackson) without touching pgSQL types.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE conversation.conversations " +
            "SET summary_cold = CAST(:envelope AS jsonb) " +
            "WHERE id = :id AND (" +
            "  summary_cold IS NULL" +
            "  OR CASE WHEN jsonb_typeof(summary_cold->'turns_covered') = 'array'" +
            "          THEN jsonb_array_length(summary_cold->'turns_covered')" +
            "          ELSE 0 END <= :coveredCount" +
            "  OR summary_cold->>'status' = '" + ColdSummaryEnvelope.STATUS_STALE + "'" +
            ")", nativeQuery = true)
    int updateSummaryCold(@Param("id") String id, @Param("envelope") String envelopeJson,
                          @Param("coveredCount") int coveredCount);

    /**
     * Stage 5.5 (hardened) - flag the stored COLD summary as {@code stale}
     * without dropping its content. Used when the envelope can no longer be
     * trusted verbatim (the COLD zone shrank under it, or an invalidation
     * keyword fired but the regeneration pass did not land): the renderer
     * downgrades a stale summary to a caution-worded block instead of the
     * authoritative one, and the monotone guard on
     * {@link #updateSummaryCold} lets the next regeneration replace it even
     * with smaller coverage. Conditional on the status not already being
     * {@code stale} so repeat calls are no-ops (returns 0) and the caller's
     * transition counter stays honest.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE conversation.conversations " +
            "SET summary_cold = jsonb_set(summary_cold, '{status}', '\"" +
            ColdSummaryEnvelope.STATUS_STALE + "\"') " +
            // jsonb_typeof = 'object' (not IS NOT NULL) also excludes
            // jsonb-null / scalar junk rows - jsonb_set would ERROR on those,
            // and there is nothing useful to flag anyway.
            "WHERE id = :id AND jsonb_typeof(summary_cold) = 'object' " +
            "AND summary_cold->>'status' IS DISTINCT FROM '" +
            ColdSummaryEnvelope.STATUS_STALE + "'", nativeQuery = true)
    int markSummaryColdStale(@Param("id") String id);

    /**
     * Stage 5.5 - wipe the cached COLD summary (rollback / invalidation).
     * Used by the Stage 5.3 invalidator when an incoming user turn contains
     * an explicit correction keyword ("actually, scratch that", etc.) that
     * renders the prior summary stale. Idempotent: no-op on rows that were
     * already NULL.
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE conversation.conversations " +
            "SET summary_cold = NULL " +
            "WHERE id = :id AND summary_cold IS NOT NULL", nativeQuery = true)
    int clearSummaryCold(@Param("id") String id);

    /**
     * Take a {@code FOR KEY SHARE} lock on the conversation row if it exists - the SAME lock an FK
     * insert into a child table ({@code conversation.streams}) implicitly takes on this parent. Held
     * until the caller's transaction commits, a concurrent conversation delete blocks on it, so the
     * child INSERT can no longer turn a deleted / never-persisted conversation into an unhandled
     * {@code streams_conversation_id_fkey} violation. Returns {@link Optional#empty()} when the
     * conversation does not exist - the caller then skips the child insert instead of FK-violating.
     *
     * <p>{@code FOR KEY SHARE} is the minimal correct lock: it conflicts only with delete / primary-key
     * change, NOT with the conversation's own status / title / summary UPDATEs (which take
     * {@code FOR NO KEY UPDATE}), so it adds no contention to normal conversation writes.
     */
    @Query(value = "SELECT 1 FROM conversation.conversations WHERE id = :conversationId FOR KEY SHARE",
            nativeQuery = true)
    Optional<Integer> lockConversationRowIfExists(@Param("conversationId") String conversationId);
}
