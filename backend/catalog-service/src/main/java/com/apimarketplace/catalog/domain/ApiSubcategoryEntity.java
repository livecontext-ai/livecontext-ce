package com.apimarketplace.catalog.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/**
 * Entity representing an API subcategory
 */
@Table("api_subcategories")
public class ApiSubcategoryEntity {
    
    @Id
    @Column("id")
    private UUID id;
    
    @Column("category_id")
    private UUID categoryId;
    
    @Column("name")
    private String name;
    
    @Column("description")
    private String description;
    
    @Column("icon")
    private String icon;
    
    @Column("color")
    private String color;
    
    @Column("sort_order")
    private Integer sortOrder;
    
    @Column("slug")
    private String slug;

    @Column("icon_url")
    private String iconUrl;
    
    @Column("created_at")
    private Long createdAt;
    
    @Column("updated_at")
    private Long updatedAt;
    
    // Constructors
    public ApiSubcategoryEntity() {}
    
    public ApiSubcategoryEntity(UUID id, UUID categoryId, String name, String description,
                               String icon, String color, Integer sortOrder, String slug, Long createdAt, Long updatedAt) {
        this.id = id;
        this.categoryId = categoryId;
        this.name = name;
        this.description = description;
        this.icon = icon;
        this.color = color;
        this.sortOrder = sortOrder;
        this.slug = slug;
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
    
    public UUID getCategoryId() {
        return categoryId;
    }
    
    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
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
    
    public String getIcon() {
        return icon;
    }
    
    public void setIcon(String icon) {
        this.icon = icon;
    }
    
    public String getColor() {
        return color;
    }
    
    public void setColor(String color) {
        this.color = color;
    }
    
    public Integer getSortOrder() {
        return sortOrder;
    }
    
    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
    
    public String getSlug() {
        return slug;
    }
    
    public void setSlug(String slug) {
        this.slug = slug;
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

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }
}
