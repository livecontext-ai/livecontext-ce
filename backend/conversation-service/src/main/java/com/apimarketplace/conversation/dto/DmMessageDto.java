package com.apimarketplace.conversation.dto;

import java.time.Instant;
import java.util.List;

/**
 * One DM message. {@code readAt} non-null once the recipient has opened the thread.
 * {@code attachments} is empty (never null) for text-only messages.
 */
public record DmMessageDto(
        String id,
        String threadId,
        String senderUserId,
        String content,
        List<DmAttachmentDto> attachments,
        Instant readAt,
        Instant createdAt
) {
}
