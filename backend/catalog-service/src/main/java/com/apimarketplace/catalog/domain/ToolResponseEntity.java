package com.apimarketplace.catalog.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Entite JPA pour representer les reponses multi-format des outils
 */
@Entity
@Table(name = "tool_responses")
public class ToolResponseEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id")
    private java.util.UUID id;

    @NotNull
    @Column(name = "tool_id", nullable = false)
    private java.util.UUID toolId;
    
    @Column(name = "name")
    private String name;
    
    @Column(name = "description")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema")
    private String schema;

    // JSON example content is stored in example_jsonb (this column stays NULL for
    // JSON responses, which is the vast majority). The DB column is nullable, so no
    // @NotBlank / nullable=false here: those contradicted the storage model and made
    // every managed-entity flush (e.g. setOtherResponsesAsNonDefault) fail validation.
    @Column(name = "example")
    private String example;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "example_jsonb")
    private String exampleJsonb;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structure_skeleton")
    private String structureSkeleton;
    
    @Column(name = "status_code")
    private Integer statusCode;

    @NotNull
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @NotNull
    @Convert(converter = ResponseFormatConverter.class)
    @Column(name = "format", nullable = false)
    private ResponseFormat responseFormat;


    @NotNull
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by")
    private String createdBy;
    
    // Constructeurs
    public ToolResponseEntity() {}
    
    public ToolResponseEntity(java.util.UUID toolId, String name, String description,
                             String schema, String example, String exampleJsonb, ResponseFormat responseFormat,
                             Integer statusCode, Boolean isDefault, Boolean isActive) {
        this.toolId = toolId;
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.example = example;
        this.exampleJsonb = exampleJsonb;
        this.responseFormat = responseFormat;
        this.statusCode = statusCode;
        this.isDefault = isDefault;
        this.isActive = isActive;
    }
    
    // Getters et Setters
    public java.util.UUID getId() {
        return id;
    }

    public void setId(java.util.UUID id) {
        this.id = id;
    }

    public java.util.UUID getToolId() {
        return toolId;
    }

    public void setToolId(java.util.UUID toolId) {
        this.toolId = toolId;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }
    
    public String getExample() {
        return example;
    }
    
    public void setExample(String example) {
        this.example = example;
    }

    public String getExampleJsonb() {
        return exampleJsonb;
    }

    public void setExampleJsonb(String exampleJsonb) {
        this.exampleJsonb = exampleJsonb;
    }

    public String getStructureSkeleton() {
        return structureSkeleton;
    }

    public void setStructureSkeleton(String structureSkeleton) {
        this.structureSkeleton = structureSkeleton;
    }
    
    public Integer getStatusCode() {
        return statusCode;
    }
    
    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }
    
    public Boolean getIsDefault() {
        return isDefault;
    }
    
    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(ResponseFormat responseFormat) {
        this.responseFormat = responseFormat;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
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
    
    @Override
    public String toString() {
        return "ToolResponseEntity{" +
                "id=" + id +
                ", toolId=" + toolId +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", responseFormat=" + responseFormat +
                ", statusCode=" + statusCode +
                ", isDefault=" + isDefault +
                ", isActive=" + isActive +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", createdBy='" + createdBy + '\'' +
                '}';
    }
}
