package com.apimarketplace.conversation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reference to an uploaded attachment.
 * Used in ChatRequest to link messages to files stored in storage.storage.
 */
public class AttachmentRef {

    @JsonProperty("storageId")
    private String storageId;

    @JsonProperty("type")
    private String type;

    @JsonProperty("fileName")
    private String fileName;

    @JsonProperty("mimeType")
    private String mimeType;

    public AttachmentRef() {}

    public AttachmentRef(String storageId, String type, String fileName, String mimeType) {
        this.storageId = storageId;
        this.type = type;
        this.fileName = fileName;
        this.mimeType = mimeType;
    }

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
}
