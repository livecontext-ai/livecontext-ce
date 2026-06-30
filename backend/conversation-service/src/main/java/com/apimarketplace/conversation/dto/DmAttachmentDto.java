package com.apimarketplace.conversation.dto;

/**
 * One attachment reference carried by a DM message. {@code storageId} is the UUID
 * returned by the chat attachment upload; {@code type} is the coarse classification
 * the frontend renders by (IMAGE | PDF | TEXT | OTHER).
 */
public record DmAttachmentDto(
        String storageId,
        String type,
        String fileName,
        String mimeType
) {
}
