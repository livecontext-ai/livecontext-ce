package com.apimarketplace.conversation.dto;

import java.time.LocalDateTime;

/**
 * Public-share view of a message - strict allowlist of fields safe to expose
 * to anonymous viewers of a shared conversation token.
 *
 * <p>Allowlist (NOT a strip-list): adding a new field to {@link MessageDto}
 * cannot leak through this record because the {@link #from(MessageDto)}
 * factory only copies the fields enumerated in the record components.
 *
 * <p>Excluded on purpose: {@code agentId}, {@code executionId}, {@code model},
 * {@code toolCalls} (may carry system prompts / tool arguments / API tokens),
 * {@code toolCallId}, {@code toolName}, {@code feedback}, {@code attachments}
 * (would expose attachment storage IDs without auth).
 */
public record PublicMessageDto(
        String id,
        String role,
        String content,
        LocalDateTime createdAt
) {
    /**
     * Allowlist projection from an internal {@link MessageDto}. Null-safe on
     * every component so a legacy row with a missing createdAt cannot 500
     * the public endpoint.
     */
    public static PublicMessageDto from(MessageDto src) {
        if (src == null) return null;
        return new PublicMessageDto(
                src.getId(),
                src.getRole(),
                src.getContent(),
                src.getCreatedAt()
        );
    }
}
