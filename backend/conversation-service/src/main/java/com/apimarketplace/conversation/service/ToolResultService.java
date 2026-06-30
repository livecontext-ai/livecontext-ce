package com.apimarketplace.conversation.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.conversation.entity.ToolResult;
import com.apimarketplace.conversation.repository.ToolResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for storing and retrieving tool execution results.
 * Uses hot/cold pattern: content_preview (first 3000 chars) for history building,
 * content_full for on-demand access via get_tool_result.
 * All tool-specific metadata (visualization, iconSlug, etc.) is stored in the metadata JSONB column.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolResultService {

    private static final int PREVIEW_MAX_CHARS = 3000;

    private final ToolResultRepository repository;
    private final StorageBreakdownService breakdownService;
    /**
     * Used to gate tool-result reads via parent-conversation scope (org-aware) instead
     * of the legacy tenant-strict predicate. A teammate viewing an org-shared
     * conversation can see its tool results even though the row's tenant_id matches the
     * agent runner, not the viewer. Without this gate, every cross-tenant view inside the
     * same org silently 404s - the 2026-05-22 "Failed to load result" prod symptom.
     */
    private final ConversationQueryService conversationQueryService;

    /**
     * Save a tool result without metadata.
     */
    @Transactional
    public ToolResult save(String conversationId, String tenantId, String toolName,
                           String toolCallId, boolean success, Long durationMs,
                           String fullContent, String errorMessage) {
        return save(conversationId, tenantId, toolName, toolCallId, success, durationMs,
                   fullContent, errorMessage, null);
    }

    /**
     * Save a tool result with metadata.
     * Metadata can contain any tool-specific data: visualization, iconSlug, displayToolName, etc.
     */
    @Transactional
    public ToolResult save(String conversationId, String tenantId, String toolName,
                           String toolCallId, boolean success, Long durationMs,
                           String fullContent, String errorMessage,
                           Map<String, Object> metadata) {
        return save(conversationId, tenantId, toolName, toolCallId, success, durationMs,
                   fullContent, errorMessage, metadata, null);
    }

    /**
     * Save a tool result with metadata and executionId.
     */
    @Transactional
    public ToolResult save(String conversationId, String tenantId, String toolName,
                           String toolCallId, boolean success, Long durationMs,
                           String fullContent, String errorMessage,
                           Map<String, Object> metadata, String executionId) {
        ToolResult result = new ToolResult(
            conversationId, tenantId, toolName, toolCallId,
            success, durationMs, fullContent, errorMessage
        );
        result.setMetadata(metadata);
        result.setExecutionId(executionId);

        // Generate hot preview for lightweight history loading
        result.setContentPreview(buildPreview(fullContent, toolCallId));

        ToolResult saved = repository.save(result);

        // Track storage breakdown
        long contentSize = fullContent != null ? fullContent.length() : 0;
        breakdownService.trackSave(tenantId, "CONVERSATIONS", contentSize);

        // DEBUG: Log at INFO level for workflow to trace metadata persistence
        if ("workflow".equals(toolName)) {
            log.info("💾 [WORKFLOW] Saved to DB - id: {}, metadata: {}", saved.getId(), metadata);
        } else {
            log.debug("Saved tool result: {} for tool: {} ({}ms) metadata keys: {}",
                     saved.getId(), toolName, durationMs, metadata != null ? metadata.keySet() : "null");
        }
        return saved;
    }

    /**
     * Get full tool result entity by ID, gated by parent-conversation access. Org-aware:
     * a caller who can {@code conversationQueryService.getConversationById(convId, userId, orgId)}
     * can read the tool result, even when the row's tenant_id matches a different
     * teammate (e.g. agent runner). Restores admin-in-org visibility without leaking
     * cross-org results.
     */
    @Transactional(readOnly = true)
    public Optional<ToolResult> getById(UUID id, String tenantId, String organizationId) {
        return repository.findById(id)
            .filter(tr -> canAccessConversation(tr.getConversationId(), tenantId, organizationId));
    }

    /** Gated by parent-conversation access - see {@link #getById}. */
    @Transactional(readOnly = true)
    public Optional<ToolResult> getByToolCallId(String toolCallId, String tenantId, String organizationId) {
        return repository.findByToolCallId(toolCallId)
            .filter(tr -> canAccessConversation(tr.getConversationId(), tenantId, organizationId));
    }

    /**
     * Get all tool results for a conversation - pre-gated by parent-conversation access
     * (single conversation scope check rather than per-row).
     */
    @Transactional(readOnly = true)
    public List<ToolResult> getByConversation(String conversationId, String tenantId, String organizationId) {
        if (!canAccessConversation(conversationId, tenantId, organizationId)) {
            return List.of();
        }
        return repository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    private boolean canAccessConversation(String conversationId, String tenantId, String organizationId) {
        return conversationQueryService.getConversationById(conversationId, tenantId, organizationId).isPresent();
    }

    /**
     * Get lightweight previews for history building (no content_full loaded).
     * Used by ConversationHistoryConverter to avoid loading full results into memory.
     */
    @Transactional(readOnly = true)
    public List<ToolResult> getPreviewsByConversation(String conversationId, String tenantId) {
        return repository.findPreviewsByConversationIdAndTenantId(conversationId, tenantId);
    }

    /**
     * Build a preview string from full content.
     * Includes exact get_tool_result call hint when truncated.
     */
    static String buildPreview(String fullContent, String toolCallId) {
        if (fullContent == null) {
            return null;
        }
        if (fullContent.length() <= PREVIEW_MAX_CHARS) {
            return fullContent;
        }
        String callId = toolCallId != null ? toolCallId : "???";
        return fullContent.substring(0, PREVIEW_MAX_CHARS)
            + "...[truncated, use get_tool_result(tool_call_id=\"" + callId + "\") for full content]";
    }
}
