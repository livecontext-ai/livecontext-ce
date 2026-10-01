package com.apimarketplace.agent.config;

/**
 * Marks a controller whose endpoints create, rename or clone agents, and can therefore end in
 * an agent-name conflict (V269 index). {@link AgentNameConflictExceptionHandler} applies ONLY to
 * controllers carrying this marker, so its first-ranked integrity-error handling never reaches
 * any other controller in the CE monolith.
 */
public interface AgentNameConflictSource {
}
