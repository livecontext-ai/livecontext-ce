package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Request DTO for creating/updating an image-generation interface.
 *
 * <p>Mirrors {@link AgentBrowseInterfaceRequest} so the frontend's
 * visualize-card pipeline can persist + re-fetch the result via
 * {@code interfaceId}. {@code data} carries the provider-specific payload
 * (typically {@code { images: [{base64, mime_type, ...}], count, provider,
 * billing_model, prompt }}) - same shape that
 * {@code ImageGenerationModule} returns to the LLM.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImageGenerationInterfaceRequest {

    private String name;
    private String description;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("agent_id")
    private String agentId;

    private Map<String, Object> data;

    @JsonProperty("organization_id")
    private String organizationId;

    public ImageGenerationInterfaceRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }
}
