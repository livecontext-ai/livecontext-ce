package com.apimarketplace.conversation.dto;

import java.util.List;

/**
 * Body of POST /api/dm/threads/{threadId}/messages. {@code content} may be blank
 * when at least one attachment is present; {@code attachments} is optional.
 */
public record SendDmMessageRequest(String content, List<DmAttachmentDto> attachments) {
}
