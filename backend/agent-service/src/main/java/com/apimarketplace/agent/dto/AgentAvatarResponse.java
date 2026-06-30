package com.apimarketplace.agent.dto;

import java.util.UUID;

/**
 * Lightweight projection of an agent - just enough for the conversation
 * sidebar to render the agent avatar next to a chat title.
 *
 * <p>Returned by {@code GET /api/agents/avatars}. Avoids serializing the
 * heavy {@code AgentEntity} payload (system prompt LOB, config, tools
 * config, …) when the caller only needs the visual identity. JPA fetches
 * just {@code id} + {@code avatar_url} via constructor projection - no LOB
 * scan, no per-row deserialization cost.
 */
public record AgentAvatarResponse(UUID id, String avatarUrl) {
}
