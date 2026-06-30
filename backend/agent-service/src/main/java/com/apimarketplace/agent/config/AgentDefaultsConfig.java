package com.apimarketplace.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Centralized agent defaults loaded from application.yml.
 * Source of truth for default values when creating new agents and for
 * per-turn / loop-detector guard thresholds that fall back to YAML when
 * the agent row has no per-agent override set (see V100 migration).
 *
 * <p>Note: model and provider are NOT configured here - they are resolved
 * dynamically from the AI provider catalog (first model of the first provider).
 * See ModelCatalogService.getEffectiveDefaultModel/Provider().</p>
 *
 * <p>Config path: {@code ai.agent.defaults}</p>
 */
@Configuration
@ConfigurationProperties(prefix = "ai.agent.defaults")
public class AgentDefaultsConfig {

    private double temperature = 0.7;
    // Output-token budget per LLM turn. Clamped at runtime to the model's real
    // ceiling via MaxTokensClamp, so this high default never 400s a low-cap
    // model (DeepSeek-chat = 8192). Keep in sync with application.yml and the
    // orchestrator-service AgentDefaultsConfig mirror.
    private int maxTokens = 16000;
    private int maxIterations = 100;
    private int executionTimeout = 3600;

    // --- Guard-threshold defaults (configurable per-agent via V100 columns) -------------------
    // These values are also enforced by CHECK constraints on the DB columns.
    // Keep YAML and constants in sync when changing defaults.

    /**
     * Uniform cap on resource-creation calls per turn, applied separately to
     * each tracked resource type: agent / skill / sub_agent / interface /
     * workflow / table. Interpreted as "up to N per type per turn".
     */
    private int maxPerResourcePerTurn = 5;

    /** LoopDetector identical-call hard-stop threshold. */
    private int loopIdenticalStop = 15;

    /** LoopDetector consecutive-call hard-stop threshold. */
    private int loopConsecutiveStop = 40;

    /**
     * Stage 5.2b - fallback model for the COLD summariser when neither
     * the agent-level override nor the agent's primary model is set.
     * Resolved by {@code AgentCompactionModelResolver.resolve(...)} as
     * the third-tier default.
     */
    private CompactionModel compactionModel = new CompactionModel();

    public CompactionModel getCompactionModel() {
        return compactionModel;
    }

    public void setCompactionModel(CompactionModel compactionModel) {
        this.compactionModel = compactionModel;
    }

    /**
     * YAML-bound {@code ai.agent.defaults.compaction-model.{provider,name}}.
     * Both fields default to Haiku per the plan (cost-sensitive summariser
     * is the intent); operators override per environment.
     */
    public static class CompactionModel {
        private String provider = "anthropic";
        private String name = "claude-haiku-4-5";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

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

    public int getLoopIdenticalStop() {
        return loopIdenticalStop;
    }

    public void setLoopIdenticalStop(int loopIdenticalStop) {
        this.loopIdenticalStop = loopIdenticalStop;
    }

    public int getLoopConsecutiveStop() {
        return loopConsecutiveStop;
    }

    public void setLoopConsecutiveStop(int loopConsecutiveStop) {
        this.loopConsecutiveStop = loopConsecutiveStop;
    }
}
