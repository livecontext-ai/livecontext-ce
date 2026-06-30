package com.apimarketplace.catalog.mapping.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Entity for mapping definitions
 */
@Entity
@Table(name = "mapping_definitions", schema = "catalog")
public class MappingDefinitionEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    
    @Column(name = "tool_id", nullable = false)
    private java.util.UUID toolId;
    
    
    @Column(name = "display_name")
    private String displayName;
    
    @Column(name = "status", nullable = false)
    private String status = "ACTIVE";
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @Column(name = "created_by")
    private String createdBy;
    
    @Column(name = "description")
    private String description;
    
    // One-to-many relationship with mapping versions
    @OneToMany(mappedBy = "mappingDefinition", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<MappingVersionEntity> versions;
    
    // Constructors
    public MappingDefinitionEntity() {}
    
    public MappingDefinitionEntity(java.util.UUID toolId, String displayName, String createdBy) {
        this.toolId = toolId;
        this.displayName = displayName;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
    
    // Getters and setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public java.util.UUID getToolId() {
        return toolId;
    }
    
    public void setToolId(java.util.UUID toolId) {
        this.toolId = toolId;
    }
    
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public String getCreatedBy() {
        return createdBy;
    }
    
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public List<MappingVersionEntity> getVersions() {
        return versions;
    }
    
    public void setVersions(List<MappingVersionEntity> versions) {
        this.versions = versions;
    }
    
    @Override
    public String toString() {
        return "MappingDefinitionEntity{" +
                "id=" + id +
                ", toolId=" + toolId +
                ", displayName='" + displayName + '\'' +
                ", status='" + status + '\'' +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", createdBy='" + createdBy + '\'' +
                '}';
    }
}
