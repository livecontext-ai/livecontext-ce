package com.apimarketplace.catalog.domain;

import com.apimarketplace.catalog.config.JsonbString;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.List;
import java.util.UUID;

/**
 * Entity representing an API tool
 */
@Table("api_tools")
public class ApiToolEntity {

    @Id
    @Column("id")
    private UUID id;

    @Column("api_id")
    private UUID apiId;

    @Column("tool_slug")
    private String toolSlug;

    @Column("description")
    private String description;


    @Column("tool_name_id")
    private String toolNameId;

    @Column("method")
    private String method;

    @Column("endpoint")
    private String endpoint;

    @Column("protocol")
    private String protocol = "HTTP";

    @Column("default_headers")
    private String defaultHeaders;

    @Column("runtime_metadata")
    private String runtimeMetadata;

    /** Declarative typed-execution contract (JSONB). See V52__catalog_typed_execution.sql.
     *  Stored as JsonbString so the Spring Data JDBC writing converter wraps it in a
     *  PGobject(jsonb) instead of sending a varchar (which Postgres rejects with 42804). */
    @Column("execution_spec")
    private JsonbString executionSpec;

    /** Typed output schema as JSONB array of OutputFieldDef-shaped entries. */
    @Column("output_schema")
    private JsonbString outputSchema;

    /** Denormalized from execution_spec.mode for indexed lookups. */
    @Column("execution_mode")
    private String executionMode;

    /** Pagination config as JSONB. See V83__extend_custom_api_fields.sql. */
    @Column("pagination")
    private JsonbString pagination;

    /**
     * Optional JSON array of OAuth scope strings the user credential must have granted
     * before LiveContext dispatches the call. NULL = no requirement. See V166.
     * Used by HttpExecutionService.preflightScopeCheck before credential resolution.
     */
    @Column("required_scopes")
    private JsonbString requiredScopes;

    /** Hint text for LLM about what to do after using this tool. */
    @Column("next_hint")
    private String nextHint;

    @Column("status")
    private String status;

    @Column("test_status")
    private String testStatus;

    @Column("is_active")
    private Boolean isActive = false;

    @Column("created_at")
    private Long createdAt;

    @Column("updated_at")
    private Long updatedAt;

    @Column("version")
    private String version = "1.0.0";

    /**
     * Soft-delete by API-catalog bundle apply (V331): set when a bundle no
     * longer lists this tool. Hidden from list/search paths, still executable
     * by UUID. NULL = live.
     */
    @Column("deprecated_at")
    private java.time.Instant deprecatedAt;

    // Enums
    public enum HttpMethod {
        GET, POST, PUT, DELETE, PATCH
    }

    public enum ToolStatus {
        DRAFT, ACTIVE, INACTIVE, DEPRECATED
    }

    public enum TestStatus {
        PENDING, SUCCESS, ERROR, TIMEOUT
    }

    // Constructors
    public ApiToolEntity() {}

    public ApiToolEntity(UUID id, UUID apiId, String description,
                         String toolNameId, String method, String endpoint,
                         String status, String testStatus, Boolean isActive, Long createdAt, Long updatedAt) {
        this.id = id;
        this.apiId = apiId;
        this.description = description;
        this.toolNameId = toolNameId;
        this.method = method;
        this.endpoint = endpoint;
        this.status = status;
        this.testStatus = testStatus;
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

    public UUID getApiId() {
        return apiId;
    }

    public void setApiId(UUID apiId) {
        this.apiId = apiId;
    }

    public String getToolSlug() {
        return toolSlug;
    }

    public void setToolSlug(String toolSlug) {
        this.toolSlug = toolSlug;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }


    public String getToolNameId() {
        return toolNameId;
    }

    public void setToolNameId(String toolNameId) {
        this.toolNameId = toolNameId;
    }


    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getDefaultHeaders() {
        return defaultHeaders;
    }

    public void setDefaultHeaders(String defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
    }

    public String getRuntimeMetadata() {
        return runtimeMetadata;
    }

    public void setRuntimeMetadata(String runtimeMetadata) {
        this.runtimeMetadata = runtimeMetadata;
    }

    public String getExecutionSpec() {
        return executionSpec == null ? null : executionSpec.value();
    }

    public void setExecutionSpec(String executionSpec) {
        this.executionSpec = JsonbString.of(executionSpec);
    }

    public String getOutputSchema() {
        return outputSchema == null ? null : outputSchema.value();
    }

    public void setOutputSchema(String outputSchema) {
        this.outputSchema = JsonbString.of(outputSchema);
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getPagination() {
        return pagination == null ? null : pagination.value();
    }

    public void setPagination(String pagination) {
        this.pagination = JsonbString.of(pagination);
    }

    /** Shared mapper for required_scopes JSON ↔ List<String> conversion. */
    private static final ObjectMapper REQUIRED_SCOPES_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    /**
     * Returns the parsed list of required scopes, or null when the column is null
     * (no requirement). Empty list is also returned as null at this layer because
     * an empty array carries no preflight meaning.
     */
    public List<String> getRequiredScopes() {
        if (requiredScopes == null || requiredScopes.value() == null) {
            return null;
        }
        try {
            List<String> parsed = REQUIRED_SCOPES_MAPPER.readValue(requiredScopes.value(), STRING_LIST_TYPE);
            return (parsed == null || parsed.isEmpty()) ? null : parsed;
        } catch (JsonProcessingException e) {
            // Defensive: never let a malformed cell crash the runtime path.
            return null;
        }
    }

    public void setRequiredScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            this.requiredScopes = null;
            return;
        }
        try {
            this.requiredScopes = JsonbString.of(REQUIRED_SCOPES_MAPPER.writeValueAsString(scopes));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize requiredScopes", e);
        }
    }

    public String getNextHint() {
        return nextHint;
    }

    public void setNextHint(String nextHint) {
        this.nextHint = nextHint;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTestStatus() {
        return testStatus;
    }

    public void setTestStatus(String testStatus) {
        this.testStatus = testStatus;
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

    public String getFullEndpoint() {
        // Cette methode necessiterait une reference a l'API parent
        // Pour l'instant, on retourne juste l'endpoint
        return endpoint;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public java.time.Instant getDeprecatedAt() {
        return deprecatedAt;
    }

    public void setDeprecatedAt(java.time.Instant deprecatedAt) {
        this.deprecatedAt = deprecatedAt;
    }
}
