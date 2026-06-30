package com.apimarketplace.conversation.dto;

/**
 * DTO for message attachment information.
 * Represents attachment metadata for display (not the actual file data).
 */
public class MessageAttachmentDto {

    private String storageId;
    private String type;
    private String fileName;
    private String mimeType;
    private Integer sizeBytes;

    // Constructors
    public MessageAttachmentDto() {}

    public MessageAttachmentDto(String storageId, String type, String fileName, String mimeType, Integer sizeBytes) {
        this.storageId = storageId;
        this.type = type;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
    }

    // Getters and Setters
    public String getStorageId() {
        return storageId;
    }

    public void setStorageId(String storageId) {
        this.storageId = storageId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Integer getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Integer sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
}
