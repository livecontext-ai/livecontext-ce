package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import com.apimarketplace.catalog.util.AllowedValuesParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for catalog tools endpoints
 * Provides tool information for orchestrator service
 */
@RestController
@RequestMapping("/api/catalog")
@RequiredArgsConstructor
@Slf4j
public class CatalogToolsController {

    private final ApiToolRepository apiToolRepository;
    private final ApiRepository apiRepository;
    private final ApiToolParameterRepository apiToolParameterRepository;
    private final ToolNameRepository toolNameRepository;

    /**
     * Get tool information by ID
     * GET /api/catalog/tools/{toolId}/info
     */
    @GetMapping("/tools/{toolId}/info")
    public ResponseEntity<Map<String, Object>> getToolInfo(@PathVariable String toolId) {
        try {
            log.info("Fetching tool info for toolId: {}", toolId);
            
            UUID toolUuid;
            try {
                toolUuid = UUID.fromString(toolId);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid tool ID format: {}", toolId);
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid tool ID format",
                    "toolId", toolId
                ));
            }

            // Recuperer le tool
            Optional<ApiToolEntity> toolOpt = apiToolRepository.findById(toolUuid);
            if (toolOpt.isEmpty()) {
                log.warn("Tool not found: {}", toolId);
                return ResponseEntity.notFound().build();
            }

            ApiToolEntity tool = toolOpt.get();

            // Recuperer l'API associee
            Optional<ApiEntity> apiOpt = apiRepository.findById(tool.getApiId());
            if (apiOpt.isEmpty()) {
                log.warn("API not found for tool: {}", toolId);
                return ResponseEntity.notFound().build();
            }

            ApiEntity api = apiOpt.get();

            // Recuperer les parametres du tool
            List<ApiToolParameterEntity> parameters = apiToolParameterRepository.findByApiToolId(toolUuid);

            // Recuperer le nom du tool
            Optional<ToolNameEntity> toolNameOpt = Optional.empty();
            if (tool.getToolNameId() != null) {
                try {
                    UUID toolNameUuid = UUID.fromString(tool.getToolNameId());
                    toolNameOpt = toolNameRepository.findById(toolNameUuid);
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid tool name ID format: {}", tool.getToolNameId());
                }
            }

            // Construire la reponse
            Map<String, Object> response = new HashMap<>();
            response.put("id", tool.getId().toString());
            response.put("name", toolNameOpt.map(ToolNameEntity::getName).orElse(tool.getToolSlug()));
            response.put("description", tool.getDescription());
            response.put("version", tool.getVersion());
            response.put("isActive", tool.getIsActive());
            response.put("status", tool.getStatus());
            response.put("method", tool.getMethod());
            response.put("endpoint", tool.getEndpoint());
            
            // Informations de l'API
            Map<String, Object> apiInfo = new HashMap<>();
            apiInfo.put("id", api.getId().toString());
            apiInfo.put("name", api.getApiName());
            apiInfo.put("baseUrl", api.getBaseUrl());
            apiInfo.put("authType", api.getAuthType());
            apiInfo.put("authHeaderName", api.getAuthHeaderName());
            apiInfo.put("authHeaderValue", api.getAuthHeaderValue());
            apiInfo.put("iconSlug", api.getIconSlug());
            response.put("api", apiInfo);

            // iconSlug at root level for easy access
            response.put("iconSlug", api.getIconSlug());

            // Parametres du tool
            List<Map<String, Object>> toolParameters = parameters.stream()
                .map(param -> {
                    Map<String, Object> paramInfo = new HashMap<>();
                    paramInfo.put("name", param.getName());
                    paramInfo.put("type", param.getParameterType());
                    paramInfo.put("dataType", param.getDataType());
                    paramInfo.put("required", param.getIsRequired());
                    paramInfo.put("description", param.getDescription());
                    paramInfo.put("exampleValue", param.getExampleValue());
                    paramInfo.put("defaultValue", param.getDefaultValue());
                    paramInfo.put("allowedValues", AllowedValuesParser.parseString(param.getAllowedValues()));
                    return paramInfo;
                })
                .collect(Collectors.toList());
            response.put("parameters", toolParameters);

            // V166: per-endpoint OAuth scope requirements + unique-per-API integration name.
            // Surfaced for the frontend MissingScopesBanner. iconSlug is brand-shared (not
            // unique per API) - integrationName matches auth.credentials.integration.
            if (api.getPlatformCredentialName() != null && !api.getPlatformCredentialName().isBlank()) {
                response.put("integrationName", api.getPlatformCredentialName());
            }
            List<String> requiredScopes = tool.getRequiredScopes();
            if (requiredScopes != null && !requiredScopes.isEmpty()) {
                response.put("requiredScopes", requiredScopes);
            }

            // Informations additionnelles pour l'orchestrator
            response.put("baseUrl", api.getBaseUrl());
            response.put("fullEndpoint", buildFullEndpoint(api.getBaseUrl(), tool.getEndpoint()));
            response.put("authType", api.getAuthType());
            response.put("authHeaderName", api.getAuthHeaderName());
            response.put("authHeaderValue", api.getAuthHeaderValue());

