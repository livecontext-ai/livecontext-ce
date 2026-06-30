package com.apimarketplace.agent.tool;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.*;

/**
 * Implementation of ToolDiscoveryService that uses catalog-service optimized search.
 * Discovers tools by querying the catalog service's tool search endpoint.
 *
 * The endpoint uses:
 * - Full-text search with weighted fields (keywords, provider, action, resource)
 * - Structured query extraction (detects provider/action from query)
 * - Fuzzy matching with pg_trgm for typo tolerance
 * - Intelligent fallback chain when no exact matches found
 *
 * Single Responsibility: Only handles tool discovery via catalog-service search.
 */
@Slf4j
@Service("sharedAgentCatalogToolDiscoveryService")
public class CatalogToolDiscoveryService implements ToolDiscoveryService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${catalog.service.url:http://localhost:8081}")
    private String catalogServiceUrl;

    @Value("${ai.agent.tool-discovery.min-score:0.02}")
    private double defaultMinScore;

    // Cache for tool definitions (simple in-memory cache)
    private final Map<String, ToolDefinition> toolCache = new HashMap<>();
    private final Map<String, ToolDefinition> toolCacheByName = new HashMap<>();

    public CatalogToolDiscoveryService() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public CatalogToolDiscoveryService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ToolDefinition> findRelevantTools(String query, int maxTools) {
        return findRelevantTools(query, maxTools, defaultMinScore);
    }

    /**
     * Find relevant tools with minimum score threshold.
     *
     * @param query    The user's query/intent
     * @param maxTools Maximum number of tools to return
     * @param minScore Minimum RRF score for inclusion
     * @return List of relevant tools sorted by relevance score
     */
    @Override
    public List<ToolDefinition> findRelevantTools(String query, int maxTools, double minScore) {
        try {
            log.debug("Searching for tools with query: '{}', maxTools: {}, minScore: {}",
                query, maxTools, minScore);

            // Call catalog-service optimized tool search endpoint
            // This endpoint supports:
            // - Structured query extraction (provider/action detection)
            // - Fuzzy matching for typo tolerance
            // - Intelligent fallback when no exact matches
            String url = UriComponentsBuilder.fromHttpUrl(catalogServiceUrl)
                .path("/api/tools/search")
                .queryParam("q", query)
                .queryParam("k", maxTools * 2) // Request more to allow filtering
                .build()
                .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, Map.class
            );

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("Failed to search for tools: {}", response.getStatusCode());
                return List.of();
            }

