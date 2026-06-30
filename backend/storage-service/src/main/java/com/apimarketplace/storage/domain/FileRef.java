package com.apimarketplace.storage.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a reference to a file stored in S3/MinIO.
 * This record is serialized in workflow outputs and recognized by the frontend
 * for rendering download buttons and image previews.
 *
 * <p>{@code id} = the {@code storage.storage} row UUID - the opaque handle the frontend/agent
 * use to build the {@code /api/proxy/files/by-id/{id}/raw} URL (no tenant id, no s3 key). Null on
 * legacy/old refs (which predate the opaque cutover).
 */
public record FileRef(
    @JsonProperty("_type") String type,
    String path,
    String name,
    String mimeType,
    long size,
    @JsonInclude(JsonInclude.Include.NON_NULL) String id
) {
    public static final String TYPE_FILE = "file";

    public static FileRef of(String path, String name, String mimeType, long size) {
        return new FileRef(TYPE_FILE, path, name, mimeType, size, null);
    }

    public static FileRef of(String path, String name, String mimeType, long size, String id) {
        return new FileRef(TYPE_FILE, path, name, mimeType, size, id);
    }

    @JsonIgnore
    public boolean isFileRef() {
        return TYPE_FILE.equals(type);
    }
}
