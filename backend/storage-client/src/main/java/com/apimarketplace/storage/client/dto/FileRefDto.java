package com.apimarketplace.storage.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO representing a file reference returned by storage-service.
 * Mirrors the FileRef record in storage-service. {@code id} = the storage row UUID (opaque handle).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FileRefDto(
    @JsonProperty("_type") String type,
    String path,
    String name,
    String mimeType,
    long size,
    @JsonInclude(JsonInclude.Include.NON_NULL) String id
) {
    public static final String TYPE_FILE = "file";

    public static FileRefDto of(String path, String name, String mimeType, long size) {
        return new FileRefDto(TYPE_FILE, path, name, mimeType, size, null);
    }

    public static FileRefDto of(String path, String name, String mimeType, long size, String id) {
        return new FileRefDto(TYPE_FILE, path, name, mimeType, size, id);
    }
}
