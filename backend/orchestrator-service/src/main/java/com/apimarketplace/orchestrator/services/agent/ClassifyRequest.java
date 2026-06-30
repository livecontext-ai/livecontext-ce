package com.apimarketplace.orchestrator.services.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Request for classification execution.
 */
public record ClassifyRequest(
    String content,
    String prompt,
    List<ClassifyCategory> categories,
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
        private List<ClassifyCategory> categories = new ArrayList<>();
        private String provider;
        private String model;
        private Double temperature;
        private Integer maxTokens;
        private String tenantId;
        private String agentEntityId;

        public Builder content(String content) { this.content = content; return this; }
        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder categories(List<ClassifyCategory> categories) { this.categories = categories; return this; }
        public Builder addCategory(String label, String description) {
            this.categories.add(new ClassifyCategory(label, description));
            return this;
        }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder maxTokens(Integer maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder agentEntityId(String agentEntityId) { this.agentEntityId = agentEntityId; return this; }

        public ClassifyRequest build() {
            return new ClassifyRequest(content, prompt, categories, provider, model,
                temperature, maxTokens, tenantId, agentEntityId);
        }
    }
}
