package com.apimarketplace.agent.dto;

import java.util.UUID;

/**
 * Minimal agent data needed to dispatch task/review executions.
 *
 * <p>Task dispatch runs asynchronously and then calls conversation-service. Loading
 * the full AgentEntity there forces PostgreSQL LOB access for system_prompt; this
 * projection avoids LOB columns and keeps the remote call outside a DB transaction.</p>
 */
public record AgentTaskDispatchView(
        UUID id,
        String name,
        String modelProvider,
        String modelName,
        Boolean isActive
) {
}
