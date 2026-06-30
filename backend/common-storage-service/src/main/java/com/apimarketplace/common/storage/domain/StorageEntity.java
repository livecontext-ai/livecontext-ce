package com.apimarketplace.common.storage.domain;

import com.apimarketplace.common.scope.OrgScopedEntity;
import com.apimarketplace.common.scope.OrgScopedEntityListener;
import jakarta.persistence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;
import java.util.Map;

/**
 * Entite JPA pour le stockage centralise des donnees volumineuses
 * Respecte les principes SOLID et les bonnes pratiques
 */
@Entity
@EntityListeners(OrgScopedEntityListener.class)
@Table(name = "storage", schema = "storage")
public class StorageEntity implements OrgScopedEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    /**
     * Org that owns this storage row. NULL = personal scope (visible only to tenantId
     * when no X-Organization-ID is active). Non-NULL = visible to ALL members of that
     * org via org-scoped finders. Set from X-Organization-ID at controller-level on
     * INSERT; backfilled by V204 for rows tied to a workflow.
     */
    @Column(name = "organization_id")
    private String organizationId;

    @Column(name = "content_type", nullable = false)
    private String contentType;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb")
    private String data;
    
    @Column(name = "data_binary")
    private byte[] dataBinary;
    
    @Column(name = "data_text", columnDefinition = "TEXT")
    private String dataText;
    
    @Column(name = "storage_type", nullable = false)
    private String storageType = "JSON";
    
    @Column(name = "file_name")
    private String fileName;
    
    @Column(name = "file_extension")
    private String fileExtension;
    
    @Column(name = "mime_type")
    private String mimeType;
    
    @Column(name = "width")
    private Integer width;
    
    @Column(name = "height")
    private Integer height;
    
    @Column(name = "duration")
    private Integer duration;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_mapped", columnDefinition = "jsonb")
    private String dataMapped;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structure_skeleton", columnDefinition = "jsonb")
    private String structureSkeleton;
    
    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;
    
    @Column(name = "checksum")
    private String checksum;
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;
    
    @Column(name = "expires_at")
    private Instant expiresAt;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StorageStatus status = StorageStatus.ACTIVE;

    // Run context columns for direct querying
    @Column(name = "run_id")
    private String runId;

    @Column(name = "step_key")
    private String stepKey;

    @Column(name = "item_index")
    private Integer itemIndex;

    @Column(name = "epoch", nullable = false)
    private Integer epoch = 0;

    @Column(name = "spawn", nullable = false)
    private Integer spawn = 0;

    @Column(name = "workflow_id")
    private String workflowId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "source_type")
    private String sourceType;

    @Column(name = "s3_key")
    private String s3Key;

    @Column(name = "source_publication_id")
    private UUID sourcePublicationId;

    /**
     * TRUE = this row is a user/agent-created manual folder (file_name holds the folder name,
     * no s3_key / no data payload). FALSE = a normal storage row. Workflow/epoch folders are
     * virtual (derived from run-context columns), so they are never persisted as folder rows.
     */
    @Column(name = "is_folder", nullable = false)
    private boolean isFolder = false;

    /**
     * Manual folder this row is filed under (a storage row with is_folder = TRUE). NULL = top
     * level / not manually filed. A file with this set has been re-filed out of its virtual
     * workflow location into a manual folder.
     */
    @Column(name = "parent_folder_id")
    private UUID parentFolderId;

    // Constructeurs
    public StorageEntity() {}
    
    public StorageEntity(String tenantId, String contentType, Object data, 
                        Integer sizeBytes, String checksum, Instant expiresAt) {
        this.tenantId = tenantId;
        this.contentType = contentType;
        this.data = serializeToJson(data);
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.createdAt = Instant.now();
        this.accessedAt = Instant.now();
        this.expiresAt = expiresAt;
        this.status = StorageStatus.ACTIVE;
        this.storageType = "JSON";
    }
    
    // Constructeur pour donnees binaires
    public StorageEntity(String tenantId, String contentType, byte[] dataBinary,
                        String fileName, String fileExtension, String mimeType,
                        Integer sizeBytes, String checksum, Instant expiresAt) {
        this.tenantId = tenantId;
        this.contentType = contentType;
        this.data = "{}"; // Default empty JSON to satisfy NOT NULL constraint
        this.dataBinary = dataBinary;
        this.fileName = fileName;
        this.fileExtension = fileExtension;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.createdAt = Instant.now();
        this.accessedAt = Instant.now();
        this.expiresAt = expiresAt;
        this.status = StorageStatus.ACTIVE;
        this.storageType = "BINARY";
    }
    
    // Constructeur pour donnees texte
    public StorageEntity(String tenantId, String contentType, String dataText,
                        String fileName, String fileExtension, String mimeType,
                        Integer sizeBytes, String checksum, Instant expiresAt) {
        this.tenantId = tenantId;
        this.contentType = contentType;
        this.data = "{}"; // Default empty JSON to satisfy NOT NULL constraint
        this.dataText = dataText;
        this.fileName = fileName;
        this.fileExtension = fileExtension;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.createdAt = Instant.now();
        this.accessedAt = Instant.now();
        this.expiresAt = expiresAt;
        this.status = StorageStatus.ACTIVE;
        this.storageType = "TEXT";
    }
    
    // Getters et Setters
    public UUID getId() {
        return id;
    }
    
    public void setId(UUID id) {
        this.id = id;
    }
    
    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public String getData() {
        return data;
    }
    
    public void setData(String data) {
        this.data = data;
    }
    
    public void setData(Object data) {
        this.data = serializeToJson(data);
    }
    
    public Integer getSizeBytes() {
        return sizeBytes;
    }
    
    public void setSizeBytes(Integer sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
    
    public String getChecksum() {
        return checksum;
    }
    
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getAccessedAt() {
        return accessedAt;
    }
    
    public void setAccessedAt(Instant accessedAt) {
        this.accessedAt = accessedAt;
    }
    
    public Instant getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public StorageStatus getStatus() {
        return status;
    }
    
    public void setStatus(StorageStatus status) {
        this.status = status;
    }
    
    // Getters et Setters pour les nouveaux champs multimedia
    public byte[] getDataBinary() {
        return dataBinary;
    }
    
    public void setDataBinary(byte[] dataBinary) {
        this.dataBinary = dataBinary;
    }
    
    public String getDataText() {
        return dataText;
    }
    
    public void setDataText(String dataText) {
        this.dataText = dataText;
    }
    
    public String getStorageType() {
        return storageType;
    }
    
    public void setStorageType(String storageType) {
        this.storageType = storageType;
    }
    
    public String getFileName() {
        return fileName;
    }
    
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }
    
    public String getFileExtension() {
        return fileExtension;
    }
    
    public void setFileExtension(String fileExtension) {
        this.fileExtension = fileExtension;
    }
    
    public String getMimeType() {
        return mimeType;
    }
    
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }
    
    public Integer getWidth() {
        return width;
    }
    
    public void setWidth(Integer width) {
        this.width = width;
    }
    
    public Integer getHeight() {
        return height;
    }
    
    public void setHeight(Integer height) {
        this.height = height;
    }
    
    public Integer getDuration() {
        return duration;
    }
    
    public void setDuration(Integer duration) {
        this.duration = duration;
    }
    
    public String getMetadata() {
        return metadata;
    }
    
    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
    
    public void setMetadata(Object metadata) {
        this.metadata = serializeToJson(metadata);
    }
    
    public String getDataMapped() {
        return dataMapped;
    }
    
    public void setDataMapped(String dataMapped) {
        this.dataMapped = dataMapped;
    }
    
    public void setDataMapped(Object dataMapped) {
        this.dataMapped = serializeToJson(dataMapped);
    }
    
    public String getStructureSkeleton() {
        return structureSkeleton;
    }
    
    public void setStructureSkeleton(String structureSkeleton) {
        this.structureSkeleton = structureSkeleton;
    }

    // Run context getters and setters
    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getStepKey() {
        return stepKey;
    }

    public void setStepKey(String stepKey) {
        this.stepKey = stepKey;
    }

    public Integer getItemIndex() {
        return itemIndex;
    }

    public void setItemIndex(Integer itemIndex) {
        this.itemIndex = itemIndex;
    }

    public Integer getEpoch() {
        return epoch;
    }

    public void setEpoch(Integer epoch) {
        this.epoch = epoch;
    }

    public Integer getSpawn() {
        return spawn;
    }

    public void setSpawn(Integer spawn) {
        this.spawn = spawn;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public void setProjectId(UUID projectId) {
        this.projectId = projectId;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getS3Key() {
        return s3Key;
    }

    public void setS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    public UUID getSourcePublicationId() {
        return sourcePublicationId;
    }

    public void setSourcePublicationId(UUID sourcePublicationId) {
        this.sourcePublicationId = sourcePublicationId;
    }

    public boolean isFolder() {
        return isFolder;
    }

    public void setIsFolder(boolean isFolder) {
        this.isFolder = isFolder;
    }

    public UUID getParentFolderId() {
        return parentFolderId;
    }

    public void setParentFolderId(UUID parentFolderId) {
        this.parentFolderId = parentFolderId;
    }

    /**
     * Met a jour la date d'acces
     */
    public void touch() {
        this.accessedAt = Instant.now();
    }
    
    /**
     * Verifie si le storage a expire
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
    
    /**
     * Verifie si le storage est actif
     */
    public boolean isActive() {
        return status == StorageStatus.ACTIVE && !isExpired();
    }
    
    /**
     * Serialise un objet en JSON
     */
    private String serializeToJson(Object data) {
        if (data == null) {
            return null;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(data);
        } catch (Exception e) {
            // In case of error, use toString() as fallback
            return data.toString();
        }
    }
    
    /**
     * Deserialise le JSON en Map
     */
    public Map<String, Object> getDataAsMap() {
        if (data == null) {
            return null;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(data, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * Deserialise le JSON mappe en Map
     */
    public Map<String, Object> getDataMappedAsMap() {
        if (dataMapped == null) {
            return null;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.readValue(dataMapped, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
