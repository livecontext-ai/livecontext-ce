package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolNextHintEntity;
import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest.*;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.dto.ToolResponseDto;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.common.web.UrlSafetyValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for registering custom APIs from the api-migrations JSON schema format.
 * Used by the agent tool (catalog:register_api) and Settings UI.
 *
 * <p>Converts the api-migrations JSON format into {@link ApiConfigurationRequest}
 * and delegates to {@link ApiService#processApiConfiguration}. Always sets
 * {@code source='custom'} so custom APIs survive catalog re-imports.
 *
 * <p>Post-processing persists synthesis (lexical search index), nextHint,
 * fixtures, and API-level metadata (apiVersion, documentation, rateLimits)
 * that are not part of the McpToolDto/ApiSubmissionOrchestrator flow.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomApiRegistrationService {

    static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final Set<String> ALLOWED_PARAM_IN =
            Set.of("query", "path", "body");
    static final Set<String> ALLOWED_OUTPUT_TYPES =
            Set.of("string", "number", "boolean", "datetime", "object", "array", "fileRef");

    private final ApiService apiService;
    private final com.apimarketplace.catalog.repository.ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final ApiToolParameterRepository apiToolParameterRepository;
    private final LexicalIndexSyncService lexicalIndexSyncService;
    private final ToolNextHintRepository toolNextHintRepository;
    private final ToolResponseService toolResponseService;
    private final com.apimarketplace.catalog.seed.CatalogSeedCredentialService catalogSeedCredentialService;
    private final com.apimarketplace.credential.client.CredentialClient credentialClient;
    private final ObjectMapper objectMapper;

    /**
     * Register a custom API from the api-migrations JSON format.
     *
     * @param apiJson  The API definition in api-migrations schema format
     * @param tenantId The tenant (user) registering the API
     * @return The created API response
     */
    @Transactional
    public ApiResponse registerCustomApi(JsonNode apiJson, String tenantId) {
        log.info("Registering custom API for tenant {}: {}", tenantId, apiJson.path("apiName").asText("unknown"));

        String apiName = requireField(apiJson, "apiName");
        String baseUrl = requireField(apiJson, "baseUrl");
        // Format-only validation at registration (no DNS resolution).
        // Full SSRF check (including DNS) happens at execution time in HttpExecutionService.
        UrlSafetyValidator.validateUrlFormat(baseUrl);

        // auth is an array of variants (see api-migrations/SCHEMA.md). The first
        // entry is the primary variant; custom APIs only expose that one for now.
        JsonNode authArray = apiJson.path("auth");
        JsonNode primaryAuth = (authArray.isArray() && authArray.size() > 0)
                ? authArray.get(0)
                : apiJson.path("auth"); // tolerates legacy object form from in-flight payloads
        String authType = primaryAuth.path("type").asText(
                apiJson.path("authType").asText("none"));
        String description = apiJson.path("apiDescription").asText(apiName + " API");
        String category = apiJson.path("apiCategory").asText("Custom APIs");
        String categoryGroup = category.endsWith(" APIs") ? category : category + " APIs";
        String subcategory = apiJson.path("apiSubcategory").asText(apiName);
        String iconSlug = apiJson.path("iconSlug").asText(null);
        // Custom APIs are always private - only visible to the creator
        String visibility = "private";

        // Build tools from endpoints array
        List<McpToolDto> tools = buildTools(apiJson, apiName);
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("API must have at least one endpoint");
        }

        // Build the ApiConfigurationRequest - no pre-assigned apiId so the orchestrator
        // uses CrudRepository.save() (auto-generated UUID) instead of jdbcAggregateTemplate.insert()
        ApiConfigurationRequest request = new ApiConfigurationRequest(
                apiName,
                description,
                category,
                categoryGroup,
                subcategory,
                description,
                "",     // subcategoryIconUrl
                "",     // categoryId
                "",     // subcategoryId
                true,   // isCustomCategory
                true,   // isCustomSubcategory
                false,  // isLocal
                iconSlug,
                null,   // apiSlug (auto-generated)
                "none".equalsIgnoreCase(authType) ? null : "user_key",  // credentialMode
                null,   // platformCredentialName (auto-derived by ApiSubmissionOrchestrator)
                "custom", // source
                apiJson.path("iconUrl").asText(null), // iconUrl
                "",     // toolCategoryIconUrl
                new ApiConfigDto(
                        baseUrl,
                        "",
                        new AuthorizationDto(authType, "Authentication for " + apiName, "Authorization", ""),
                        visibility
                ),
                buildFreeMonetization(),
                tools,
                null    // no pre-assigned apiId - let DB generate
        );

        ApiResponse response;
        try {
            response = apiService.processApiConfiguration(request, tenantId);
        } catch (RuntimeException e) {
            if (isDuplicateKeyException(e)) {
                throw new IllegalArgumentException(
                        "An API named '" + apiName + "' already exists for this user. "
                                + "Use catalog(action='update_api', api_id='<uuid>', api_definition={...}) to modify it, "
                                + "or catalog(action='list_custom_apis') to find its id.", e);
            }
            throw e;
        }

        // Post-processing: set API-level metadata not in ApiSubmissionOrchestrator flow
        UUID apiId = response.id();
        postProcessApiMetadata(apiJson, apiId);
        List<ApiToolEntity> savedTools = apiToolRepository.findByApiIdAndIsActiveTrue(apiId);
        postProcessSynthesisAndHints(apiJson, apiId, savedTools);
        postProcessFixtures(apiJson, apiId, savedTools);

        // Link credentials when authType requires authentication. iconSlug is the
        // single canonical per-API key (see ApiSubmissionOrchestrator) - derive it
        // first and reuse it as credential_name so the runtime OAuth resolver
        // matches what `auth.credentials.integration` carries.
        if (!"none".equalsIgnoreCase(authType)) {
            String normalizedIconSlug = com.apimarketplace.catalog.util.IconSlugNormalizer.deriveIconSlug(apiName, iconSlug);
            String credentialName = normalizedIconSlug;
            String apiIconUrl = apiJson.path("iconUrl").asText(null);
            catalogSeedCredentialService.linkCredentials(apiId, credentialName, authType, normalizedIconSlug, apiIconUrl);
        }

        return response;
    }

    /**
     * Update an existing custom API.
     * Validates tenant ownership, deletes the old API, then re-creates with new definition.
     * Wrapped in a single transaction so a failed re-create rolls back the delete.
     *
     * <p><b>Note:</b> This generates a new UUID for the API. Any external references to the
     * old UUID (e.g., tool IDs in workflow plans) will break. A future improvement could
     * update the entity in-place and only replace tools.
     */
    @Transactional
    public ApiResponse updateCustomApi(String apiId, JsonNode updates, String tenantId) {
        UUID id = UUID.fromString(apiId);
        var existing = apiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API not found: " + apiId));
        if (!"custom".equals(existing.getSource())) {
            throw new IllegalArgumentException("Only custom APIs can be updated via this endpoint");
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, existing.getCreatedBy(), existing.getOrganizationId())) {
            throw new IllegalArgumentException("You can only update your own custom APIs");
        }

        // Validate the updates JSON is an object
        if (!updates.isObject()) {
            throw new IllegalArgumentException("Updates must be a JSON object");
        }

        // Clean up old credentials before deleting the API
        cleanupCredentials(existing, tenantId);

        // Delete old API and re-create with new definition
        apiService.deleteApi(id);
        return registerCustomApi(updates, tenantId);
    }

    /**
     * Get full details of a custom API, including endpoints/tools.
     * Validates that the API belongs to the requesting tenant.
     */
    public Map<String, Object> getCustomApiDetails(String apiId, String tenantId) {
        UUID id = UUID.fromString(apiId);
        var entity = apiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API not found: " + apiId));
        if (!"custom".equals(entity.getSource())) {
            throw new IllegalArgumentException("Not a custom API");
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, entity.getCreatedBy(), entity.getOrganizationId())) {
            throw new IllegalArgumentException("Access denied");
        }

        var apiResponse = apiService.getApiById(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", apiResponse.id().toString());
        result.put("apiName", apiResponse.apiName());
        result.put("description", apiResponse.description());
        result.put("baseUrl", apiResponse.baseUrl());
        result.put("authType", apiResponse.authType() != null ? apiResponse.authType() : "none");
        result.put("categoryName", apiResponse.categoryName());
        if (entity.getIconUrl() != null) result.put("iconUrl", entity.getIconUrl());

        // V83: API-level metadata
        if (entity.getApiVersion() != null) result.put("apiVersion", entity.getApiVersion());
        if (entity.getDocumentation() != null) result.put("documentation", entity.getDocumentation());
        if (entity.getRateLimits() != null) {
            try {
                result.put("rateLimits", objectMapper.readTree(entity.getRateLimits()));
            } catch (Exception e) {
                log.warn("Malformed rateLimits JSON for API {}, skipping", apiId);
            }
        }

        // Build tool entity lookup for pagination/nextHint overlay
        List<ApiToolEntity> toolEntities = apiToolRepository.findByApiIdAndIsActiveTrue(id);

        // Map tools to endpoint definitions the frontend can use to pre-populate the form
        List<Map<String, Object>> endpoints = new ArrayList<>();
        if (apiResponse.tools() != null) {
            for (var tool : apiResponse.tools()) {
                Map<String, Object> ep = new LinkedHashMap<>();
                ep.put("name", tool.name());
                ep.put("endpoint", tool.endpoint());
                ep.put("method", tool.method());
                ep.put("description", tool.description());

                List<Map<String, Object>> params = new ArrayList<>();
                if (tool.parameters() != null) {
                    for (var p : tool.parameters()) {
                        Map<String, Object> param = new LinkedHashMap<>();
                        param.put("name", p.name());
                        param.put("in", p.parameterType() != null ? p.parameterType() : "query");
                        param.put("type", p.type() != null ? p.type() : "string");
                        param.put("required", Boolean.TRUE.equals(p.required()));
                        param.put("description", p.description() != null ? p.description() : "");
                        if (p.defaultValue() != null) param.put("default", p.defaultValue());
                        if (p.exampleValue() != null) param.put("example", p.exampleValue());
                        params.add(param);
                    }
                }
                ep.put("params", params);

                // V83: overlay tool-level fields from tool entities
                String toolName = tool.name();
                ApiToolEntity matchedToolEntity = toolName != null
                        ? findMatchingTool(toolEntities, toolName) : null;

                // V83: overlay tool-level fields from tool entities
                if (matchedToolEntity != null) {
                    // Hidden flag from parameter entities
                    List<ApiToolParameterEntity> paramEntities =
                            apiToolParameterRepository.findByApiToolId(matchedToolEntity.getId());
                    for (ApiToolParameterEntity pe : paramEntities) {
                        if (Boolean.TRUE.equals(pe.getIsHidden())) {
                            for (Map<String, Object> pm : params) {
                                if (pe.getName() != null && pe.getName().equals(pm.get("name"))) {
                                    pm.put("hidden", true);
                                    break;
                                }
                            }
                        }
                    }

                    if (matchedToolEntity.getPagination() != null) {
                        try {
                            ep.put("pagination", objectMapper.readTree(matchedToolEntity.getPagination()));
                        } catch (Exception ignored) { /* skip malformed JSON */ }
                    }
                    if (matchedToolEntity.getNextHint() != null) {
                        ep.put("nextHint", matchedToolEntity.getNextHint());
                    }
                    if (matchedToolEntity.getOutputSchema() != null) {
                        try {
                            ep.put("outputSchema", objectMapper.readTree(matchedToolEntity.getOutputSchema()));
                        } catch (Exception ignored) { /* skip malformed JSON */ }
                    }
                    if (matchedToolEntity.getExecutionSpec() != null) {
                        try {
                            JsonNode execSpec = objectMapper.readTree(matchedToolEntity.getExecutionSpec());
                            ep.put("execution", execSpec);
                        } catch (Exception ignored) { /* skip malformed JSON */ }
                    }
                }

                endpoints.add(ep);
            }
        }
        result.put("endpoints", endpoints);
        return result;
    }

    /**
     * List custom APIs for a tenant.
     */
    public List<Map<String, Object>> listCustomApis(String tenantId) {
        return apiService.getCustomApisForTenant(tenantId, TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Delete a custom API. Only the owner can delete their own custom APIs.
     */
    @Transactional
    public void deleteCustomApi(String apiId, String tenantId) {
        UUID id = UUID.fromString(apiId);
        var entity = apiRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("API not found: " + apiId));
        if (!"custom".equals(entity.getSource())) {
            throw new IllegalArgumentException("Only custom APIs can be deleted via this endpoint");
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, entity.getCreatedBy(), entity.getOrganizationId())) {
            throw new IllegalArgumentException("You can only delete your own custom APIs");
        }

        cleanupCredentials(entity, tenantId);
        apiService.deleteApi(id);
        log.info("Deleted custom API {} for tenant {}", apiId, tenantId);
    }

    /**
     * Clean up credential template, tool_credentials, and tenant-scoped platform credential.
     * Called from both delete and update paths.
     *
     * <p>Reads {@code entity.getPlatformCredentialName()} which is set by
     * {@code ApiSubmissionOrchestrator} using {@code IconSlugNormalizer.normalizeForKey(apiName)}.
     * The same normalizer is used by {@code linkCredentials} during registration, so the
     * credential name is consistent between create and delete.
     *
     * <p>The auth-service HTTP call is best-effort: if it fails, the API delete still proceeds
     * but the tenant's platform credential may remain orphaned in {@code auth.platform_credentials}.
     */
    private void cleanupCredentials(com.apimarketplace.catalog.domain.ApiEntity entity, String tenantId) {
        String credentialName = entity.getPlatformCredentialName();
        if (credentialName == null || credentialName.isBlank()) return;

        // Delete catalog.tool_credentials + catalog.credentials
        catalogSeedCredentialService.deleteCredentialByName(credentialName);
        // Best-effort: delete tenant-scoped platform credential in auth-service
        try {
            credentialClient.deleteTenantPlatformCredential(credentialName, tenantId);
        } catch (Exception e) {
            log.warn("Failed to delete tenant platform credential '{}': {}", credentialName, e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POST-PROCESSING: metadata, synthesis, hints, fixtures
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Post-process API-level metadata fields (apiVersion, documentation, rateLimits)
     * that bypass the ApiConfigurationRequest/ApiSubmissionOrchestrator flow.
     */
    private void postProcessApiMetadata(JsonNode apiJson, UUID apiId) {
        apiRepository.findById(apiId).ifPresent(entity -> {
            boolean updated = false;

            String apiVersion = apiJson.path("apiVersion").asText(null);
            if (apiVersion != null && !apiVersion.isBlank()) {
                entity.setApiVersion(apiVersion);
                updated = true;
            }
            String documentation = apiJson.path("documentation").asText(null);
            if (documentation != null && !documentation.isBlank()) {
                if (documentation.length() > 1000) {
                    documentation = documentation.substring(0, 1000);
                }
                entity.setDocumentation(documentation);
                updated = true;
            }
            JsonNode rateLimitsNode = apiJson.path("rateLimits");
            if (!rateLimitsNode.isMissingNode() && rateLimitsNode.isObject()) {
                entity.setRateLimits(rateLimitsNode.toString());
                updated = true;
            }

            if (updated) {
                apiRepository.save(entity);
                log.info("Post-processed API metadata for {}", apiId);
            }
        });
    }

    /**
     * Persist synthesis (lexical search index) and nextHint for each tool endpoint.
     * These fields are NOT part of the McpToolDto flow -- they need a second pass
     * after the API+tools are created.
     */
    private void postProcessSynthesisAndHints(JsonNode apiJson, UUID apiId, List<ApiToolEntity> savedTools) {
        JsonNode endpoints = apiJson.path("endpoints");
        if (!endpoints.isArray()) return;

        String providerName = apiJson.path("apiName").asText("").toLowerCase().replaceAll("[^a-z0-9]", "_");

        for (JsonNode ep : endpoints) {
            String epName = ep.path("name").asText(ep.path("toolName").asText(""));
            if (epName.isBlank()) continue;

            // Find the matching saved tool by tool_slug
            ApiToolEntity matchedTool = findMatchingTool(savedTools, epName);
            if (matchedTool == null) continue;

            // Persist synthesis to lexical search index
            persistSynthesis(ep, apiJson, matchedTool, providerName, epName);

            // Persist nextHint
            persistNextHint(ep, matchedTool, epName);
        }
    }

    private void persistSynthesis(JsonNode ep, JsonNode apiJson, ApiToolEntity matchedTool,
                                   String providerName, String epName) {
        JsonNode synthesis = ep.path("synthesis");
        if (synthesis.isMissingNode() || !synthesis.isObject()) return;

        try {
            String resource = synthesis.path("resource").asText(epName);
            String action = synthesis.path("action").asText("execute");
            String summary = synthesis.path("summary").asText(ep.path("description").asText(""));
            String summaryExtended = synthesis.path("summaryExtended").asText("");

            List<String> keywordsPrimary = jsonArrayToList(synthesis.path("keywordsPrimary"));
            List<String> keywordsSecondary = jsonArrayToList(synthesis.path("keywordsSecondary"));

            // Build param lists from endpoint params
            List<String> paramsRequired = new ArrayList<>();
            List<String> paramsOptional = new ArrayList<>();
            List<String> paramExamples = new ArrayList<>();
            JsonNode params = ep.path("params");
            if (params.isArray()) {
                for (JsonNode p : params) {
                    String pName = p.path("name").asText("");
                    if (p.path("required").asBoolean(false)) {
                        paramsRequired.add(pName);
                    } else {
                        paramsOptional.add(pName);
                    }
                    String example = p.path("example").asText(null);
                    if (example != null) {
                        paramExamples.add(pName + "=" + example);
                    }
                }
            }

            String toolCategory = ep.path("toolCategory").asText(null);
            String categoryForIndex = toolCategory != null ? toolCategory
                    : apiJson.path("apiCategory").asText("Custom APIs");

            lexicalIndexSyncService.sync(matchedTool.getId(), LexicalIndexSyncService.SyncData.builder()
                    .toolName(epName)
                    .provider(providerName)
                    .resource(resource)
                    .action(action)
                    .endpoint(matchedTool.getEndpoint())
                    .paramsRequired(paramsRequired)
                    .paramsOptional(paramsOptional)
                    .paramExamples(paramExamples)
                    .summary(summary)
                    .summaryExtended(summaryExtended)
                    .keywords(String.join(", ", keywordsPrimary))
                    .keywordsPrimary(keywordsPrimary)
                    .keywordsSynonyms(keywordsSecondary)
                    .keywordsParams(List.of())
                    .useCases(List.of())
                    .category(categoryForIndex)
                    .subcategory(apiJson.path("apiName").asText(""))
                    .build());
            log.info("Persisted synthesis for tool {} ({})", matchedTool.getId(), epName);
        } catch (Exception e) {
            log.warn("Failed to persist synthesis for tool {}: {}", epName, e.getMessage());
        }
    }

    private void persistNextHint(JsonNode ep, ApiToolEntity matchedTool, String epName) {
        String nextHint = ep.path("nextHint").asText(null);
        if (nextHint == null || nextHint.isBlank()) return;

        try {
            // Remove existing hints for this tool to prevent duplicates on re-registration
            List<ToolNextHintEntity> existing = toolNextHintRepository.findByApiToolId(matchedTool.getId());
            if (!existing.isEmpty()) {
                toolNextHintRepository.deleteAll(existing);
            }

            ToolNextHintEntity hint = new ToolNextHintEntity();
            hint.setApiToolId(matchedTool.getId());
            hint.setHint(nextHint);
            hint.setPriority(0);
            hint.setIsActive(true);
            hint.setCreatedAt(System.currentTimeMillis());
            hint.setUpdatedAt(System.currentTimeMillis());
            toolNextHintRepository.save(hint);
            log.info("Persisted nextHint for tool {} ({})", matchedTool.getId(), epName);
        } catch (Exception e) {
            log.warn("Failed to persist nextHint for tool {}: {}", epName, e.getMessage());
        }
    }

    /**
     * Persist API fixtures as example responses via {@link ToolResponseService}.
     * Each fixture becomes a {@code tool_responses} row linked to the matching tool.
     */
    private void postProcessFixtures(JsonNode apiJson, UUID apiId, List<ApiToolEntity> savedTools) {
        JsonNode fixtures = apiJson.path("apiFixtures");
        if (!fixtures.isArray() || fixtures.isEmpty()) return;

        for (JsonNode fixture : fixtures) {
            String endpointName = fixture.path("endpointName").asText("");
            if (endpointName.isBlank()) continue;

            ApiToolEntity matchedTool = findMatchingTool(savedTools, endpointName);
            if (matchedTool == null) {
                log.debug("No matching tool found for fixture endpoint: {}", endpointName);
                continue;
            }

            try {
                JsonNode response = fixture.path("response");
                if (response.isMissingNode() || response.isNull()) continue;

                String exampleJson = objectMapper.writeValueAsString(response);

                ToolResponseDto dto = new ToolResponseDto();
                dto.setToolId(matchedTool.getId());
                dto.setName("fixture_" + endpointName);
                dto.setDescription("Fixture example response for " + endpointName);
                dto.setExample(exampleJson);
                dto.setIsDefault(true);
                dto.setIsActive(true);
                dto.setStatusCode(200);

                toolResponseService.createResponse(dto, null);
                log.info("Persisted fixture for tool {} ({})", matchedTool.getId(), endpointName);
            } catch (Exception e) {
                log.warn("Failed to persist fixture for tool {}: {}", endpointName, e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL BUILDING
    // ═══════════════════════════════════════════════════════════════════════════

    private List<McpToolDto> buildTools(JsonNode apiJson, String apiName) {
        List<McpToolDto> tools = new ArrayList<>();
        JsonNode endpoints = apiJson.path("endpoints");

        if (!endpoints.isArray() || endpoints.isEmpty()) {
            return tools;
        }

        // Build a shared runtime_metadata node at API level so every tool on this API
        // has the same {type, baseUrl} shape imported APIs have. Parity with
        // ApiMigrationImporter.buildTool (see backend/catalog-service-import ~line 726).
        String apiBaseUrl = apiJson.path("baseUrl").asText("");
        ObjectNode runtimeMetadata = objectMapper.createObjectNode();
        runtimeMetadata.put("type", "http");
        if (!apiBaseUrl.isEmpty()) {
            runtimeMetadata.put("baseUrl", apiBaseUrl);
        }

        for (JsonNode ep : endpoints) {
            String name = ep.path("name").asText(ep.path("toolName").asText(""));
            if (name.isBlank()) continue;

            String epDescription = ep.path("description").asText(name);
            String endpoint = ep.path("endpoint").asText(ep.path("path").asText(""));

            // Validate against help-declared contracts so silent drops don't mislead the agent.
            String method = validateHttpMethod(ep.path("method").asText("GET"), name);
            validateParams(ep.path("params"), name);

            // Build parameters (supports both "in" and "location" alias)
            List<PathParameterDto> pathParams = buildPathParams(ep);
            List<QueryParameterDto> queryParams = buildQueryParams(ep);
            List<BodyParamDto> bodyParams = buildBodyParams(ep);
            List<HeaderDto> headers = buildHeaders(ep);

            // Extract execution spec and output schema
            JsonNode executionSpec = ep.has("execution") ? ep.get("execution") : null;
            JsonNode outputSchema = ep.has("outputSchema") ? ep.get("outputSchema") : null;

            // outputSchema is required - it drives agent workflow hints and variable picker
            if (outputSchema == null || !outputSchema.isArray() || outputSchema.isEmpty()) {
                throw new IllegalArgumentException(
                        "Endpoint '" + name + "' is missing a required outputSchema. " +
                        "Provide a JSON array of {key, type, description} entries describing the response fields.");
            }
            validateOutputSchema(outputSchema, name);

            // Default the execution spec to {"mode":"sync"} so custom tools get the typed
            // execution path (V52) instead of silently falling back to the legacy untyped path
            // when the user omits the block. Mirrors ApiMigrationImporter's default behaviour.
            if (executionSpec == null || !executionSpec.isObject()) {
                executionSpec = objectMapper.createObjectNode().put("mode", "sync");
            }
            String executionMode = executionSpec.path("mode").asText("sync");

            // V83: new fields for McpToolDto pass-through
            JsonNode synthesisNode = ep.has("synthesis") ? ep.get("synthesis") : null;
            JsonNode paginationNode = ep.has("pagination") ? ep.get("pagination") : null;
            String nextHintStr = ep.path("nextHint").asText(null);
            // Use endpoint-level toolCategory if provided, else default to the API name
            // (mirrors the importer which uses synthesis.resource as toolCategory)
            String rawToolCategory = ep.path("toolCategory").asText(apiName);
            // McpToolDto.toolCategory is @Size(max=50)
            String toolCategory = rawToolCategory.length() > 50 ? rawToolCategory.substring(0, 50) : rawToolCategory;

            tools.add(new McpToolDto(
                    ep.path("toolId").asText(null),
                    name,
                    epDescription,
                    endpoint,
                    method,
                    "HTTP",
                    runtimeMetadata,
                    toolCategory,
                    epDescription, // toolCategoryDescription
                    null, // toolCategoryIconUrl
                    null, // toolNameId
                    true, // isCustomCategory
                    true, // isCustomToolName
                    headers,
                    pathParams,
                    queryParams,
                    bodyParams,
                    Map.of(), // defaultHeaders
                    null, // response
                    null, null, null, null, null, // sql/amqp/kafka/mqtt/redis
                    executionSpec,
                    outputSchema,
                    executionMode,
                    synthesisNode,
                    paginationNode,
                    nextHintStr,
                    null  // V166: requiredScopes - custom API registration path
            ));
        }

        return tools;
    }

    private List<PathParameterDto> buildPathParams(JsonNode ep) {
        List<PathParameterDto> params = new ArrayList<>();
        JsonNode paramsNode = ep.path("params");
        if (!paramsNode.isArray()) return params;

        for (JsonNode p : paramsNode) {
            String in = p.path("in").asText(p.path("location").asText(""));
            if ("path".equalsIgnoreCase(in)) {
                boolean hidden = p.path("hidden").asBoolean(false);
                ObjectNode extras = hidden ? objectMapper.createObjectNode().put("hidden", true) : null;

                // Handle example for any value type
                String example = extractExample(p);

                params.add(new PathParameterDto(
                        p.path("name").asText(),
                        p.path("type").asText("string"),
                        p.path("required").asBoolean(true),
                        p.path("description").asText(""),
                        example,
                        null,
                        extractParamDefaultValue(p),
                        extractParamAllowedValues(p),
                        extras
                ));
            }
        }
        return params;
    }

    private List<QueryParameterDto> buildQueryParams(JsonNode ep) {
        List<QueryParameterDto> params = new ArrayList<>();
        JsonNode paramsNode = ep.path("params");
        if (!paramsNode.isArray()) return params;

        for (JsonNode p : paramsNode) {
            String in = p.path("in").asText(p.path("location").asText("query"));
            if ("query".equalsIgnoreCase(in) || in.isBlank()) {
                boolean hidden = p.path("hidden").asBoolean(false);
                ObjectNode extras = hidden ? objectMapper.createObjectNode().put("hidden", true) : null;

                // Handle default value (can be any type)
                String defaultValue = null;
                JsonNode defaultNode = p.path("default");
                if (!defaultNode.isMissingNode() && !defaultNode.isNull()) {
                    defaultValue = defaultNode.isTextual() ? defaultNode.asText() : defaultNode.toString();
                }

                String example = extractExample(p);

                params.add(new QueryParameterDto(
                        p.path("name").asText(),
                        p.path("type").asText("string"),
                        p.path("required").asBoolean(false),
                        p.path("description").asText(""),
                        example,
                        defaultValue,
                        extractParamAllowedValues(p),
                        extras
                ));
            }
        }
        return params;
    }

    private List<BodyParamDto> buildBodyParams(JsonNode ep) {
        List<BodyParamDto> params = new ArrayList<>();
        JsonNode paramsNode = ep.path("params");
        if (!paramsNode.isArray()) return params;

        for (JsonNode p : paramsNode) {
            String in = p.path("in").asText(p.path("location").asText(""));
            if ("body".equalsIgnoreCase(in)) {
                boolean hidden = p.path("hidden").asBoolean(false);
                ObjectNode extras = hidden ? objectMapper.createObjectNode().put("hidden", true) : null;

                params.add(new BodyParamDto(
                        p.path("name").asText(),
                        null,
                        p.path("type").asText("string"),
                        p.path("required").asBoolean(false),
                        p.path("description").asText(""),
                        null,
                        extractParamDefaultValue(p),
                        extractParamAllowedValues(p),
                        extras
                ));
            }
        }
        return params;
    }

    private List<HeaderDto> buildHeaders(JsonNode ep) {
        List<HeaderDto> headers = new ArrayList<>();
        JsonNode headersNode = ep.path("headers");
        if (!headersNode.isObject()) return headers;

        var it = headersNode.fields();
        while (it.hasNext()) {
            var entry = it.next();
            headers.add(new HeaderDto(entry.getKey(), entry.getValue().asText(""), false, null, null, null));
        }
        return headers;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts the scalar {@code default} value of a param spec for the typed DTO.
     * Returns {@code null} when {@code default} is missing/null/an array (the legacy
     * array-shape is treated as {@code allowedValues}, see {@link #extractParamAllowedValues}).
     */
    private String extractParamDefaultValue(JsonNode p) {
        JsonNode d = p.path("default");
        if (d.isMissingNode() || d.isNull() || d.isArray()) {
            return null;
        }
        return d.isTextual() ? d.asText() : d.toString();
    }

    /**
     * Extracts the closed enum from a param spec, supporting BOTH the explicit
     * {@code allowedValues} field and the legacy {@code default: [array]} fallback
     * (per {@code SCHEMA.md}). Returns {@code null} when neither is set or the
     * result would be empty.
     */
    private List<String> extractParamAllowedValues(JsonNode p) {
        JsonNode allowed = p.path("allowedValues");
        if (allowed.isArray() && allowed.size() > 0) {
            List<String> out = new ArrayList<>(allowed.size());
            allowed.forEach(v -> out.add(v.isTextual() ? v.asText() : v.toString()));
            return out;
        }
        // Legacy fallback: default-as-array means "exhaustive admissible values"
        JsonNode def = p.path("default");
        if (def.isArray() && def.size() > 0) {
            List<String> out = new ArrayList<>(def.size());
            def.forEach(v -> out.add(v.isTextual() ? v.asText() : v.toString()));
            return out;
        }
        return null;
    }

    private static MonetizationConfigDto buildFreeMonetization() {
        return new MonetizationConfigDto(
                "FREEMIUM", null, null,
                999999,     // freeRequestsPerUser - effectively unlimited
                "per-user", // freeRequestsType
                null, null,
                null, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null
        );
    }

    private String requireField(JsonNode json, String fieldName) {
        JsonNode node = json.path(fieldName);
        if (node.isMissingNode() || node.asText("").isBlank()) {
            throw new IllegalArgumentException("Required field missing: " + fieldName);
        }
        return node.asText();
    }

    /**
     * Walk the Throwable cause chain and return true if any layer is a
     * Spring {@link DuplicateKeyException}. Required because the orchestrator
     * wraps the original {@link DuplicateKeyException} in a generic
     * {@code RuntimeException("Processing error: ...")}, so a simple
     * {@code catch (DuplicateKeyException)} or message substring match misses it.
     */
    static boolean isDuplicateKeyException(Throwable t) {
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth++ < 12) {
            if (cur instanceof DuplicateKeyException) return true;
            if (cur == cur.getCause()) return false;
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * Validate HTTP method against the whitelist promised in the help text.
     * Case-insensitive but the returned value is the canonical upper-case form.
     */
    static String validateHttpMethod(String rawMethod, String endpointName) {
        String method = rawMethod == null ? "" : rawMethod.trim().toUpperCase();
        if (!ALLOWED_METHODS.contains(method)) {
            throw new IllegalArgumentException(
                    "Endpoint '" + endpointName + "' has an invalid HTTP method '"
                            + rawMethod + "'. Allowed methods: " + String.join(", ", ALLOWED_METHODS) + ".");
        }
        return method;
    }

    /**
     * Validate that every param declares an {@code in} (or {@code location} alias)
     * from the allowed set. Silently dropping unknown values would lose the param
     * with no feedback to the agent.
     */
    static void validateParams(JsonNode paramsNode, String endpointName) {
        if (paramsNode == null || !paramsNode.isArray()) return;
        for (int i = 0; i < paramsNode.size(); i++) {
            JsonNode p = paramsNode.get(i);
            String pName = p.path("name").asText("");
            String label = pName.isBlank() ? ("params[" + i + "]") : pName;

            // description is declared required in help → enforce here
            JsonNode descNode = p.path("description");
            if (descNode.isMissingNode() || descNode.asText("").isBlank()) {
                throw new IllegalArgumentException(
                        "Parameter '" + label + "' on endpoint '" + endpointName
                                + "' is missing a required description. Describe the parameter's purpose for agents.");
            }

            // "in" - accept "location" alias; default to "query" only when absent entirely
            JsonNode inNode = p.path("in");
            JsonNode locNode = p.path("location");
            String in;
            if (!inNode.isMissingNode() && !inNode.asText("").isBlank()) {
                in = inNode.asText().toLowerCase();
            } else if (!locNode.isMissingNode() && !locNode.asText("").isBlank()) {
                in = locNode.asText().toLowerCase();
            } else {
                in = "query"; // default retained for backward compat
            }
            if (!ALLOWED_PARAM_IN.contains(in)) {
                throw new IllegalArgumentException(
                        "Parameter '" + label + "' on endpoint '" + endpointName
                                + "' has invalid 'in' value '" + in + "'. Allowed: "
                                + String.join(", ", ALLOWED_PARAM_IN) + ".");
            }
        }
    }

    /**
     * Recursively validate {@code outputSchema[].type} against the whitelist
     * promised in help. Walks nested {@code children} arrays for object/array types.
     */
    static void validateOutputSchema(JsonNode outputSchema, String endpointName) {
        if (outputSchema == null || !outputSchema.isArray()) return;
        for (int i = 0; i < outputSchema.size(); i++) {
            JsonNode field = outputSchema.get(i);
            String fieldKey = field.path("key").asText("");
            String label = fieldKey.isBlank() ? ("outputSchema[" + i + "]") : fieldKey;

            String rawType = field.path("type").asText("").trim();
            if (rawType.isEmpty()) {
                throw new IllegalArgumentException(
                        "Field '" + label + "' on endpoint '" + endpointName
                                + "' is missing a required 'type'. Allowed: "
                                + String.join(", ", ALLOWED_OUTPUT_TYPES) + ".");
            }
            // Normalize case so "String" / "FILEREF" match the whitelist the same way
            // validateParams normalizes the `in` value. `fileRef` keeps its canonical
            // camelCase form in the whitelist; accept any casing from the LLM.
            String type = "fileref".equals(rawType.toLowerCase()) ? "fileRef" : rawType.toLowerCase();
            if (!ALLOWED_OUTPUT_TYPES.contains(type)) {
                throw new IllegalArgumentException(
                        "Field '" + label + "' on endpoint '" + endpointName
                                + "' has invalid type '" + rawType + "'. Allowed: "
                                + String.join(", ", ALLOWED_OUTPUT_TYPES) + ".");
            }
            JsonNode children = field.path("children");
            if (children.isArray()) {
                validateOutputSchema(children, endpointName);
            }
        }
    }

    /**
     * Find a tool entity matching the endpoint name by tool_slug.
     */
    private ApiToolEntity findMatchingTool(List<ApiToolEntity> tools, String endpointName) {
        // Normalize: lowercase, spaces→underscores, then also prepare a hyphenated variant
        // because tool_slug uses hyphens (e.g. "test-api-list-items" for endpoint "list_items")
        String normalized = endpointName.toLowerCase().replace(" ", "_");
        String hyphenated = normalized.replace("_", "-");
        return tools.stream()
                .filter(t -> {
                    if (t.getToolSlug() == null) return false;
                    String slug = t.getToolSlug().toLowerCase();
                    return slug.endsWith("-" + hyphenated)
                            || slug.endsWith("_" + normalized)
                            || slug.equals(normalized)
                            || slug.equals(hyphenated);
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Extract example value from a param node, handling text, number, and other value types.
     */
    private String extractExample(JsonNode p) {
        JsonNode exampleNode = p.path("example");
        if (exampleNode.isMissingNode() || exampleNode.isNull()) return null;
        if (exampleNode.isTextual()) return exampleNode.asText();
        if (exampleNode.isValueNode()) return exampleNode.asText();
        // Object/array examples: serialize to JSON string
        return exampleNode.toString();
    }

    /**
     * Convert a JSON array node to a List of strings.
     */
    private List<String> jsonArrayToList(JsonNode arrayNode) {
        List<String> result = new ArrayList<>();
        if (arrayNode != null && arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                String text = item.asText("");
                if (!text.isBlank()) result.add(text);
            }
        }
        return result;
    }
}
