package com.apimarketplace.catalog.domain;

import com.apimarketplace.catalog.config.JsonbString;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity representing an API configuration
 */
@Table("apis")
public class ApiEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("api_name")
    private String apiName;

    @Column("api_slug")
    private String apiSlug;

    @Column("description")
    private String description;

    @Column("base_url")
    private String baseUrl;

    @Column("healthcheck_endpoint")
    private String healthcheckEndpoint;

    @Column("category_id")
    private UUID categoryId;

    @Column("subcategory_id")
    private UUID subcategoryId;

    @Column("auth_type")
    private String authType;

    @Column("auth_header_name")
    private String authHeaderName;

    @Column("auth_header_value")
    private String authHeaderValue;

    @Column("visibility")
    private String visibility = "public";

    @Column("is_public")
    private Boolean isPublic = false;

    @Column("is_active")
    private Boolean isActive = false;

    @Column("is_local")
    private Boolean isLocal = false;

    @Column("created_at")
    private Long createdAt;

    @Column("updated_at")
    private Long updatedAt;

    @Column("created_by")
    private String createdBy;

    @Column("organization_id")
    private String organizationId;

    @Column("pricing_model")
    private String pricingModel = "free";

    @Column("status")
    private String status = "DRAFT";

    @Column("version")
    private String version = "1.0.0";

    @Column("icon_slug")
    private String iconSlug;

    @Column("platform_credential_name")
    private String platformCredentialName;

    @Column("source")
    private String source = "import";

    @Column("icon_url")
    private String iconUrl;

    @Column("api_version")
    private String apiVersion;

    @Column("documentation")
    private String documentation;

    @Column("rate_limits")
    private JsonbString rateLimits;

    /**
     * Soft-delete by API-catalog bundle apply (V331): set when a bundle no
     * longer lists this row. Hidden from list/search paths, still executable
     * by UUID. NULL = live. Only bundle-managed rows
     * ({@code source IN ('import','bundle')}) ever get this set.
     */
    @Column("deprecated_at")
    private java.time.Instant deprecatedAt;

    // Enum pour les statuts
    public enum ApiStatus {
        REVIEWING,
        SUBMITTED,     // Soumis pour validation
        APPROVED,      // Approuve et disponible
        REJECTED,      // Rejete
        SUSPENDED      // Suspendu
    }

    // Constructors
    public ApiEntity() {}

    public ApiEntity(UUID id, String apiName, String description, String baseUrl,
                     UUID categoryId, UUID subcategoryId, Boolean isActive, Long createdAt,
                     Long updatedAt, String createdBy) {
        this.id = id;
        this.apiName = apiName;
        this.description = description;
        this.baseUrl = baseUrl;
        this.categoryId = categoryId;
        this.subcategoryId = subcategoryId;
        this.isActive = isActive;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.createdBy = createdBy;
    }

    // Getters and Setters
    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getApiName() {
        return apiName;
    }

    public void setApiName(String apiName) {
        this.apiName = apiName;
    }

    public String getApiSlug() {
        return apiSlug;
    }

    public void setApiSlug(String apiSlug) {
        this.apiSlug = apiSlug;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getHealthcheckEndpoint() {
        return healthcheckEndpoint;
    }

    public void setHealthcheckEndpoint(String healthcheckEndpoint) {
        this.healthcheckEndpoint = healthcheckEndpoint;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public UUID getSubcategoryId() {
        return subcategoryId;
    }

    public void setSubcategoryId(UUID subcategoryId) {
        this.subcategoryId = subcategoryId;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public void setIsActive(Boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean getIsLocal() {
        return isLocal;
    }

    public void setIsLocal(Boolean isLocal) {
        this.isLocal = isLocal;
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

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getAuthHeaderName() {
        return authHeaderName;
    }

    public void setAuthHeaderName(String authHeaderName) {
        this.authHeaderName = authHeaderName;
    }

    public String getAuthHeaderValue() {
        return authHeaderValue;
    }

    public void setAuthHeaderValue(String authHeaderValue) {
        this.authHeaderValue = authHeaderValue;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public Boolean getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    public String getPricingModel() {
        return pricingModel;
    }

    public void setPricingModel(String pricingModel) {
        this.pricingModel = pricingModel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getIconSlug() {
        return iconSlug;
    }

    public void setIconSlug(String iconSlug) {
        this.iconSlug = iconSlug;
    }

    public String getPlatformCredentialName() {
        return platformCredentialName;
    }

    public void setPlatformCredentialName(String platformCredentialName) {
        this.platformCredentialName = platformCredentialName;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public void setApiVersion(String apiVersion) {
        this.apiVersion = apiVersion;
    }

    public String getDocumentation() {
        return documentation;
    }

    public void setDocumentation(String documentation) {
        this.documentation = documentation;
    }

    public String getRateLimits() {
        return rateLimits == null ? null : rateLimits.value();
    }

    public void setRateLimits(String rateLimits) {
        this.rateLimits = JsonbString.of(rateLimits);
    }

    public java.time.Instant getDeprecatedAt() {
        return deprecatedAt;
    }

    public void setDeprecatedAt(java.time.Instant deprecatedAt) {
        this.deprecatedAt = deprecatedAt;
    }
}
