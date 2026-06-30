package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.apimarketplace.catalog.domain.dto.ApiCreateRequest;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.domain.dto.ApiBasicInfoUpdateRequest;
import com.apimarketplace.catalog.domain.dto.ApiConfigUpdateRequest;
import com.apimarketplace.catalog.dto.ToolUpdateDto;
import com.apimarketplace.catalog.util.SlugUtils;
import com.apimarketplace.catalog.repository.ApiCategoryRepository;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiSubcategoryRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.submission.ApiSubmissionCommand;
import com.apimarketplace.catalog.service.submission.ApiSubmissionCommandFactory;
import com.apimarketplace.catalog.service.submission.ApiSubmissionOrchestrator;
import com.apimarketplace.catalog.service.http.HttpExecutionService;
import com.apimarketplace.catalog.service.submission.ApiSlugService;
import com.apimarketplace.catalog.service.monetization.MonetizationService;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Service to process developer API submissions and manage their execution.
 * Refactored to delegate to specialized services for better SOLID compliance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiService {

    private final ApiRepository apiRepository;
    private final ApiToolRepository apiToolRepository;
    private final ApiCategoryRepository categoryRepository;
    private final ApiSubcategoryRepository subcategoryRepository;
    private final ToolCategoryService toolCategoryService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApiSubmissionCommandFactory submissionCommandFactory;
    private final ApiSubmissionOrchestrator submissionOrchestrator;
    private final ApiSlugService apiSlugService;
    private final UserCredentialService userCredentialService;
    private final HttpExecutionService httpExecutionService;
    private final MonetizationService monetizationService;
    private final ApiToolParameterService parameterService;
    private final ProtocolConfigService protocolConfigService;
    private final ApiResponseConverter responseConverter;
    private final RestTemplate restTemplate;

    /**
     * Self-reference so we can invoke {@code @Transactional} methods through the
     * Spring AOP proxy (self-invocation via {@code this} bypasses proxies and
     * would silently drop the transaction). Lazy-injected to avoid the cycle.
     */
    @Autowired
    @Lazy
    private ApiService self;

    private static final String PLATFORM_TENANT_ID = "PLATFORM";

    // ========== Tool Name Resolution ==========

    public String getToolName(ApiToolEntity tool) {
        if (tool.getToolNameId() == null || tool.getToolNameId().trim().isEmpty()) {
            return "Unknown Tool";
        }
        return toolCategoryService.getToolNameByToolNameId(tool.getToolNameId())
                .map(ToolNameEntity::getName)
                .orElse("Unknown Tool");
    }

    // ========== API Submission ==========

    @Transactional
    public ApiResponse processApiSubmission(JsonNode submissionData, String userId) {
        ApiSubmissionCommand command = submissionCommandFactory.from(submissionData, userId);
        log.info("Processing API submission: {}", command.apiName());
        ApiEntity savedApi = submissionOrchestrator.process(command);
        return convertToApiResponse(savedApi);
    }

    @Transactional
    public ApiResponse processApiConfiguration(ApiConfigurationRequest request, String userId) {
        log.info("Processing API configuration: {}", request.apiName());
        try {
            JsonNode submissionData = new ApiConfigurationConverter(objectMapper).toJsonNode(request);
            return processApiSubmission(submissionData, userId);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new RuntimeException("An API with this name already exists for this developer.", e);
        }
    }

    /**
     * Get custom APIs created by a specific tenant.
     */
    public List<Map<String, Object>> getCustomApisForTenant(String tenantId) {
        return getCustomApisForTenant(tenantId, TenantResolver.currentRequestOrganizationId());
    }

    public List<Map<String, Object>> getCustomApisForTenant(String tenantId, String organizationId) {
        return apiRepository.findCustomApisInScope(tenantId, organizationId).stream()
                .map(api -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", api.getId().toString());
                    result.put("name", api.getApiName());
                    result.put("description", api.getDescription());
                    result.put("baseUrl", api.getBaseUrl());
                    result.put("iconSlug", api.getIconSlug());
                    result.put("iconUrl", api.getIconUrl());
                    result.put("source", api.getSource());
                    result.put("visibility", api.getVisibility());
                    result.put("organizationId", api.getOrganizationId());
                    result.put("toolCount", apiToolRepository.findByApiIdAndIsActiveTrue(api.getId()).size());
                    return result;
                })
                .toList();
    }

    // ========== Tool Execution ==========

    public Map<String, Object> executeApiTool(String apiId, String toolName, JsonNode parameters) {
        return executeApiTool(apiId, toolName, parameters, null, null);
    }

    public Map<String, Object> executeApiTool(String apiId, String toolName, JsonNode parameters, Set<String> allowedParamNames) {
        return executeApiTool(apiId, toolName, parameters, allowedParamNames, null);
    }

    public Map<String, Object> executeApiTool(String apiId, String toolName, JsonNode parameters, Set<String> allowedParamNames, String userId) {
        log.info("[ApiService] Executing tool {} of API {} for userId={}", toolName, apiId, userId);

        try {
            UUID apiUuid = UUID.fromString(apiId);
            ApiEntity api = apiRepository.findById(apiUuid)
                    .orElseThrow(() -> new RuntimeException("API not found: " + apiId));

            ApiToolEntity tool = apiToolRepository.findByApiId(apiUuid).stream()
                    .filter(t -> getToolName(t).equals(toolName))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Tool not found: " + toolName));

            if (tool.getProtocol() != null && !"HTTP".equalsIgnoreCase(tool.getProtocol())) {
                throw new UnsupportedOperationException("Protocol not supported for execution: " + tool.getProtocol());
            }

            CredentialRequirement credReq = getRequiredCredentialInfo(tool.getId());
            Map<String, Object> credError = validateCredentials(api, tool, credReq, userId, toolName);
            if (credError != null) return credError;

            // V166: per-endpoint OAuth scope preflight. Runs BEFORE billing - when this
            // throws, no provider call dispatches and no credit is deducted. The catch
            // for InsufficientScopesException below converts the throw into a structured
            // error map the frontend can render specifically (errorCode = insufficient_scopes).
            String credentialName = credReq != null ? credReq.credentialName() : null;
            httpExecutionService.preflightScopeCheck(userId, credentialName, api, tool);

            long startTime = System.currentTimeMillis();
            // Typed-execution dispatcher (Phases 8/9/10): if the tool's execution_spec declares
            // a non-trivial mode (binary response, multipart upload, async polling), route to
            // the new typed path. Otherwise the legacy JSON-in / JSON-out path is unchanged.
            Map<String, Object> result;
            if (httpExecutionService.needsTypedExecutionPath(tool)) {
                result = httpExecutionService.executeHttpCallTyped(
                        api, tool, parameters, allowedParamNames, userId,
                        credentialName,
                        userId
                );
            } else {
                result = httpExecutionService.executeHttpCallWithCredentials(
                        api, tool, parameters, allowedParamNames, userId,
                        credentialName
                );
            }
            result.put("executionTime", System.currentTimeMillis() - startTime);
            result.put("toolName", toolName);
            result.put("apiId", apiId);
            return result;

        } catch (com.apimarketplace.catalog.service.exception.InsufficientScopesException e) {
            // V166: structured error path. Surfaces enough info for the frontend to render
            // a "reconnect to enable" banner without re-deriving anything from the user side.
            log.info("[ApiService] Preflight scope check failed for tool {}: missing {}",
                    toolName, e.getMissingScopes());
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("errorCode", "insufficient_scopes");
            errorResult.put("error", e.getMessage());
            errorResult.put("toolName", toolName);
            errorResult.put("apiId", apiId);
            errorResult.put("credentialName", e.getCredentialName());
            errorResult.put("integration", e.getIntegration());
            errorResult.put("missingScopes", new java.util.ArrayList<>(e.getMissingScopes()));
            errorResult.put("remediation", "reconnect_credential");
            return errorResult;
        } catch (Exception e) {
            log.error("Error executing tool: {}", e.getMessage(), e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("success", false);
            errorResult.put("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
            errorResult.put("toolName", toolName);
            errorResult.put("apiId", apiId);
            return errorResult;
        }
    }

    private record CredentialRequirement(String credentialName, String credentialType) {}

    private CredentialRequirement getRequiredCredentialInfo(UUID toolId) {
        try {
            String sql = """
                SELECT tc.credential_name, c.credential_type
                FROM catalog.tool_credentials tc
                LEFT JOIN catalog.credentials c ON tc.credential_id = c.id
                WHERE tc.api_tool_id = ? AND tc.is_required = true LIMIT 1
                """;
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, toolId);
            if (results.isEmpty()) return null;
            Map<String, Object> row = results.get(0);
            String credName = (String) row.get("credential_name");
            String credType = (String) row.get("credential_type");
            return new CredentialRequirement(credName, credType != null ? credType : credName);
        } catch (Exception e) {
            log.warn("[ApiService] Error getting credential info for tool {}: {}", toolId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> validateCredentials(ApiEntity api, ApiToolEntity tool, CredentialRequirement credReq, String userId, String toolName) {
        if (credReq == null || credReq.credentialName() == null) return null;

        String platformCredName = api.getPlatformCredentialName();
        // Use getCredentialDataMap to check if ANY user credential data exists.
        // getAccessToken only recognises standard fields (access_token, api_key, etc.)
        // and fails for custom auth types (e.g. Telegram bot_token_in_url stores "token").
        String explicitSource = com.apimarketplace.catalog.service.http.CredentialModeContext.getExplicitSource();
        Long selectedCredentialId = com.apimarketplace.catalog.service.http.CredentialModeContext.getSelectedCredentialId();
        // When a plan pins a specific user credential (selectedCredentialId) we accept
        // EITHER that exact credential OR - when it has been deleted/reconnected - the
        // user's default credential for this integration. This "take pinned, else
        // default" mirrors HttpExecutionService's resolution so a pinned plan whose
        // credential was removed still runs (instead of failing credentials_required).
        boolean hasUserCredentials = userId != null && userCredentialService != null &&
                ("user".equals(explicitSource) && selectedCredentialId != null
                        ? (!userCredentialService.getCredentialDataMapById(userId, selectedCredentialId).isEmpty()
                                || !userCredentialService.getCredentialDataMap(userId, credReq.credentialName()).isEmpty())
                        : !userCredentialService.getCredentialDataMap(userId, credReq.credentialName()).isEmpty());
        boolean hasPlatformCredentials = platformCredName != null && userCredentialService != null &&
                userCredentialService.getAccessToken(PLATFORM_TENANT_ID, platformCredName).isPresent();

        // 1) Workflow node toggle (durci) - same precedence as
        //    HttpExecutionService.tryGetCredentialResolution.
        if (explicitSource != null) {
            boolean ok = "platform".equals(explicitSource) ? hasPlatformCredentials : hasUserCredentials;
            if (ok) return null;
            return credentialsRequiredError(credReq, tool, toolName);
        }

        // 2) Agentic path → user-then-platform fallback by default. Pass if
        //    user has a credential OR the API has a platform credential
        //    configured (the actual presence/pricing check happens in
        //    HttpExecutionService.tryGetCredentialResolution at call time).
        if (hasUserCredentials || hasPlatformCredentials) {
            return null;
        }
        return credentialsRequiredError(credReq, tool, toolName);
    }

    private Map<String, Object> credentialsRequiredError(CredentialRequirement credReq, ApiToolEntity tool, String toolName) {
        Map<String, Object> credError = new HashMap<>();
        credError.put("error", "credentials_required");
        credError.put("credential_type", credReq.credentialType());
        credError.put("credential_name", credReq.credentialName());
        credError.put("tool_id", tool.getId().toString());
        credError.put("tool_name", toolName);
        credError.put("message", "This tool requires " + credReq.credentialName() + " credentials");
        return credError;
    }

    // ========== API Connection Test ==========

    public Map<String, Object> testApiConnection(String apiId) {
        try {
            UUID apiUuid = UUID.fromString(apiId);
            ApiEntity api = apiRepository.findById(apiUuid)
                    .orElseThrow(() -> new RuntimeException("API not found: " + apiId));

            String healthUrl = api.getBaseUrl() + "/health";
            long startTime = System.currentTimeMillis();

            try {
                ResponseEntity<Object> response = restTemplate.getForEntity(healthUrl, Object.class);
                return Map.of("success", true, "status", response.getStatusCode().value(),
                        "responseTime", System.currentTimeMillis() - startTime, "url", healthUrl);
            } catch (Exception e) {
                return Map.of("success", false, "error", e.getMessage(),
                        "responseTime", System.currentTimeMillis() - startTime, "url", healthUrl);
            }
        } catch (Exception e) {
            return Map.of("success", false, "error", "Test error: " + e.getMessage());
        }
    }

    // ========== API CRUD Operations ==========

    @Transactional
    public ApiResponse createApi(ApiCreateRequest request, String userId) {
        log.info("Creating API: {}", request.apiName());

        ApiEntity api = new ApiEntity();
        api.setApiName(request.apiName());
        api.setDescription(request.description());
        api.setBaseUrl(request.baseUrl());
        api.setCategoryId(request.categoryId());
        api.setSubcategoryId(request.subcategoryId());
        api.setCreatedBy(userId);
        api.setVisibility("public");
        api.setIsPublic(true);
        api.setIsActive(true);

        String baseSlug = SlugUtils.generateSlug(request.apiName());
        api.setApiSlug(apiSlugService.generateUniqueSlug(baseSlug, userId));

        long currentTime = System.currentTimeMillis();
        api.setCreatedAt(currentTime);
        api.setUpdatedAt(currentTime);

        ApiEntity savedApi = apiRepository.save(api);

        if (request.tools() != null) {
            for (ApiCreateRequest.ToolCreateRequest toolRequest : request.tools()) {
                ApiToolEntity tool = new ApiToolEntity();
                tool.setApiId(savedApi.getId());
                tool.setDescription(toolRequest.description());
                tool.setEndpoint(toolRequest.endpoint());
                tool.setMethod(toolRequest.method());
                tool.setProtocol("HTTP");
                tool.setStatus("DRAFT");
                tool.setTestStatus("PENDING");
                tool.setIsActive(true);
                tool.setCreatedAt(currentTime);
                tool.setUpdatedAt(currentTime);
                ApiToolEntity savedTool = apiToolRepository.save(tool);
                generateAndSetToolSlug(savedTool, savedApi.getApiSlug());
            }
        }
        return convertToApiResponse(savedApi);
    }

    /**
     * Non-transactional: fetches the entity inside a short read-only txn, then
     * performs the cross-service credential probe <em>after</em> the txn closes
     * so we never hold a Hikari connection across an HTTP round-trip.
     */
    public ApiResponse getApiById(UUID id) {
        ApiEntity api = self.loadApiForResponse(id);
        return convertToApiResponseWithCredentialCheck(api);
    }

    @Transactional(readOnly = true)
    public ApiEntity loadApiForResponse(UUID id) {
        return apiRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("API not found with ID: " + id));
    }

    public List<ApiResponse> getAllApis() {
        return getAllApis(null);
    }

    public List<ApiResponse> getAllApis(String nameFilter) {
        List<ApiEntity> apis;
        if (nameFilter != null && !nameFilter.trim().isEmpty()) {
            String trimmed = nameFilter.trim();
            try {
                UUID filterUuid = UUID.fromString(trimmed);
                apis = apiRepository.findById(filterUuid).map(List::of).orElse(List.of());
            } catch (IllegalArgumentException e) {
                apis = apiRepository.findByApiNameContainingIgnoreCase(trimmed);
            }
        } else {
            apis = StreamSupport.stream(apiRepository.findAll().spliterator(), false).collect(Collectors.toList());
        }
        return apis.stream().map(this::convertToApiResponse).collect(Collectors.toList());
    }

    public List<ApiResponse> getApisByCreator(String createdBy) {
        return apiRepository.findByCreatedBy(createdBy).stream()
                .map(this::convertToApiResponse)
                .collect(Collectors.toList());
    }

    public List<ApiEntity> getPublicApisByCategory(String categoryName) {
        String categorySlug = SlugUtils.generateSlug(categoryName);
        UUID categoryId = categoryRepository.findBySlug(categorySlug)
                .map(ApiCategoryEntity::getId)
                .orElse(null);
        if (categoryId == null) return Collections.emptyList();
        return apiRepository.findByCategoryId(categoryId).stream()
                .filter(api -> Boolean.TRUE.equals(api.getIsActive()))
                .collect(Collectors.toList());
    }

    @Transactional
    public ApiResponse updateApi(UUID id, ApiCreateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("API not found with ID: " + id));
        api.setApiName(request.apiName());
        api.setDescription(request.description());
        api.setBaseUrl(request.baseUrl());
        api.setCategoryId(request.categoryId());
        api.setSubcategoryId(request.subcategoryId());
        api.setUpdatedAt(System.currentTimeMillis());
        return convertToApiResponse(apiRepository.save(api));
    }

    @Transactional
    public ApiResponse updateApiBasicInfo(UUID id, ApiBasicInfoUpdateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("API not found with ID: " + id));
        api.setDescription(request.description());
        updateCategoryFromRequest(api, request.category());
        updateSubcategoryFromRequest(api, request.subcategory());
        api.setUpdatedAt(System.currentTimeMillis());
        return convertToApiResponse(apiRepository.save(api));
    }

    @Transactional
    public ApiResponse updateApiConfig(UUID id, ApiConfigUpdateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("API not found with ID: " + id));
        if (request.baseUrl() != null) api.setBaseUrl(request.baseUrl());
        if (request.healthcheckEndpoint() != null) api.setHealthcheckEndpoint(request.healthcheckEndpoint());
        if (request.visibility() != null) api.setVisibility(request.visibility());
        if (request.authType() != null) api.setAuthType(request.authType());
        if (request.authHeaderName() != null) api.setAuthHeaderName(request.authHeaderName());
        if (request.authHeaderValue() != null) api.setAuthHeaderValue(request.authHeaderValue());
        api.setUpdatedAt(System.currentTimeMillis());
        return convertToApiResponse(apiRepository.save(api));
    }

    @Transactional
    public void deleteApi(UUID id) {
        if (!apiRepository.existsById(id)) {
            throw new RuntimeException("API not found with ID: " + id);
        }
        for (ApiToolEntity tool : apiToolRepository.findByApiId(id)) {
            monetizationService.deleteMonetizationsForTool(tool.getId());
            parameterService.deleteAllParameters(tool.getId());
            apiToolRepository.delete(tool);
        }
        apiRepository.deleteById(id);
    }

    // ========== Tool Update ==========

    @Transactional
    public ApiResponse updateTool(UUID apiId, UUID toolId, ToolUpdateDto dto, String userId) {
        log.info("Updating tool {} for API {}", toolId, apiId);

        ApiEntity api = apiRepository.findById(apiId)
                .orElseThrow(() -> new RuntimeException("API not found with ID: " + apiId));
        ApiToolEntity tool = apiToolRepository.findById(toolId)
                .orElseThrow(() -> new RuntimeException("Tool not found with ID: " + toolId));
        if (!tool.getApiId().equals(apiId)) {
            throw new RuntimeException("Tool " + toolId + " does not belong to API " + apiId);
        }

        // Update tool fields
        if (dto.getDescription() != null) tool.setDescription(dto.getDescription());
        if (dto.getMethod() != null) tool.setMethod(dto.getMethod());
        if (dto.getEndpoint() != null) tool.setEndpoint(dto.getEndpoint());
        if (dto.getProtocol() != null) tool.setProtocol(dto.getProtocol());
        if (dto.getRuntimeMetadata() != null) {
            try {
                tool.setRuntimeMetadata(objectMapper.writeValueAsString(dto.getRuntimeMetadata()));
            } catch (Exception e) {
                log.warn("Unable to serialize runtime metadata: {}", e.getMessage());
            }
        }
        if (dto.getStatus() != null) tool.setStatus(dto.getStatus());
        if (dto.getIsActive() != null) tool.setIsActive(dto.getIsActive());
        if (dto.getVersion() != null) tool.setVersion(dto.getVersion());
        tool.setUpdatedAt(System.currentTimeMillis());

        apiToolRepository.save(tool);

        // Delegate to specialized services
        if (hasParameters(dto)) {
            parameterService.updateParametersFromDto(tool.getId(), dto);
        }
        protocolConfigService.updateConfigsFromDto(tool, dto.getSqlConfig(), dto.getAmqpConfig(),
                dto.getKafkaConfig(), dto.getMqttConfig(), dto.getRedisConfig());

        api.setUpdatedAt(System.currentTimeMillis());
        apiRepository.save(api);
        return convertToApiResponse(api);
    }

    private boolean hasParameters(ToolUpdateDto dto) {
        return dto.getPathParameters() != null || dto.getQueryParameters() != null ||
                dto.getHeaders() != null || dto.getBodyParams() != null;
    }

    // ========== Monetization (delegated to MonetizationService) ==========

    /**
     * Non-transactional outer: mutates state in a committed transaction, then
     * performs the cross-service credential probe once the DB connection is
     * back in the pool.
     */
    public ApiResponse updatePricingModels(UUID apiId, ApiConfigurationRequest.PricingModelsUpdateRequest request, String userId) {
        ApiEntity saved = self.applyPricingModelsUpdate(apiId, request, userId);
        return convertToApiResponseWithCredentialCheck(saved);
    }

    @Transactional
    public ApiEntity applyPricingModelsUpdate(UUID apiId, ApiConfigurationRequest.PricingModelsUpdateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(apiId)
                .orElseThrow(() -> new RuntimeException("API not found with ID: " + apiId));
        List<ApiToolEntity> tools = apiToolRepository.findByApiId(apiId);
        monetizationService.updatePricingModelsForTools(tools, request, this::getToolName);
        api.setUpdatedAt(System.currentTimeMillis());
        return apiRepository.save(api);
    }

    @Transactional
    public ApiResponse updateToolFreemiumConfig(UUID apiId, UUID apiToolId, ApiConfigurationRequest.ToolFreemiumConfigUpdateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(apiId)
                .orElseThrow(() -> new RuntimeException("API not found: " + apiId));
        ApiToolEntity tool = apiToolRepository.findById(apiToolId)
                .orElseThrow(() -> new RuntimeException("Tool not found: " + apiToolId));
        if (!tool.getApiId().equals(apiId)) throw new RuntimeException("Tool does not belong to API: " + apiId);
        monetizationService.updateToolFreemiumConfig(apiToolId, request.config());
        return convertToApiResponse(api);
    }

    @Transactional
    public ApiResponse batchUpdateToolsFreemiumConfig(UUID apiId, ApiConfigurationRequest.BatchToolFreemiumConfigUpdateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(apiId)
                .orElseThrow(() -> new RuntimeException("API not found: " + apiId));
        Map<UUID, ApiConfigurationRequest.ToolFreemiumConfigDto> validated = validateToolConfigs(apiId, request.toolsConfig());
        monetizationService.batchUpdateToolsFreemiumConfig(validated);
        return convertToApiResponse(api);
    }

    @Transactional
    public ApiResponse updateToolPaidConfig(UUID apiId, UUID apiToolId, ApiConfigurationRequest.ToolPaidConfigUpdateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(apiId)
                .orElseThrow(() -> new RuntimeException("API not found: " + apiId));
        ApiToolEntity tool = apiToolRepository.findById(apiToolId)
                .orElseThrow(() -> new RuntimeException("Tool not found: " + apiToolId));
        if (!tool.getApiId().equals(apiId)) throw new RuntimeException("Tool does not belong to API: " + apiId);
        monetizationService.updateToolPaidConfig(apiToolId, request.config());
        return convertToApiResponse(api);
    }

    @Transactional
    public ApiResponse batchUpdateToolsPaidConfig(UUID apiId, ApiConfigurationRequest.BatchToolPaidConfigUpdateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(apiId)
                .orElseThrow(() -> new RuntimeException("API not found: " + apiId));
        Map<UUID, ApiConfigurationRequest.ToolPaidConfigDto> validated = validateToolPaidConfigs(apiId, request.toolsConfig());
        monetizationService.batchUpdateToolsPaidConfig(validated);
        return convertToApiResponse(api);
    }

    /**
     * Non-transactional outer: mirrors {@link #updatePricingModels} - mutate in a
     * committed txn, probe auth-service outside the txn.
     */
    public ApiResponse updatePaidPlans(UUID apiId, ApiConfigurationRequest.PaidPlansUpdateRequest request, String userId) {
        ApiEntity saved = self.applyPaidPlansUpdate(apiId, request, userId);
        return convertToApiResponseWithCredentialCheck(saved);
    }

    @Transactional
    public ApiEntity applyPaidPlansUpdate(UUID apiId, ApiConfigurationRequest.PaidPlansUpdateRequest request, String userId) {
        ApiEntity api = apiRepository.findById(apiId)
                .orElseThrow(() -> new RuntimeException("API not found: " + apiId));
        List<ApiToolEntity> tools = apiToolRepository.findByApiId(apiId);
        monetizationService.updatePaidPlans(tools, request, this::getToolName);
        api.setUpdatedAt(System.currentTimeMillis());
        return apiRepository.save(api);
    }

    // ========== Query Methods ==========

    public List<Map<String, Object>> getToolResponses(UUID toolId) {
        String sql = """
            SELECT id, tool_id, name, description, schema, example, example_jsonb, format,
                   status_code, is_default, is_active, created_at, updated_at, created_by
            FROM tool_responses WHERE tool_id = ? AND is_active = true ORDER BY is_default DESC, created_at DESC
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> response = new HashMap<>();
            response.put("id", rs.getObject("id", UUID.class).toString());
            response.put("toolId", rs.getObject("tool_id", UUID.class).toString());
            response.put("name", rs.getString("name"));
            response.put("description", rs.getString("description"));
            response.put("schema", rs.getString("schema"));
            response.put("example", rs.getString("example"));
            response.put("exampleJsonb", rs.getString("example_jsonb"));
            response.put("format", rs.getString("format"));
            response.put("statusCode", rs.getInt("status_code"));
            response.put("isDefault", rs.getBoolean("is_default"));
            response.put("isActive", rs.getBoolean("is_active"));
            response.put("createdAt", rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").getTime() : null);
            response.put("updatedAt", rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").getTime() : null);
            response.put("createdBy", rs.getString("created_by"));
            return response;
        }, toolId);
    }

    public List<Map<String, Object>> getToolCredentials(UUID toolId) {
        String sql = """
            SELECT tc.id, tc.api_tool_id, tc.credential_id, tc.credential_name, tc.is_required, tc.usage, tc.condition, tc.metadata, tc.created_at, tc.updated_at,
                   c.credential_name as full_credential_name, c.display_name, c.description, c.credential_type, c.auth_type, c.test_endpoint, c.documentation_url, c.icon_url, c.properties, c.extends_
            FROM catalog.tool_credentials tc LEFT JOIN catalog.credentials c ON tc.credential_id = c.id WHERE tc.api_tool_id = ?
            """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> credential = new HashMap<>();
            credential.put("id", rs.getObject("id", UUID.class).toString());
            credential.put("apiToolId", rs.getObject("api_tool_id", UUID.class).toString());
            credential.put("credentialId", rs.getObject("credential_id", UUID.class) != null ? rs.getObject("credential_id", UUID.class).toString() : null);
            credential.put("credentialName", rs.getString("credential_name"));
            credential.put("isRequired", rs.getBoolean("is_required"));
            credential.put("usage", rs.getString("usage"));
            credential.put("condition", rs.getString("condition"));
            credential.put("metadata", rs.getString("metadata"));
            credential.put("createdAt", rs.getObject("created_at", Long.class));
            credential.put("updatedAt", rs.getObject("updated_at", Long.class));
            if (rs.getString("full_credential_name") != null) {
                Map<String, Object> details = new HashMap<>();
                details.put("credentialName", rs.getString("full_credential_name"));
                details.put("displayName", rs.getString("display_name"));
                details.put("description", rs.getString("description"));
                details.put("credentialType", rs.getString("credential_type"));
                details.put("authType", rs.getString("auth_type"));
                details.put("testEndpoint", rs.getString("test_endpoint"));
                details.put("documentationUrl", rs.getString("documentation_url"));
                details.put("iconUrl", rs.getString("icon_url"));
                details.put("properties", rs.getString("properties"));
                details.put("extends", rs.getString("extends_"));
                credential.put("credentialDetails", details);
            }
            return credential;
        }, toolId);
    }

    public Map<String, Object> getMonetizationStateByUser(String userId) {
        List<ApiResponse> userApis = getApisByCreator(userId);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ApiResponse api : userApis) {
            if (api.tools() != null) {
                for (ApiResponse.ToolResponse tool : api.tools()) {
                    Map<String, Object> toolData = new HashMap<>();
                    toolData.put("id", tool.id().toString());
                    toolData.put("name", tool.name());
                    toolData.put("apiName", api.apiName());
                    toolData.put("apiSlug", api.apiSlug());
                    toolData.put("endpoint", tool.endpoint());
                    toolData.put("method", tool.method());
                    if (tool.toolCategories() != null) {
                        Map<String, Object> tc = new HashMap<>();
                        tc.put("id", tool.toolCategories().id());
                        tc.put("name", tool.toolCategories().name());
                        tc.put("description", tool.toolCategories().description());
                        tc.put("icon", tool.toolCategories().icon());
                        tc.put("color", tool.toolCategories().color());
                        tc.put("sortOrder", tool.toolCategories().sortOrder());
                        tc.put("isActive", tool.toolCategories().isActive());
                        tc.put("createdAt", tool.toolCategories().createdAt());
                        tc.put("updatedAt", tool.toolCategories().updatedAt());
                        toolData.put("toolCategories", tc);
                    }
                    toolData.put("monetization", monetizationService.convertMonetizationToMapList(tool.monetization()));
                    tools.add(toolData);
                }
            }
        }
        return Map.of("tools", tools, "totalTools", tools.size(), "userId", userId);
    }

    // ========== Uniqueness Checks ==========

    public boolean isApiNameUniqueForUser(String apiName, String userId) {
        try {
            return apiRepository.findByCreatedByAndApiName(userId, apiName).isEmpty();
        } catch (Exception e) {
            log.error("Error checking API name uniqueness: {}", e.getMessage());
            return true;
        }
    }

    public boolean isApiNameUniqueGlobally(String apiName) {
        try {
            return apiRepository.findByApiName(apiName).isEmpty();
        } catch (Exception e) {
            log.error("Error checking global API name uniqueness: {}", e.getMessage());
            return true;
        }
    }

    public boolean isApiSlugUnique(String slug) {
        try {
            return apiRepository.findByApiSlug(slug).isEmpty();
        } catch (Exception e) {
            log.error("Error checking API slug uniqueness: {}", e.getMessage());
            return true;
        }
    }

    // ========== Private Helper Methods ==========

    private ApiResponse convertToApiResponse(ApiEntity api) {
        return responseConverter.toApiResponse(api, this::getToolName);
    }

    /**
     * Same as {@link #convertToApiResponse(ApiEntity)} but also probes auth-service to
     * populate {@link ApiResponse#platformCredentialMissing()}. Use on pricing-related
     * endpoints where the UI needs the warning banner; the extra HTTP call is not
     * justified on list endpoints.
     */
    private ApiResponse convertToApiResponseWithCredentialCheck(ApiEntity api) {
        return responseConverter.toApiResponse(api, this::getToolName, true);
    }

    private void updateCategoryFromRequest(ApiEntity api, String category) {
        if (category == null || category.isEmpty()) return;
        String categorySlug = SlugUtils.generateSlug(category);
        categoryRepository.findBySlug(categorySlug).ifPresent(c -> api.setCategoryId(c.getId()));
        if (api.getCategoryId() == null) {
            try {
                UUID categoryId = UUID.fromString(category);
                if (categoryRepository.existsById(categoryId)) api.setCategoryId(categoryId);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void updateSubcategoryFromRequest(ApiEntity api, String subcategory) {
        if (subcategory == null || subcategory.isEmpty()) return;
        String subcategorySlug = SlugUtils.generateSlug(subcategory);
        subcategoryRepository.findBySlugAndCategoryId(subcategorySlug, api.getCategoryId())
                .ifPresent(s -> api.setSubcategoryId(s.getId()));
        if (api.getSubcategoryId() == null) {
            try {
                UUID subcategoryId = UUID.fromString(subcategory);
                if (subcategoryRepository.existsById(subcategoryId)) api.setSubcategoryId(subcategoryId);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void generateAndSetToolSlug(ApiToolEntity tool, String apiSlug) {
        if (tool.getToolSlug() != null && !tool.getToolSlug().trim().isEmpty()) return;
        String toolName = getToolName(tool);
        if (toolName == null || toolName.trim().isEmpty() || "Unknown Tool".equals(toolName)) {
            toolName = "unknown-tool";
        }
        String baseSlug = (apiSlug != null ? apiSlug : "api") + "-" + SlugUtils.generateSlug(toolName);
        String uniqueSlug = ensureUniqueToolSlug(tool.getApiId(), baseSlug, tool.getId());
        tool.setToolSlug(uniqueSlug);
        apiToolRepository.save(tool);
    }

    private String ensureUniqueToolSlug(UUID apiId, String baseSlug, UUID toolId) {
        List<String> existingSlugs = jdbcTemplate.queryForList(
                "SELECT tool_slug FROM api_tools WHERE api_id = ? AND tool_slug IS NOT NULL AND tool_slug != '' AND id != ?",
                String.class, apiId, toolId
        );
        if (!existingSlugs.contains(baseSlug)) return baseSlug;
        int counter = 1;
        String uniqueSlug = baseSlug + "-" + counter;
        while (existingSlugs.contains(uniqueSlug)) {
            counter++;
            uniqueSlug = baseSlug + "-" + counter;
        }
        return uniqueSlug;
    }

    private <T> Map<UUID, T> validateToolConfigs(UUID apiId, Map<String, T> configs) {
        Map<UUID, T> validated = new HashMap<>();
        for (Map.Entry<String, T> entry : configs.entrySet()) {
            try {
                UUID toolId = UUID.fromString(entry.getKey());
                apiToolRepository.findById(toolId).ifPresent(tool -> {
                    if (tool.getApiId().equals(apiId)) {
                        validated.put(toolId, entry.getValue());
                    }
                });
            } catch (IllegalArgumentException e) {
                log.error("Invalid tool ID format: {}", entry.getKey());
            }
        }
        return validated;
    }

    private Map<UUID, ApiConfigurationRequest.ToolPaidConfigDto> validateToolPaidConfigs(UUID apiId, Map<String, ApiConfigurationRequest.ToolPaidConfigDto> configs) {
        return validateToolConfigs(apiId, configs);
    }
}
