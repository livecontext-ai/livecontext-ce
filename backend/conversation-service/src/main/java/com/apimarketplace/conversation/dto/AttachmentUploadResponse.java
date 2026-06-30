package com.apimarketplace.conversation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response returned after successfully uploading an attachment.
 */
public record AttachmentUploadResponse(
    @JsonProperty("storageId")
    String storageId,

    @JsonProperty("type")
    String type,

    @JsonProperty("fileName")
    String fileName,

    @JsonProperty("mimeType")
    String mimeType,

    @JsonProperty("sizeBytes")
    long sizeBytes
) {
    /**
     * Create a response from upload result
     */
    public static AttachmentUploadResponse of(String storageId, String type, String fileName, String mimeType, long sizeBytes) {
        return new AttachmentUploadResponse(storageId, type, fileName, mimeType, sizeBytes);
    }
}
