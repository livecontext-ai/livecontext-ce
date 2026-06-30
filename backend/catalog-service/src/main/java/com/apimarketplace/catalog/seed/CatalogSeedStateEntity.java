package com.apimarketplace.catalog.seed;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("catalog_seed_state")
public class CatalogSeedStateEntity {

    @Id
    private UUID id;

    @Column("seed_id")
    private String seedId;

    @Column("api_id")
    private UUID apiId;

    @Column("file_checksum")
    private String fileChecksum;

    @Column("user_modified")
    private boolean userModified;

    @Column("last_imported_at")
    private long lastImportedAt;

    @Column("spec_version")
    private String specVersion;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getSeedId() {
        return seedId;
    }

    public void setSeedId(String seedId) {
        this.seedId = seedId;
    }

    public UUID getApiId() {
        return apiId;
    }

    public void setApiId(UUID apiId) {
        this.apiId = apiId;
    }

    public String getFileChecksum() {
        return fileChecksum;
    }

    public void setFileChecksum(String fileChecksum) {
        this.fileChecksum = fileChecksum;
    }

    public boolean isUserModified() {
        return userModified;
    }

    public void setUserModified(boolean userModified) {
        this.userModified = userModified;
    }

    public long getLastImportedAt() {
        return lastImportedAt;
    }

    public void setLastImportedAt(long lastImportedAt) {
        this.lastImportedAt = lastImportedAt;
    }

    public String getSpecVersion() {
        return specVersion;
    }

    public void setSpecVersion(String specVersion) {
        this.specVersion = specVersion;
    }
}