            return parseCapabilityResponse(response.getBody(), maxTools, minScore);

        } catch (Exception e) {
            log.error("Error searching for tools: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public List<ToolDefinition> getAllTools(String tenantId) {
        try {
            log.debug("Fetching all tools for tenant: {}", tenantId);

            String url = UriComponentsBuilder.fromHttpUrl(catalogServiceUrl)
                .path("/api/tools")
                .queryParam("tenantId", tenantId)
                .build()
                .toUriString();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            @SuppressWarnings("unchecked")
            ResponseEntity<List> response = restTemplate.exchange(
                url, HttpMethod.GET, entity, List.class
            );

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("Failed to fetch all tools: {}", response.getStatusCode());
                return List.of();
            }

            List<ToolDefinition> tools = new ArrayList<>();
            for (Object item : response.getBody()) {
                if (item instanceof Map) {
                    @SuppressWarnings("unchecked")
                    ToolDefinition tool = parseToolFromApi((Map<String, Object>) item);
                    if (tool != null) {
                        tools.add(tool);
                    }
                }
            }

            return tools;

        } catch (Exception e) {
            log.error("Error fetching all tools: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get a specific tool by ID.
     *
     * @param toolId The tool ID
     * @return The tool definition, or null if not found
     */
    public ToolDefinition getToolById(String toolId) {
        // Check cache first
        if (toolCache.containsKey(toolId)) {
            return toolCache.get(toolId);
        }

        try {
            // Fetch from catalog-service
            String url = catalogServiceUrl + "/api/tools/" + toolId;

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.getForEntity(url, Map.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                ToolDefinition tool = parseToolFromApi(response.getBody());
                if (tool != null) {
                    toolCache.put(toolId, tool);
                    toolCacheByName.put(tool.name(), tool);
                }
                return tool;
            }
        } catch (Exception e) {
            log.error("Error fetching tool {}: {}", toolId, e.getMessage());
        }

        return null;
    }

    /**
     * Get a specific tool by name.
     *
     * @param toolName The tool name
     * @return The tool definition, or null if not found
     */
    public ToolDefinition getToolByName(String toolName) {
        // Check cache first
        if (toolCacheByName.containsKey(toolName)) {
            return toolCacheByName.get(toolName);
        }

        // For now, we can't search by name directly - would need catalog-service endpoint
        log.warn("getToolByName not fully implemented - tool {} not in cache", toolName);
        return null;
    }

    /**
     * Parse the tool search response from /api/tools/search endpoint.
     * Response format:
     * {
     *   "query": "...",
     *   "parsed": { "provider": "...", "action": "...", "remaining": "..." },
     *   "matchType": "full_text|fuzzy|provider_only",
     *   "count": N,
     *   "tools": [{ "name": "...", "provider": "...", "score": 0.8, ... }]
     * }
     */
    @SuppressWarnings("unchecked")
    private List<ToolDefinition> parseCapabilityResponse(Map<String, Object> response,
                                                          int maxTools, double minScore) {
        List<ToolDefinition> tools = new ArrayList<>();

        // New endpoint returns "tools" array
        List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("tools");

        // Fallback to old format for backwards compatibility
        if (results == null) {
            results = (List<Map<String, Object>>) response.get("results");
        }
        if (results == null) {
            results = (List<Map<String, Object>>) response.get("capabilities");
        }

        if (results == null || results.isEmpty()) {
            log.debug("No tools found in response");
            return tools;
        }

        // Log match type for debugging
        String matchType = (String) response.get("matchType");
        if (matchType != null) {
            log.debug("Tool search match type: {}", matchType);
        }

        for (Map<String, Object> result : results) {
            if (tools.size() >= maxTools) {
                break;
            }

            // Check score
            Double score = getDoubleValue(result, "score");
            if (score == null) {
                score = getDoubleValue(result, "rrfScore");
            }
            if (score != null && score < minScore) {
                log.trace("Skipping tool {} with score {} < minScore {}",
                    result.get("name"), score, minScore);
                continue;
            }

            ToolDefinition tool = parseToolFromCapability(result, score);
            if (tool != null) {
                tools.add(tool);
                if (tool.id() != null) {
                    toolCache.put(tool.id(), tool);
                }
                toolCacheByName.put(tool.name(), tool);
            }
        }

        log.debug("Found {} relevant tools (matchType: {})", tools.size(), matchType);
        return tools;
    }

    /**
     * Parse a tool from capability search result.
     */
    @SuppressWarnings("unchecked")
    private ToolDefinition parseToolFromCapability(Map<String, Object> result, Double score) {
        try {
            String id = getString(result, "id");
            if (id == null) {
                id = getString(result, "toolId");
            }

            String name = getString(result, "name");
            if (name == null) {
                name = getString(result, "toolName");
            }

            // Skip tools with empty or invalid names
            if (name == null || name.isBlank()) {
                log.warn("Skipping tool with empty name: id={}", id);
                return null;
            }

            String description = getString(result, "description");
            if (description == null) {
                description = getString(result, "summary");
            }

            String apiSlug = getString(result, "apiSlug");
            if (apiSlug == null) {
                apiSlug = getString(result, "provider");
            }

            String toolSlug = getString(result, "toolSlug");
            if (toolSlug == null) {
                toolSlug = getString(result, "slug");
            }

            // Parse parameters if available
            List<ToolParameter> parameters = new ArrayList<>();
            List<String> requiredParams = new ArrayList<>();

            Object paramsObj = result.get("parameters");
            if (paramsObj instanceof List) {
                List<Map<String, Object>> paramsList = (List<Map<String, Object>>) paramsObj;
                for (Map<String, Object> param : paramsList) {
                    ToolParameter tp = parseParameter(param);
                    if (tp != null) {
                        parameters.add(tp);
                        if (tp.required()) {
                            requiredParams.add(tp.name());
                        }
                    }
                }
            }

            return ToolDefinition.builder()
                .id(id)
                .name(sanitizeToolName(name))
                .description(description)
                .apiSlug(apiSlug)
                .toolSlug(toolSlug)
                .parameters(parameters)
                .requiredParameters(requiredParams)
                .relevanceScore(score)
                .metadata(Map.of("source", "rrf_search"))
                .build();

        } catch (Exception e) {
            log.warn("Error parsing tool from capability: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse tool from direct API response.
     */
    @SuppressWarnings("unchecked")
    private ToolDefinition parseToolFromApi(Map<String, Object> response) {
        try {
            String id = getString(response, "id");
            String name = getString(response, "name");
            String description = getString(response, "description");
            String apiSlug = getString(response, "apiSlug");
            String toolSlug = getString(response, "slug");

            List<ToolParameter> parameters = new ArrayList<>();
            List<String> requiredParams = new ArrayList<>();

            Object paramsObj = response.get("parameters");
            if (paramsObj instanceof List) {
                List<Map<String, Object>> paramsList = (List<Map<String, Object>>) paramsObj;
                for (Map<String, Object> param : paramsList) {
                    ToolParameter tp = parseParameter(param);
                    if (tp != null) {
                        parameters.add(tp);
                        if (tp.required()) {
                            requiredParams.add(tp.name());
                        }
                    }
                }
            }

            return ToolDefinition.builder()
                .id(id)
                .name(sanitizeToolName(name))
                .description(description)
                .apiSlug(apiSlug)
                .toolSlug(toolSlug)
                .parameters(parameters)
                .requiredParameters(requiredParams)
                .build();

        } catch (Exception e) {
            log.warn("Error parsing tool from API: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse a tool parameter.
     */
    @SuppressWarnings("unchecked")
    private ToolParameter parseParameter(Map<String, Object> param) {
        String name = getString(param, "name");
        if (name == null) {
            return null;
        }

        String type = getString(param, "type");
        if (type == null) {
            type = getString(param, "dataType");
        }
        if (type == null) {
            type = "string";
        }

        String description = getString(param, "description");

        Boolean required = (Boolean) param.get("required");
        if (required == null) {
            required = (Boolean) param.get("isRequired");
        }

        Object defaultValue = param.get("defaultValue");
        if (defaultValue == null) {
            defaultValue = param.get("default");
        }

        List<String> enumValues = null;
        Object enumObj = param.get("enum");
        if (enumObj instanceof List) {
            enumValues = (List<String>) enumObj;
        }

        return ToolParameter.builder()
            .name(name)
            .type(type.toLowerCase())
            .description(description)
            .required(required != null && required)
            .defaultValue(defaultValue)
            .enumValues(enumValues)
            .build();
    }

    /**
     * Sanitize tool name for LLM function calling (alphanumeric + underscore only).
     */
    private String sanitizeToolName(String name) {
        if (name == null || name.isBlank()) {
            return "unknown_tool";
        }
        // Replace non-alphanumeric chars with underscore
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_").toLowerCase();
        // Ensure result is not empty after sanitization
        if (sanitized.isBlank() || sanitized.matches("^_+$")) {
            return "unknown_tool";
        }
        return sanitized;
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }
}
