package com.apimarketplace.conversation.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * DTO for creating a new conversation
 * User ID is provided by the gateway via X-User-ID header, so no validation needed here
 */
public class CreateConversationDto {

    private String title;

    private String model;

    private String provider;

    private String workflowId;

    private String agentId;

    private String parentConversationId;

    private Boolean active = true;

    private Boolean memoryEnabled;

    private Map<String, Object> chatConfig;
    
    // Constructors
    public CreateConversationDto() {}
    
    public CreateConversationDto(String title, String model, String provider) {
        this.title = title;
        this.model = model;
        this.provider = provider;
    }
    
    // Getters and Setters
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

    @Override
    public String toString() {
        return "CreateConversationDto{" +
                "title='" + title + '\'' +
                ", model='" + model + '\'' +
                ", provider='" + provider + '\'' +
                ", active=" + active +
                '}';
    }
}
