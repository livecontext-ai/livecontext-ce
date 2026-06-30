package com.apimarketplace.interfaces.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Request DTO for creating/updating a browser-agent (agent_browse) interface.
 *
 * <p>Replaces the legacy {@code WebSearchInterfaceRequest} (removed 2026-05-22) -
 * search/fetch results are now rendered inline via the favicon stack (commit
 * f600c8885), so the only remaining persisted-interface case is the live
 * browser-agent CDP card.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentBrowseInterfaceRequest {

    private String name;
    private String description;

    @JsonProperty("html_template")
    private String htmlTemplate;

    @JsonProperty("css_template")
    private String cssTemplate;

    @JsonProperty("js_template")
    private String jsTemplate;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("agent_id")
    private String agentId;

    private Map<String, Object> data;

    @JsonProperty("organization_id")
    private String organizationId;

    public AgentBrowseInterfaceRequest() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getHtmlTemplate() { return htmlTemplate; }
    public void setHtmlTemplate(String htmlTemplate) { this.htmlTemplate = htmlTemplate; }

    public String getCssTemplate() { return cssTemplate; }
    public void setCssTemplate(String cssTemplate) { this.cssTemplate = cssTemplate; }

    public String getJsTemplate() { return jsTemplate; }
    public void setJsTemplate(String jsTemplate) { this.jsTemplate = jsTemplate; }

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
