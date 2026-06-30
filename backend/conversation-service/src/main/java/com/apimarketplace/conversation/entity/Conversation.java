package com.apimarketplace.conversation.entity;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entity representing a conversation between a user and the AI assistant.
 *
 * Supports:
 * - Message history with USER, ASSISTANT, SYSTEM, and TOOL roles
 * - Optional workflow linking
 * - Pending actions for interrupted flows (e.g., waiting for credentials)
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "conversations", schema = "conversation")
public class Conversation implements OrgScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @NotBlank(message = "User ID is required")
    @Column(name = "user_id", nullable = false)
    private String userId;

    /**
     * Workspace the conversation belongs to. Post-V261 always non-null:
     * personal-workspace users carry their personal org UUID (resolved at
     * onboarding from {@code auth.organization_member.is_default=true}),
     * team-workspace users carry the active org. Strict isolation: readers
     * see {@code organization_id = :orgId} only - no IS NULL branch.
     */
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "title")
    private String title;

    @Column(name = "model")
    private String model;

    @Column(name = "provider")
    private String provider;

    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "agent_id")
    private String agentId;

    @Column(name = "parent_conversation_id")
    private String parentConversationId;

    @NotNull
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /**
     * Pending action waiting for user intervention.
     *
     * Structure:
     * {
     *   "tool_call": {"id": "call_123", "name": "catalog", "arguments": {...}},
     *   "waiting_for": "credential:gmail",
     *   "original_request": "Check my Gmail",
     *   "context_summary": "Found gmail-messages tool, need to execute",
     *   "created_at": "2025-01-03T10:00:00Z",
     *   "expires_at": "2025-01-03T11:00:00Z"
     * }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_action", columnDefinition = "jsonb")
    private Map<String, Object> pendingAction;

    /**
     * List of parallel pending actions (approval/authorization cards) awaiting the user.
     *
     * <p>The agent raises approval/authorization cards asynchronously (without pausing the
     * run), so a single turn can leave several cards waiting at once. Each element has the
     * SAME shape as {@link #pendingAction}. The legacy single {@link #pendingAction} is kept
     * in sync with element[0] for backward compatibility (see {@code PendingActionService}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_actions", columnDefinition = "jsonb")
    private List<Map<String, Object>> pendingActions = new ArrayList<>();

    /**
     * Set of approved external services for this conversation.
     * Services are identified by their type (e.g., "gmail", "slack", "stripe").
     * Once approved, the agent can execute tools from these services without further confirmation.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_services", columnDefinition = "jsonb")
    private Set<String> approvedServices = new HashSet<>();

    /**
     * User-authorized sensitive tool actions for this conversation (rule keys
     * like {@code "application:acquire"}). Shape:
     * {@code { "always": ["application:acquire"], "once": ["catalog:execute"] }}.
     * <ul>
     *   <li>{@code always} - "Toujours autoriser dans cette conversation": the rule
     *       skips the authorization card for every subsequent turn.</li>
     *   <li>{@code once} - single-shot "Autoriser": consumed by the next turn's
     *       execution-request build, then cleared (per-call default).</li>
     * </ul>
     * See {@code ToolAuthorizationApprovalService}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_tool_actions", columnDefinition = "jsonb")
    private Map<String, Object> approvedToolActions = new HashMap<>();

    @Column(name = "share_token", unique = true, length = 64)
    private String shareToken;

    @Column(name = "share_mode", nullable = false, length = 20)
    private String shareMode = "off";

    @Column(name = "memory_enabled", nullable = false)
    private Boolean memoryEnabled = true;

    /**
     * Per-conversation chat configuration for general chat (no agent).
     * Stores: temperature, maxTokens, maxIterations, executionTimeout,
     * systemPrompt, toolsMode, webSearch.
     * Null means "use defaults".
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "chat_config", columnDefinition = "jsonb")
    private Map<String, Object> chatConfig;

    /**
     * Stage 1a.2 - cached rendered [SKILLS] tree for this conversation. See
     * {@code SkillsSnapshotService} for the contract. Shape is a free-form
     * {@link Map} of {@code key / rendered_text / cached_at} (not a typed
     * record) to stay backward-compatible with future schema additions
     * (e.g. {@code agentSkillTreeVersion} once R37 ships).
     * {@code null} = no snapshot yet (first turn, or invalidated).
     *
     * <p>ORM-read-only ({@code insertable/updatable = false}): the column is
     * written exclusively through the concurrency-safe native query
     * ({@code ConversationRepository#mergeSkillsSnapshotEntry}, atomic JSONB
     * merge). Without this, any {@code conversationRepository.save(...)} of
     * an entity loaded before a native write would flush the stale snapshot
     * back and silently undo the merge - the entity has no
     * {@code @DynamicUpdate}, so a full-row UPDATE rewrites every column.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "skills_snapshot_json", columnDefinition = "jsonb",
            insertable = false, updatable = false)
    private Map<String, Object> skillsSnapshotJson;

    /**
     * Stage 1b.1 - pinned {@code ThinkingLevel} for this conversation.
     * Only written on Claude MAIN turns to keep the Anthropic prompt-cache
     * stable (the {@code thinking} parameter invalidates system+messages
     * cache when it flips). Null until the first MAIN Claude turn resolves
     * a tier. Non-Claude conversations leave this null.
     *
     * <p>ORM-read-only: written only via the race-tolerant native pin
     * ({@code ConversationRepository#pinThinkingLevelIfAbsent}); see the
     * {@code skillsSnapshotJson} note for why entity flushes must not be
     * able to rewrite this column.
     */
    @Column(name = "thinking_level_pinned", length = 16,
            insertable = false, updatable = false)
    private String thinkingLevelPinned;

    /**
     * Stage 5.1 - cached structured COLD summary for this conversation.
     * Written by {@code ColdSummarizerService} whenever the Stage 5.3
     * gate fires; read on subsequent turns and spliced into the system
     * prompt in place of the COLD zone's raw turns. Shape:
     * {@code {decisions, ids_resolved, errors_resolved, user_intents,
     * helped_actions, generated_at, model, cold_tokens_at_generation,
     * turns_covered, status}}. {@code null} = no summary yet (first turns,
     * or invalidated via Stage 5.5 rollback).
     *
     * <p>ORM-read-only: the monotone-recall guard and the stale flag live in
     * the conditional native queries ({@code ConversationRepository#updateSummaryCold},
     * {@code #markSummaryColdStale}). A plain entity flush from any of the
     * {@code save(conversation)} call sites (pending actions, title renames,
     * sharing, …) would bypass both and restore whatever envelope was loaded
     * with the entity - including resurrecting an authoritative header on a
     * summary that had been flagged stale. Read-only mapping closes that
     * bypass for good; setters remain for unit-test fixture wiring only.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary_cold", columnDefinition = "jsonb",
            insertable = false, updatable = false)
    private Map<String, Object> summaryCold;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<Message> messages = new ArrayList<>();

    // Constructors
    public Conversation() {}

    public Conversation(String userId, String title, String model, String provider) {
        this.userId = userId;
        this.title = title;
        this.model = model;
        this.provider = provider;
    }

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getParentConversationId() {
        return parentConversationId;
    }

    public void setParentConversationId(String parentConversationId) {
        this.parentConversationId = parentConversationId;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Map<String, Object> getPendingAction() {
        return pendingAction;
    }

    public void setPendingAction(Map<String, Object> pendingAction) {
        this.pendingAction = pendingAction;
    }

    public List<Map<String, Object>> getPendingActions() {
        return pendingActions;
    }

    public void setPendingActions(List<Map<String, Object>> pendingActions) {
        this.pendingActions = pendingActions != null ? pendingActions : new ArrayList<>();
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public String getShareToken() {
        return shareToken;
    }

    public void setShareToken(String shareToken) {
        this.shareToken = shareToken;
    }

    public String getShareMode() {
        return shareMode;
    }

    public void setShareMode(String shareMode) {
        this.shareMode = shareMode;
    }

    public Boolean getMemoryEnabled() {
        return memoryEnabled;
    }

    public void setMemoryEnabled(Boolean memoryEnabled) {
        this.memoryEnabled = memoryEnabled;
    }

    public Map<String, Object> getChatConfig() {
        return chatConfig;
    }

    public void setChatConfig(Map<String, Object> chatConfig) {
        this.chatConfig = chatConfig;
    }

    public Map<String, Object> getSkillsSnapshotJson() {
        return skillsSnapshotJson;
    }

    public void setSkillsSnapshotJson(Map<String, Object> skillsSnapshotJson) {
        this.skillsSnapshotJson = skillsSnapshotJson;
    }

    public String getThinkingLevelPinned() {
        return thinkingLevelPinned;
    }

    public void setThinkingLevelPinned(String thinkingLevelPinned) {
        this.thinkingLevelPinned = thinkingLevelPinned;
    }

    public Map<String, Object> getSummaryCold() {
        return summaryCold;
    }

    public void setSummaryCold(Map<String, Object> summaryCold) {
        this.summaryCold = summaryCold;
    }

    public void addMessage(Message message) {
        message.setConversation(this);
        this.messages.add(message);
    }

    public void removeMessage(Message message) {
        message.setConversation(null);
        this.messages.remove(message);
    }

    /**
     * Check if this conversation has a pending action.
     */
    public boolean hasPendingAction() {
        return pendingAction != null && !pendingAction.isEmpty();
    }

    /**
     * Get the type of action this conversation is waiting for.
     * e.g., "credential:gmail", "user_input:confirmation"
     */
    public String getWaitingFor() {
        if (pendingAction == null) return null;
        return (String) pendingAction.get("waiting_for");
    }

    /**
     * Clear all pending actions (after they've been processed).
     */
    public void clearPendingAction() {
        this.pendingAction = null;
        this.pendingActions = new ArrayList<>();
    }

    // ==================== Approved Services ====================

    public Set<String> getApprovedServices() {
        if (approvedServices == null) {
            approvedServices = new HashSet<>();
        }
        return approvedServices;
    }

    public void setApprovedServices(Set<String> approvedServices) {
        this.approvedServices = approvedServices != null ? approvedServices : new HashSet<>();
    }

    /**
     * Check if a service is approved for this conversation.
     *
     * @param serviceType The service type (e.g., "gmail", "slack")
     * @return true if the service is approved
     */
    public boolean isServiceApproved(String serviceType) {
        return approvedServices != null && approvedServices.contains(serviceType);
    }

    /**
     * Approve a service for this conversation.
     *
     * @param serviceType The service type to approve
     */
    public void approveService(String serviceType) {
        if (approvedServices == null) {
            approvedServices = new HashSet<>();
        }
        approvedServices.add(serviceType);
    }

    /**
     * Approve multiple services for this conversation.
     *
     * @param serviceTypes The service types to approve
     */
    public void approveServices(Set<String> serviceTypes) {
        if (approvedServices == null) {
            approvedServices = new HashSet<>();
        }
        approvedServices.addAll(serviceTypes);
    }

    /**
     * Revoke approval for a service.
     *
     * @param serviceType The service type to revoke
     */
    public void revokeServiceApproval(String serviceType) {
        if (approvedServices != null) {
            approvedServices.remove(serviceType);
        }
    }

    // ==================== Approved Tool Actions ====================

    public Map<String, Object> getApprovedToolActions() {
        if (approvedToolActions == null) {
            approvedToolActions = new HashMap<>();
        }
        return approvedToolActions;
    }

    public void setApprovedToolActions(Map<String, Object> approvedToolActions) {
        this.approvedToolActions = approvedToolActions != null ? approvedToolActions : new HashMap<>();
    }
}
