package com.apimarketplace.interfaces.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Thin interface-service-local view over the {@code ai.agent.defaults.*} YAML keys
 * the interface tool module needs. Exposes the uniform per-resource cap so ops can
 * override it without a rebuild.
 *
 * <p>This is intentionally separate from agent-service's {@code AgentDefaultsConfig} -
 * interface-service does not depend on agent-service at compile time, and binding
 * {@code @ConfigurationProperties} to the same prefix from two unrelated classes is
 * supported by Spring Boot (each service just reads the keys it cares about).</p>
 *
 * <p>Per-agent override is honored by reading the effective cap from the
 * {@code __chatMaxPerResourcePerTurn__} credential, which
 * {@code conversation-service/AgentContextBuilder} populates from the caller-agent
 * entity (agent scope) or from {@code chatConfig.turnLimits.maxPerResourcePerTurn}
 * (conversation scope).</p>
 *
 * <p>Config path: {@code ai.agent.defaults}</p>
 */
@Configuration
@ConfigurationProperties(prefix = "ai.agent.defaults")
public class InterfaceAgentDefaultsConfig {

    /**
     * YAML default for the uniform per-resource per-turn cap, mirrored from
     * {@code agent-service/AgentDefaultsConfig.maxPerResourcePerTurn}. Keep the
     * two in sync when changing platform defaults.
     */
    private int maxPerResourcePerTurn = 5;

    public int getMaxPerResourcePerTurn() {
        return maxPerResourcePerTurn;
    }

    public void setMaxPerResourcePerTurn(int maxPerResourcePerTurn) {
        this.maxPerResourcePerTurn = maxPerResourcePerTurn;
    }
}
