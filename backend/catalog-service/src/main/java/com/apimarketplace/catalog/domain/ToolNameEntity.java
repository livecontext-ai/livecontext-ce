package com.apimarketplace.catalog.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Entity representing a tool name
 */
@Table("tool_names")
public class ToolNameEntity {
    
    @Id
    @Column("id")
    private UUID id;
    
    @Column("name")
    private String name;
    
    @Column("description")
    private String description;
    
    @Column("tool_category_id")
    private UUID toolCategoryId;

    @Column("subcategory_id")
    private UUID subcategoryId;

    // REMOVED: method and endpoint_pattern - these are API-specific, stored in ApiToolEntity only
    // Columns still exist in DB but are no longer used by the application

    @Column("run_scope")
    private String runScope;

    @Column("requires_user_credentials")
    private Boolean requiresUserCredentials = false;
    
    @Column("slug")
    private String slug;
    
    @Column("is_active")
    private Boolean isActive = false;
    
    @Column("created_at")
    private Long createdAt;
    
    @Column("updated_at")
    private Long updatedAt;
    
    
    // Constructors
    public ToolNameEntity() {}

    public ToolNameEntity(UUID id, String name, String description, UUID toolCategoryId, UUID subcategoryId,
                         String runScope, Boolean requiresUserCredentials,
                         String slug, Boolean isActive, Long createdAt, Long updatedAt) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.toolCategoryId = toolCategoryId;
        this.subcategoryId = subcategoryId;
        this.runScope = runScope;
        this.requiresUserCredentials = requiresUserCredentials;
        this.slug = slug;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
    
    // Getters and Setters
    public UUID getId() {
        return id;
    }
    
    public void setId(UUID id) {
        this.id = id;
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
    
    public UUID getToolCategoryId() {
        return toolCategoryId;
    }
    
    public void setToolCategoryId(UUID toolCategoryId) {
        this.toolCategoryId = toolCategoryId;
    }

    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    public void setSubcategoryId(UUID subcategoryId) {
        this.subcategoryId = subcategoryId;
    }

    // REMOVED: getMethod(), setMethod(), getEndpointPattern(), setEndpointPattern()
    // These fields are now stored only in ApiToolEntity

    public String getRunScope() {
        return runScope;
    }

    public void setRunScope(String runScope) {
        this.runScope = runScope;
    }

    public Boolean getRequiresUserCredentials() {
        return requiresUserCredentials;
    }

    public void setRequiresUserCredentials(Boolean requiresUserCredentials) {
        this.requiresUserCredentials = requiresUserCredentials;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public Boolean getIsActive() {
        return isActive;
    }
    
    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }
    
    public Long getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
    
    public Long getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
    
}
