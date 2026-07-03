package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.catalog.util.ApiScopedSearchParser;
import com.apimarketplace.catalog.util.ApiScopedSearchParser.ParsedSearch;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * Module handling catalog search operations.
 * Operations: search
 */
@Slf4j
@Component
public class CatalogSearchModule implements ToolModule {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CredentialClient credentialClient;

    @Value("${server.port:8081}")
    private int serverPort;

    private static final Set<String> HANDLED_ACTIONS = Set.of("search");

    public CatalogSearchModule(ObjectMapper objectMapper, CredentialClient credentialClient) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
        this.credentialClient = credentialClient;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String toolName) {
        return HANDLED_ACTIONS.contains(toolName);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String toolName, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(toolName)) {
            return Optional.empty();
        }
        return Optional.of(executeCatalogSearch(parameters, context));
    }

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeCatalogSearch(Map<String, Object> parameters, ToolExecutionContext context) {
        String rawQuery = parameters.get("query") == null ? "" : String.valueOf(parameters.get("query"));
        ParsedSearch parsedSearch = ApiScopedSearchParser.parse(rawQuery, parameters.get("api"), parameters.get("apis"));
        String query = parsedSearch.query();
        if (query == null || query.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "query is required");
        }

        int limit = 10;
        Object limitObj = parameters.get("limit");
        if (limitObj instanceof Number) {
            limit = Math.min(((Number) limitObj).intValue(), 25);
        }

        // Check for agent tool restrictions
        List<String> allowedToolIds = getAllowedToolIds(context);

        String tenantId = context != null ? context.tenantId() : null;

        try {
            List<Map<String, Object>> tools;

            if (allowedToolIds != null && allowedToolIds.isEmpty()) {
                log.info("Agent restriction: mode=none, returning empty catalog search results");
                return ToolExecutionResult.success(Map.of(
                    "status", "OK",
                    "count", 0,
                    "tools", List.of(),
                    "message", "No external API tools are available for this agent."
                ));
            } else if (allowedToolIds != null) {
                log.info("Agent restriction: mode=custom, fetching {} allowed tools by ID", allowedToolIds.size());
                tools = filterFetchedTools(fetchToolsByIds(allowedToolIds, tenantId, context), parsedSearch);
            } else {
                String url = buildSearchUrl(query, limit, parsedSearch.apiFilters());

                HttpHeaders headers = CatalogToolHeaderSupport.jsonHeaders(tenantId, context);
                HttpEntity<Void> entity = new HttpEntity<>(headers);

                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    tools = parseToolsResponse(response.getBody());
                } else {
                    tools = List.of();
                }
            }

            if (tools != null && !tools.isEmpty()) {
                Set<String> userDefaultCredentialIntegrations = getUserDefaultCredentialIntegrations(context);
                Map<String, Map<String, Object>> servicesRequiringApprovalMap = new LinkedHashMap<>();

                List<Map<String, Object>> results = tools.stream()
                    .map(tool -> mapToolToSummary(tool, userDefaultCredentialIntegrations, servicesRequiringApprovalMap))
                    .toList();

                int count = results.size();

                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("status", "OK");
                resultMap.put("count", count);
                resultMap.put("tools", results);
                resultMap.put("query", query);
                if (parsedSearch.hasApiFilters()) {
                    resultMap.put("api_filters", parsedSearch.apiFilters());
                    resultMap.put("api_scope_source", parsedSearch.inlineScope() ? "query" : "parameter");
                }

                addTableOperationsHint(resultMap, query);
                addCredentialsRequiredInfo(resultMap, servicesRequiringApprovalMap, query);

                if (count > 0) {
                    addNextActionHint(resultMap, results);
                } else {
                    resultMap.put("message", "No tools found for: " + query);
                    Map<String, Object> nextAction = new LinkedHashMap<>();
                    nextAction.put("hint", "Try different keywords or check available providers");
                    resultMap.put("nextAction", nextAction);
                }

                return ToolExecutionResult.success(resultMap);
            } else {
                return ToolExecutionResult.success(Map.of(
                    "tools", List.of(),
                    "count", 0,
                    "query", query,
                    "api_filters", parsedSearch.apiFilters(),
                    "status", "OK",
                    "message", "No tools found for query: " + query
                ));
            }

        } catch (Exception e) {
            log.error("Error searching catalog for '{}': {}", query, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to search catalog: " + e.getMessage());
        }
    }

    private String buildSearchUrl(String query, int limit, List<String> apiFilters) {
        StringBuilder url = new StringBuilder("http://localhost:")
            .append(serverPort)
            .append("/api/tools/search?q=")
            .append(encode(query))
            .append("&k=")
            .append(limit);
        for (String apiFilter : apiFilters) {
            url.append("&api=").append(encode(apiFilter));
        }
        return url.toString();
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseToolsResponse(String responseBody) {
        try {
            Object parsed = objectMapper.readValue(responseBody, Object.class);
            if (parsed instanceof List) {
                return (List<Map<String, Object>>) parsed;
            } else if (parsed instanceof Map) {
                Map<String, Object> wrapper = (Map<String, Object>) parsed;
                Object toolsObj = wrapper.get("tools");
                if (toolsObj == null) toolsObj = wrapper.get("results");
                if (toolsObj == null) toolsObj = wrapper.get("data");
                if (toolsObj instanceof List) {
                    return (List<Map<String, Object>>) toolsObj;
                }
            }
        } catch (Exception parseError) {
            log.warn("Failed to parse catalog response: {}", parseError.getMessage());
        }
        return List.of();
    }

    private Set<String> getUserDefaultCredentialIntegrations(ToolExecutionContext context) {
        Set<String> userDefaultCredentialIntegrations = new HashSet<>();
        if (context != null && context.tenantId() != null) {
            try {
                List<CredentialSummaryDto> userCreds = credentialClient.getAllCredentials(context.tenantId());
                for (CredentialSummaryDto cred : userCreds) {
                    if (cred.isDefault() && cred.getIntegration() != null) {
                        String integration = cred.getIntegration().toLowerCase();
                        userDefaultCredentialIntegrations.add(integration);
                        String[] parts = integration.split("\\s+");
                        if (parts.length > 0) {
                            userDefaultCredentialIntegrations.add(parts[0]);
                        }
                    }
                }
                log.debug("User {} has DEFAULT credentials for: {}", context.tenantId(), userDefaultCredentialIntegrations);
            } catch (Exception e) {
                log.warn("Failed to get user credentials: {}", e.getMessage());
            }
        }
        return userDefaultCredentialIntegrations;
    }

    private Map<String, Object> mapToolToSummary(Map<String, Object> tool,
                                                  Set<String> userDefaultCredentialIntegrations,
                                                  Map<String, Map<String, Object>> servicesRequiringApprovalMap) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", tool.get("id"));
        if (tool.get("name") != null) {
            summary.put("name", tool.get("name"));
        }
        summary.put("description", tool.get("description"));
        if (tool.containsKey("provider")) {
            summary.put("provider", tool.get("provider"));
        }
        if (tool.containsKey("apiName")) {
            summary.put("apiName", tool.get("apiName"));
        } else if (tool.get("api") instanceof Map<?, ?> api && api.get("name") != null) {
            summary.put("apiName", api.get("name"));
        }
        if (tool.containsKey("iconSlug")) {
            summary.put("iconSlug", tool.get("iconSlug"));
            String iconSlug = (String) tool.get("iconSlug");
            if (iconSlug != null && !iconSlug.isBlank()) {
                String serviceType = iconSlug.toLowerCase();
                boolean hasCredential = userDefaultCredentialIntegrations.contains(serviceType);
                if (!hasCredential) {
                    summary.put("requiresCredential", true);
                    if (!servicesRequiringApprovalMap.containsKey(serviceType)) {
                        Map<String, Object> serviceInfo = new LinkedHashMap<>();
                        serviceInfo.put("serviceType", serviceType);
                        serviceInfo.put("serviceName", iconSlug.substring(0, 1).toUpperCase() + iconSlug.substring(1));
                        serviceInfo.put("iconSlug", iconSlug);
                        serviceInfo.put("toolName", tool.get("name"));
                        serviceInfo.put("toolId", tool.get("id"));
                        serviceInfo.put("description", tool.get("description"));
                        servicesRequiringApprovalMap.put(serviceType, serviceInfo);
                    }
                }
            }
        }
        if (tool.containsKey("score")) {
            summary.put("score", tool.get("score"));
        }
        if (tool.containsKey("requiredParams")) {
            summary.put("required_inputs", tool.get("requiredParams"));
        }
        if (tool.containsKey("optionalParams")) {
            summary.put("optional_inputs", tool.get("optionalParams"));
        }
        return summary;
    }

    private List<Map<String, Object>> filterFetchedTools(List<Map<String, Object>> tools, ParsedSearch parsedSearch) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        String query = parsedSearch.query() == null ? "" : parsedSearch.query().trim().toLowerCase(Locale.ROOT);
        List<String> queryTokens = Arrays.stream(query.split("\\s+"))
            .map(String::trim)
            .filter(token -> token.length() >= 2)
            .toList();
        List<String> compactApiFilters = parsedSearch.apiFilters().stream()
            .map(ApiScopedSearchParser::compactIdentifier)
            .filter(s -> !s.isBlank())
            .toList();

        return tools.stream()
            .filter(tool -> matchesApiFilters(tool, compactApiFilters))
            .filter(tool -> matchesQueryTokens(tool, queryTokens))
            .toList();
    }

    private boolean matchesApiFilters(Map<String, Object> tool, List<String> compactApiFilters) {
        if (compactApiFilters.isEmpty()) {
            return true;
        }
        String haystack = compactApiIdentityText(tool);
        return compactApiFilters.stream().anyMatch(haystack::contains);
    }

    private boolean matchesQueryTokens(Map<String, Object> tool, List<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return true;
        }
        String haystack = toolText(tool).toLowerCase(Locale.ROOT);
        return queryTokens.stream().allMatch(haystack::contains);
    }

    private String compactApiIdentityText(Map<String, Object> tool) {
        return ApiScopedSearchParser.compactIdentifier(apiIdentityText(tool));
    }

    private String apiIdentityText(Map<String, Object> tool) {
        StringBuilder text = new StringBuilder();
        appendValue(text, tool.get("provider"));
        appendValue(text, tool.get("iconSlug"));
        appendValue(text, tool.get("apiName"));
        appendValue(text, tool.get("apiSlug"));
        appendValue(text, tool.get("integrationName"));
        if (tool.get("api") instanceof Map<?, ?> api) {
            appendValue(text, api.get("name"));
            appendValue(text, api.get("slug"));
            appendValue(text, api.get("iconSlug"));
            appendValue(text, api.get("platformCredentialName"));
        }
        return text.toString();
    }

    private String toolText(Map<String, Object> tool) {
        StringBuilder text = new StringBuilder();
        appendValue(text, tool.get("name"));
        appendValue(text, tool.get("description"));
        appendValue(text, tool.get("provider"));
        appendValue(text, tool.get("iconSlug"));
        appendValue(text, tool.get("apiName"));
        if (tool.get("api") instanceof Map<?, ?> api) {
            appendValue(text, api.get("name"));
            appendValue(text, api.get("iconSlug"));
        }
        return text.toString();
    }

    private void appendValue(StringBuilder text, Object value) {
        if (value != null) {
            text.append(' ').append(value);
        }
    }

    private void addTableOperationsHint(Map<String, Object> resultMap, String query) {
        String queryLower = query.toLowerCase();
        if (queryLower.contains("insert") || queryLower.contains("save") ||
            queryLower.contains("store") || queryLower.contains("database") ||
            queryLower.contains("row") || queryLower.contains("record") ||
            (queryLower.contains("table") && !queryLower.contains("airtable"))) {

            resultMap.put("⚠️_TABLE_OPERATIONS_HINT", Map.of(
                "message", "Looking to save data to a TABLE? Use workflow actions directly!",
                "insert_row", "workflow(action='add_node', type='insert_row', label='Save', params={table_id: X, columns: {...}}, connect_after='...')",
                "get_rows", "workflow(action='add_node', type='get_rows', label='Fetch', params={table_id: X, where: {...}}, connect_after='...')",
                "note", "These work with YOUR tables (table(action='list') to see them) - NOT external APIs"
            ));
        }
    }

    private void addCredentialsRequiredInfo(Map<String, Object> resultMap,
                                             Map<String, Map<String, Object>> servicesRequiringApprovalMap,
                                             String query) {
        if (!servicesRequiringApprovalMap.isEmpty()) {
            List<String> serviceTypes = new ArrayList<>(servicesRequiringApprovalMap.keySet());
            // JIT MODE: Need credentials now for immediate execution
            resultMap.put("credentialsRequired", Map.of(
                "action", "credential",
                "services", serviceTypes,
                "call", String.format("credential(action=\"require\", services=%s, reason=\"%s\")", serviceTypes, query),
                "alt", "Or search for alternative APIs if user has other services connected"
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private void addNextActionHint(Map<String, Object> resultMap, List<Map<String, Object>> results) {
        String bestToolId = (String) results.get(0).get("id");
        String bestToolName = (String) results.get(0).get("name");

        List<String> bestToolRequiredInputs = (List<String>) results.get(0).get("required_inputs");
        Map<String, Object> suggestedParams = new LinkedHashMap<>();
        if (bestToolRequiredInputs != null) {
            for (String param : bestToolRequiredInputs) {
                suggestedParams.put(param, "...");
            }
        }

        // JIT mode: hint for catalog(action='execute')
        Map<String, Object> nextAction = new LinkedHashMap<>();
        nextAction.put("hint", "Execute this tool directly");
        nextAction.put("tool", "catalog");
        Map<String, Object> execParams = new LinkedHashMap<>();
        execParams.put("action", "execute");
        execParams.put("tool_id", bestToolId);
        execParams.put("params", suggestedParams);
        nextAction.put("params", execParams);

        resultMap.put("nextAction", nextAction);
    }

    @SuppressWarnings("unchecked")
    private List<String> getAllowedToolIds(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) {
            return null;
        }
        Object allowed = context.credentials().get("allowedToolIds");
        if (allowed instanceof List) {
            return (List<String>) allowed;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchToolsByIds(List<String> toolIds,
                                                      String tenantId,
                                                      ToolExecutionContext context) {
        List<Map<String, Object>> allTools = new ArrayList<>();
        String localUrl = "http://localhost:" + serverPort;
        HttpHeaders headers = CatalogToolHeaderSupport.jsonHeaders(tenantId, context);

        for (String toolId : toolIds) {
            try {
                String url = localUrl + "/api/catalog/tools/" + toolId + "/info";
                HttpEntity<Void> entity = new HttpEntity<>(headers);
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                    Map<String, Object> tool = objectMapper.readValue(response.getBody(), Map.class);
                    allTools.add(tool);
                } else {
                    log.warn("Could not fetch allowed tool {}: {}", toolId, response.getStatusCode());
                }
            } catch (Exception e) {
                log.warn("Error fetching allowed tool {}: {}", toolId, e.getMessage());
            }
        }

        log.info("Fetched {}/{} allowed tools from catalog", allTools.size(), toolIds.size());
        return allTools;
    }
}
