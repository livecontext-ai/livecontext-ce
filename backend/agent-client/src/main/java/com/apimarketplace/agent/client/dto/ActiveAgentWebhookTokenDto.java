package com.apimarketplace.agent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal projection of an agent webhook token row, used by the dashboard
 * "active automations" widget. Returned in batch by
 * {@code GET /api/internal/agents/active-webhook-tokens} so orchestrator
 * can enumerate every active agent webhook for a tenant in one call.
 *
 * <p>Only carries the fields the widget actually needs - the token value
 * is included so the URL can be reconstructed client-side; auth config
 * and memory flags stay inside agent-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActiveAgentWebhookTokenDto {

    private UUID agentId;
    private String token;
    private String httpMethod;
    private Boolean isActive;
    private Instant updatedAt;

    public ActiveAgentWebhookTokenDto() {}

    public ActiveAgentWebhookTokenDto(UUID agentId, String token, String httpMethod, Boolean isActive, Instant updatedAt) {
        this.agentId = agentId;
        this.token = token;
        this.httpMethod = httpMethod;
        this.isActive = isActive;
        this.updatedAt = updatedAt;
    }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
