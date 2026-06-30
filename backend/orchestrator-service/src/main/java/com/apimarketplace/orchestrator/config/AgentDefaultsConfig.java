package com.apimarketplace.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized agent defaults loaded from application-agents.yml.
 * Source of truth for default values when creating new agents.
 *
 * Note: model and provider are NOT configured here - they are resolved
 * dynamically from the AI provider catalog via AgentClient.getModelsInfo().
 *
 * Config path: ai.agent.defaults
 */
@Configuration
@ConfigurationProperties(prefix = "ai.agent.defaults")
public class AgentDefaultsConfig {

    private double temperature = 0.7;
    // Mirror of agent-service AgentDefaultsConfig.maxTokens. Clamped at runtime to
    // the model's real output ceiling (MaxTokensClamp) so this high default never
    // 400s a low-cap model (DeepSeek-chat = 8192). Keep the two in sync.
    private int maxTokens = 16000;
    private int maxIterations = 100;
    private int executionTimeout = 3600;

    /**
     * Uniform per-turn cap on resource-creation calls, applied separately to each
     * tracked resource type (agent / skill / sub_agent / interface / workflow / table).
     * Mirrored from agent-service/AgentDefaultsConfig - keep the two in sync.
     * Honored per-agent via the {@code __chatMaxPerResourcePerTurn__} credential.
     */
    private int maxPerResourcePerTurn = 5;

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getExecutionTimeout() {
        return executionTimeout;
    }

    public void setExecutionTimeout(int executionTimeout) {
        this.executionTimeout = executionTimeout;
    }

    public int getMaxPerResourcePerTurn() {
        return maxPerResourcePerTurn;
    }

    public void setMaxPerResourcePerTurn(int maxPerResourcePerTurn) {
        this.maxPerResourcePerTurn = maxPerResourcePerTurn;
    }
}
