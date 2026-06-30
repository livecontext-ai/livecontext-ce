package com.apimarketplace.conversation.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DTO for Conversation operations.
 *
 * Supports:
 * - Basic conversation metadata
 * - Optional workflow linking
 * - Pending actions for interrupted flows
 */
public class ConversationDto {

    private String id;

    @NotBlank(message = "User ID is required")
    private String userId;

    /**
     * PR21 - workspace identity. NULL = personal scope, non-null = org workspace
     * the chat was created in. Reads route to strict-isolation finders based on this.
     */
    private String organizationId;

    private String title;

    private String model;

    private String provider;

    private String workflowId;

    private String agentId;

    private String parentConversationId;

    private Boolean active = true;

    /**
     * Pending action waiting for user intervention.
     * Structure: {tool_call, waiting_for, original_request, context_summary, created_at, expires_at}
     */
    private Map<String, Object> pendingAction;

    /**
     * Parallel pending actions (approval/authorization cards) awaiting the user.
     * Each element mirrors {@link #pendingAction}; the frontend renders one card per entry.
     * {@link #pendingAction} stays in sync with element[0] for backward compatibility.
     */
    private List<Map<String, Object>> pendingActions;

    /**
     * Set of approved external services for this conversation.
     * Services are identified by their type (e.g., "gmail", "slack", "stripe").
     * Once approved, the agent can execute tools from these services without further confirmation.
     */
    private Set<String> approvedServices;

    private String shareToken;

    private String shareMode;

    private Boolean memoryEnabled;

    private Map<String, Object> chatConfig;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<MessageDto> messages;

    private Long messageCount;

    /**
     * Truncated content of the first user message (max 100 chars). Used as a
     * sidebar title fallback when the conversation has no meaningful title
     * (e.g. agent/workflow conversations whose entity was deleted).
     */
    private String firstMessagePreview;

    /**
     * Lightweight marker of the most recent COLD-zone compaction, if any.
     * Populated from {@code conversation.summary_cold} (JSONB column) and
     * exposed so the frontend can render a "prior context summarised"
     * divider between the last COLD turn and the HOT+WARM window - giving
     * the user a persistent visual cue that the LLM no longer sees the
     * oldest raw turns verbatim. Null when no summary has been generated.
     *
     * <p>Intentionally excludes the actual summary text / user_intents /
     * fork points - those are internal to the summariser pipeline and add
     * payload weight to every conversation fetch without user value.
     */
    private CompactionMarker compactionMarker;

    /**
     * Lightweight projection of {@code conversation.summary_cold} for the
     * frontend. Mirrors only the fields the UI needs to render a divider:
     * which turns are covered (so the marker is placed at the right
     * position), when the summary was generated, and which model produced
     * it (for display / debugging).
     */
    public static class CompactionMarker {
        private List<Integer> turnsCovered;
        private String generatedAt;
        private String model;
        /**
         * Recall trust level of the stored summary: {@code "active"} (or
         * null, for rows predating the field) when fresh, {@code "stale"}
         * once flagged unreliable (COLD zone shrank under it, or the user
         * corrected course and no regeneration has landed yet).
         */
        private String status;

        public CompactionMarker() {}

        public CompactionMarker(List<Integer> turnsCovered, String generatedAt, String model) {
            this(turnsCovered, generatedAt, model, null);
        }

        public CompactionMarker(List<Integer> turnsCovered, String generatedAt, String model,
                                String status) {
            this.turnsCovered = turnsCovered;
            this.generatedAt = generatedAt;
            this.model = model;
            this.status = status;
        }

        public List<Integer> getTurnsCovered() { return turnsCovered; }
        public void setTurnsCovered(List<Integer> turnsCovered) { this.turnsCovered = turnsCovered; }

        public String getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    // Constructors
    public ConversationDto() {}

    public ConversationDto(String userId, String title, String model, String provider) {
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
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
        this.pendingActions = pendingActions;
    }

    public Set<String> getApprovedServices() {
        return approvedServices;
    }

    public void setApprovedServices(Set<String> approvedServices) {
        this.approvedServices = approvedServices;
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

    public List<MessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<MessageDto> messages) {
        this.messages = messages;
    }

    public Long getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Long messageCount) {
        this.messageCount = messageCount;
    }

    /**
     * Check if this conversation has a pending action.
     */
    public boolean hasPendingAction() {
        return pendingAction != null && !pendingAction.isEmpty();
    }

    /**
     * Get the type of action this conversation is waiting for.
     */
    public String getWaitingFor() {
        if (pendingAction == null) return null;
        return (String) pendingAction.get("waiting_for");
    }

    public String getFirstMessagePreview() { return firstMessagePreview; }
    public void setFirstMessagePreview(String firstMessagePreview) { this.firstMessagePreview = firstMessagePreview; }

    public CompactionMarker getCompactionMarker() { return compactionMarker; }
    public void setCompactionMarker(CompactionMarker compactionMarker) { this.compactionMarker = compactionMarker; }
}
