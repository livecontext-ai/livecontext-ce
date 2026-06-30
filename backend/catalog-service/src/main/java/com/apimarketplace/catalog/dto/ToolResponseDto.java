package com.apimarketplace.catalog.dto;

import com.apimarketplace.catalog.domain.ResponseFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO pour les reponses des outils
 */
public class ToolResponseDto {
    
    private java.util.UUID id;
    
    @JsonProperty("tool_id")
    private java.util.UUID toolId;
    
    private String name;
    
    private String description;
    
    private String schema;
    
    @NotBlank(message = "Example is required")
    private String example;

    @JsonProperty("example_jsonb")
    private String exampleJsonb;

    @JsonProperty("structure_skeleton")
    private String structureSkeleton;

    @JsonProperty("format")
    private ResponseFormat responseFormat;

    @JsonProperty("status_code")
    @Min(value = 200, message = "Status code must be between 200 and 299")
    @Max(value = 299, message = "Status code must be between 200 and 299")
    private Integer statusCode = 200;
    
    @JsonProperty("is_default")
    private Boolean isDefault = false;

    @JsonProperty("is_active")
    private Boolean isActive = false;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
    
    @JsonProperty("updated_at")
    private LocalDateTime updatedAt;
    
    @JsonProperty("created_by")
    private String createdBy;
    
    // Constructeurs
    public ToolResponseDto() {}
    
    public ToolResponseDto(java.util.UUID id, java.util.UUID toolId, String name, String description,
                          String schema, String example, String exampleJsonb, String structureSkeleton, ResponseFormat responseFormat, Integer statusCode,
                          Boolean isDefault, Boolean isActive, LocalDateTime createdAt,
                          LocalDateTime updatedAt, String createdBy) {
        this.id = id;
        this.toolId = toolId;
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.example = example;
        this.exampleJsonb = exampleJsonb;
        this.structureSkeleton = structureSkeleton;
        this.responseFormat = responseFormat;
        this.statusCode = statusCode;
        this.isDefault = isDefault;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
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

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public ResponseFormat getResponseFormat() {
        return responseFormat;
    }

    public void setResponseFormat(ResponseFormat responseFormat) {
        this.responseFormat = responseFormat;
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
        return "ToolResponseDto{" +
                "id=" + id +
                ", toolId=" + toolId +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", responseFormat=" + responseFormat +
                ", statusCode=" + statusCode +
                ", isDefault=" + isDefault +
                ", isActive=" + isActive +
                ", hasStructureSkeleton=" + (structureSkeleton != null) +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                ", createdBy=" + createdBy +
                '}';
    }
}
