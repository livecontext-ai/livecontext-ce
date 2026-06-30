package com.apimarketplace.conversation.service.attachment;

import java.util.Optional;

/**
 * Object-storage backend for chat / DM attachment BYTES. Two implementations keep
 * the same contract across deployment topologies:
 * <ul>
 *   <li>{@code HttpAttachmentBlobStore} (microservice) - uploads/downloads bytes
 *       through storage-service over HTTP (storage-client).</li>
 *   <li>{@code MonolithAttachmentBlobStore} (CE monolith, {@code @Primary}) - the
 *       same operations in-process, because the storage internal HTTP controller
 *       is NOT mounted in the monolith.</li>
 * </ul>
 *
 * <p>Bytes physically live in S3 (the {@code storage.storage} row's {@code s3_key});
 * that row stays the canonical opaque handle and keeps workspace scope -
 * {@code organization_id} is stamped by the {@code OrgScopedEntityListener} from
 * the request's {@code X-Organization-ID} at upload time, exactly like every other
 * S3-backed upload.
 */
public interface AttachmentBlobStore {

    /**
     * Upload attachment bytes to object storage under {@code tenantId} (the owner),
     * within the active workspace {@code organizationId}. The s3 key is namespaced
     * {@code "<tenantId>/general/chat/..."}.
     *
     * @return the stored blob reference, or {@code null} when the upload failed.
     */
    BlobRef upload(String tenantId, String organizationId, String fileName, String mimeType, byte[] content);

    /**
     * Download attachment bytes by {@code s3Key}. {@code ownerTenantId} MUST be the
     * key's owning tenant (the row's {@code tenant_id}) - object keys are namespaced
     * {@code "<tenantId>/..."} and storage refuses a cross-tenant fetch.
     */
    Optional<byte[]> download(String ownerTenantId, String s3Key);

    /** A stored attachment blob: the storage row id (opaque handle) + its s3 key + size. */
    record BlobRef(String storageId, String s3Key, long size) {}
}
