package com.apimarketplace.catalog.mapping.entity;

import com.apimarketplace.catalog.mapping.dsl.MappingSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * Entity for mapping versions
 */
@Entity
@Table(name = "mapping_versions", schema = "catalog")
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
public class MappingVersionEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;
    
    @Column(name = "mapping_definition_id", nullable = false)
    private Long mappingDefinitionId;
    
    @Column(name = "version", nullable = false)
    private String version;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "spec", nullable = false)
    private String spec;
    
    @Column(name = "is_latest", nullable = false)
    private Boolean isLatest = false;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "created_by")
    private String createdBy;
    
    @Column(name = "description")
    private String description;
    
    // Many-to-one relationship with mapping definition
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mapping_definition_id", insertable = false, updatable = false)
    private MappingDefinitionEntity mappingDefinition;
    
    // Transient field for parsed spec
    @Transient
    private MappingSpec parsedSpec;
    
    // Constructors
    public MappingVersionEntity() {}
    
    public MappingVersionEntity(Long mappingDefinitionId, String version, String spec, String createdBy) {
        this.mappingDefinitionId = mappingDefinitionId;
        this.version = version;
        this.spec = spec;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }
    
    // Getters and setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public Long getMappingDefinitionId() {
        return mappingDefinitionId;
    }
    
    public void setMappingDefinitionId(Long mappingDefinitionId) {
        this.mappingDefinitionId = mappingDefinitionId;
    }
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public String getSpec() {
        return spec;
    }
    
    public void setSpec(String spec) {
        this.spec = spec;
        this.parsedSpec = null; // Reset parsed spec when raw spec changes
    }
    
    public Boolean getIsLatest() {
        return isLatest;
    }
    
    public void setIsLatest(Boolean isLatest) {
        this.isLatest = isLatest;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
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
    
    public MappingDefinitionEntity getMappingDefinition() {
        return mappingDefinition;
    }
    
    public void setMappingDefinition(MappingDefinitionEntity mappingDefinition) {
        this.mappingDefinition = mappingDefinition;
    }
    
    /**
     * Get parsed mapping spec
     */
    public MappingSpec getParsedSpec() {
        if (parsedSpec == null && spec != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                parsedSpec = mapper.readValue(spec, MappingSpec.class);
            } catch (Exception e) {
                // Log error but don't throw exception
                System.err.println("Error parsing mapping spec: " + e.getMessage());
            }
        }
        return parsedSpec;
    }
    
    /**
     * Set parsed mapping spec
     */
    public void setParsedSpec(MappingSpec parsedSpec) {
        this.parsedSpec = parsedSpec;
        if (parsedSpec != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                this.spec = mapper.writeValueAsString(parsedSpec);
            } catch (Exception e) {
                // Log error but don't throw exception
                System.err.println("Error serializing mapping spec: " + e.getMessage());
            }
        }
    }
    
    @Override
    public String toString() {
        return "MappingVersionEntity{" +
                "id=" + id +
                ", mappingDefinitionId=" + mappingDefinitionId +
                ", version='" + version + '\'' +
                ", isLatest=" + isLatest +
                ", createdAt=" + createdAt +
                ", createdBy='" + createdBy + '\'' +
                '}';
    }
}
