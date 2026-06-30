package com.apimarketplace.orchestrator.domain.file;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a reference to a file stored in S3/MinIO.
 * This record is serialized in workflow outputs and recognized by the frontend
 * for rendering download buttons and image previews.
 *
 * Example JSON output:
 * {
 *   "_type": "file",
 *   "path": "tenant123/workflow456/run789/step1/output.pdf",
 *   "name": "report.pdf",
 *   "mimeType": "application/pdf",
 *   "size": 102400
 * }
 */
public record FileRef(
    /** Discriminator field for frontend detection */
    @JsonProperty("_type") String type,

    /** Storage path (S3 object key) */
    String path,

    /** Original filename for display/download */
    String name,

    /** MIME type of the file */
    String mimeType,

    /** File size in bytes */
    long size,

    /** storage.storage row UUID - opaque handle for {@code /api/proxy/files/by-id/{id}/raw}.
     *  Null on legacy/old refs (pre opaque-cutover). */
    @JsonInclude(JsonInclude.Include.NON_NULL) String id
) {
    /** Type discriminator value */
    public static final String TYPE_FILE = "file";

    /**
     * Creates a new FileRef with the standard type discriminator (no storage id - legacy callers).
     */
    public static FileRef of(String path, String name, String mimeType, long size) {
        return new FileRef(TYPE_FILE, path, name, mimeType, size, null);
    }

    /** Creates a FileRef carrying the storage row UUID (opaque handle). */
    public static FileRef of(String path, String name, String mimeType, long size, String id) {
        return new FileRef(TYPE_FILE, path, name, mimeType, size, id);
    }

    /**
     * Checks if an object is a FileRef based on the _type field.
     * Useful for frontend-side detection in JavaScript.
     */
    @JsonIgnore
    public boolean isFileRef() {
        return TYPE_FILE.equals(type);
    }
}