            log.info("Successfully fetched tool info for: {}", toolId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching tool info for {}: {}", toolId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", "Internal server error",
                "message", e.getMessage()
            ));
        }
    }

    /**
     * Get all tools
     * GET /api/catalog/tools
     */
    @GetMapping("/tools")
    public ResponseEntity<List<Map<String, Object>>> getAllTools(
            @RequestParam(defaultValue = "false") boolean activeOnly) {
        try {
            log.info("Fetching all tools, activeOnly: {}", activeOnly);
            
            List<ApiToolEntity> tools;
            if (activeOnly) {
                // User-facing list: hide bundle-deprecated tools (V331).
                tools = apiToolRepository.findByIsActiveTrueAndDeprecatedAtIsNull();
            } else {
                tools = new ArrayList<>();
                apiToolRepository.findAll().forEach(tools::add);
            }

            List<Map<String, Object>> response = tools.stream()
                .map(tool -> {
                    Map<String, Object> toolInfo = new HashMap<>();
                    toolInfo.put("id", tool.getId().toString());
                    toolInfo.put("toolSlug", tool.getToolSlug());
                    toolInfo.put("description", tool.getDescription());
                    toolInfo.put("version", tool.getVersion());
                    toolInfo.put("isActive", tool.getIsActive());
                    toolInfo.put("status", tool.getStatus());
                    toolInfo.put("method", tool.getMethod());
                    toolInfo.put("endpoint", tool.getEndpoint());
                    toolInfo.put("apiId", tool.getApiId().toString());
                    return toolInfo;
                })
                .collect(Collectors.toList());

            log.info("Successfully fetched {} tools", response.size());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error fetching all tools: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Health check endpoint
     * GET /api/catalog/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> response = Map.of(
            "status", "UP",
            "service", "catalog-service",
            "timestamp", System.currentTimeMillis()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Returns every catalog API tool with its typed-execution metadata so that the orchestrator
     * can register them as dynamic NodeDefinitions in NodeDefinitionRegistry on startup.
     *
     * Output is intentionally minimal: just what's needed to build a NodeDefinition
     * (nodeType, label, iconSlug, description, executionMode, outputSchema as raw JSON string).
     *
     * GET /api/catalog/tools/typed-definitions
     */
    @GetMapping("/tools/typed-definitions")
    public ResponseEntity<List<Map<String, Object>>> getTypedDefinitions() {
        try {
            // Pre-load APIs to avoid N+1 lookups for iconSlug.
            Map<UUID, ApiEntity> apisById = new HashMap<>();
            for (ApiEntity api : apiRepository.findAll()) {
                apisById.put(api.getId(), api);
            }

            // Deliberately NOT filtered on deprecated_at: this feeds the
            // orchestrator's NodeDefinitionRegistry, which existing workflows
            // rely on to keep executing their already-placed MCP nodes. A
            // bundle-deprecated tool must stay executable (soft-delete
            // contract, V331) - it disappears from search/list paths instead.
            List<ApiToolEntity> tools = apiToolRepository.findByIsActiveTrue();
            List<Map<String, Object>> out = new ArrayList<>(tools.size());
            for (ApiToolEntity tool : tools) {
                ApiEntity api = apisById.get(tool.getApiId());
                if (api == null) continue;
                String iconSlug = api.getIconSlug();
                if (iconSlug == null || iconSlug.isBlank()) continue;
                String toolSlug = tool.getToolSlug();
                if (toolSlug == null || toolSlug.isBlank()) continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                // nodeType format: MCP:{iconSlug}:{tool_slug} - globally unique, lookupable.
                entry.put("nodeType", "MCP:" + iconSlug + ":" + toolSlug);
                entry.put("label", toolSlug);
                entry.put("iconSlug", iconSlug);
                if (api.getIconUrl() != null && !api.getIconUrl().isBlank()) {
                    entry.put("iconUrl", api.getIconUrl());
                }
                entry.put("apiName", api.getApiName());
                entry.put("description", tool.getDescription());
                entry.put("executionMode", tool.getExecutionMode() == null ? "sync" : tool.getExecutionMode());
                // V166: unique per-API integration name (matches auth.credentials.integration)
                // and the optional per-endpoint required OAuth scopes. Surfaced for the
                // orchestrator's CatalogMcpRegistrar to put on the node-definitions metadata.
                if (api.getPlatformCredentialName() != null && !api.getPlatformCredentialName().isBlank()) {
                    entry.put("platformCredentialName", api.getPlatformCredentialName());
                }
                List<String> requiredScopes = tool.getRequiredScopes();
                if (requiredScopes != null && !requiredScopes.isEmpty()) {
                    entry.put("requiredScopes", requiredScopes);
                }
                // Raw JSON strings - orchestrator parses them with its own ObjectMapper.
                entry.put("outputSchemaJson", tool.getOutputSchema());
                entry.put("executionSpecJson", tool.getExecutionSpec());
                out.add(entry);
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("Failed to fetch typed definitions: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(List.of());
        }
    }

    /**
     * Build full endpoint URL
     */
    private String buildFullEndpoint(String baseUrl, String endpoint) {
        if (baseUrl == null || endpoint == null) {
            return endpoint;
        }
        
        if (baseUrl.endsWith("/") && endpoint.startsWith("/")) {
            return baseUrl + endpoint.substring(1);
        } else if (!baseUrl.endsWith("/") && !endpoint.startsWith("/")) {
            return baseUrl + "/" + endpoint;
        } else {
            return baseUrl + endpoint;
        }
    }
}
