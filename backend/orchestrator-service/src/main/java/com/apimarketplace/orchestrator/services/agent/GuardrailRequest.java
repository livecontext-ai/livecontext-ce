package com.apimarketplace.orchestrator.services.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Request for guardrail validation.
 */
public record GuardrailRequest(
    String content,
    String prompt,
    List<GuardrailRule> rules,
    String action,  // flag, block, redact
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    String tenantId,
    String agentEntityId
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String content;
        private String prompt;
        private List<GuardrailRule> rules = new ArrayList<>();
        private String action = "flag";
        private String provider;
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private String tenantId;
        private String agentEntityId;

        public Builder content(String content) { this.content = content; return this; }
        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder rules(List<GuardrailRule> rules) { this.rules = rules; return this; }
        public Builder addRule(String id, String description) {
            this.rules.add(new GuardrailRule(id, description));
            return this;
        }
        public Builder action(String action) { this.action = action; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder agentEntityId(String agentEntityId) { this.agentEntityId = agentEntityId; return this; }

        public GuardrailRequest build() {
            return new GuardrailRequest(content, prompt, rules, action, provider, model,
                temperature, maxTokens, tenantId, agentEntityId);
        }
    }
}
