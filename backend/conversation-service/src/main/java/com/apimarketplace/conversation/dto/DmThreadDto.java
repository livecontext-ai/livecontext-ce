package com.apimarketplace.conversation.dto;

import java.time.Instant;

/**
 * A DM thread as seen by the requesting user. {@code otherUserId} is the participant
 * that ISN'T the caller - the frontend resolves their handle/avatar via the public
 * profile endpoint (GET /api/users/public/by-id/{otherUserId}). No display name /
 * avatar is embedded here (conversation-service never queries the auth schema).
 */
public record DmThreadDto(
        String id,
        String otherUserId,
        Instant lastMessageAt,
        String lastMessagePreview,
        long unreadCount,
        Instant createdAt
) {
}
