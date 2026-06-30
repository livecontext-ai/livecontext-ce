package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.ToolResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for ToolResult entities.
 *
 * <p><strong>Scope model - INHERITED_VIA_FK (carve-out).</strong>
 * The {@code conversation.tool_results} table has NO {@code organization_id}
 * column by design (see V211 migration commentary + V263 hors-scope list).
 * Org scope is enforced upstream through the {@code conversation_id} FK:
 * every {@code tool_results} row belongs to exactly one
 * {@code conversation.conversations} row, and that parent table IS
 * org-scoped (V211 added the {@code organization_id} column there). Read
 * paths in this service always route through a parent-conversation lookup
 * that is itself scope-checked, so the {@code tenant_id} predicates on
 * these finders act as a per-user defense-in-depth filter rather than the
 * primary org-isolation gate.
 *
 * <p>Phase 6 hors-scope tables (V263) preserve this contract:
 * messages, tool_results, widget_configs, message_attachments inherit
 * scope from their parent conversation via FK - adding NOT NULL
 * {@code organization_id} columns to them was deliberately deferred.
 */
@Repository
public interface ToolResultRepository extends JpaRepository<ToolResult, UUID> {

    /**
     * Find tool result by ID and tenant (security check).
     */
    Optional<ToolResult> findByIdAndTenantId(UUID id, String tenantId);

    /**
     * Find tool results by conversation and tenant (full entities).
     */
    List<ToolResult> findByConversationIdAndTenantIdOrderByCreatedAtAsc(String conversationId, String tenantId);

    /**
     * Tenant-agnostic load by conversation. Callers MUST gate access via
     * {@link com.apimarketplace.conversation.service.ConversationQueryService#getConversationById}
     * - the row contains no organization_id of its own, so authorization is delegated to
     * the parent conversation's org-aware scope check (2026-05-23 "Failed to load result" fix).
     */
    List<ToolResult> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    /**
     * Find tool result by tool call ID and tenant.
     */
    Optional<ToolResult> findByToolCallIdAndTenantId(String toolCallId, String tenantId);

    /** Tenant-agnostic - caller MUST gate via parent conversation. See {@link #findByConversationIdOrderByCreatedAtAsc}. */
    Optional<ToolResult> findByToolCallId(String toolCallId);

    /**
     * Lightweight projection: loads all columns EXCEPT content_full.
     * content_full is replaced with NULL to keep entities lightweight (~3KB vs ~100KB each).
     * ConversationHistoryConverter uses getContentForHistory() which reads content_preview.
     */
    @Query(value = "SELECT id, conversation_id, tenant_id, tool_name, tool_call_id, " +
           "success, duration_ms, NULL AS content_full, content_preview, error_message, " +
           "metadata, execution_id, created_at " +
           "FROM conversation.tool_results " +
           "WHERE conversation_id = :conversationId AND tenant_id = :tenantId " +
           "ORDER BY created_at ASC", nativeQuery = true)
    List<ToolResult> findPreviewsByConversationIdAndTenantId(
            @Param("conversationId") String conversationId,
            @Param("tenantId") String tenantId);
}
