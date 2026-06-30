package com.apimarketplace.conversation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.List;
import java.util.Map;

public class ChatRequest {
    @JsonProperty("message")
    private String message;
    
    @JsonProperty("model")
    private String model;
    
    @JsonProperty("provider")
    private String provider;
    
    @JsonProperty("userId")
    private String userId;
    
    @JsonProperty("conversationId")
    private String conversationId;
    
    @JsonProperty("timestamp")
    private String timestamp;
    
    @JsonProperty("conversationHistory")
    private List<ChatMessage> conversationHistory;

    @JsonProperty("attachments")
    private List<AttachmentRef> attachments;

    @JsonProperty("agentId")
    private String agentId;

    @JsonProperty("defaultSkillIds")
    private List<String> defaultSkillIds;

    private boolean defaultSkillIdsProvided;

    @JsonProperty("chatConfig")
    private Map<String, Object> chatConfig;

    /**
     * Per-conversation reasoning-effort override for CLI/bridge models
     * ({@code minimal|low|medium|high|xhigh}), chosen in the chat model selector.
     * Highest precedence - overrides the per-agent setting and per-model default.
     * Ignored by non-bridge providers. Null/blank ⇒ no override.
     */
    @JsonProperty("reasoningEffort")
    private String reasoningEffort;

    /** Source of the chat request: "CHAT" (default), "WEBHOOK", etc. */
    @JsonProperty("source")
    private String source;

    /**
     * When true, the start-of-turn cleanup MUST NOT wipe the conversation's pending
     * approval/authorization cards. Set by the frontend when this message is a RESUME sent
     * after the user resolved ONE card - the OTHER parallel cards are still pending and must
     * survive. A normal fresh message leaves this false so the prior cards are dismissed.
     */
    @JsonProperty("keepPendingActions")
    private boolean keepPendingActions;

    /** Task ID when the chat is triggered by an agent task execution. */
    @JsonProperty("taskId")
    private String taskId;

    /** Reviewer execution token when the chat is triggered by a task review run. */
    @JsonProperty("reviewerExecutionId")
    private String reviewerExecutionId;

    // Org context (set from HTTP headers, not from JSON body)
    private transient String orgId;
    private transient String orgRole;

    /**
     * Comma-separated Keycloak realm roles from the {@code X-User-Roles} header.
     * Forwarded to {@code AgentLoopContext.userRoles} so downstream guards
     * (notably the CLI-bridge access guard) can resolve {@code admin_only}
     * bridges without re-issuing a Keycloak lookup.
     *
     * <p>Header-derived, never deserialized from the JSON body.
     */
    private transient String userRoles;

    public ChatRequest() {}
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    
    public List<ChatMessage> getConversationHistory() { return conversationHistory; }
    public void setConversationHistory(List<ChatMessage> conversationHistory) { this.conversationHistory = conversationHistory; }

    public List<AttachmentRef> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentRef> attachments) { this.attachments = attachments; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public List<String> getDefaultSkillIds() { return defaultSkillIds; }
    @JsonSetter("defaultSkillIds")
    public void setDefaultSkillIds(List<String> defaultSkillIds) {
        this.defaultSkillIds = defaultSkillIds;
        this.defaultSkillIdsProvided = true;
    }
    public boolean isDefaultSkillIdsProvided() { return defaultSkillIdsProvided; }

    public Map<String, Object> getChatConfig() { return chatConfig; }
    public void setChatConfig(Map<String, Object> chatConfig) { this.chatConfig = chatConfig; }

    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public boolean isKeepPendingActions() { return keepPendingActions; }
    public void setKeepPendingActions(boolean keepPendingActions) { this.keepPendingActions = keepPendingActions; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getReviewerExecutionId() { return reviewerExecutionId; }
    public void setReviewerExecutionId(String reviewerExecutionId) { this.reviewerExecutionId = reviewerExecutionId; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getOrgRole() { return orgRole; }
    public void setOrgRole(String orgRole) { this.orgRole = orgRole; }

    public String getUserRoles() { return userRoles; }
    public void setUserRoles(String userRoles) { this.userRoles = userRoles; }

    public static class ChatMessage {
        @JsonProperty("role")
        private String role;

        @JsonProperty("content")
        private String content;

        @JsonProperty("timestamp")
        private String timestamp;

        @JsonProperty("toolCalls")
        private String toolCalls;

        public ChatMessage() {}

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getToolCalls() { return toolCalls; }
        public void setToolCalls(String toolCalls) { this.toolCalls = toolCalls; }
    }
}
